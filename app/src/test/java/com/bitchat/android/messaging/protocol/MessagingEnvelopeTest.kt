package com.bitchat.android.messaging.protocol

import com.bitchat.android.messaging.identity.MessagingPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers envelope framing and conversation identity — in particular that a malformed envelope is
 * dropped whole rather than partially understood.
 */
class MessagingEnvelopeTest {

    private val alice = MessagingPublicKey.parse("a".repeat(64))!!
    private val bob = MessagingPublicKey.parse("b".repeat(64))!!
    private val carol = MessagingPublicKey.parse("c".repeat(64))!!

    // MARK: - Conversation identity

    @Test
    fun `a direct conversation id is the same from both sides`() {
        // Neither participant should have to be told the identifier; both must derive it.
        assertEquals(ConversationId.direct(alice, bob), ConversationId.direct(bob, alice))
    }

    @Test
    fun `different pairs get different conversation ids`() {
        assertNotEquals(ConversationId.direct(alice, bob), ConversationId.direct(alice, carol))
    }

    @Test
    fun `conversation ids are tagged by kind`() {
        assertTrue(ConversationId.direct(alice, bob).isDirect)
        assertFalse(ConversationId.direct(alice, bob).isGroup)
        assertTrue(ConversationId.group("g1").isGroup)
        assertFalse(ConversationId.group("g1").isDirect)
    }

    @Test
    fun `conversation id parsing rejects anything unrecognised`() {
        assertNull(ConversationId.parse(null))
        assertNull(ConversationId.parse(""))
        assertNull(ConversationId.parse("nonsense"))
        assertNull("wrong length", ConversationId.parse("dm_abc"))
        assertNull("non hex", ConversationId.parse("dm_" + "z".repeat(64)))
        assertNull("empty group id", ConversationId.parse("grp_"))
    }

    @Test
    fun `a valid conversation id round trips through parsing`() {
        val direct = ConversationId.direct(alice, bob)
        assertEquals(direct, ConversationId.parse(direct.value))
        val group = ConversationId.group("study-team")
        assertEquals(group, ConversationId.parse(group.value))
    }

    // MARK: - Size, the constraint that shapes this format

    @Test
    fun `an ordinary message fits inside the transport's private message limit`() {
        // The transport discards an oversized private message while still reporting a successful
        // route, so exceeding this is silent data loss rather than an error.
        val envelope = MessagingEnvelope.directText(alice, bob, "hi")
        val size = envelope.encode().toByteArray(Charsets.UTF_8).size

        assertTrue("a two-letter message took $size bytes", size <= MessagingEnvelope.MAX_WIRE_BYTES)
        assertFalse(envelope.exceedsWireLimit())
    }

    @Test
    fun `envelope overhead is small enough to leave room for a real message`() {
        val overhead = MessagingEnvelope.directText(alice, bob, "").encode()
            .toByteArray(Charsets.UTF_8).size

        // The first version of this format spent about 390 bytes here, so nothing could ever be
        // sent. Pinning it low keeps that from creeping back.
        assertEquals(MessagingEnvelope.HEADER_BYTES, overhead)
        assertTrue("overhead of $overhead bytes", overhead < 32)
    }

    @Test
    fun `a message at the payload limit still fits`() {
        val atLimit = "x".repeat(MessagingEnvelope.MAX_PAYLOAD_BYTES)
        assertFalse(MessagingEnvelope.directText(alice, bob, atLimit).exceedsWireLimit())
    }

    @Test
    fun `a message past the payload limit is reported rather than silently dropped`() {
        val tooLong = "x".repeat(MessagingEnvelope.MAX_PAYLOAD_BYTES + 1)
        assertTrue(MessagingEnvelope.directText(alice, bob, tooLong).exceedsWireLimit())
    }

    // MARK: - Round trip

