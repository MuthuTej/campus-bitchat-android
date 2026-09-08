package com.bitchat.android.messaging.conversation

import com.bitchat.android.messaging.contacts.ContactRepository
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.ConversationId
import com.bitchat.android.messaging.protocol.EnvelopeAdmission
import com.bitchat.android.messaging.protocol.MessageKind
import com.bitchat.android.messaging.protocol.MessagingEnvelope
import com.bitchat.android.messaging.transport.OutboundTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * One-to-one messaging.
 *
 * Holds the two rules that make a direct conversation actually direct:
 *
 *  1. Outgoing messages are addressed to an identity, and the identity is written into the envelope
 *     so the recipient can confirm the message was meant for them.
 *  2. Incoming messages are admitted only when the sender's claim matches what the transport proved
 *     *and* the message names this device as its recipient. Everything else is dropped before it
 *     can reach a conversation.
 *
 * History is durable. The transport-facing store persists arriving private messages before this
 * layer sees them, but it holds only the raw envelopes and knows nothing of outgoing messages, which
 * never pass through it — sending goes straight to the router so that addressing cannot depend on
 * which conversation happens to be open. Keeping decoded history here is therefore not a duplicate
 * store but the only complete one, and without it a restart left the user looking at the other
 * person's side of a conversation with their own replies missing.
 */
