package io.hibira.chat

/** A member of a conversation and how far they have read. */
data class Member(
    val userId: String,
    val readSeq: Long,
    val role: String? = null,
) {
    companion object {
        fun fromJson(m: Map<String, Any?>) = Member(
            userId = m.str("user_id") ?: "",
            readSeq = m.long("read_seq") ?: 0,
            role = m.str("role")?.takeIf { it.isNotEmpty() },
        )
    }
}

/** A conversation of the Chat API. */
data class Conversation(
    val id: String,
    val name: String? = null,
    val metadata: Map<String, Any?> = emptyMap(),
    val members: List<Member> = emptyList(),
    val lastSeq: Long = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** In conversation lists: the messages after the user's read_seq that are not system messages. */
    val unread: Long? = null,
    /** The whole object as the API sent it. */
    val raw: Map<String, Any?> = emptyMap(),
) {
    /** How far [userId] has read, or 0. */
    fun readSeq(userId: String): Long = members.firstOrNull { it.userId == userId }?.readSeq ?: 0

    companion object {
        fun fromJson(m: Map<String, Any?>) = Conversation(
            id = m.str("id") ?: "",
            name = m.str("name")?.takeIf { it.isNotEmpty() },
            metadata = m["metadata"].asMap(),
            members = m["members"].asList().map { Member.fromJson(it.asMap()) },
            lastSeq = m.long("last_seq") ?: 0,
            createdAt = m.str("created_at"),
            updatedAt = m.str("updated_at"),
            unread = m.long("unread"),
            raw = m,
        )
    }
}

/** A file attached to a message. [url] downloads it for an hour. */
data class Attachment(
    val id: String,
    val name: String,
    val contentType: String,
    val size: Long = 0,
    val url: String? = null,
) {
    internal fun toJson(): Map<String, Any?> = mapOf("id" to id, "name" to name, "content_type" to contentType)

    companion object {
        fun fromJson(m: Map<String, Any?>) = Attachment(
            id = m.str("id") ?: "",
            name = m.str("name") ?: "",
            contentType = m.str("content_type") ?: "application/octet-stream",
            size = m.long("size") ?: 0,
            url = m.str("url"),
        )
    }
}

/** A stored message. A changed message keeps its [seq]. */
data class Message(
    val id: String,
    val seq: Long,
    /** The sender's user ID; null for system messages. */
    val sender: String? = null,
    val name: String? = null,
    /** "text", or "system" for messages your server posts about the conversation. */
    val kind: String = "text",
    val text: String = "",
    val metadata: Map<String, Any?> = emptyMap(),
    val createdAt: String? = null,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    /** Each emoji and the members who reacted, in order. */
    val reactions: Map<String, List<String>> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    /** The roles this message is limited to (internal notes), if any. */
    val visibleTo: List<String> = emptyList(),
    val raw: Map<String, Any?> = emptyMap(),
) {
    val isSystem get() = kind == "system"
    val isDeleted get() = deletedAt != null
    val isEdited get() = editedAt != null

    companion object {
        fun fromJson(m: Map<String, Any?>) = Message(
            id = m.str("id") ?: "",
            seq = m.long("seq") ?: 0,
            sender = m.str("sender")?.takeIf { it.isNotEmpty() },
            name = m.str("name")?.takeIf { it.isNotEmpty() },
            kind = m.str("kind")?.takeIf { it.isNotEmpty() } ?: "text",
            text = m.str("text") ?: "",
            metadata = m["metadata"].asMap(),
            createdAt = m.str("created_at"),
            editedAt = m.str("edited_at")?.takeIf { it.isNotEmpty() },
            deletedAt = m.str("deleted_at")?.takeIf { it.isNotEmpty() },
            reactions = m["reactions"].asMap().mapValues { (_, v) -> v.asList().map { it.toString() } },
            attachments = m["attachments"].asList().map { Attachment.fromJson(it.asMap()) },
            visibleTo = m["visible_to"].asList().map { it.toString() },
            raw = m,
        )
    }
}

/** A message someone reported. */
data class Report(
    val id: String,
    val conversationId: String,
    val seq: Long,
    val reportedBy: String,
    val reason: String,
    val message: Message?,
) {
    companion object {
        fun fromJson(m: Map<String, Any?>) = Report(
            id = m.str("id") ?: "",
            conversationId = m.str("conversation_id") ?: "",
            seq = m.long("seq") ?: 0,
            reportedBy = m.str("reported_by") ?: "",
            reason = m.str("reason") ?: "",
            message = (m["message"] as? Map<*, *>)?.let { Message.fromJson(it.asMap()) },
        )
    }
}

/** A page of the user's conversations, latest activity first. Pass [next] as `before` for the next page. */
data class ConversationPage(val conversations: List<Conversation>, val next: String)

/** Messages, oldest first, and the conversation's newest sequence number. */
data class MessagePage(val messages: List<Message>, val lastSeq: Long)

/** Search results, newest first. Pass [next] as `before` for more. */
data class SearchPage(val messages: List<Message>, val next: String)

/** The answer to a send: the stored message, and how many members were pushed. */
data class SendResult(val message: Message, val pushedUsers: Int?, val pushError: String?)

/** An error answered by the Chat API, with its HTTP status (0 when the request did not reach it). */
class ChatException(val status: Int, message: String, cause: Throwable? = null) : Exception(message, cause)
