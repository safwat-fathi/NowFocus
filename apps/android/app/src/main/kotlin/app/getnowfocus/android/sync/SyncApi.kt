package app.getnowfocus.android.sync

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The server said no. [code] is the stable machine-readable one (services/api/WIRE_FORMAT.md). */
class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

/** Could not reach the server at all (offline, DNS, TLS, timeout). Always safe to retry later. */
class NetworkException(cause: Throwable) : Exception(cause.message ?: "Network error", cause)

/** The refresh token is dead (device revoked or token lost): the user has to sign in again. */
class AuthExpired : Exception("Signed out: this device's session ended")

data class StoredAuth(val userId: String, val email: String, val deviceId: String, val refreshToken: String)

interface AuthStore {
    suspend fun load(): StoredAuth?
    suspend fun save(auth: StoredAuth)
    suspend fun clear()
}

data class AccountSession(val userId: String, val email: String, val deviceId: String)
data class DeviceInfo(val id: String, val name: String, val platform: String, val lastSeenAt: String?, val current: Boolean, val revoked: Boolean)
data class PullPage(val changes: List<ServerRecord>, val cursor: Long, val hasMore: Boolean)

/** What the sync engine needs from the network. */
interface SyncApiPort {
    suspend fun pull(cursor: Long, limit: Int = 500): PullPage
    suspend fun push(changes: List<Outgoing>): List<PushOutcome>
}

/**
 * HTTP client for services/api. Access tokens live in memory only; the refresh token goes through [AuthStore].
 * Refresh is single-flight (one at a time) and the new refresh token is persisted BEFORE it is used, because
 * refresh tokens are single-use: losing one signs the device out.
 */
