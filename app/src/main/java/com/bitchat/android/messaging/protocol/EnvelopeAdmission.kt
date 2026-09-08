package com.bitchat.android.messaging.protocol

import com.bitchat.android.messaging.identity.MessagingPublicKey

/**
 * Decides whether an arriving envelope may be shown to the user.
 *
 * This is the privacy boundary for direct messages. Everything upstream of it is untrusted input,
 * and every rejection below is a case where displaying the message would tell the user something
 * false about who they are talking to, or show them a conversation that was not theirs.
 *
 * The sender is supplied by the transport, which has already authenticated it, and is never read
 * from the message body. There is consequently no "claimed sender" to disagree with a real one:
 * the opportunity to lie was removed rather than detected.
 */
object EnvelopeAdmission {

    sealed interface Result {
        /** Safe to file and display. */
        data class Admitted(val envelope: MessagingEnvelope) : Result

        /** Not an application envelope at all — ordinary chat text, or another protocol. */
        data object NotAnEnvelope : Result

        /** Structurally invalid. Never partially applied. */
        data object Malformed : Result

        /**
         * The peer that sent this has not proven any identity, so nothing about it can be trusted.
         * Fails closed rather than assuming.
         */
        data object UnauthenticatedSender : Result

        /**
         * Addressed to somebody else.
         *
         * A message that arrives here but names another recipient must never be displayed, even
         * though it arrived: on a mesh, traffic for other people passes through constantly.
         */
        data object NotForThisRecipient : Result
    }

    /**
     * Admit a direct message.
     *
     * @param content the raw message body as received.
     * @param messageId the transport's message id, which is the sender's own id preserved verbatim.
     * @param timestampEpochMillis the transport's packet timestamp.
     * @param authenticatedSender the key the transport proved for the peer this arrived from, or
     *        null when that peer has proven nothing.
     * @param localKey this device's own messaging key.
     */
    fun admitDirect(
        content: String,
        messageId: String,
        timestampEpochMillis: Long,
        authenticatedSender: MessagingPublicKey?,
        localKey: MessagingPublicKey
    ): Result {
        if (!MessagingEnvelope.looksLikeEnvelope(content)) return Result.NotAnEnvelope

        // Establish who really sent this before considering anything it says.
        if (authenticatedSender == null) return Result.UnauthenticatedSender

        // Distinguish "for someone else" from "malformed" so the caller can tell an ordinary relay
        // from a broken message. Both are refused; only the reason differs.
        if (MessagingEnvelope.isAddressedElsewhere(content, localKey)) {
            return Result.NotForThisRecipient
        }

        val envelope = MessagingEnvelope.decode(
            content = content,
            messageId = messageId,
            timestampEpochMillis = timestampEpochMillis,
            sender = authenticatedSender,
            localKey = localKey
        ) ?: return Result.Malformed

        return Result.Admitted(envelope)
    }
}
