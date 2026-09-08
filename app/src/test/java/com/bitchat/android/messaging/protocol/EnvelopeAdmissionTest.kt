package com.bitchat.android.messaging.protocol

import com.bitchat.android.messaging.identity.MessagingPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the direct-message privacy boundary.
 *
 * The property under test throughout: arriving at this device is not the same as being addressed to
 * this device. On a relaying mesh, messages for other people pass through routinely, and none of
 * them may ever reach a conversation list.
 */
class EnvelopeAdmissionTest {

    private val alice = MessagingPublicKey.parse("a".repeat(64))!!
    private val bob = MessagingPublicKey.parse("b".repeat(64))!!
    private val carol = MessagingPublicKey.parse("c".repeat(64))!!

    /** Bob is this device throughout. */
    private val me = bob

    private val timestamp = 1_700_000_000_000L

    private fun admit(
        envelope: MessagingEnvelope,
        from: MessagingPublicKey? = alice,
        messageId: String = envelope.messageId
    ) = EnvelopeAdmission.admitDirect(
        content = envelope.encode(),
        messageId = messageId,
        timestampEpochMillis = timestamp,
        authenticatedSender = from,
        localKey = me
    )

    @Test
    fun `a message addressed to me from an authenticated sender is admitted`() {
        val result = admit(MessagingEnvelope.directText(alice, bob, "see you at 4"))

        assertTrue(result is EnvelopeAdmission.Result.Admitted)
        assertEquals(
            "see you at 4",
            (result as EnvelopeAdmission.Result.Admitted).envelope.payload
        )
    }

    @Test
    fun `a message for someone else is not admitted even though it arrived here`() {
        // Alice to Carol, relayed through this device.
        val result = admit(MessagingEnvelope.directText(alice, carol, "private plans"))

        assertEquals(EnvelopeAdmission.Result.NotForThisRecipient, result)
    }

    @Test
    fun `a relayed message for someone else never exposes its payload`() {
        val secret = "meeting at the back gate"
        val result = admit(MessagingEnvelope.directText(alice, carol, secret))

        assertTrue(result !is EnvelopeAdmission.Result.Admitted)
        assertFalse("a rejection must not carry the message", result.toString().contains(secret))
    }

    @Test
    fun `the sender is whoever the transport proved, not anything on the wire`() {
        // There is no sender field to forge: Carol relaying Alice's bytes is Carol's message.
        val fromAlice = MessagingEnvelope.directText(alice, bob, "lend me your notes")

        val result = admit(fromAlice, from = carol)

        assertTrue(result is EnvelopeAdmission.Result.Admitted)
        assertEquals(
            carol,
            (result as EnvelopeAdmission.Result.Admitted).envelope.senderPublicKey
        )
    }

    @Test
    fun `an unauthenticated peer cannot have anything admitted`() {
        // Failing closed: with nothing proven there is no basis to attribute the message.
        assertEquals(
            EnvelopeAdmission.Result.UnauthenticatedSender,
            admit(MessagingEnvelope.directText(alice, bob, "hi"), from = null)
        )
    }

    @Test
    fun `the sender is established before anything the envelope says`() {
        // Addressed elsewhere *and* unauthenticated: the missing proof is the reported reason,
        // because nothing can be evaluated until the sender is known.
        assertEquals(
            EnvelopeAdmission.Result.UnauthenticatedSender,
            admit(MessagingEnvelope.directText(alice, carol, "hi"), from = null)
        )
    }

    @Test
    fun `ordinary chat text is reported as not an envelope`() {
        val result = EnvelopeAdmission.admitDirect(
            content = "Is the lecture at 4?",
            messageId = "m1",
            timestampEpochMillis = timestamp,
            authenticatedSender = alice,
            localKey = me
        )
        assertEquals(EnvelopeAdmission.Result.NotAnEnvelope, result)
    }

    @Test
    fun `a malformed envelope is rejected without partial application`() {
        val result = EnvelopeAdmission.admitDirect(
            // Right prefix and recipient, unknown kind.
            content = MessagingEnvelope.PREFIX + "Z" + bob.hex.take(16) + "hi",
            messageId = "m1",
            timestampEpochMillis = timestamp,
            authenticatedSender = alice,
            localKey = me
        )
        assertEquals(EnvelopeAdmission.Result.Malformed, result)
    }

    @Test
    fun `a message with no transport id is refused`() {
        assertEquals(
            EnvelopeAdmission.Result.Malformed,
            admit(MessagingEnvelope.directText(alice, bob, "hi"), messageId = "   ")
        )
    }

    @Test
    fun `a self addressed message is rejected`() {
        assertEquals(
            EnvelopeAdmission.Result.Malformed,
            admit(MessagingEnvelope.directText(bob, bob, "note to self"), from = bob)
        )
    }

    @Test
    fun `my own outgoing message is not admitted as an arrival`() {
        // Echoed back from the transport: addressed to Alice, so not for this device.
        assertEquals(
            EnvelopeAdmission.Result.NotForThisRecipient,
            admit(MessagingEnvelope.directText(bob, alice, "on my way"), from = bob)
        )
    }

    @Test
    fun `control traffic is admitted so the caller can act on it`() {
        val control = MessagingEnvelope(
            messageId = "c1",
            conversationId = ConversationId.direct(alice, bob),
            kind = MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = bob,
            timestampEpochMillis = timestamp,
            payload = "GKEY1:opaque"
        )

        val result = admit(control)

        assertTrue(result is EnvelopeAdmission.Result.Admitted)
        assertEquals(
            MessageKind.SYSTEM,
            (result as EnvelopeAdmission.Result.Admitted).envelope.kind
        )
    }

    @Test
    fun `control traffic for someone else is refused like any other message`() {
        val control = MessagingEnvelope(
            messageId = "c1",
            conversationId = ConversationId.direct(alice, carol),
            kind = MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = carol,
            timestampEpochMillis = timestamp,
            payload = "GKEY1:not-for-me"
        )

        assertEquals(EnvelopeAdmission.Result.NotForThisRecipient, admit(control))
    }
}
