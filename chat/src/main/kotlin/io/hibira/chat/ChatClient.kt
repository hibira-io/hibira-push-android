package io.hibira.chat

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The Hibira Chat API as the signed-in user (the client routes under
 * `/apps/{appId}/chat/me`).
 *
 * [token] returns a user token: an HS256 JWT your server signs with the app
 * secret (`{"sub": userId, "exp": ...}`), or one from the API's
 * `POST /chat/tokens`. It is called again before the token expires and after
 * a 401.
 *
 * Live events come from a [ChatSocket], or from your own Pusher client
 * through [handleEvent].
 */
class ChatClient @JvmOverloads constructor(
    val appId: String,
    private val token: suspend () -> String,
    host: String = DEFAULT_HOST,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val base: HttpUrl = run {
        var h = host.trimEnd('/')
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "https://$h"
        "$h/apps/".toHttpUrl().newBuilder().addPathSegment(appId).addPathSegment("chat").addPathSegment("me").build()
    }

    private val tokenLock = Mutex()
    @Volatile private var cachedToken: String? = null

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 256)
    private val _live = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** Chat events for this user, from [handleEvent] or a [ChatSocket]. */
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    /** Fires each time the realtime connection is (re)established, so views can read what they missed. */
    val live: SharedFlow<Unit> = _live.asSharedFlow()

    /** The token's user (its `sub`), once a token has been fetched; "" before. */
    val userId: String get() = cachedToken?.let { tokenUser(it) } ?: ""

    /** Fetches the token now (if needed) and returns its user. */
    suspend fun currentUser(): String = tokenUser(currentToken(false))

    private suspend fun currentToken(force: Boolean): String = tokenLock.withLock {
        val t = cachedToken
        if (!force && t != null && !expiresSoon(t)) return t
        token().also { cachedToken = it }
    }

    /**
     * Feeds an event received on the user's `#server-to-user-<id>` channel
     * (after `pusher.signin()`), as the event name and its JSON data. Returns
     * true when it was a chat event.
     */
    fun handleEvent(name: String, data: String): Boolean = handleEvent(name, JsonValues.parseData(data))

    internal fun handleEvent(name: String, data: Any?): Boolean {
        val event = ChatEvent.parse(name, data) ?: return false
        _events.tryEmit(event)
        return true
    }

    /** Tell views the realtime connection is (back) up, when you feed events from your own client. */
    fun notifyLive() {
        _live.tryEmit(Unit)
    }

    // ---- requests -----------------------------------------------------------

    private fun conv(id: String) = listOf("conversations", id)

    internal suspend fun call(
        method: String,
        path: List<String>,
        body: Map<String, Any?>? = null,
        query: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val url = base.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> if (v != null && v != "") addQueryParameter(k, v.toString()) }
        }.build()
        val payload: RequestBody? = body?.let { JsonValues.stringify(it).toRequestBody(JSON) }
        for (attempt in 0..1) {
            val t = currentToken(attempt > 0)
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $t")
                .header("Accept", "application/json")
                .header("X-Hibira-Client", "hibira-chat-kotlin/$SDK_VERSION")
                .method(method, payload ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(null) else null)
                .build()
            val (status, text) = execute(req)
            if (status == 401 && attempt == 0) continue
            val data = JsonValues.parse(text).asMap()
            if (status >= 400) {
                throw ChatException(status, data.str("error") ?: "Chat API request failed ($status)")
            }
            return data
        }
        throw ChatException(401, "unauthorized")
    }

    private suspend fun execute(req: Request): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ChatException(0, e.message ?: "request failed", e))
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.use { it.body?.string() ?: "" }
                if (cont.isActive) cont.resume(response.code to text)
            }
        })
    }

    /** The user's conversations, latest activity first, each with its unread count. */
    @JvmOverloads
    suspend fun conversations(limit: Int? = null, before: String? = null): ConversationPage {
        val d = call("GET", listOf("conversations"), query = mapOf("limit" to limit, "before" to before))
        return ConversationPage(d["conversations"].asList().map { Conversation.fromJson(it.asMap()) }, d.str("next") ?: "")
    }

    suspend fun conversation(id: String): Conversation =
        Conversation.fromJson(call("GET", conv(id))["conversation"].asMap())

    /**
     * Messages oldest first: those after [after] (up to [limit], at most 200),
     * or the newest [last].
     */
    @JvmOverloads
    suspend fun messages(id: String, after: Long? = null, last: Int? = null, limit: Int? = null): MessagePage {
        val d = call("GET", conv(id) + "messages", query = mapOf("after" to after, "last" to last, "limit" to limit))
        return MessagePage(d["messages"].asList().map { Message.fromJson(it.asMap()) }, d.long("last_seq") ?: 0)
    }

    /** Messages containing [q] (inside words, ignoring case), newest first. */
    @JvmOverloads
    suspend fun search(id: String, q: String, before: String? = null, limit: Int? = null): SearchPage {
        val d = call("GET", conv(id) + "search", query = mapOf("q" to q, "before" to before, "limit" to limit))
        return SearchPage(d["messages"].asList().map { Message.fromJson(it.asMap()) }, d.str("next") ?: "")
    }

    /** Sends a message as the user. [visibleTo] may hold only the user's own role. */
    @JvmOverloads
    suspend fun send(
        id: String,
        text: String,
        metadata: Map<String, Any?>? = null,
        attachments: List<Attachment> = emptyList(),
        name: String? = null,
        visibleTo: List<String>? = null,
    ): SendResult {
        val body = buildMap<String, Any?> {
            put("text", text)
            if (metadata != null) put("metadata", metadata)
            if (attachments.isNotEmpty()) put("attachments", attachments.map { it.toJson() })
            if (name != null) put("name", name)
            if (visibleTo != null) put("visible_to", visibleTo)
        }
        val d = call("POST", conv(id) + "messages", body)
        val push = d["push"].asMap()
        return SendResult(Message.fromJson(d["message"].asMap()), push.long("users")?.toInt(), push.str("error"))
    }

    /** Edits the user's own message; [metadata] null keeps it. */
    @JvmOverloads
    suspend fun edit(id: String, seq: Long, text: String, metadata: Map<String, Any?>? = null): Message {
        val body = buildMap<String, Any?> {
            put("text", text)
            if (metadata != null) put("metadata", metadata)
        }
        return Message.fromJson(call("PUT", conv(id) + listOf("messages", "$seq"), body)["message"].asMap())
    }

    /** Deletes the user's own message; it keeps its place with [Message.deletedAt] set. */
    suspend fun delete(id: String, seq: Long): Message =
        Message.fromJson(call("DELETE", conv(id) + listOf("messages", "$seq"))["message"].asMap())

    suspend fun react(id: String, seq: Long, emoji: String): Message =
        Message.fromJson(call("POST", conv(id) + listOf("messages", "$seq", "reactions"), mapOf("emoji" to emoji))["message"].asMap())

    suspend fun unreact(id: String, seq: Long, emoji: String): Message =
        Message.fromJson(call("DELETE", conv(id) + listOf("messages", "$seq", "reactions"), query = mapOf("emoji" to emoji))["message"].asMap())

    @JvmOverloads
    suspend fun report(id: String, seq: Long, reason: String = ""): Report =
        Report.fromJson(call("POST", conv(id) + listOf("messages", "$seq", "reports"), mapOf("reason" to reason))["report"].asMap())

    /** Marks the conversation read up to [seq]; returns the user's read_seq (it never goes back). */
    suspend fun read(id: String, seq: Long): Long =
        call("POST", conv(id) + "read", mapOf("seq" to seq)).long("read_seq") ?: seq

    /** Tells the other members the user is typing (or stopped). Nothing is stored. */
    @JvmOverloads
    suspend fun typing(id: String, typing: Boolean = true) {
        call("POST", conv(id) + "typing", mapOf("typing" to typing))
    }

    /**
     * Uploads a file (up to 25 MB) straight to storage; send the returned
     * [Attachment] in a message's attachments.
     */
    suspend fun upload(id: String, name: String, contentType: String, bytes: ByteArray): Attachment =
        upload(id, name, contentType, bytes.size.toLong(), bytes.toRequestBody(contentType.toMediaType()))

    /** Uploads a file from a [RequestBody] you build (for example from a content URI), [size] bytes long. */
    suspend fun upload(id: String, name: String, contentType: String, size: Long, body: RequestBody): Attachment {
        val d = call("POST", conv(id) + "attachments", mapOf("name" to name, "content_type" to contentType, "size" to size))
        val attachment = Attachment.fromJson(d["attachment"].asMap())
        val upload = d["upload"].asMap()
        val req = Request.Builder().url(upload.str("url") ?: throw ChatException(0, "no upload URL"))
            .apply { upload["headers"].asMap().forEach { (k, v) -> header(k, v.toString()) } }
            .method(upload.str("method") ?: "PUT", body)
            .build()
        val (status, _) = execute(req)
        if (status !in 200..299) throw ChatException(status, "upload failed ($status)")
        return attachment
    }

    /** A live view of one conversation. Call [ConversationController.start], and [ConversationController.close] when done. */
    @JvmOverloads
    fun conversationController(id: String, pageSize: Int = 50) = ConversationController(this, id, pageSize)

    /** A live view of the user's conversations with unread counts. */
    @JvmOverloads
    fun conversationList(pageSize: Int = 30) = ConversationListController(this, pageSize)

    companion object {
        const val DEFAULT_HOST = "api.hibira.io"
        private val JSON = "application/json".toMediaType()

        /** The user ID (`sub`) inside a user token, or "" when it cannot be read. */
        @JvmStatic
        fun tokenUser(token: String): String = claims(token).str("sub") ?: ""

        internal fun claims(token: String): Map<String, Any?> = try {
            val part = token.split('.')[1]
            JsonValues.parse(String(Base64.getUrlDecoder().decode(part.trimEnd('=')))).asMap()
        } catch (_: Exception) {
            emptyMap()
        }

        internal fun expiresSoon(token: String): Boolean {
            val exp = claims(token).long("exp") ?: return false
            return exp - System.currentTimeMillis() / 1000 < 30
        }
    }
}
