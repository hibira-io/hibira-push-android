package io.hibira.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min

/** A signed user, as your user authentication endpoint returns it: `{"auth": ..., "user_data": ...}`. */
data class UserAuth(val auth: String, val userData: String)

/** Signs the connection in as the user: given the socket ID, return [UserAuth] (your server's `authenticateUser`). */
fun interface UserAuthenticator {
    suspend fun authenticate(socketId: String): UserAuth
}

/**
 * A realtime connection that signs in as the user (`pusher:signin`) and feeds
 * the Chat API events on `#server-to-user-<id>` to a [ChatClient]. It speaks
 * the Pusher Channels protocol, so it needs nothing else; if your app already
 * runs a Pusher client that signs in, forward its user events to
 * [ChatClient.handleEvent] instead.
 *
 * [key] is the app key (not the app ID). It reconnects with backoff until
 * [disconnect].
 */
class ChatSocket @JvmOverloads constructor(
    private val key: String,
    private val client: ChatClient,
    private val authenticator: UserAuthenticator,
    host: String = DEFAULT_HOST,
    private val http: OkHttpClient = OkHttpClient(),
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, LIVE }

    private val url = run {
        var h = host.trimEnd('/')
        if (!h.startsWith("ws://") && !h.startsWith("wss://")) h = "wss://$h"
        "$h/app/$key?protocol=7&client=hibira-chat-kotlin&version=$SDK_VERSION&flash=false"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State.DISCONNECTED)
    private val _errors = MutableStateFlow<String?>(null)

    /** CONNECTED once the socket is open, LIVE once signed in and subscribed to the user's channel. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** The last error the server or the authenticator gave, if any. */
    val lastError: StateFlow<String?> = _errors.asStateFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var socketId: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var lastActivity = 0L
    private var attempt = 0
    private var watchdog: Job? = null
    private var retry: Job? = null
    private var activityTimeoutMs = 120_000L

    /** The signed-in user's ID, once signed in. */
    val user: String? get() = userId

    @Synchronized
    fun connect() {
        wanted = true
        if (ws != null) return
        retry?.cancel()
        open()
    }

    @Synchronized
    fun disconnect() {
        wanted = false
        retry?.cancel()
        watchdog?.cancel()
        ws?.close(1000, null)
        ws = null
        socketId = null
        userId = null
        _state.value = State.DISCONNECTED
    }

    /** Disconnects for good and frees the socket's threads. */
    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun open() {
        _state.value = State.CONNECTING
        val socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
        ws = socket
    }

    private fun send(event: String, data: Map<String, Any?>) {
        ws?.send(JsonValues.stringify(mapOf("event" to event, "data" to data)))
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket != ws) return
            lastActivity = System.currentTimeMillis()
            val msg = JsonValues.parse(text).asMap()
            val event = msg.str("event") ?: return
            val data = JsonValues.parseData(msg["data"])
            val channel = msg.str("channel") ?: ""
            when (event) {
                "pusher:connection_established" -> {
                    val d = data.asMap()
                    socketId = d.str("socket_id")
                    d.long("activity_timeout")?.let { activityTimeoutMs = min(120_000L, it * 1000) }
                    attempt = 0
                    _state.value = State.CONNECTED
                    startWatchdog(webSocket)
                    signIn(webSocket)
                }
                "pusher:signin_success" -> {
                    val userData = JsonValues.parseData(data.asMap()["user_data"]).asMap()
                    val id = userData.str("id") ?: return
                    userId = id
                    send("pusher:subscribe", mapOf("channel" to "#server-to-user-$id"))
                }
                "pusher:error" -> {
                    val d = data.asMap()
                    _errors.value = d.str("message") ?: "error"
                    val code = d.long("code")?.toInt()
                    if (code != null && code in 4000..4099) {
                        wanted = false
                        webSocket.close(1000, null)
                    }
                }
                "pusher:ping" -> send("pusher:pong", emptyMap())
                "pusher:pong" -> Unit
                else -> if (channel.startsWith("#server-to-user-")) {
                    if (event == "pusher_internal:subscription_succeeded") {
                        _state.value = State.LIVE
                        client.notifyLive()
                    } else if (!event.startsWith("pusher")) {
                        client.handleEvent(event, data)
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            dropped(webSocket, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, code)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _errors.value = t.message
            dropped(webSocket, 0)
        }
    }

    private fun signIn(webSocket: WebSocket) {
        val sid = socketId ?: return
        scope.launch {
            val auth = try {
                authenticator.authenticate(sid)
            } catch (e: Exception) {
                _errors.value = "sign-in failed: ${e.message}"
                return@launch
            }
            if (webSocket == ws && sid == socketId) {
                send("pusher:signin", mapOf("auth" to auth.auth, "user_data" to auth.userData))
            }
        }
    }

    private fun startWatchdog(webSocket: WebSocket) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (webSocket == ws) {
                delay(activityTimeoutMs)
                if (System.currentTimeMillis() - lastActivity < activityTimeoutMs) continue
                send("pusher:ping", emptyMap())
                delay(PONG_TIMEOUT_MS)
                if (System.currentTimeMillis() - lastActivity >= activityTimeoutMs + PONG_TIMEOUT_MS) {
                    webSocket.cancel()
                    dropped(webSocket, 0)
                    return@launch
                }
            }
        }
    }

    @Synchronized
    private fun dropped(webSocket: WebSocket, code: Int) {
        if (webSocket != ws) return
        ws = null
        socketId = null
        userId = null
        watchdog?.cancel()
        _state.value = State.DISCONNECTED
        if (!wanted || code in 4000..4099) return
        val wait = if (code in 4200..4299) 0L else min(30_000L, 1000L shl min(attempt, 5))
        attempt++
        retry = scope.launch {
            delay(wait)
            synchronized(this@ChatSocket) { if (wanted && ws == null) open() }
        }
    }

    companion object {
        const val DEFAULT_HOST = "ws.hibira.io"
        private const val PONG_TIMEOUT_MS = 30_000L

        /**
         * An authenticator that POSTs `socket_id` as a form to your endpoint
         * (the same one pusher-js uses for `pusher.signin()`), with extra
         * [headers] such as your session's `Authorization`.
         */
        @JvmStatic
        @JvmOverloads
        fun endpoint(url: String, headers: Map<String, String> = emptyMap(), http: OkHttpClient = OkHttpClient()) =
            UserAuthenticator { socketId ->
                val req = Request.Builder().url(url)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .header("Accept", "application/json")
                    .post(FormBody.Builder().add("socket_id", socketId).build())
                    .build()
                val (status, text) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
                }
                val d = JsonValues.parse(text).asMap()
                val auth = d.str("auth")
                val userData = d["user_data"]
                if (status != 200 || auth == null || userData == null) {
                    throw ChatException(status, d.str("error") ?: "user auth endpoint answered $status")
                }
                UserAuth(auth, if (userData is String) userData else JsonValues.stringify(userData))
            }
    }
}
