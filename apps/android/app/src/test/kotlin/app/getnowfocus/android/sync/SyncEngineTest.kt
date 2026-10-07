package app.getnowfocus.android.sync

import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tiny stand-in for services/api: per-user seq, last-write-wins, tombstones, domain normalization. */
private class FakeServer {
    class Rec(var data: JSONObject, var revision: Int, var seq: Long, var deleted: Boolean, var updatedAt: Long)
    val records = linkedMapOf<String, Rec>()
    var seq = 0L
    var calls = 0
    var failNextPush: Exception? = null
    var rejectPolicyNamed: String? = null
    var goneOnce = false
    var inUse = false

    private fun key(type: String, id: String) = "$type:${id.lowercase()}"
    private fun view(type: String, id: String, r: Rec) = ServerRecord(type, id, r.data.toString(), r.deleted, r.revision, r.updatedAt)

    fun pull(cursor: Long, limit: Int): PullPage {
        calls++
        if (goneOnce && cursor > 0) { goneOnce = false; throw ApiException(410, null, "cursor too old") }
        val all = records.entries.filter { it.value.seq > cursor }.sortedBy { it.value.seq }
        val page = all.take(limit)
        return PullPage(page.map { view(it.key.substringBefore(':'), it.key.substringAfter(':'), it.value) }, page.lastOrNull()?.value?.seq ?: cursor, all.size > page.size)
    }

    fun push(changes: List<Outgoing>): List<PushOutcome> {
        calls++
        failNextPush?.let { failNextPush = null; throw it }
        return changes.map { c ->
            val k = key(c.type, c.id)
            val cur = records[k]
            if (inUse && c.type == "policy" && cur != null) return@map PushOutcome(c.type, c.id, "rejected", view(c.type, c.id.lowercase(), cur), "policy_in_use")
            if (c.type == "policy" && !c.deleted && JSONObject(c.dataJson!!).getString("name") == rejectPolicyNamed) return@map PushOutcome(c.type, c.id, "rejected", null, "invalid_data")
            if (cur != null && c.updatedAt <= cur.updatedAt) return@map PushOutcome(c.type, c.id, "stale", view(c.type, c.id.lowercase(), cur), null)
            val data = if (c.deleted) JSONObject() else JSONObject(c.dataJson!!).also { d ->
                d.optJSONArray("domainRules")?.let { rules -> for (i in 0 until rules.length()) rules.getJSONObject(i).let { r -> r.put("domain", r.getString("domain").lowercase().removePrefix("www.")) } }
            }
            val rec = cur ?: Rec(data, 0, 0, false, 0).also { records[k] = it }
            rec.data = data; rec.revision++; rec.seq = ++seq; rec.deleted = c.deleted; rec.updatedAt = c.updatedAt
            PushOutcome(c.type, c.id, "applied", view(c.type, c.id.lowercase(), rec), null)
        }
    }
}

private class Device(val server: FakeServer, val clock: Clock, seed: List<BlockPolicy> = emptyList(), user: String = "u1") {
    val store = FakeStore(Local(seed, BedtimeSettings(), SyncLogic.link(SyncState(), user)), clock)
    var offline = false
    val api = object : SyncApiPort {
        override suspend fun pull(cursor: Long, limit: Int): PullPage { if (offline) throw NetworkException(RuntimeException("offline")); return server.pull(cursor, limit) }
        override suspend fun push(changes: List<Outgoing>): List<PushOutcome> { if (offline) throw NetworkException(RuntimeException("offline")); return server.push(changes) }
    }
    val engine = SyncEngine(store, api) { clock.now }
    fun sync() = runBlocking { engine.syncOnce() }
    val policies get() = store.local.policies
}

class SyncEngineTest {
    private fun p(id: String, name: String = "P$id", vararg d: String) = BlockPolicy(id = id, name = name, domains = d.toList())

    @Test fun `a profile made on one device appears on the other, and edits and deletions follow`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.edit { it + p("a1", "Work", "x.com") }; clock.tick()
        a.sync(); b.sync()
        assertEquals(listOf("Work"), b.policies.map { it.name })

        clock.tick(); b.store.edit { it.map { x -> x.copy(domains = x.domains + "y.com") } }; b.sync(); a.sync()
        assertEquals(listOf("x.com", "y.com"), a.policies.single().domains)

