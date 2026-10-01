package app.getnowfocus.android.sync

import app.getnowfocus.android.BedtimeSettings
import app.getnowfocus.android.BlockPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Same switch as [LiveSyncTest]: needs SYNC_IT_URL pointing at a running services/api. */
class LiveControllerTest {
    private class MemoryAuth : AuthStore {
        var stored: StoredAuth? = null
        override suspend fun load() = stored
        override suspend fun save(auth: StoredAuth) { stored = auth }
        override suspend fun clear() { stored = null }
    }

    private class Phone(val url: String, val name: String, val scope: CoroutineScope) {
        val clock = Clock()
        val auth = MemoryAuth()
        val api = SyncApi(url, "NowFocus-Android/it", auth)
        val store = FakeStore(Local(emptyList(), BedtimeSettings(), SyncState()), clock)
        val changes = MutableSharedFlow<Unit>(replay = 1).also { it.tryEmit(Unit) }
        val controller = SyncController(scope, SyncEngine(store, api) { clock.now }, api, auth, store, SyncSocket(url, "NowFocus-Android/it"), changes, name)
        fun edit(f: (List<BlockPolicy>) -> List<BlockPolicy>) { Thread.sleep(3); clock.now = System.currentTimeMillis(); store.edit(f); changes.tryEmit(Unit) }
    }

    private suspend fun until(ms: Long = 20_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out waiting for: $what" }; delay(50) }
    }

    @Test fun `the websocket tells a device when another one changes something`() {
        val url = System.getenv("SYNC_IT_URL")
        assumeTrue("set SYNC_IT_URL to run against a live API", url != null)
        runBlocking {
            val auth = MemoryAuth(); val api = SyncApi(url!!, "NowFocus-Android/it", auth)
            val authB = MemoryAuth(); val apiB = SyncApi(url, "NowFocus-Android/it", authB)
            val email = "ws-${System.currentTimeMillis()}@example.com"
            try {
                api.register(email, "pw-pw-pw-pw", "A"); apiB.login(email, "pw-pw-pw-pw", "B")
                withTimeout(15_000) {
                    val events = SyncSocket(url, "NowFocus-Android/it").connect(api.accessTokenForSocket())
                    val hello = events.first()
                    assertEquals(SocketEvent.Changes(0), hello)                  // current cursor on connect
                    val ev = async(Dispatchers.Default) { events.first { it is SocketEvent.Changes && it.cursor > 0 } }
                    delay(500)                                                   // let the second connection register
                    val id = "22222222-2222-4222-8222-222222222222"
                    apiB.push(listOf(Outgoing("policy", id, System.currentTimeMillis(), BlockPolicy(id, "X", listOf("x.com")).let { PolicyWire.merge(it, null).toString() }, false, "f")))
                    assertEquals(SocketEvent.Changes(1), ev.await())
                }
                // revoking A closes its socket with a clear signal
                withTimeout(15_000) {
                    val events = SyncSocket(url, "NowFocus-Android/it").connect(apiB.accessTokenForSocket())
                    events.first()
                    val closed = async(Dispatchers.Default) { events.first { it is SocketEvent.Revoked || it is SocketEvent.Closed } }
                    delay(300)
                    api.revokeDevice(authB.stored!!.deviceId)
                    val e = closed.await()
                    assertTrue("expected Revoked or Closed(4403), got $e", e is SocketEvent.Revoked || (e is SocketEvent.Closed && e.code == 4403))
                }
            } finally { runCatching { api.deleteAccount("pw-pw-pw-pw") } }
        }
    }

    @Test fun `the server's close codes reach us promptly, so an expired token is noticed`() {
        val url = System.getenv("SYNC_IT_URL")
        assumeTrue("set SYNC_IT_URL to run against a live API", url != null)
        runBlocking {
            // A junk token is accepted at the upgrade and closed by the server with 4401; the app must hear that quickly.
            val e = withTimeout(5_000) { SyncSocket(url!!, "NowFocus-Android/it").connect("junk").first { it is SocketEvent.Closed } }
            assertEquals(SocketEvent.Closed(4401), e)
        }
    }

