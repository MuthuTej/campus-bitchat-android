package com.bitchat.android.messaging.conversation

import com.bitchat.android.messaging.protocol.Canonical

/**
 * A confirmation sent back to whoever wrote a message.
 *
 * ## Why this exists rather than reusing the transport's acknowledgements
 *
 * The transport does track acknowledgements, but only for messages sent through its own durable
 * send path. This layer addresses messages by identity through the router directly — which is what
 * keeps a send independent of whichever conversation happens to be open — and that path carries no
 * acknowledgement reporting back here.
 *
 * The result was a message that had genuinely arrived still reading as merely sent, forever. A
 * receipt travelling as ordinary control traffic closes that without touching the transport: it is
 * one small addressed message per received message, which is what a read receipt costs anywhere.
 *
 * Receipts are never sent for control traffic, only for real messages. Otherwise a receipt would
 * provoke a receipt, and two devices would acknowledge each other indefinitely.
 */
object DeliveryReceipt {

    const val PREFIX = "RCPT1:"

    private const val DELIVERED = "D"
    private const val READ = "R"

    fun looksLikeReceipt(payload: String): Boolean = payload.startsWith(PREFIX)

    fun encode(state: DeliveryState, messageId: String): String? {
        val marker = when (state) {
            DeliveryState.DELIVERED -> DELIVERED
            DeliveryState.READ -> READ
            // Nothing else is a statement the recipient is in a position to make: only they know
            // whether a message arrived or was looked at.
            else -> return null
        }
        return PREFIX + Canonical.b64(Canonical.encode(marker, messageId))
    }

    /** The state being confirmed and the message it refers to, or null if this is not a receipt. */
    fun decode(payload: String): Pair<DeliveryState, String>? {
        if (!looksLikeReceipt(payload)) return null
        val raw = Canonical.unb64(payload.removePrefix(PREFIX)) ?: return null
        val fields = Canonical.decodeFields(raw, expected = 2) ?: return null
        val state = when (fields[0]) {
            DELIVERED -> DeliveryState.DELIVERED
            READ -> DeliveryState.READ
            else -> return null
        }
        val messageId = fields[1].takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        return state to messageId
    }
}
