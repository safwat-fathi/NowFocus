package app.getnowfocus.android.sync

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed interface SocketEvent {
    /** Another device committed changes; if [cursor] is ahead of ours, sync. */
    data class Changes(val cursor: Long) : SocketEvent
    /** The server revoked this device. */
    data object Revoked : SocketEvent
    /** The connection ended. [code] is the WebSocket close code, or an HTTP status when the upgrade itself was refused (401), or -1. */
    data class Closed(val code: Int) : SocketEvent
}

/**
 * WS /ws/device. Only a hint that a pull is worthwhile: sync never depends on it. The server pings every 30 s;
 * OkHttp answers pongs on its own and also pings from this side so a dead connection is noticed.
 */
class SyncSocket(baseUrl: String, private val userAgent: String, client: OkHttpClient = SyncApi.defaultClient()) {
    private val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/ws/device"
    private val client = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()

    fun connect(accessToken: String): Flow<SocketEvent> = callbackFlow {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").header("User-Agent", userAgent).build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (o.s("type")) {
                    "changes" -> o.l("cursor")?.let { trySend(SocketEvent.Changes(it)) }
                    "device_revoked" -> trySend(SocketEvent.Revoked)
                }
            }
            // The server closes with 4401/4403. OkHttp only reports onClosed after WE answer the close frame, so answer it
            // here; otherwise the code would arrive late (or as a plain failure) and an expired token would go unnoticed.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null); trySend(SocketEvent.Closed(code)); close() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { trySend(SocketEvent.Closed(code)); close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { trySend(SocketEvent.Closed(response?.code ?: -1)); close() }
        })
        awaitClose { ws.cancel() }
    }
}
