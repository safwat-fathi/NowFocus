package app.getnowfocus.android.sync

import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLogicTest {
    private val user = "user-1"
    private fun policy(id: String, name: String = "P $id", vararg domains: String) = BlockPolicy(id = id, name = name, domains = domains.toList())

    /** A server record for [p], as the server would hold it. */
    private fun serverRaw(p: BlockPolicy, extra: String = ""): String = PolicyWire.merge(p, null).also { if (extra.isNotEmpty()) it.put("mode", extra) }.toString()
    private fun rec(p: BlockPolicy, updatedAt: Long, rev: Int = 1, mode: String = "") = ServerRecord(SyncLogic.POLICY, p.id, serverRaw(p, mode), false, rev, updatedAt)
    private fun tomb(id: String, updatedAt: Long) = ServerRecord(SyncLogic.POLICY, id, "{}", true, 2, updatedAt)
    private fun bedRec(b: BedtimeSettings, updatedAt: Long) = ServerRecord(SyncLogic.BEDTIME, "default", BedtimeWire.merge(b, null).toString(), false, 1, updatedAt)

    /** A device that is linked and fully synced with exactly [policies]. */
    private fun synced(vararg policies: BlockPolicy, bedtime: BedtimeSettings = BedtimeSettings()): Local {
        val metas = policies.associate { it.id to Meta(rawJson = serverRaw(it), revision = 1) }
        val bm = if (BedtimeWire.same(bedtime, BedtimeWire.DEFAULT)) null else Meta(rawJson = BedtimeWire.merge(bedtime, null).toString(), revision = 1)
        return Local(policies.toList(), bedtime, SyncState(userId = user, cursor = 5, initialPullDone = true, policies = metas, bedtime = bm))
    }

    private fun Local.edit(now: Long, f: (List<BlockPolicy>) -> List<BlockPolicy>): Local {
        val next = f(policies)
        return copy(policies = next, state = SyncLogic.stampPolicies(state, policies, next, now))
    }

    // ------------------------------------------------------------ write-time bookkeeping

    @Test fun `an unlinked device does no bookkeeping at all`() {
        val s = SyncLogic.stampPolicies(SyncState(), emptyList(), listOf(policy("a")), 100)
        assertEquals(SyncState(), s)
        assertEquals(SyncState(), SyncLogic.stampBedtime(SyncState(), BedtimeSettings(), BedtimeSettings(enabled = true), 100))
    }

    @Test fun `a new profile is recorded as never uploaded, stamped with the time of the change`() {
        val l = synced().edit(1000) { it + policy("a") }
        assertEquals(Meta(rawJson = null, dirtyAt = 1000), l.state.policies["a"])
    }

    @Test fun `editing a synced profile stamps it, every further edit moves the stamp, and undoing it clears it`() {
        val a = policy("a", "A", "x.com")
        var l = synced(a).edit(1000) { listOf(a.copy(domains = listOf("x.com", "y.com"))) }
        assertEquals(1000L, l.state.policies["a"]!!.dirtyAt)
        l = l.edit(2000) { listOf(a.copy(domains = listOf("x.com", "y.com", "z.com"))) }
        assertEquals(2000L, l.state.policies["a"]!!.dirtyAt)               // an offline edit at 10:05 must beat one at 10:02
        l = l.edit(3000) { listOf(a) }
        assertNull(l.state.policies["a"]!!.dirtyAt)                         // back to what the server has: nothing to send
    }

    @Test fun `writing one profile does not move another profile's stamp`() {
        val a = policy("a", "A", "x.com"); val b = policy("b", "B", "y.com")
        var l = synced(a, b).edit(1000) { listOf(it[0].copy(name = "A2"), it[1]) }
        l = l.edit(2000) { listOf(it[0], it[1].copy(name = "B2")) }
        assertEquals(1000L, l.state.policies["a"]!!.dirtyAt)
        assertEquals(2000L, l.state.policies["b"]!!.dirtyAt)
    }

    @Test fun `deleting a synced profile records a tombstone where it happens`() {
        val a = policy("a")
        val l = synced(a).edit(1000) { emptyList() }
        assertEquals(Meta(rawJson = serverRaw(a), revision = 1, dirtyAt = 1000, deleted = true), l.state.policies["a"])
    }

    @Test fun `deleting a profile that never reached the server leaves no trace`() {
        val l = synced().edit(1000) { it + policy("a") }.edit(2000) { emptyList() }
        assertNull(l.state.policies["a"])
        assertTrue(SyncLogic.planPush(l, 3000).isEmpty())
    }

    @Test fun `bedtime changes are stamped, and returning to the server value clears the stamp`() {
        val synced = synced(bedtime = BedtimeSettings(enabled = true))
        val changed = BedtimeSettings(enabled = true, sleepMinute = 1400)
        var s = SyncLogic.stampBedtime(synced.state, synced.bedtime, changed, 1000)
        assertEquals(1000L, s.bedtime!!.dirtyAt)
        s = SyncLogic.stampBedtime(s, changed, BedtimeSettings(enabled = true), 2000)
        assertNull(s.bedtime!!.dirtyAt)
    }

    // ------------------------------------------------------------ what gets pushed

    @Test fun `nothing is uploaded before the first full pull has been applied`() {
        val l = synced().edit(1000) { it + policy("a") }
        assertTrue(SyncLogic.planPush(l.copy(state = l.state.copy(initialPullDone = false)), 5000).isEmpty())
        assertEquals(1, SyncLogic.planPush(l, 5000).size)
    }

    @Test fun `push carries new profiles, edits, tombstones and a changed bedtime, each with the time the user made the change`() {
        val a = policy("a", "A", "x.com"); val b = policy("b", "B", "y.com"); val c = policy("c", "C")
        var l = synced(a, b, c)
        l = l.edit(1000) { it + policy("n", "New") }
        l = l.edit(2000) { it.map { p -> if (p.id == "a") p.copy(name = "A2") else p } }
        l = l.edit(3000) { it.filterNot { p -> p.id == "b" } }
        l = l.copy(bedtime = BedtimeSettings(enabled = true), state = SyncLogic.stampBedtime(l.state, l.bedtime, BedtimeSettings(enabled = true), 4000))
        val plan = SyncLogic.planPush(l, 9999).associateBy { it.type + ":" + it.id }
        assertEquals(setOf("policy:n", "policy:a", "policy:b", "bedtime_settings:default"), plan.keys)
        assertEquals(1000L, plan["policy:n"]!!.updatedAt); assertEquals(2000L, plan["policy:a"]!!.updatedAt)
        assertEquals(3000L, plan["policy:b"]!!.updatedAt); assertEquals(4000L, plan["bedtime_settings:default"]!!.updatedAt)
        assertTrue(plan["policy:b"]!!.deleted && plan["policy:b"]!!.dataJson == null)
        assertEquals("A2", JSONObject(plan["policy:a"]!!.dataJson!!).getString("name"))
    }

    @Test fun `a default bedtime that never reached the server is not uploaded, a customized one is`() {
        assertTrue(SyncLogic.planPush(synced(), 1).isEmpty())
        val custom = synced(bedtime = BedtimeSettings(enabled = true))
        val l = custom.copy(state = custom.state.copy(bedtime = null))
        assertEquals(listOf("bedtime_settings"), SyncLogic.planPush(l, 1).map { it.type })
    }

    @Test fun `a payload the server rejected is not resent until the user changes it`() {
        val l = synced().edit(1000) { it + policy("a", "A", "x.com") }
        val out = SyncLogic.planPush(l, 2000).single()
        val after = SyncLogic.applyPushResults(l, listOf(out), listOf(PushOutcome("policy", "a", "rejected", null, "invalid_data"))).local
        assertEquals(1, SyncLogic.applyPushResults(l, listOf(out), listOf(PushOutcome("policy", "a", "rejected", null, "invalid_data"))).rejected)
        assertTrue(SyncLogic.planPush(after, 3000).isEmpty())
        assertEquals(1, SyncLogic.planPush(after.edit(4000) { it.map { p -> p.copy(name = "A2") } }, 5000).size)
    }

    @Test fun `an allowlist profile is kept but never pushed, shown or turned into a deletion`() {
        val allow = policy("al", "Allow", "ok.com")
        val pulled = SyncLogic.applyPulled(synced(), listOf(rec(allow, 100, mode = "allowlist")), 6).local
        assertTrue(pulled.policies.isEmpty())                                  // not shown, not enforced
        assertFalse(pulled.state.policies["al"]!!.imported)
        assertTrue(SyncLogic.planPush(pulled, 200).isEmpty())
        val after = pulled.edit(300) { it + policy("b") }                      // unrelated edit
        assertFalse(after.state.policies["al"]!!.deleted)
        assertEquals(listOf("b"), SyncLogic.planPush(after, 400).map { it.id })
    }

    // ------------------------------------------------------------ what a pull does

    @Test fun `a new server profile is imported and a clean local one is updated`() {
        val a = policy("a", "A", "x.com")
        val res = SyncLogic.applyPulled(synced(a), listOf(rec(a.copy(name = "A from Mac", domains = listOf("x.com", "z.com")), 500, rev = 2), rec(policy("n", "N", "n.com"), 500)), 9).local
        assertEquals("A from Mac", res.policies.first { it.id == "a" }.name)
        assertEquals(listOf("x.com", "z.com"), res.policies.first { it.id == "a" }.domains)
        assertNotNull(res.policies.find { it.id == "n" })
        assertEquals(9L, res.state.cursor)
        assertNull(res.state.policies["a"]!!.dirtyAt)
        assertTrue(SyncLogic.planPush(res, 600).isEmpty())                    // adopting server data must never echo it back
    }

    @Test fun `when both sides changed a profile the later edit wins`() {
        val a = policy("a", "A", "x.com")
        val mine = synced(a).edit(2000) { listOf(a.copy(name = "mine")) }
        val serverNewer = SyncLogic.applyPulled(mine, listOf(rec(a.copy(name = "theirs"), 3000, rev = 2)), 9).local
        assertEquals("theirs", serverNewer.policies.single().name)
        assertTrue(SyncLogic.planPush(serverNewer, 4000).isEmpty())

        val serverOlder = SyncLogic.applyPulled(mine, listOf(rec(a.copy(name = "theirs"), 1000, rev = 2)), 9).local
        assertEquals("mine", serverOlder.policies.single().name)              // my edit stays...
        val plan = SyncLogic.planPush(serverOlder, 4000).single()
        assertEquals(2000L, plan.updatedAt)                                    // ...and goes up with the time I made it
        assertEquals(2, serverOlder.state.policies["a"]!!.revision)           // merged into the server's newer copy
    }

    @Test fun `a server deletion removes a clean profile but not one edited here afterwards`() {
        val a = policy("a", "A", "x.com")
        assertTrue(SyncLogic.applyPulled(synced(a), listOf(tomb("a", 500)), 9).local.policies.isEmpty())

        val edited = synced(a).edit(2000) { listOf(a.copy(name = "edited")) }
        val kept = SyncLogic.applyPulled(edited, listOf(tomb("a", 1000)), 9).local
        assertEquals("edited", kept.policies.single().name)
        val plan = SyncLogic.planPush(kept, 3000).single()                     // resurrected as a brand-new record, newer than the tombstone
        assertEquals(2000L, plan.updatedAt); assertFalse(plan.deleted)

        val gone = SyncLogic.applyPulled(edited, listOf(tomb("a", 3000)), 9).local
        assertTrue(gone.policies.isEmpty() && gone.state.policies.isEmpty())   // the deletion was later than my edit
    }

    @Test fun `a server tombstone for a profile this device never synced does not delete it`() {
        val local = Local(listOf(policy("a")), BedtimeSettings(), SyncState(userId = user))
        val res = SyncLogic.applyPulled(local, listOf(tomb("a", 500)), 3).local
        assertEquals(1, res.policies.size)
    }

    @Test fun `a local deletion confirmed by the server leaves nothing behind`() {
        val a = policy("a")
        val l = synced(a).edit(1000) { emptyList() }
        val out = SyncLogic.planPush(l, 2000).single()
        val done = SyncLogic.applyPushResults(l, listOf(out), listOf(PushOutcome("policy", "a", "applied", tomb("a", 1000), null))).local
        assertTrue(done.state.policies.isEmpty())
        assertTrue(SyncLogic.planPush(done, 3000).isEmpty())
    }

    @Test fun `a policy deleted here but edited on another device later comes back`() {
        val a = policy("a", "A", "x.com")
        val l = synced(a).edit(1000) { emptyList() }
        val res = SyncLogic.applyPulled(l, listOf(rec(a.copy(name = "revived"), 2000, rev = 3)), 9).local
        assertEquals("revived", res.policies.single().name)
        assertFalse(res.state.policies["a"]!!.deleted)
        val lWins = SyncLogic.applyPulled(l, listOf(rec(a.copy(name = "old"), 500, rev = 3)), 9).local
        assertTrue(lWins.policies.isEmpty())
        assertTrue(lWins.state.policies["a"]!!.deleted)                        // my later deletion still goes out
    }

    @Test fun `record types this build does not sync only move the cursor`() {
        val l = synced()
        val res = SyncLogic.applyPulled(l, listOf(ServerRecord("session", "s", "{}", false, 1, 1), ServerRecord("shield_item", "x", "{}", false, 1, 1)), 42).local
        assertEquals(l.copy(state = l.state.copy(cursor = 42)), res)
    }

    @Test fun `bedtime from the server is applied and reported so alarms can be rescheduled`() {
        val server = BedtimeSettings(enabled = true, sleepMinute = 1400)
        val res = SyncLogic.applyPulled(synced(), listOf(bedRec(server, 500)), 9)
        assertEquals(server, res.local.bedtime)
        assertTrue(res.bedtimeChanged)
        assertTrue(SyncLogic.planPush(res.local, 600).isEmpty())
        assertFalse(SyncLogic.applyPulled(res.local, listOf(bedRec(server, 600)), 10).bedtimeChanged)  // same value again: nothing to reschedule
    }

    @Test fun `bedtime follows last write wins too`() {
        val l = synced()
        val mine = l.copy(bedtime = BedtimeSettings(enabled = true), state = SyncLogic.stampBedtime(l.state, l.bedtime, BedtimeSettings(enabled = true), 2000))
        val older = SyncLogic.applyPulled(mine, listOf(bedRec(BedtimeSettings(sleepMinute = 1300), 1000)), 9)
        assertTrue(older.local.bedtime.enabled && !older.bedtimeChanged)
        val newer = SyncLogic.applyPulled(mine, listOf(bedRec(BedtimeSettings(sleepMinute = 1300), 3000)), 9)
        assertEquals(1300, newer.local.bedtime.sleepMinute)
    }

    // ------------------------------------------------------------ what the push answer does

    @Test fun `an accepted push becomes the new base and a later identical pass has nothing to send`() {
        val l = synced().edit(1000) { it + policy("a", "A", "x.com") }
        val out = SyncLogic.planPush(l, 2000).single()
        val saved = ServerRecord("policy", "a", out.dataJson!!, false, 1, 1000)
        val done = SyncLogic.applyPushResults(l, listOf(out), listOf(PushOutcome("policy", "a", "applied", saved, null))).local
        assertNull(done.state.policies["a"]!!.dirtyAt)
        assertTrue(SyncLogic.planPush(done, 3000).isEmpty())
    }

    @Test fun `an edit made while the push was in flight stays dirty`() {
        val l = synced().edit(1000) { it + policy("a", "A", "x.com") }
        val out = SyncLogic.planPush(l, 2000).single()
        val during = l.edit(1500) { it.map { p -> p.copy(name = "A edited again") } }
        val saved = ServerRecord("policy", "a", out.dataJson!!, false, 1, 1000)
        val done = SyncLogic.applyPushResults(during, listOf(out), listOf(PushOutcome("policy", "a", "applied", saved, null))).local
        assertEquals(1500L, done.state.policies["a"]!!.dirtyAt)
        assertEquals("A edited again", JSONObject(SyncLogic.planPush(done, 3000).single().dataJson!!).getString("name"))
    }

    @Test fun `stale means adopt the server's copy`() {
        val a = policy("a", "A", "x.com")
        val l = synced(a).edit(1000) { listOf(a.copy(name = "mine")) }
        val out = SyncLogic.planPush(l, 2000).single()
        val theirs = rec(a.copy(name = "theirs"), 5000, rev = 4)
        val done = SyncLogic.applyPushResults(l, listOf(out), listOf(PushOutcome("policy", "a", "stale", theirs, null))).local
        assertEquals("theirs", done.policies.single().name)
        assertTrue(SyncLogic.planPush(done, 6000).isEmpty())
    }

    // ------------------------------------------------------------ first sign-in on a device

    private fun fresh(policies: List<BlockPolicy>, bedtime: BedtimeSettings = BedtimeSettings()) = Local(policies, bedtime, SyncLogic.link(SyncState(), user))
    private fun seed() = BlockPolicy.DEFAULT.copy(id = "seed-1")

    @Test fun `first sign-in against an empty account uploads this device's profiles, starter included`() {
        val mine = fresh(listOf(seed(), policy("u1", "Mine", "mine.com")))
        val pulled = SyncLogic.applyPulled(mine, emptyList(), 0).local
        val done = SyncLogic.finishInitialPull(pulled, emptySet())
        assertEquals(2, done.policies.size)
        assertEquals(setOf("seed-1", "u1"), SyncLogic.planPush(done, 100).map { it.id }.toSet())
    }

    @Test fun `first sign-in against an account that already has profiles drops the untouched starter and unions the rest`() {
        val theirs = policy("t1", "Theirs", "t.com")
        val mine = fresh(listOf(seed(), policy("u1", "Mine", "mine.com")))
        val pulled = SyncLogic.applyPulled(mine, listOf(rec(theirs, 100)), 1).local
        val done = SyncLogic.finishInitialPull(pulled, emptySet())
        assertEquals(setOf("t1", "u1"), done.policies.map { it.id }.toSet())     // starter gone, union of the rest
        assertEquals(listOf("u1"), SyncLogic.planPush(done, 200).map { it.id })  // only my own profile goes up
        assertNull(done.state.policies["seed-1"])                                // and no tombstone for the dropped starter
    }

    @Test fun `an edited starter profile is kept, and so is one that bedtime or the running session points at`() {
        val theirs = policy("t1", "Theirs", "t.com")
        val edited = seed().copy(domains = BlockPolicy.DEFAULT.domains + "extra.com")
        val a = SyncLogic.finishInitialPull(SyncLogic.applyPulled(fresh(listOf(edited)), listOf(rec(theirs, 1)), 1).local, emptySet())
        assertTrue(a.policies.any { it.id == "seed-1" })
        val b = SyncLogic.finishInitialPull(SyncLogic.applyPulled(fresh(listOf(seed())), listOf(rec(theirs, 1)), 1).local, setOf("seed-1"))
        assertTrue(b.policies.any { it.id == "seed-1" })
    }

    @Test fun `first sign-in takes the account's bedtime, and uploads this device's only if the account has none`() {
        val mineCustom = fresh(emptyList(), BedtimeSettings(enabled = true, sleepMinute = 1300))
        val serverWins = SyncLogic.finishInitialPull(SyncLogic.applyPulled(mineCustom, listOf(bedRec(BedtimeSettings(enabled = true, sleepMinute = 1400), 100)), 1).local, emptySet())
        assertEquals(1400, serverWins.bedtime.sleepMinute)
        assertTrue(SyncLogic.planPush(serverWins, 200).isEmpty())

        val emptyServer = SyncLogic.finishInitialPull(SyncLogic.applyPulled(mineCustom, emptyList(), 0).local, emptySet())
        assertEquals(1300, emptyServer.bedtime.sleepMinute)
        assertEquals(listOf("bedtime_settings"), SyncLogic.planPush(emptyServer, 200).map { it.type })

        val mineDefault = SyncLogic.finishInitialPull(SyncLogic.applyPulled(fresh(emptyList()), emptyList(), 0).local, emptySet())
        assertTrue(SyncLogic.planPush(mineDefault, 200).isEmpty())            // never invents a bedtime record
    }

    @Test fun `a profile with the same id on both sides takes the server's version on first sign-in`() {
        val a = policy("a", "local name", "x.com")
        val pulled = SyncLogic.applyPulled(fresh(listOf(a)), listOf(rec(a.copy(name = "server name"), 100)), 1).local
        assertEquals("server name", pulled.policies.single().name)
    }

    @Test fun `linking a different account starts clean, the same account resumes`() {
        val l = synced(policy("a"))
        assertEquals(l.state, SyncLogic.link(l.state, user))
        val other = SyncLogic.link(l.state, "someone-else")
        assertEquals(SyncState(userId = "someone-else"), other)               // no cursor, no raw data carried over
        assertEquals(SyncState(), SyncLogic.unlink())
    }

    // ------------------------------------------------------------ persistence

    @Test fun `state survives being written and read back`() {
        val a = policy("a", "A", "x.com")
        val l = synced(a, bedtime = BedtimeSettings(enabled = true)).edit(1000) { listOf(a.copy(name = "edited")) }
        assertEquals(l.state, SyncState.decode(l.state.encode()))
    }

    @Test fun `unreadable state degrades to never-linked instead of crashing`() {
        assertEquals(SyncState(), SyncState.decode("{not json"))
        assertEquals(SyncState(), SyncState.decode(null))
        assertEquals(SyncState(), SyncState.decode(""))
    }
}
