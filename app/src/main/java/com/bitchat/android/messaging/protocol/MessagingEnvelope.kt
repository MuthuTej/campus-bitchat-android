package com.bitchat.android.messaging.protocol

import com.bitchat.android.messaging.identity.MessagingPublicKey
import java.util.UUID

/** What kind of thing an envelope carries. Unknown values are rejected, never guessed at. */
enum class MessageKind(val wire: Char) {
    TEXT('T'),
    FILE_CONTROL('F'),
    SYSTEM('S');

    companion object {
        fun fromWire(value: Char): MessageKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The application layer's message: who sent it, who it is for, and which conversation it belongs to.
 *
 * ## The size constraint that shapes this
 *
 * A private message body is TLV-encoded with a one-byte length field, so **255 bytes is a hard
 * ceiling**. Anything larger is rejected during encoding, and because the router reports success
 * before that happens, an oversized message is dropped silently while the sender is told it went.
 *
 * The first version of this envelope carried its own message id, timestamp, sender key,
 * recipient key and conversation id as length-prefixed base64 — about 390 bytes before a single
 * character of the actual message. Every message therefore failed to encode and nothing was ever
 * delivered, while the interface showed "Sent".
 *
 * Almost all of that was redundant. The transport already carries the message id and timestamp
 * verbatim, and already identifies the authenticated sender. Only two things genuinely have to be
 * on the wire:
 *
 *  - **what kind of message this is**, since control traffic must never render as text, and
 *  - **who it was addressed to**, so a recipient can refuse a message that merely reached them.
 *
 * The rest is reconstructed from transport metadata, leaving the overhead at 21 bytes.
 *
 * ## What this deliberately does not do
 *
 * It defines no cryptography. The transport authenticates the sender and encrypts the body, and a
 * second scheme beside a working one would add attack surface without adding a guarantee.
 *
 * The sender is no longer written into the envelope at all. It was previously included only so it
 * could be compared against the authenticated peer; omitting it removes the opportunity to lie
 * rather than merely detecting the lie, and saves 64 bytes.
 */
data class MessagingEnvelope(
    val messageId: String,
    val conversationId: ConversationId,
    val kind: MessageKind,
    /** Established by the transport, never taken from the message body. */
    val senderPublicKey: MessagingPublicKey,
    /** Intended recipient. Always present: this envelope is for one-to-one messages only. */
    val recipientPublicKey: MessagingPublicKey?,
    val timestampEpochMillis: Long,
    val payload: String
) {

    /**
     * Wire form: `BCM1` + kind + 16 hex characters identifying the recipient + payload.
     *
     * The recipient is identified by a prefix of their key rather than the whole thing. Sixty-four
     * bits is far beyond what an accidental collision needs, and this field is a check on top of
     * transport addressing rather than the thing that keeps a message private.
     */
    fun encode(): String = buildString {
        append(PREFIX)
        append(kind.wire)
        append(requireNotNull(recipientPublicKey) { "a direct envelope must name a recipient" }
            .hex.take(RECIPIENT_TAG_LENGTH))
        append(payload)
    }

    /** True when this will not fit in a private message body. */
    fun exceedsWireLimit(): Boolean =
        encode().toByteArray(Charsets.UTF_8).size > MAX_WIRE_BYTES

    companion object {
        const val PREFIX = "BCM1"
        private const val RECIPIENT_TAG_LENGTH = 16

        /** The transport's private-message body limit. Exceeding it means silent loss. */
        const val MAX_WIRE_BYTES = 255

        /** Header cost: prefix, kind, recipient tag. */
        const val HEADER_BYTES = 4 + 1 + RECIPIENT_TAG_LENGTH

        /** Longest message text that still fits. */
        const val MAX_PAYLOAD_BYTES = MAX_WIRE_BYTES - HEADER_BYTES

        /**
         * The human-readable part of an envelope, for surfaces that only display.
         *
         * Reads the payload without admitting the message, so it makes no trust decision and must
         * never gate anything: it exists so a notification can show what was written rather than
         * the framing around it. Returns null when this is not an envelope.
         */
        fun previewPayload(content: String): String? =
            if (looksLikeEnvelope(content)) content.drop(HEADER_BYTES) else null

        /**
         * True when this envelope carries machine traffic rather than something a person wrote.
         *
         * Receipts and key distribution are addressed to the device, not to its owner, and
         * announcing each one is as unhelpful as a phone ringing to report that a previous call
         * connected.
         */
        fun isControlTraffic(content: String): Boolean =
            looksLikeEnvelope(content) &&
                MessageKind.fromWire(content[PREFIX.length]) != MessageKind.TEXT

        /** Cheap check so ordinary chat text is not run through the full parser. */
        fun looksLikeEnvelope(content: String): Boolean =
            content.length > HEADER_BYTES && content.startsWith(PREFIX)

        fun directText(
            sender: MessagingPublicKey,
            recipient: MessagingPublicKey,
            payload: String,
            messageId: String = UUID.randomUUID().toString(),
            timestampEpochMillis: Long = System.currentTimeMillis()
        ): MessagingEnvelope = MessagingEnvelope(
            messageId = messageId,
            conversationId = ConversationId.direct(sender, recipient),
            kind = MessageKind.TEXT,
            senderPublicKey = sender,
            recipientPublicKey = recipient,
            timestampEpochMillis = timestampEpochMillis,
            payload = payload
        )

        /**
         * Rebuild an envelope from the wire plus the metadata the transport supplies.
         *
         * @param sender the key the transport proved for the peer this arrived from. This is the
         *        only source of sender identity; nothing in [content] can influence it.
         * @param localKey this device's own key, used to resolve the recipient tag.
         * @return null when this is not an application envelope, or is malformed in any way.
         */
        fun decode(
            content: String,
            messageId: String,
            timestampEpochMillis: Long,
            sender: MessagingPublicKey,
            localKey: MessagingPublicKey
        ): MessagingEnvelope? {
            if (!looksLikeEnvelope(content)) return null

            val kind = MessageKind.fromWire(content[PREFIX.length]) ?: return null
            val tagStart = PREFIX.length + 1
            val tag = content.substring(tagStart, tagStart + RECIPIENT_TAG_LENGTH)
            val payload = content.substring(tagStart + RECIPIENT_TAG_LENGTH)

            if (messageId.isBlank()) return null
            if (timestampEpochMillis <= 0L) return null

            // The privacy check. Arriving here is not the same as being addressed here — on a mesh,
            // messages routinely pass through devices they are not for.
            if (!localKey.hex.startsWith(tag)) return null
            if (sender == localKey) return null

            return MessagingEnvelope(
                messageId = messageId,
                conversationId = ConversationId.direct(sender, localKey),
                kind = kind,
                senderPublicKey = sender,
                recipientPublicKey = localKey,
                timestampEpochMillis = timestampEpochMillis,
                payload = payload
            )
        }

        /** True when the wire form names a recipient other than [localKey]. */
        fun isAddressedElsewhere(content: String, localKey: MessagingPublicKey): Boolean {
            if (!looksLikeEnvelope(content)) return false
            val tagStart = PREFIX.length + 1
            val tag = content.substring(tagStart, tagStart + RECIPIENT_TAG_LENGTH)
            return !localKey.hex.startsWith(tag)
        }
    }
}
