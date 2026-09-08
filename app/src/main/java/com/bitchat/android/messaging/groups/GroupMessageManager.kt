package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.conversation.DeliveryState
import com.bitchat.android.messaging.conversation.SendOutcome
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.Canonical
import com.bitchat.android.messaging.protocol.ConversationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One message in a group, after it has passed every admission check. */
data class GroupMessage(
    val messageId: String,
    val groupId: String,
    val senderPublicKey: MessagingPublicKey,
    val content: String,
    val timestampEpochMillis: Long,
    val isOutgoing: Boolean,
    val deliveryState: DeliveryState
)

/**
 * Group messaging.
 *
 * Enforces authorization at three independent points, because each catches something the others
 * cannot:
 *
 *  1. **Before sending** — the sender's own permission to publish. Stops a muted member's message
 *     from ever reaching the network, rather than relying on every recipient to reject it.
 *  2. **On arrival, before decrypting** — the sender must be a member *and* hold the permission.
 *     Stops a removed or muted member whose message did reach the network, and does so without the
 *     recipient having to trust that the sender's own device checked anything.
 *  3. **Decryption itself** — possession of the group key. This is the one that does not depend on
 *     any list being correct: an observer who is not in the group simply cannot read the content,
 *     because they were never given the key.
 *
 * The third is why removal must rotate the key. Dropping someone from the member list makes their
 * future messages unauthorized, but only a new key makes the group's future messages unreadable to
 * them.
 */