class DirectMessageManager(
    private val localKey: MessagingPublicKey,
    private val transport: OutboundTransport,
    private val contacts: ContactRepository,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /**
     * Where history is kept. Null in tests, which exercise the conversation logic without a
     * database; in the app it is always supplied, because a messenger that forgets every
     * conversation when it closes is not a messenger.
     */
    private val persistence: DirectMessagePersistence? = null
) {

    /**
     * Repopulate from stored history.
     *
     * Called once at startup before any live traffic is offered. Messages are replayed through the
     * same bookkeeping as new ones, so a restored conversation is indistinguishable from one built
     * up live.
     */
    fun restore(messages: List<DirectMessage>, readConversations: Set<ConversationId>) {
        messages.forEach { message ->
            val bucket = messagesByConversation.getOrPut(message.conversationId) { mutableListOf() }
            if (bucket.none { it.messageId == message.messageId }) bucket.add(message)
        }
        this.readConversations.addAll(readConversations)
        // Read receipts were already sent for anything restored; re-sending on first open would
        // acknowledge the same messages again every launch.
        messages.filterNot { it.isOutgoing }
            .filter { it.conversationId in readConversations }
            .forEach { readReceiptsSent.add(it.messageId) }
        publish()
    }

    private val messagesByConversation =
        mutableMapOf<ConversationId, MutableList<DirectMessage>>()
    private val readConversations = mutableSetOf<ConversationId>()

    /**
     * Incoming messages already acknowledged as read.
     *
     * Tracked per message rather than per conversation so that a new arrival, which makes the
     * conversation unread again, does not cause a fresh read receipt for every older message the
     * next time it is opened.
     */
    private val readReceiptsSent = mutableSetOf<String>()

    private val _conversations = MutableStateFlow<List<DirectConversation>>(emptyList())

    /** Every conversation with at least one message, most recently active first. */
    val conversations: StateFlow<List<DirectConversation>> = _conversations.asStateFlow()

    /** Why a send did not happen, for the caller to surface. */
    sealed interface SendResult {
        data class Accepted(val message: DirectMessage) : SendResult
        data object BlockedRecipient : SendResult
        data object EmptyMessage : SendResult
        data object CannotMessageSelf : SendResult

        /**
         * Longer than a private message body can carry.
         *
         * Reported rather than truncated or silently dropped: the transport's own failure mode here
         * is to discard the message while reporting success, which is precisely the behaviour that
         * made this impossible to notice.
         */
        data object TooLong : SendResult
    }

    /**
     * Send a message to one person.
     *
     * A blocked recipient is refused here rather than filtered from the UI, because a block that
     * only hides the compose field is not a block.
     */
    fun send(recipient: MessagingPublicKey, text: String): SendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendResult.EmptyMessage
        if (recipient == localKey) return SendResult.CannotMessageSelf
        if (contacts.isBlocked(recipient)) return SendResult.BlockedRecipient

        val messageId = UUID.randomUUID().toString()
        val now = clockMillis()
        val envelope = MessagingEnvelope.directText(
            sender = localKey,
            recipient = recipient,
            payload = trimmed,
            messageId = messageId,
            timestampEpochMillis = now
        )

        // The transport silently discards an oversized private message while still reporting a
        // successful route, so this is checked here rather than discovered by its absence.
        if (envelope.exceedsWireLimit()) return SendResult.TooLong

        val outcome = transport.sendDirect(recipient, envelope.encode(), messageId)
        val message = DirectMessage(
            messageId = messageId,
            conversationId = envelope.conversationId,
            senderPublicKey = localKey,
            recipientPublicKey = recipient,
            content = trimmed,
            timestampEpochMillis = now,
            isOutgoing = true,
            deliveryState = outcome.toDeliveryState()
        )
        record(message)
        return SendResult.Accepted(message)
    }

    /**
     * Send machine-to-machine traffic to one person.
     *
     * Carried on the same authenticated, encrypted path as a message, but marked as control and
     * never recorded as one — so it cannot surface in a conversation as unreadable text. Group key
     * distribution uses this; without it a key would arrive looking like somebody typing gibberish.
     */
    fun sendControl(recipient: MessagingPublicKey, payload: String): SendResult {
        if (payload.isEmpty()) return SendResult.EmptyMessage
        if (recipient == localKey) return SendResult.CannotMessageSelf
        if (contacts.isBlocked(recipient)) return SendResult.BlockedRecipient

        val messageId = UUID.randomUUID().toString()
        val now = clockMillis()
        val envelope = MessagingEnvelope(
            messageId = messageId,
            conversationId = ConversationId.direct(localKey, recipient),
            kind = MessageKind.SYSTEM,
            senderPublicKey = localKey,
            recipientPublicKey = recipient,
            timestampEpochMillis = now,
            payload = payload
        )
        if (envelope.exceedsWireLimit()) return SendResult.TooLong

        val outcome = transport.sendDirect(recipient, envelope.encode(), messageId)
        return SendResult.Accepted(
            DirectMessage(
                messageId = messageId,
                conversationId = envelope.conversationId,
                senderPublicKey = localKey,
                recipientPublicKey = recipient,
                content = payload,
                timestampEpochMillis = now,
                isOutgoing = true,
                deliveryState = outcome.toDeliveryState()
            )
        )
    }

    /**
     * Offer an arriving message body to the conversation layer.
     *
     * Control traffic is admitted — so the caller can act on it — but never filed as a message.
     *
     * @param authenticatedSender the key the transport proved for the peer this came from.
     * @return the admission result, so a caller can distinguish ordinary chat text from a rejected
     *         envelope without this class deciding what to do about it.
     */
    fun receive(
        content: String,
        messageId: String,
        timestampEpochMillis: Long,
        authenticatedSender: MessagingPublicKey?
    ): EnvelopeAdmission.Result {
        val result = EnvelopeAdmission.admitDirect(
            content = content,
            messageId = messageId,
            timestampEpochMillis = timestampEpochMillis,
            authenticatedSender = authenticatedSender,
            localKey = localKey
        )
        if (result !is EnvelopeAdmission.Result.Admitted) return result

        val envelope = result.envelope
        // A blocked sender's message is discarded after admission: it was genuinely for this user,
        // and they have said they do not want it.
        if (contacts.isBlocked(envelope.senderPublicKey)) return result

        // Machine traffic is handed back to the caller without being recorded. Rendering it would
        // put protocol bytes in front of the user as though someone had typed them.
        if (envelope.kind == MessageKind.SYSTEM) {
            // A receipt is the sender's own message coming back confirmed, so it is applied here
            // rather than handed outward: nothing above this layer needs to know receipts exist.
            DeliveryReceipt.decode(envelope.payload)?.let { (state, messageId) ->
                applyReceipt(from = envelope.senderPublicKey, messageId = messageId, state = state)
            }
            return result
        }

        val isNew = record(
            DirectMessage(
                messageId = envelope.messageId,
                conversationId = envelope.conversationId,
                senderPublicKey = envelope.senderPublicKey,
                recipientPublicKey = localKey,
                content = envelope.payload,
                timestampEpochMillis = envelope.timestampEpochMillis,
                isOutgoing = false,
                deliveryState = DeliveryState.DELIVERED
            )
        )

        // Acknowledged once, on first sight only. Re-acknowledging a message already stored would
        // put traffic on the network every time the conversation list happened to be re-examined.
        if (isNew) {
            DeliveryReceipt.encode(DeliveryState.DELIVERED, envelope.messageId)?.let { receipt ->
                sendControl(envelope.senderPublicKey, receipt)
            }
        }
        return result
    }

    /**
     * Apply a confirmation from the person a message was actually sent to.
     *
     * Scoped to that person's conversation rather than searching by message id alone. Admission
     * proves who sent the receipt, but not that the message it names was ever theirs to confirm —
     * so without this check anyone who learned or guessed a message id could mark someone else's
     * messages as read, asserting that a person saw something they were never sent.
     */
    private fun applyReceipt(
        from: MessagingPublicKey,
        messageId: String,
        state: DeliveryState
    ): Boolean {
        val conversationId = ConversationId.direct(localKey, from)
        val messages = messagesByConversation[conversationId] ?: return false
        val index = messages.indexOfFirst {
            it.messageId == messageId && it.isOutgoing && it.recipientPublicKey == from
        }
        if (index < 0) return false

        val existing = messages[index]
        if (state.progress <= existing.deliveryState.progress) return false
        messages[index] = existing.copy(deliveryState = state)
        persistence?.persistDeliveryState(existing.messageId, state)
        publish()
        return true
    }

    /**
     * File the arrival or departure of an attachment.
     *
     * Attachments never pass through envelope admission because they never travel as envelopes:
     * the transport carries them on its own private file path and hands over a completed local
     * file. Recipiency is therefore already established by the same Noise session that carries a
     * text message — this only records what arrived.
     */
    fun recordAttachment(
        counterparty: MessagingPublicKey,
        messageId: String,
        timestampEpochMillis: Long,
        attachment: Attachment,
        isOutgoing: Boolean,
        deliveryState: DeliveryState = if (isOutgoing) DeliveryState.SENT else DeliveryState.DELIVERED
    ): Boolean {
        if (contacts.isBlocked(counterparty)) return false
        return record(
            DirectMessage(
                messageId = messageId,
                conversationId = ConversationId.direct(localKey, counterparty),
                senderPublicKey = if (isOutgoing) localKey else counterparty,
                recipientPublicKey = if (isOutgoing) counterparty else localKey,
                content = attachment.fileName,
                timestampEpochMillis = timestampEpochMillis,
                isOutgoing = isOutgoing,
                deliveryState = deliveryState,
                attachment = attachment
            )
        )
    }

    /** Update an outgoing message as the transport learns more about it. */
    fun updateDeliveryState(messageId: String, state: DeliveryState): Boolean {
        var changed = false
        messagesByConversation.forEach { (_, messages) ->
            val index = messages.indexOfFirst { it.messageId == messageId && it.isOutgoing }
            if (index >= 0) {
                val existing = messages[index]
                // Never move a message backwards: a late acknowledgement of an earlier hop must not
                // turn a delivered message back into a sending one. Ranked by DeliveryState.progress
                // rather than by declaration order, so a queued message can still advance to sent
                // once a route appears.
                if (state.progress > existing.deliveryState.progress ||
                    state == DeliveryState.FAILED
                ) {
                    messages[index] = existing.copy(deliveryState = state)
                    persistence?.persistDeliveryState(existing.messageId, state)
                    changed = true
                }
            }
        }
        if (changed) publish()
        return changed
    }

    /** Messages exchanged with one person, oldest first. */
    fun conversationWith(counterparty: MessagingPublicKey): DirectConversation? =
        _conversations.value.firstOrNull { it.counterparty == counterparty }

    /**
     * Mark a conversation as read, clearing its unread count and telling the sender.
     *
     * A read receipt goes out only for messages not already acknowledged as read, so reopening a
     * conversation does not re-send one for every message in it each time.
     */
    fun markRead(counterparty: MessagingPublicKey) {
        val conversationId = ConversationId.direct(localKey, counterparty)
        readConversations.add(conversationId)
        persistence?.persistRead(conversationId)
        publish()

        messagesByConversation[conversationId]
            ?.filter { !it.isOutgoing && readReceiptsSent.add(it.messageId) }
            ?.forEach { message ->
                DeliveryReceipt.encode(DeliveryState.READ, message.messageId)?.let { receipt ->
                    sendControl(counterparty, receipt)
                }
            }
    }

    /**
     * Store a message.
     *
     * @return true only when it was genuinely new. Callers use this to avoid acting twice on the
     *         same message: a delivery receipt sent every time a message is merely re-examined
     *         becomes traffic that provokes the other device to re-examine its own history and
     *         reply in kind, and the two devices then generate messages for each other without end.
     */
    private fun record(message: DirectMessage): Boolean {
        val bucket = messagesByConversation.getOrPut(message.conversationId) { mutableListOf() }
        // Re-delivery is normal on a flooding mesh; the same message must not appear twice.
        if (bucket.any { it.messageId == message.messageId }) return false
        bucket.add(message)
        // An arriving message makes a previously-read conversation unread again.
        if (!message.isOutgoing) readConversations.remove(message.conversationId)
        persistence?.persist(message)
        publish()
        return true
    }

    private fun publish() {
        _conversations.value = messagesByConversation
            .mapNotNull { (conversationId, messages) ->
                val counterparty = messages.firstOrNull()?.counterparty ?: return@mapNotNull null
                DirectConversation(
                    id = conversationId,
                    counterparty = counterparty,
                    messages = messages.sortedBy { it.timestampEpochMillis },
                    unreadCount = if (conversationId in readConversations) {
                        0
                    } else {
                        messages.count { !it.isOutgoing }
                    }
                )
            }
            .sortedByDescending { it.lastActivityMillis }
    }
}