        clock.tick(); a.store.edit { emptyList() }; a.sync(); b.sync()
        assertTrue(b.policies.isEmpty())
        assertTrue(a.store.local.state.policies.isEmpty() && b.store.local.state.policies.isEmpty())   // no leftover bookkeeping on either side
    }

    @Test fun `nothing ping-pongs once both are in step`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.edit { it + p("a1", "W", "WWW.X.com") }   // spelled the way the server will rewrite it
        a.sync(); b.sync(); a.sync(); b.sync()
        val seqBefore = server.seq
        repeat(3) { a.sync(); b.sync() }
        assertEquals(seqBefore, server.seq)                   // no change was written
        assertTrue(SyncLogic.planPush(a.store.local, clock.now).isEmpty() && SyncLogic.planPush(b.store.local, clock.now).isEmpty())
    }

    @Test fun `an edit made offline syncs when the connection returns, with the time it was made`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.edit { it + p("a1", "W", "x.com") }; clock.tick(); a.sync(); b.sync()

        a.offline = true
        clock.tick(10_000); a.store.edit { it.map { x -> x.copy(name = "A edit") } }       // at t+10s, offline
        clock.tick(10_000); b.store.edit { it.map { x -> x.copy(name = "B edit") } }; b.sync()  // at t+20s, online
        assertTrue(runCatching { a.sync() }.exceptionOrNull() is NetworkException)
        assertEquals("A edit", a.policies.single().name)         // nothing lost locally

        a.offline = false; a.sync(); b.sync()
        assertEquals("B edit", a.policies.single().name)         // B's edit was later, so it wins everywhere
        assertEquals("B edit", b.policies.single().name)
    }

    @Test fun `a later offline edit beats an earlier online one`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.edit { it + p("a1", "W", "x.com") }; clock.tick(); a.sync(); b.sync()
        b.offline = true
        clock.tick(10_000); a.store.edit { it.map { x -> x.copy(name = "A early") } }; a.sync()
        clock.tick(10_000); b.store.edit { it.map { x -> x.copy(name = "B later") } }
        b.offline = false; b.sync(); a.sync()
        assertEquals("B later", a.policies.single().name)
        assertEquals("B later", b.policies.single().name)
    }

    @Test fun `a failed push loses nothing and the next pass sends it`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock)
        a.store.edit { it + p("a1", "W", "x.com") }
        server.failNextPush = NetworkException(RuntimeException("reset"))
        assertTrue(runCatching { a.sync() }.isFailure)
        assertEquals(1, a.policies.size)
        assertTrue(server.records.isEmpty())
        a.sync()
        assertEquals(1, server.records.size)
    }

    @Test fun `bedtime syncs and the receiving device is told to reschedule`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.setBedtime(BedtimeSettings(enabled = true, sleepMinute = 1400)); a.sync(); b.sync()
        assertEquals(1400, b.store.local.bedtime.sleepMinute)
        assertEquals(1, b.store.bedtimeApplied.size)
        b.sync(); assertEquals(1, b.store.bedtimeApplied.size)   // an unchanged value doesn't reschedule again
        assertTrue(a.store.bedtimeApplied.isEmpty())             // the author is not told about its own change
    }

    @Test fun `a second phone signing in to an account that has profiles drops its starter and keeps its own`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock)
        a.store.edit { it + p("a1", "Mac profile", "mac.com") }; a.sync()
        val seed = BlockPolicy.DEFAULT.copy(id = "seed-b")
        val b = Device(server, clock, seed = listOf(seed, p("b1", "Phone profile", "phone.com")))
        b.sync(); a.sync()
        assertEquals(setOf("a1", "b1"), b.policies.map { it.id }.toSet())
        assertEquals(setOf("a1", "b1"), a.policies.map { it.id }.toSet())
        assertTrue(server.records.keys.none { it.contains("seed-b") })
    }

    @Test fun `a rejected change is reported and not retried until edited`() {
        val server = FakeServer(); val clock = Clock(); server.rejectPolicyNamed = "bad"
        val a = Device(server, clock)
        a.store.edit { it + p("a1", "bad", "x.com") }
        val first = a.sync()
        assertEquals(1, first.rejected)
        val callsAfterFirst = server.calls
        val second = a.sync()
        assertEquals(1, second.rejected)                  // still outstanding, still reported
        assertEquals(callsAfterFirst + 1, server.calls)   // only the pull; no push attempt
        clock.tick(); a.store.edit { it.map { x -> x.copy(name = "good") } }
        assertEquals(0, a.sync().rejected)
        assertEquals(1, server.records.size)
    }

    @Test fun `a signed-out store makes no network calls at all`() {
        val server = FakeServer(); val clock = Clock()
        val d = Device(server, clock)
        d.store.local = d.store.local.copy(state = SyncState())     // never linked
        d.store.edit { it + p("a1") }
        val r = d.sync()
        assertEquals(SyncReport(0, 0, 0), r)
        assertEquals(0, server.calls)
    }

    @Test fun `pulling pages through a long history`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.edit { (1..30).fold(it) { acc, i -> acc + p("p$i", "N$i", "d$i.com") } }
        a.sync()
        val small = object : SyncApiPort {
            override suspend fun pull(cursor: Long, limit: Int) = server.pull(cursor, 7)   // the server hands out 7 at a time
            override suspend fun push(changes: List<Outgoing>) = server.push(changes)
        }
        SyncEngine(b.store, small) { clock.now }.let { runBlocking { it.syncOnce() } }
        assertEquals(30, b.policies.size)
        assertEquals(server.seq, b.store.local.state.cursor)
    }

    @Test fun `switching accounts never carries the previous account's data across`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock, user = "u1")
        a.store.edit { it + p("a1", "mine", "x.com") }; a.sync()
        a.store.local = a.store.local.copy(state = SyncLogic.link(a.store.local.state, "u2"))
        assertNull(a.store.local.state.policies["a1"])
        assertEquals(0L, a.store.local.state.cursor)
        assertFalse(a.store.local.state.initialPullDone)
    }

    @Test fun `a 410 on pull restarts from cursor zero and reconciles`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.edit { it + p("a1", "Work", "x.com") }; a.sync(); b.sync()
        assertTrue(b.store.local.state.cursor > 0)
        server.goneOnce = true
        val report = b.sync()
        assertTrue("a full pull ran: $report", report.pulled >= 1)
        assertEquals(listOf("x.com"), b.policies.single().domains)
    }

    @Test fun `policy_in_use puts the servers copy back and stops resending`() {
        val server = FakeServer(); val clock = Clock()
        val a = Device(server, clock); val b = Device(server, clock)
        a.store.edit { it + p("a1", "Work", "x.com") }; a.sync(); b.sync()
        clock.tick(); b.store.edit { it.map { x -> x.copy(domains = emptyList()) } }
        server.inUse = true
        val report = b.sync()
        assertEquals(0, report.rejected)
        assertEquals(listOf("x.com"), b.policies.single().domains)
        val calls = server.calls
        b.sync()
        assertTrue("nothing left to push", SyncLogic.planPush(b.store.local, clock.now).isEmpty())
        assertTrue(server.calls > calls)
    }
}