    @Test
    fun `a direct envelope round trips using transport metadata`() {
        val original = MessagingEnvelope.directText(alice, bob, "see you at 4", messageId = "m1")

        val decoded = MessagingEnvelope.decode(
            content = original.encode(),
            messageId = "m1",
            timestampEpochMillis = 1_700_000_000_000L,
            sender = alice,
            localKey = bob
        )

        assertNotNull(decoded)
        assertEquals("see you at 4", decoded?.payload)
        assertEquals(alice, decoded?.senderPublicKey)
        assertEquals(bob, decoded?.recipientPublicKey)
        assertEquals("m1", decoded?.messageId)
        assertEquals(MessageKind.TEXT, decoded?.kind)
    }

    @Test
    fun `payload content survives exactly`() {
        val awkward = "a|b\nc d 🎓 ::: \\ \" '"
        val original = MessagingEnvelope.directText(alice, bob, awkward)
        assertEquals(
            awkward,
            MessagingEnvelope.decode(original.encode(), "m1", 1L, alice, bob)?.payload
        )
    }

    @Test
    fun `both parties derive the same conversation`() {
        val sent = MessagingEnvelope.directText(alice, bob, "hi")
        val received = MessagingEnvelope.decode(sent.encode(), "m1", 1L, alice, bob)
        assertEquals(sent.conversationId, received?.conversationId)
    }

    @Test
    fun `ordinary chat text is not mistaken for an envelope`() {
        assertFalse(MessagingEnvelope.looksLikeEnvelope("Is the lecture at 4?"))
        assertNull(MessagingEnvelope.decode("Is the lecture at 4?", "m1", 1L, alice, bob))
    }

    // MARK: - The privacy rule

    @Test
    fun `a message addressed to someone else does not decode here`() {
        // Alice to Carol, relayed through Bob's device.
        val forCarol = MessagingEnvelope.directText(alice, carol, "private plans")

        assertNull(MessagingEnvelope.decode(forCarol.encode(), "m1", 1L, alice, bob))
        assertTrue(MessagingEnvelope.isAddressedElsewhere(forCarol.encode(), bob))
    }

    @Test
    fun `a message addressed here is recognised as such`() {
        val forBob = MessagingEnvelope.directText(alice, bob, "hi")
        assertFalse(MessagingEnvelope.isAddressedElsewhere(forBob.encode(), bob))
    }

    @Test
    fun `the sender cannot be taken from the message body`() {
        // Whatever is on the wire, the sender is whoever the transport proved. Carol relaying
        // Alice's bytes is attributed to Carol, not to Alice.
        val fromAlice = MessagingEnvelope.directText(alice, bob, "hi")

        val decoded = MessagingEnvelope.decode(fromAlice.encode(), "m1", 1L, carol, bob)

        assertEquals(carol, decoded?.senderPublicKey)
    }

    // MARK: - Malformed input

    @Test
    fun `a truncated envelope is rejected`() {
        assertNull(MessagingEnvelope.decode(MessagingEnvelope.PREFIX + "T", "m1", 1L, alice, bob))
        assertNull(MessagingEnvelope.decode(MessagingEnvelope.PREFIX, "m1", 1L, alice, bob))
    }

    @Test
    fun `an unknown message kind is rejected rather than guessed`() {
        val unknown = MessagingEnvelope.PREFIX + "Z" + bob.hex.take(16) + "hi"
        assertNull(MessagingEnvelope.decode(unknown, "m1", 1L, alice, bob))
    }

    @Test
    fun `a blank message id or non positive timestamp is rejected`() {
        val encoded = MessagingEnvelope.directText(alice, bob, "hi").encode()
        assertNull(MessagingEnvelope.decode(encoded, "  ", 1L, alice, bob))
        assertNull(MessagingEnvelope.decode(encoded, "m1", 0L, alice, bob))
        assertNull(MessagingEnvelope.decode(encoded, "m1", -5L, alice, bob))
    }

    @Test
    fun `a self addressed message is rejected`() {
        val selfie = MessagingEnvelope.directText(bob, bob, "note to self")
        assertNull(MessagingEnvelope.decode(selfie.encode(), "m1", 1L, bob, bob))
    }
}