class SyncApi(
    baseUrl: String,
    private val userAgent: String,
    private val auth: AuthStore,
    private val http: OkHttpClient = defaultClient(),
) : SyncApiPort {
    private val base = baseUrl.trimEnd('/')
    private val refreshLock = Mutex()
    @Volatile private var accessToken: String? = null

    // ---- account

    suspend fun register(email: String, password: String, deviceName: String): AccountSession = open("/v1/auth/register", email, password, deviceName)
    suspend fun login(email: String, password: String, deviceName: String): AccountSession = open("/v1/auth/login", email, password, deviceName)

    private suspend fun open(path: String, email: String, password: String, deviceName: String): AccountSession {
        val body = JSONObject().put("email", email.trim()).put("password", password)
            .put("device", JSONObject().put("name", deviceName).put("platform", "android"))
        val res = send(Request.Builder().url(base + path).post(body.toRequestBody(JSON)))
        val user = res.getJSONObject("user")
        val stored = StoredAuth(user.getString("id"), user.getString("email"), res.getJSONObject("device").getString("id"), res.getString("refreshToken"))
        auth.save(stored)
        accessToken = res.getString("accessToken")
        return AccountSession(stored.userId, stored.email, stored.deviceId)
    }

    /** Best effort: whatever the network says, this device forgets its tokens. */
    suspend fun logout() {
        runCatching { authed { it.url("$base/v1/auth/logout").post("".toRequestBody(null)) } }
        forget()
    }

    suspend fun forget() { accessToken = null; auth.clear() }

    suspend fun deleteAccount(password: String) {
        authed { it.url("$base/v1/me/delete").post(JSONObject().put("password", password).toRequestBody(JSON)) }
        forget()
    }

    suspend fun devices(): List<DeviceInfo> =
        JSONArray(authedText { it.url("$base/v1/devices").get() }).objects()
            .map { DeviceInfo(it.getString("id"), it.s("name") ?: "", it.s("platform") ?: "", it.s("lastSeenAt"), it.b("current", false), !it.isNull("revokedAt")) }

    suspend fun revokeDevice(id: String) { authed { it.url("$base/v1/devices/$id").delete() } }

    // ---- sync

    override suspend fun pull(cursor: Long, limit: Int): PullPage {
        val res = authed { it.url("$base/v1/sync/pull?cursor=$cursor&limit=$limit").get() }
        return PullPage(res.arr("changes").objects().map(::record), res.l("cursor") ?: cursor, res.b("hasMore", false))
    }

    override suspend fun push(changes: List<Outgoing>): List<PushOutcome> {
        val body = JSONObject().put("changes", JSONArray().also { arr ->
            changes.forEach { c ->
                arr.put(JSONObject().put("type", c.type).put("id", c.id).put("updatedAt", Instant.ofEpochMilli(c.updatedAt).toString())
                    .put("data", c.dataJson?.let { JSONObject(it) } ?: JSONObject()).also { if (c.deleted) it.put("deleted", true) })
            }
        })
        val res = authed { it.url("$base/v1/sync/push").post(body.toRequestBody(JSON)) }
        return res.arr("results").objects().map { PushOutcome(it.s("type") ?: "", it.s("id") ?: "", it.s("status") ?: "rejected", it.obj("record")?.let(::record), it.s("code")) }
    }

    /** A fresh access token for the WebSocket. */
    suspend fun accessTokenForSocket(): String {
        accessToken?.let { return it }
        refresh()
        return accessToken ?: throw AuthExpired()
    }

    /** The token the socket just used was refused (it expired): get a newer one. */
    suspend fun refreshedAccessToken(stale: String): String {
        refresh(stale)
        return accessToken ?: throw AuthExpired()
    }

    // ---- plumbing

    private fun record(o: JSONObject) = ServerRecord(
        type = o.s("type") ?: "", id = o.s("id") ?: "", dataJson = (o.obj("data") ?: JSONObject()).toString(),
        deleted = o.b("deleted", false), revision = o.i("revision") ?: 0, updatedAt = o.s("updatedAt")?.let(::parseTime) ?: 0,
    )

    private suspend fun authed(build: (Request.Builder) -> Request.Builder): JSONObject =
        authedText(build).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    /** Runs an authenticated call: one transparent refresh on 401, then it gives up with [AuthExpired] or the server's error. */
    private suspend fun authedText(build: (Request.Builder) -> Request.Builder): String {
        val token = accessToken ?: run { refresh(); accessToken ?: throw AuthExpired() }
        try {
            return sendText(build(Request.Builder()).header("Authorization", "Bearer $token"))
        } catch (e: ApiException) {
            if (e.status != 401) throw e
        }
        refresh(stale = token)
        val fresh = accessToken ?: throw AuthExpired()
        try {
            return sendText(build(Request.Builder()).header("Authorization", "Bearer $fresh"))
        } catch (e: ApiException) {
            if (e.status == 401) { forget(); throw AuthExpired() }
            throw e
        }
    }

    /** [stale] is the access token that just failed: if someone else already replaced it, don't refresh again. */
    private suspend fun refresh(stale: String? = null) = refreshLock.withLock {
        if (stale != null && accessToken != null && accessToken != stale) return@withLock
        val stored = auth.load() ?: throw AuthExpired()
        val res = try {
            send(Request.Builder().url("$base/v1/auth/refresh").post(JSONObject().put("refreshToken", stored.refreshToken).toRequestBody(JSON)))
        } catch (e: ApiException) {
            if (e.status == 401) { forget(); throw AuthExpired() }
            throw e
        }
        auth.save(stored.copy(refreshToken = res.getString("refreshToken")))   // persist the new single-use token first
        accessToken = res.getString("accessToken")
    }

    private suspend fun send(req: Request.Builder): JSONObject = sendText(req).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private suspend fun sendText(req: Request.Builder): String {
        val r = req.header("User-Agent", userAgent).header("Accept", "application/json").build()
        http.newCall(r).await().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val o = runCatching { JSONObject(text) }.getOrNull()
                val msg = o?.let { j -> j.s("message") ?: j.arr("message").items().joinToString("; ") }.orEmpty()
                throw ApiException(it.code, o?.s("code"), msg.ifEmpty { "HTTP ${it.code}" })
            }
            return text
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS).build()
        internal fun parseTime(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }
}

private fun JSONObject.toRequestBody(type: okhttp3.MediaType) = toString().toRequestBody(type)

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont: CancellableContinuation<Response> ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(NetworkException(e)) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
