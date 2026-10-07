package app.getnowfocus.android.sync

import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real SyncApi and SyncEngine against a real services/api. Skipped unless SYNC_IT_URL is set:
 *   (cd services/api && DATABASE_URL=... JWT_SECRET=... PORT=3996 node dist/main.js)
 *   SYNC_IT_URL=http://127.0.0.1:3996 ./gradlew testDebugUnitTest --tests '*LiveSyncTest'
 * Field names, enum casing and ISO formats can only be proven wrong against the real server.
 */
class LiveSyncTest {
    private class MemoryAuth : AuthStore {
        var stored: StoredAuth? = null
        override suspend fun load() = stored
        override suspend fun save(auth: StoredAuth) { stored = auth }
        override suspend fun clear() { stored = null }
    }

    private class Phone(url: String, val clock: Clock) {
        val auth = MemoryAuth()
        val api = SyncApi(url, "NowFocus-Android/it", auth)
        lateinit var store: FakeStore
        lateinit var engine: SyncEngine
        suspend fun link(session: AccountSession, seed: List<BlockPolicy> = emptyList()) {
            store = FakeStore(Local(seed, BedtimeSettings(), SyncLogic.link(SyncState(), session.userId)), clock)
            engine = SyncEngine(store, api) { clock.now }
        }
        suspend fun sync() = engine.syncOnce()
        fun at() { Thread.sleep(3); clock.now = System.currentTimeMillis() }
    }

    @Test fun `report an issue is accepted signed out`() {
        val url = System.getenv("SYNC_IT_URL")
        assumeTrue("set SYNC_IT_URL to run against a live API", url != null)
        runBlocking { SyncApi(url!!, "NowFocus-Android/it", MemoryAuth()).reportIssue("it works", "", "it", "Android test") }
    }

    @Test fun `two phones converge through the real API`() {
        val url = System.getenv("SYNC_IT_URL")
        assumeTrue("set SYNC_IT_URL to run against a live API", url != null)
        runBlocking {
            val email = "it-${System.currentTimeMillis()}@example.com"
            val password = "pw-pw-pw-pw"
            val a = Phone(url!!, Clock()); val b = Phone(url, Clock())
            try {
                a.link(a.api.register(email, password, "Test phone A"))
                b.link(b.api.login(email, password, "Test phone B"))
                assertEquals(2, a.api.devices().count { !it.revoked })

                // A creates a profile (a messy domain: the server normalizes it) and bedtime.
                a.at(); a.store.edit { it + BlockPolicy(id = "11111111-1111-4111-8111-111111111111", name = "Work", domains = listOf("https://WWW.Example.com/x", "reddit.com")) }
                a.at(); a.store.setBedtime(BedtimeSettings(enabled = true, sleepMinute = 1400, policyId = "11111111-1111-4111-8111-111111111111"))
                val up = a.sync(); assertTrue("pushed something: $up", up.pushed >= 2); assertEquals(0, up.rejected)

                b.sync()
                val seen = b.store.local.policies.single()
                assertEquals("Work", seen.name)
                assertEquals(listOf("example.com", "reddit.com"), seen.domains)       // normalized by the server, adopted here
                assertEquals(1400, b.store.local.bedtime.sleepMinute)
                assertEquals(1, b.store.bedtimeApplied.size)

                // Steady state: another round changes nothing and sends nothing.
                a.sync(); b.sync()
                assertTrue(SyncLogic.planPush(a.store.local, System.currentTimeMillis()).isEmpty())
                assertTrue(SyncLogic.planPush(b.store.local, System.currentTimeMillis()).isEmpty())

                // B edits, A receives; A deletes, B receives.
                b.at(); b.store.edit { it.map { p -> p.copy(name = "Work (B)", domains = p.domains + "news.ycombinator.com") } }
                b.sync(); a.sync()
                assertEquals(listOf("Work (B)"), a.store.local.policies.map { it.name })
                assertEquals(3, a.store.local.policies.single().domains.size)
                a.at(); a.store.edit { emptyList() }
                a.sync(); b.sync()
                assertTrue(b.store.local.policies.isEmpty())

                // Revoking B from A cuts B off: its next call fails with "signed out".
                val bId = b.auth.stored!!.deviceId
                a.api.revokeDevice(bId)
                try { b.sync(); fail("a revoked device must not keep syncing") } catch (_: AuthExpired) { }
                assertEquals(null, b.auth.stored)                    // tokens were forgotten, local data untouched
            } finally {
                runCatching { a.api.deleteAccount(password) }        // always leave the server clean
            }
        }
    }
}
