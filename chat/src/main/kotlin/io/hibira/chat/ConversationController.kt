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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a [ConversationController] shows. */
data class ConversationState(
    val conversation: Conversation? = null,
    /** Loaded messages ordered by seq, without repeats. */
    val messages: List<Message> = emptyList(),
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    /** Older messages can be loaded with [ConversationController.loadOlder]. */
    val hasOlder: Boolean = false,
    /** Members typing right now (not the user). */
    val typing: Set<String> = emptySet(),
    /** Each member's read_seq, kept live from `chat.read`. */
    val reads: Map<String, Long> = emptyMap(),
    /** The conversation was deleted or the user removed from it. */
    val closed: Boolean = false,
    val error: Throwable? = null,
) {
    val lastSeq: Long get() = messages.lastOrNull()?.seq ?: 0

    /** Members other than [userId] who have read message [seq]. */
    fun readBy(seq: Long, userId: String): List<String> = reads.filter { it.key != userId && it.value >= seq }.keys.toList()
}

/**
 * One conversation kept up to date: the newest page of messages, older pages
 * on demand, live messages, edits, reactions, typing and read receipts, and a
 * catch-up read each time the realtime connection comes back. Collect [state].
 */
class ConversationController internal constructor(
    private val client: ChatClient,
    val conversationId: String,
    private val pageSize: Int,
) {
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private val loadLock = Mutex()
    private val typingTimers = mutableMapOf<String, Job>()
    private var floor = Long.MAX_VALUE
    private var lastTypingSent = 0L
    private var typingOff: Job? = null

    /** Starts loading and listening; safe to call again. */
    @Synchronized
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch { client.events.collect { if (it.conversationId == conversationId) onEvent(it) } }
        s.launch { client.live.collect { refresh() } }
        s.launch { refresh() }
    }

    /** Stops listening. */
    @Synchronized
    fun close() {
        scope?.cancel()
        scope = null
        typingTimers.clear()
    }

    private fun merge(list: List<Message>, page: List<Message>): List<Message> {
        if (page.isEmpty()) return list
        val bySeq = LinkedHashMap<Long, Message>()
        list.forEach { bySeq[it.seq] = it }
        page.forEach { bySeq[it.seq] = it }
        return bySeq.values.sortedBy { it.seq }
    }

    /**
     * Reads what is new, and re-reads the newest messages for edits, deletes
     * and reactions made while the connection was down.
     */
    suspend fun refresh() = loadLock.withLock {
        try {
            if (_state.value.conversation == null || _state.value.messages.isEmpty()) {
                val conv = client.conversation(conversationId)
                _state.update { it.copy(conversation = conv, reads = conv.members.associate { m -> m.userId to m.readSeq }) }
            }
            var after = _state.value.lastSeq
            if (after > 0) {
                while (true) {
                    val page = client.messages(conversationId, after = after, limit = 200)
                    _state.update { it.copy(messages = merge(it.messages, page.messages)) }
                    if (page.messages.size < 200) break
                    after = page.messages.last().seq
                }
            }
            val count = _state.value.messages.size.coerceIn(pageSize, 200)
            val page = client.messages(conversationId, last = count)
            if (floor == Long.MAX_VALUE) {
                floor = page.messages.firstOrNull()?.seq ?: (page.lastSeq + 1)
            }
            _state.update {
                it.copy(messages = merge(it.messages, page.messages), loading = false, hasOlder = floor > 1, error = null)
            }
        } catch (e: ChatException) {
            if (e.status == 404) _state.update { it.copy(closed = true, loading = false, error = e) }
            else _state.update { it.copy(loading = false, error = e) }
        }
    }

    /** Loads the page of messages before the oldest one loaded. */
    suspend fun loadOlder() {
        if (floor <= 1 || _state.value.loadingOlder) return
        _state.update { it.copy(loadingOlder = true) }
        try {
            val after = (floor - 1 - pageSize).coerceAtLeast(0)
            val page = client.messages(conversationId, after = after, limit = (floor - 1 - after).toInt())
            floor = after + 1
            _state.update { it.copy(messages = merge(it.messages, page.messages), loadingOlder = false, hasOlder = floor > 1) }
        } catch (e: Exception) {
            _state.update { it.copy(loadingOlder = false, error = e) }
        }
    }

    /** Sends a message and adds the stored copy. */
    @JvmOverloads
    suspend fun send(text: String, metadata: Map<String, Any?>? = null, attachments: List<Attachment> = emptyList()): Message {
        stopTyping()
        val sent = client.send(conversationId, text, metadata, attachments).message
        _state.update { it.copy(messages = merge(it.messages, listOf(sent))) }
        return sent
    }

    suspend fun edit(seq: Long, text: String): Message = apply(client.edit(conversationId, seq, text))
    suspend fun delete(seq: Long): Message = apply(client.delete(conversationId, seq))
    suspend fun react(seq: Long, emoji: String): Message = apply(client.react(conversationId, seq, emoji))
    suspend fun unreact(seq: Long, emoji: String): Message = apply(client.unreact(conversationId, seq, emoji))

    /** Translates message [seq] into [language] on request. */
    suspend fun translate(seq: Long, language: String): Message = apply(client.translate(conversationId, seq, language))

    /** Adds the user's reaction, or takes it back if they already reacted with [emoji]. */
    suspend fun toggleReaction(seq: Long, emoji: String): Message {
        val me = client.currentUser()
        val mine = _state.value.messages.firstOrNull { it.seq == seq }?.reactions?.get(emoji)?.contains(me) == true
        return if (mine) unreact(seq, emoji) else react(seq, emoji)
    }

    private fun apply(m: Message): Message {
        _state.update { it.copy(messages = merge(it.messages, listOf(m))) }
        return m
    }

    /** Marks everything loaded as read, when it is not already. */
    suspend fun markRead() {
        val seq = _state.value.lastSeq
        val me = client.currentUser()
        if (seq == 0L || (_state.value.reads[me] ?: 0) >= seq) return
        val readSeq = client.read(conversationId, seq)
        _state.update { it.copy(reads = it.reads + (me to maxOf(readSeq, it.reads[me] ?: 0))) }
    }

    /**
     * Call on every keystroke: tells the others the user is typing at most
     * every 3 seconds, and that they stopped after 5 seconds without one.
     */
    fun typing() {
        val s = scope ?: return
        val now = System.currentTimeMillis()
        if (now - lastTypingSent > TYPING_EVERY_MS) {
            lastTypingSent = now
            s.launch { runCatching { client.typing(conversationId, true) } }
        }
        typingOff?.cancel()
        typingOff = s.launch {
            delay(TYPING_IDLE_MS)
            stopTyping()
        }
    }

    /** Tells the others the user stopped typing (sent only if they were told it started). */
    suspend fun stopTyping() {
        typingOff?.cancel()
        if (lastTypingSent == 0L) return
        lastTypingSent = 0L
        runCatching { client.typing(conversationId, false) }
    }

    private fun onEvent(e: ChatEvent) {
        when (e) {
            is ChatEvent.MessageAdded -> {
                clearTyping(e.message.sender)
                _state.update {
                    val reads = e.message.sender?.let { s -> it.reads + (s to maxOf(e.message.seq, it.reads[s] ?: 0)) } ?: it.reads
                    it.copy(messages = merge(it.messages, listOf(e.message)), reads = reads)
                }
            }
            is ChatEvent.MessageUpdated -> _state.update { st ->
                if (st.messages.any { it.seq == e.message.seq }) st.copy(messages = merge(st.messages, listOf(e.message))) else st
            }
            is ChatEvent.Typing -> if (e.userId != client.userId) {
                if (e.typing) {
                    _state.update { it.copy(typing = it.typing + e.userId) }
                    synchronized(typingTimers) {
                        typingTimers.remove(e.userId)?.cancel()
                        scope?.launch {
                            delay(TYPING_SHOW_MS)
                            clearTyping(e.userId)
                        }?.let { typingTimers[e.userId] = it }
                    }
                } else {
                    clearTyping(e.userId)
                }
            }
            is ChatEvent.Read -> _state.update { it.copy(reads = it.reads + (e.userId to maxOf(e.readSeq, it.reads[e.userId] ?: 0))) }
            is ChatEvent.ConversationChanged -> _state.update {
                it.copy(conversation = e.conversation, reads = it.reads + e.conversation.members.associate { m -> m.userId to maxOf(m.readSeq, it.reads[m.userId] ?: 0) })
            }
            is ChatEvent.Left, is ChatEvent.Deleted -> _state.update { it.copy(closed = true) }
        }
    }

    private fun clearTyping(userId: String?) {
        if (userId == null) return
        synchronized(typingTimers) { typingTimers.remove(userId)?.cancel() }
        _state.update { if (userId in it.typing) it.copy(typing = it.typing - userId) else it }
    }

    companion object {
        /** How long a typing indicator shows without another `chat.typing`. */
        const val TYPING_SHOW_MS = 8_000L
        private const val TYPING_EVERY_MS = 3_000L
        private const val TYPING_IDLE_MS = 5_000L
    }
}
