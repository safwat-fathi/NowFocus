package app.getnowfocus.android.sync

import app.getnowfocus.android.EnforcementMode
import app.getnowfocus.android.FocusSession
import app.getnowfocus.android.FocusSessionStatus
import app.getnowfocus.android.AppRule
import app.getnowfocus.android.BlockPolicy
import app.getnowfocus.android.PartialRule
import app.getnowfocus.android.PolicyMode
import app.getnowfocus.android.SessionOrigin
import app.getnowfocus.android.SessionType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionWireTest {
    private val now = 1_800_000_000_000L
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private val hour = 3_600_000L

    private fun record(
        id: String = "s1", status: String = "active", mode: String = "normal", type: String = "focus", source: String? = "user",
        start: Long = now - hour / 2, end: Long = now + hour, extra: JSONObject.() -> Unit = {},
    ): ServerRecord {
        val o = JSONObject().put("id", id).put("policyId", "P1").put("sessionType", type).put("status", status)
            .put("enforcementMode", mode).put("startAt", iso(start)).put("endAt", iso(end)).put("startedOn", "Windows PC")
            .put("policySnapshot", JSONObject()
                .put("domainRules", org.json.JSONArray().put(JSONObject().put("domain", "YouTube.com").put("enabled", true)).put(JSONObject().put("domain", "bad domain").put("enabled", true)).put(JSONObject().put("domain", "off.com").put("enabled", false)))
                .put("applicationRules", org.json.JSONArray()
                    .put(JSONObject().put("platform", "android").put("nativeIdentifier", "com.insta").put("enabled", true))
                    .put(JSONObject().put("platform", "windows").put("nativeIdentifier", "C:\\a.exe").put("enabled", true)))
                .put("partial", org.json.JSONArray().put("YT_SHORTS").put("NOPE")))
        if (source != null) o.put("source", source)
        o.extra()
        return ServerRecord("session", id, o.toString(), false, 1, now)
    }

    private fun local(id: String = "mine", status: FocusSessionStatus = FocusSessionStatus.ACTIVE, end: Long = now + hour, origin: SessionOrigin = SessionOrigin.USER, type: SessionType = SessionType.FOCUS) =
        FocusSession(id = id, policyId = "p1", startAt = now - hour / 2, endAt = end, status = status, createdAt = now, origin = origin, sessionType = type)

    @Test
    fun `a pulled session keeps only what this phone can enforce, validated`() {
        val r = SessionWire.parse(record())!!
        assertEquals(setOf("youtube.com"), r.domains)          // normalised; the invalid and the disabled one are dropped
        assertEquals(setOf("com.insta"), r.packages)           // another platform's app is not ours
        assertEquals(setOf(PartialRule.YT_SHORTS), r.partial)  // unknown names are dropped
        assertEquals("p1", r.policyId)                         // ids compare lowercase
        assertEquals("Windows PC", r.startedOn)
        assertEquals(EnforcementMode.NORMAL, r.mode)
    }

    @Test
    fun `only user-started focus sessions are read`() {
        assertNull(SessionWire.parse(record(type = "bedtime_winddown")))
        assertNull(SessionWire.parse(record(source = "schedule")))
        assertNull(SessionWire.parse(record(mode = "weird")))
        assertNotNull(SessionWire.parse(record(source = null))) // a missing source means "user"
        assertNull(SessionWire.parse(record().copy(deleted = true)))
    }

    private fun remote(r: ServerRecord = record()) = SessionWire.parse(r)!!

    @Test
    fun `joins a running session when nothing is running here`() {
        assertEquals(SessionDecision.Join, SessionWire.decide(remote(), null, now, joinEnabled = true))
        assertEquals(SessionDecision.Join, SessionWire.decide(remote(), local(status = FocusSessionStatus.COMPLETED), now, true))
    }

    @Test
    fun `waits its turn behind a session already running here`() {
        assertEquals(SessionDecision.Defer, SessionWire.decide(remote(), local(), now, true))
    }

    @Test
    fun `does nothing when the user switched joining off`() {
        assertEquals(SessionDecision.Ignore, SessionWire.decide(remote(), null, now, joinEnabled = false))
    }

    @Test
    fun `never joins a session that is over, not started, or longer than a day`() {
        assertEquals(SessionDecision.Ignore, SessionWire.decide(remote(record(end = now - 1)), null, now, true))
        assertEquals(SessionDecision.Ignore, SessionWire.decide(remote(record(status = "completed")), null, now, true))
        assertEquals(SessionDecision.Ignore, SessionWire.decide(remote(record(status = "cancelled")), null, now, true))
        assertEquals(SessionDecision.Ignore, SessionWire.decide(remote(record(start = now - hour, end = now - hour + SessionWire.MAX_MS + 1)), null, now, true))
        assertEquals(SessionDecision.Join, SessionWire.decide(remote(record(start = now - hour, end = now - hour + SessionWire.MAX_MS)), null, now, true))
        assertEquals(SessionDecision.Ignore, SessionWire.decide(remote(record(status = "scheduled", start = now + hour, end = now + 2 * hour)), null, now, true))
    }

    @Test
    fun `a session cancelled elsewhere ends the same session here, and only that one`() {
        val cancelled = remote(record(id = "mine", status = "cancelled"))
        assertEquals(SessionDecision.EndLocal, SessionWire.decide(cancelled, local(id = "mine"), now, true))
        assertEquals(SessionDecision.Ignore, SessionWire.decide(cancelled, local(id = "other"), now, true))   // not ours: and it is over anyway
        assertEquals(SessionDecision.Ignore, SessionWire.decide(cancelled, local(id = "mine", status = FocusSessionStatus.COMPLETED), now, true))
    }

    @Test
    fun `a session extended elsewhere is extended here, never shortened`() {
        val longer = remote(record(id = "mine", end = now + 2 * hour))
        assertEquals(SessionDecision.Extend(now + 2 * hour), SessionWire.decide(longer, local(id = "mine"), now, true))
        val shorter = remote(record(id = "mine", end = now + hour / 2))
        assertEquals(SessionDecision.Ignore, SessionWire.decide(shorter, local(id = "mine"), now, true))
    }

    @Test
    fun `an extension past a day is ignored`() {
        val absurd = remote(record(id = "mine", end = now + 3 * SessionWire.MAX_MS))
        assertEquals(SessionDecision.Ignore, SessionWire.decide(absurd, local(id = "mine"), now, true))
    }

    @Test
    fun `a started session goes out with its snapshot and the device it started on`() {
        val s = local(id = "abc").copy(domains = setOf("a.com"), packages = setOf("com.x"), partial = setOf(PartialRule.FB_REELS), enforcementMode = EnforcementMode.STRICT)
        val o = SessionWire.toWire(s, "Galaxy", null)
        assertEquals("focus", o.getString("sessionType"))
        assertEquals("user", o.getString("source"))
        assertEquals("active", o.getString("status"))
        assertEquals("strict", o.getString("enforcementMode"))
        assertEquals("Galaxy", o.getString("startedOn"))
        assertEquals("a.com", o.getJSONObject("policySnapshot").getJSONArray("domainRules").getJSONObject(0).getString("domain"))
        assertEquals("android", o.getJSONObject("policySnapshot").getJSONArray("applicationRules").getJSONObject(0).getString("platform"))
    }

    @Test
    fun `a whitelist session goes out with its mode and empty rule arrays`() {
        // Android 0.6 joins from the snapshot alone and would read allowed apps as apps to block.
        val s = local(id = "abc").copy(domains = setOf("a.com"), packages = setOf("com.code"), partial = setOf(PartialRule.FB_REELS), policyMode = PolicyMode.ALLOWLIST)
        val snap = SessionWire.toWire(s, "Galaxy", null).getJSONObject("policySnapshot")
        assertEquals("allowlist", snap.getString("mode"))
        assertEquals(0, snap.getJSONArray("domainRules").length())
        assertEquals(0, snap.getJSONArray("applicationRules").length())
        assertEquals("FB_REELS", snap.getJSONArray("partial").getString(0))
        assertEquals("blocklist", SessionWire.toWire(local(id = "abc"), "Galaxy", null).getJSONObject("policySnapshot").getString("mode"))
    }

    @Test
    fun `a pulled session carries the mode its device wrote, a missing one is a blocklist`() {
        assertEquals(PolicyMode.BLOCKLIST, SessionWire.parse(record())!!.policyMode)
        val allow = record { getJSONObject("policySnapshot").put("mode", "allowlist") }
        assertEquals(PolicyMode.ALLOWLIST, SessionWire.parse(allow)!!.policyMode)
    }

    @Test
    fun `a whitelist is only enforceable here with the synced policy and an app on this phone`() {
        val allow = SessionWire.parse(record { getJSONObject("policySnapshot").put("mode", "allowlist") })!!
        val list = BlockPolicy(id = "p1", name = "n", mode = PolicyMode.ALLOWLIST, apps = listOf(AppRule("com.code", "Code")))
        assertFalse("policy not here yet: wait", SessionWire.enforceable(allow, emptyList()))
        assertFalse("no app on this phone", SessionWire.enforceable(allow, listOf(list.copy(apps = emptyList()))))
        assertFalse("a blocklist under the same id is not what the other device meant", SessionWire.enforceable(allow, listOf(list.copy(mode = PolicyMode.BLOCKLIST))))
        assertTrue(SessionWire.enforceable(allow, listOf(list)))
        // A snapshot without a mode (an older device) over a policy that is a whitelist here must still not be applied as blocks.
        val old = SessionWire.parse(record())!!
        assertFalse(SessionWire.enforceable(old, listOf(list.copy(apps = emptyList()))))
        assertTrue("an ordinary blocklist session is unchanged", SessionWire.enforceable(old, emptyList()))
    }

    @Test
    fun `a status change to a joined session keeps the other device's extras`() {
        val base = JSONObject(record(id = "r1").dataJson).put("extraFromWindows", "keep me")
        val cancelled = local(id = "r1").copy(status = FocusSessionStatus.CANCELLED, cancelledAt = now, origin = SessionOrigin.REMOTE)
        val o = SessionWire.toWire(cancelled, "Galaxy", base)
        assertEquals("cancelled", o.getString("status"))
        assertEquals("keep me", o.getString("extraFromWindows"))
        assertEquals("Windows PC", o.getString("startedOn"))          // not overwritten by this phone's name
        assertEquals(base.getJSONObject("policySnapshot").toString(), o.getJSONObject("policySnapshot").toString())
        assertNotNull(o.opt("cancelledAt"))
    }

    @Test
    fun `fingerprint changes when what the server should hear changes`() {
        val s = local()
        assertEquals(SessionWire.fingerprint(s), SessionWire.fingerprint(s.copy(passes = listOf())))
        assertNotEquals(SessionWire.fingerprint(s), SessionWire.fingerprint(s.copy(status = FocusSessionStatus.CANCELLED, cancelledAt = now)))
        assertNotEquals(SessionWire.fingerprint(s), SessionWire.fingerprint(s.copy(endAt = s.endAt + 1)))
    }

    @Test
    fun `bedtime and schedule sessions never leave the device`() {
        assertTrue(SessionWire.syncs(local()))
        assertTrue(SessionWire.syncs(local(origin = SessionOrigin.REMOTE)))
        assertFalse(SessionWire.syncs(local(origin = SessionOrigin.SCHEDULE)))
        assertFalse(SessionWire.syncs(local(type = SessionType.BEDTIME_WINDDOWN)))
    }

    @Test
    fun `session sync state survives encoding and keeps only what matters`() {
        val st = SessionSyncState(acked = mapOf("a" to "fp", "b" to "fp2"), raws = mapOf("a" to "{}"), pending = mapOf("c" to "{}"))
        assertEquals(st, SessionSyncState.decode(st.encode()))
        val pruned = st.pruned(setOf("a", "c"))
        assertEquals(setOf("a"), pruned.acked.keys)
        assertEquals(setOf("c"), pruned.pending.keys)
        assertEquals(SessionSyncState(), SessionSyncState.decode(null))
        assertEquals(SessionSyncState(), SessionSyncState.decode("not json"))
    }
}
