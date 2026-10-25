package app.getnowfocus.android.sync

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `GET /v1/sync/pull` is also how the app learns its plan: 200 is Pro, 403 `pro_required` is Free. */
class ProProbeTest {
    private class Auth : AuthStore {
        var stored: StoredAuth? = StoredAuth("u", "a@b.co", "d", "d.secret")
        var pro: Boolean? = null
        override suspend fun load() = stored
        override suspend fun save(auth: StoredAuth) { stored = auth }
        override suspend fun clear() { stored = null }
        override suspend fun setPro(pro: Boolean) { this.pro = pro }
    }

    /** A server that refreshes tokens fine and answers every pull with [status] and [body]. */
    private fun api(auth: Auth, status: Int, body: String): SyncApi {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val refreshing = chain.request().url.encodedPath.endsWith("/auth/refresh")
            val (code, text) = if (refreshing) 200 to """{"accessToken":"a","refreshToken":"d.next","expiresIn":900}""" else status to body
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(text.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SyncApi("http://localhost", "test", auth, http)
    }

    private val page = """{"changes":[],"cursor":0,"hasMore":false}"""
    private fun denied(code: String) = """{"statusCode":403,"code":"$code","message":"no"}"""

    @Test fun `a pull that works means Pro`() = runBlocking {
        val auth = Auth()
        api(auth, 200, page).pull(0)
        assertEquals(true, auth.pro)
    }

    @Test fun `a pro_required 403 means Free, and the error still reaches the caller`() = runBlocking {
        val auth = Auth()
        try { api(auth, 403, denied("pro_required")).pull(0); fail("expected the 403") } catch (e: ApiException) {
            assertEquals("pro_required", e.code)
        }
        assertEquals(false, auth.pro)
    }

    @Test fun `any other failure says nothing about the plan`() = runBlocking {
        for ((status, body) in listOf(403 to denied("wrong_password"), 500 to """{"message":"oops"}""")) {
            val auth = Auth()
            try { api(auth, status, body).pull(0); fail("expected a failure") } catch (_: ApiException) {}
            assertNull("status $status", auth.pro)
        }
    }

    @Test fun `the refresh token survives the probe`() = runBlocking {
        val auth = Auth()
        api(auth, 200, page).pull(0)
        assertTrue(auth.stored?.refreshToken == "d.next")
    }
}