    @Test fun `a refused access token is replaced and the rotated refresh token is saved`() {
        val url = System.getenv("SYNC_IT_URL")
        assumeTrue("set SYNC_IT_URL to run against a live API", url != null)
        runBlocking {
            val auth = MemoryAuth(); val api = SyncApi(url!!, "NowFocus-Android/it", auth)
            try {
                api.register("rf-${System.currentTimeMillis()}@example.com", "pw-pw-pw-pw", "A")
                val first = api.accessTokenForSocket(); val refreshBefore = auth.stored!!.refreshToken
                delay(1_100)                                            // a JWT issued in the same second would be identical
                val second = api.refreshedAccessToken(first)
                assertTrue("a new access token", second != first)
                assertTrue("the single-use refresh token was rotated and persisted", auth.stored!!.refreshToken != refreshBefore)
                assertEquals(SocketEvent.Changes(0), withTimeout(5_000) { SyncSocket(url, "NowFocus-Android/it").connect(second).first() })
                assertTrue(api.pull(0).changes.isEmpty())              // and HTTP still works with the rotated pair
            } finally { runCatching { api.deleteAccount("pw-pw-pw-pw") } }
        }
    }

    @Test fun `controllers on two phones keep each other up to date on their own`() {
        val url = System.getenv("SYNC_IT_URL")
        assumeTrue("set SYNC_IT_URL to run against a live API", url != null)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        runBlocking {
            val email = "ctl-${System.currentTimeMillis()}@example.com"
            val a = Phone(url!!, "Phone A", scope); val b = Phone(url, "Phone B", scope)
            try {
                a.controller.start(); b.controller.start()
                a.controller.setForeground(true); b.controller.setForeground(true)   // the socket only runs on screen
                until(what = "controllers loaded") { a.controller.status.value.loaded && b.controller.status.value.loaded }

                // Wrong password is a friendly message, not an exception.
                assertEquals("Wrong email or password.", a.controller.signIn(email, "pw-pw-pw-pw", false))
                assertNull(a.controller.signIn(email, "pw-pw-pw-pw", true))                       // creates the account
                assertNull(b.controller.signIn(email, "pw-pw-pw-pw", false))
                assertEquals("An account with this email already exists. Sign in instead.", a.controller.signIn(email, "pw-pw-pw-pw", true))
                until(what = "both signed in and idle") { a.controller.status.value.lastSyncedAt != null && b.controller.status.value.lastSyncedAt != null }

                // A edits: B must get it without anyone pressing anything (edit -> debounce -> push -> websocket -> pull).
                a.edit { it + BlockPolicy("33333333-3333-4333-8333-333333333333", "From A", listOf("a.com")) }
                until(what = "B received A's profile") { b.store.local.policies.any { it.name == "From A" } }

                b.edit { it.map { p -> p.copy(name = "From B") } }
                until(what = "A received B's edit") { a.store.local.policies.any { it.name == "From B" } }

                // Sign out keeps data; it stops the sync.
                a.controller.signOut()
                assertTrue(!a.controller.status.value.signedIn)
                assertEquals(1, a.store.local.policies.size)
                assertNull(a.auth.stored)
                b.edit { it + BlockPolicy("44444444-4444-4444-8444-444444444444", "While A is out", listOf("z.com")) }
                delay(4_000)
                assertTrue("a signed-out phone must not receive anything", a.store.local.policies.none { it.name == "While A is out" })

                // Wrong password on delete, then the real thing.
                assertEquals("Wrong password.", b.controller.deleteAccount("nope-nope-nope"))
                assertNull(b.controller.deleteAccount("pw-pw-pw-pw"))
                assertEquals(SyncState(), b.store.local.state)
                assertEquals(2, b.store.local.policies.size)   // its data stays on the phone
            } finally {
                runCatching { b.api.deleteAccount("pw-pw-pw-pw") }
                scope.cancel()
            }
        }
    }
}
