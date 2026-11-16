package io.hibira.chat

/** A Chat API event, sent to each member concerned on their `#server-to-user-<id>` channel. */
sealed class ChatEvent {
    abstract val conversationId: String

    /** `chat.message`: a new message. */
    data class MessageAdded(override val conversationId: String, val message: Message) : ChatEvent()

    /** `chat.message_updated`: a message was edited, deleted or its reactions changed. */
    data class MessageUpdated(override val conversationId: String, val message: Message) : ChatEvent()

    /** `chat.typing`: another member started or stopped typing. */
    data class Typing(override val conversationId: String, val userId: String, val typing: Boolean) : ChatEvent()

    /** `chat.read`: a member's read_seq moved forward. */
    data class Read(override val conversationId: String, val userId: String, val readSeq: Long) : ChatEvent()

    /** `chat.conversation`: the conversation was created or changed, or a member was added. */
    data class ConversationChanged(val conversation: Conversation) : ChatEvent() {
        override val conversationId get() = conversation.id
    }

    /** `chat.left`: the user was removed from the conversation. */
    data class Left(override val conversationId: String) : ChatEvent()

    /** `chat.deleted`: the conversation was deleted. */
    data class Deleted(override val conversationId: String) : ChatEvent()

    companion object {
        /** The event names the Chat API sends. */
        @JvmField
        val NAMES = listOf("chat.message", "chat.message_updated", "chat.typing", "chat.read", "chat.conversation", "chat.left", "chat.deleted")

        internal fun parse(name: String, data: Any?): ChatEvent? {
            val d = data.asMap()
            val id = d.str("conversation_id") ?: ""
            return when (name) {
                "chat.message" -> MessageAdded(id, Message.fromJson(d["message"].asMap()))
                "chat.message_updated" -> MessageUpdated(id, Message.fromJson(d["message"].asMap()))
                "chat.typing" -> Typing(id, d.str("user_id") ?: "", d["typing"] != false)
                "chat.read" -> Read(id, d.str("user_id") ?: "", d.long("read_seq") ?: 0)
                "chat.conversation" -> ConversationChanged(Conversation.fromJson(d["conversation"].asMap()))
                "chat.left" -> Left(id)
                "chat.deleted" -> Deleted(id)
                else -> null
            }?.takeIf { it.conversationId.isNotEmpty() }
        }
    }
}
