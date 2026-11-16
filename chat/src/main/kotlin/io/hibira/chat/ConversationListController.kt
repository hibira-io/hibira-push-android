package io.hibira.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a [ConversationListController] shows. */
data class ConversationListState(
    /** Latest activity first, each with [Conversation.unread]. */
    val conversations: List<Conversation> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: Throwable? = null,
) {
    /** Unread messages across the loaded conversations. */
    val unread: Long get() = conversations.sumOf { it.unread ?: 0 }
}

/**
 * The user's conversations kept up to date: new messages move a conversation
 * to the top and count as unread, reads clear the count, and conversations
 * the user joins, leaves or that are deleted come and go. Collect [state].
 */
class ConversationListController internal constructor(
    private val client: ChatClient,
    private val pageSize: Int,
) {
    private val _state = MutableStateFlow(ConversationListState())
    val state: StateFlow<ConversationListState> = _state.asStateFlow()
    private var scope: CoroutineScope? = null
    private var next = ""

    @Synchronized
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        s.launch { client.events.collect { onEvent(it) } }
        s.launch { client.live.collect { refresh() } }
        s.launch { refresh() }
    }

    @Synchronized
    fun close() {
        scope?.cancel()
        scope = null
    }

    /** Loads the first page again. */
    suspend fun refresh() {
        try {
            val page = client.conversations(limit = pageSize)
            next = page.next
            _state.update { it.copy(conversations = page.conversations, loading = false, hasMore = next.isNotEmpty(), error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e) }
        }
    }

    /** Loads the next page, if there is one. */
    suspend fun loadMore() {
        if (next.isEmpty() || _state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        try {
            val page = client.conversations(limit = pageSize, before = next)
            next = page.next
            _state.update { st ->
                val have = st.conversations.map { it.id }.toSet()
                st.copy(
                    conversations = st.conversations + page.conversations.filter { it.id !in have },
                    loadingMore = false,
                    hasMore = next.isNotEmpty(),
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(loadingMore = false, error = e) }
        }
    }

    private fun onEvent(e: ChatEvent) {
        val me = client.userId
        when (e) {
            is ChatEvent.MessageAdded -> {
                val found = _state.value.conversations.any { it.id == e.conversationId }
                if (!found) {
                    scope?.launch { refresh() }
                    return
                }
                _state.update { st ->
                    val c = st.conversations.first { it.id == e.conversationId }
                    val counts = e.message.sender != me && !e.message.isSystem
                    val mine = e.message.sender == me
                    val moved = c.copy(
                        lastSeq = maxOf(c.lastSeq, e.message.seq),
                        updatedAt = e.message.createdAt ?: c.updatedAt,
                        unread = if (mine) 0 else (c.unread ?: 0) + if (counts) 1 else 0,
                    )
                    st.copy(conversations = listOf(moved) + st.conversations.filter { it.id != e.conversationId })
                }
            }
            is ChatEvent.Read -> if (e.userId == me) _state.update { st ->
                st.copy(conversations = st.conversations.map { c ->
                    if (c.id == e.conversationId && e.readSeq >= c.lastSeq) c.copy(unread = 0) else c
                })
            }
            is ChatEvent.ConversationChanged -> _state.update { st ->
                val old = st.conversations.firstOrNull { it.id == e.conversationId }
                val updated = e.conversation.copy(unread = old?.unread ?: 0)
                if (old == null) st.copy(conversations = listOf(updated) + st.conversations)
                else st.copy(conversations = st.conversations.map { if (it.id == e.conversationId) updated else it })
            }
            is ChatEvent.Left, is ChatEvent.Deleted -> _state.update { st ->
                st.copy(conversations = st.conversations.filter { it.id != e.conversationId })
            }
            else -> Unit
        }
    }
}
