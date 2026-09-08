package com.bitchat.android.messaging.conversation

import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.ConversationId

/** What kind of file is attached, which decides how it is shown rather than how it travels. */
enum class AttachmentKind { IMAGE, FILE, VOICE }

/**
 * A file that came with a message.
 *
 * Files do not travel inside the application envelope. The envelope rides the private *text* path,
 * which caps a body at 255 bytes — far less than any real document — so attachments use the
 * transport's own private file path instead, which fragments and reassembles them properly. What is
 * recorded here is the result: a local path to something already received in full.
 */
data class Attachment(
    val path: String,
    val kind: AttachmentKind,
    val fileName: String
)

/**
 * One message in a conversation, as the application understands it.
 *
 * [senderPublicKey] is the *verified* sender — this type is only ever constructed after admission
 * has checked the claim against the transport's proof, so nothing holding a [DirectMessage] needs
 * to re-examine whether the attribution is trustworthy.
 */
data class DirectMessage(
    val messageId: String,
    val conversationId: ConversationId,
    val senderPublicKey: MessagingPublicKey,
    val recipientPublicKey: MessagingPublicKey,
    val content: String,
    val timestampEpochMillis: Long,
    val isOutgoing: Boolean,
    val deliveryState: DeliveryState,
    /** Present when this message is a file rather than text. */
    val attachment: Attachment? = null
) {
    /** The other party, whichever direction this went. */
    val counterparty: MessagingPublicKey
        get() = if (isOutgoing) recipientPublicKey else senderPublicKey
}

/**
 * A one-to-one thread.
 *
 * Exactly two participants, and the identity of the thread is derived from them rather than
 * assigned, so the same pair always resolves to the same conversation without any negotiation.
 */
data class DirectConversation(
    val id: ConversationId,
    /** The person on the other side. The local user is the implicit second participant. */
    val counterparty: MessagingPublicKey,
    val messages: List<DirectMessage>,
    val unreadCount: Int
) {
    val lastMessage: DirectMessage? get() = messages.lastOrNull()

    val lastActivityMillis: Long get() = lastMessage?.timestampEpochMillis ?: 0L

    val hasUnread: Boolean get() = unreadCount > 0

    companion object {
        fun of(
            localKey: MessagingPublicKey,
            counterparty: MessagingPublicKey,
            messages: List<DirectMessage>,
            unreadCount: Int = 0
        ): DirectConversation = DirectConversation(
            id = ConversationId.direct(localKey, counterparty),
            counterparty = counterparty,
            messages = messages.sortedBy { it.timestampEpochMillis },
            unreadCount = unreadCount
        )
    }
}