class GroupMessageManager(
    private val localKey: MessagingPublicKey,
    private val groups: GroupManager,
    private val keys: GroupKeyManager,
    private val transport: GroupTransport,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {

    /** How a group message reaches the network. Implemented over the existing broadcast path. */
    fun interface GroupTransport {
        fun broadcast(channelTag: String, encodedEnvelope: String): SendOutcome
    }

    sealed interface SendResult {
        data class Accepted(val message: GroupMessage) : SendResult
        data object NotAMember : SendResult
        data object NotPermitted : SendResult
        data object NoGroupKey : SendResult
        data object EmptyMessage : SendResult
        data object NoSuchGroup : SendResult
    }

    /** Why an arriving group message was not shown. */
    sealed interface ReceiveResult {
        data class Accepted(val message: GroupMessage) : ReceiveResult
        data object NotAnEnvelope : ReceiveResult
        data object Rejected : ReceiveResult

        /** This device is not in the group, so the message is none of its business. */
        data object NotAMember : ReceiveResult
        data object SenderNotAMember : ReceiveResult
        data object SenderNotPermitted : ReceiveResult
        data object Undecryptable : ReceiveResult
        data object UnknownGroup : ReceiveResult
    }

    private val messagesByGroup = mutableMapOf<String, MutableList<GroupMessage>>()
    private val _messages = MutableStateFlow<Map<String, List<GroupMessage>>>(emptyMap())

    val messages: StateFlow<Map<String, List<GroupMessage>>> = _messages.asStateFlow()

    fun messagesFor(groupId: String): List<GroupMessage> = _messages.value[groupId].orEmpty()

    /** Channel the group's traffic is broadcast on. Derived, so every member computes the same one. */
    fun channelTagFor(groupId: String): String = "$CHANNEL_PREFIX$groupId"

    fun send(groupId: String, text: String): SendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SendResult.EmptyMessage
        val group = groups.group(groupId) ?: return SendResult.NoSuchGroup
        if (!group.isMember(localKey)) return SendResult.NotAMember
        if (!group.can(localKey, GroupPermission.CAN_SEND_MESSAGES)) return SendResult.NotPermitted

        // Without the key there is nothing safe to send: transmitting in the clear "just this once"
        // would put the content in front of every device in range.
        val key = keys.currentKey(groupId) ?: return SendResult.NoGroupKey

        val messageId = UUID.randomUUID().toString()
        val now = clockMillis()
        val wire = encodeGroupWire(
            groupId = groupId,
            messageId = messageId,
            senderKeyHex = localKey.hex,
            keyVersion = key.version,
            sealed = GroupCipher.seal(key, trimmed)
        )

        val outcome = transport.broadcast(channelTagFor(groupId), wire)
        val message = GroupMessage(
            messageId = messageId,
            groupId = groupId,
            senderPublicKey = localKey,
            content = trimmed,
            timestampEpochMillis = now,
            isOutgoing = true,
            deliveryState = outcome.toDeliveryState()
        )
        record(message)
        return SendResult.Accepted(message)
    }

    /**
     * Offer an arriving group message body.
     *
     * @param authenticatedSender the key the transport proved for the peer this arrived from.
     */
    fun receive(
        content: String,
        timestampEpochMillis: Long,
        authenticatedSender: MessagingPublicKey?
    ): ReceiveResult {
        if (!looksLikeGroupWire(content)) return ReceiveResult.NotAnEnvelope
        if (authenticatedSender == null) return ReceiveResult.Rejected

        val wire = decodeGroupWire(content) ?: return ReceiveResult.Rejected

        // The sender is whoever the transport authenticated. The name carried on the wire is only
        // a hint for matching, and disagreeing with the proven sender is grounds for rejection
        // rather than something to reconcile.
        if (!wire.senderKeyHex.equals(authenticatedSender.hex, ignoreCase = true)) {
            return ReceiveResult.Rejected
        }

        val group = groups.group(wire.groupId) ?: return ReceiveResult.UnknownGroup

        // Traffic for a group this device does not belong to is discarded before anything else is
        // considered. Not holding the key would stop the content being read regardless, but a
        // non-member should not accumulate group state or surface a conversation it has no part in
        // — the same reasoning as refusing a direct message addressed to somebody else.
        if (!group.isMember(localKey)) return ReceiveResult.NotAMember

        // Checked on the receiving side too. A sender's device claiming to have checked its own
        // permission is not evidence of anything.
        if (!group.isMember(authenticatedSender)) return ReceiveResult.SenderNotAMember
        if (!group.can(authenticatedSender, GroupPermission.CAN_SEND_MESSAGES)) {
            return ReceiveResult.SenderNotPermitted
        }

        val key = keys.keyForVersion(wire.groupId, wire.keyVersion)
            ?: return ReceiveResult.Undecryptable
        val plaintext = GroupCipher.open(key, wire.sealed) ?: return ReceiveResult.Undecryptable

        val message = GroupMessage(
            messageId = wire.messageId,
            groupId = wire.groupId,
            senderPublicKey = authenticatedSender,
            content = plaintext,
            timestampEpochMillis = timestampEpochMillis,
            isOutgoing = false,
            deliveryState = DeliveryState.DELIVERED
        )
        record(message)
        return ReceiveResult.Accepted(message)
    }

    private fun record(message: GroupMessage) {
        val bucket = messagesByGroup.getOrPut(message.groupId) { mutableListOf() }
        if (bucket.any { it.messageId == message.messageId }) return
        bucket.add(message)
        _messages.value = messagesByGroup.mapValues { (_, list) ->
            list.sortedBy { it.timestampEpochMillis }
        }
    }

    /** The addressing a group message carries, since a channel broadcast supplies none. */
    data class GroupWire(
        val groupId: String,
        val messageId: String,
        val senderKeyHex: String,
        val keyVersion: Int,
        val sealed: String
    )

    private companion object {
        const val CHANNEL_PREFIX = "#g_"

        const val WIRE_PREFIX = "BCG1:"

        /**
         * Group traffic carries its own addressing because it travels a different path from a
         * direct message: it is broadcast to a channel, so the transport cannot say who it is for
         * or which group it belongs to.
         */
        fun encodeGroupWire(
            groupId: String,
            messageId: String,
            senderKeyHex: String,
            keyVersion: Int,
            sealed: String
        ): String = WIRE_PREFIX + Canonical.b64(
            Canonical.encode(groupId, messageId, senderKeyHex, keyVersion.toString(), sealed)
        )

        fun looksLikeGroupWire(content: String): Boolean = content.startsWith(WIRE_PREFIX)

        fun decodeGroupWire(content: String): GroupWire? {
            if (!looksLikeGroupWire(content)) return null
            val raw = Canonical.unb64(content.removePrefix(WIRE_PREFIX)) ?: return null
            val fields = Canonical.decodeFields(raw, expected = 5) ?: return null
            val groupId = fields[0].takeIf { it.isNotBlank() } ?: return null
            val messageId = fields[1].takeIf { it.isNotBlank() } ?: return null
            val senderKeyHex = fields[2].takeIf { it.length == 64 } ?: return null
            val version = fields[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return GroupWire(groupId, messageId, senderKeyHex, version, fields[4])
        }
    }
}
