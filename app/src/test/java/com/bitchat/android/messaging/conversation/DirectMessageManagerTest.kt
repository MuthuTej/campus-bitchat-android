package com.bitchat.android.messaging.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.messaging.contacts.ContactRepository
import com.bitchat.android.messaging.identity.IdentityRepository
import com.bitchat.android.messaging.identity.MessagingIdentity
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.ConversationId
import com.bitchat.android.messaging.protocol.EnvelopeAdmission
import com.bitchat.android.messaging.protocol.MessagingEnvelope
import com.bitchat.android.messaging.transport.OutboundTransport
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Covers one-to-one messaging: addressing, the recipient privacy rule, delivery states, and the
 * offline-first behaviour that makes an unreachable recipient a waiting message rather than an
 * error.
 */
@RunWith(RobolectricTestRunner::class)
class DirectMessageManagerTest {

    /** Records what was sent and lets a test choose the transport's answer. */
    private class FakeTransport : OutboundTransport {
        var outcome: SendOutcome = SendOutcome.SENT_LOCAL
        var reachable = true
        val sent = mutableListOf<Pair<MessagingPublicKey, String>>()

        override fun sendDirect(
            recipient: MessagingPublicKey,
            encodedEnvelope: String,
            messageId: String
        ): SendOutcome {
            sent.add(recipient to encodedEnvelope)
            return outcome
        }

        override fun canReachNow(recipient: MessagingPublicKey) = reachable
    }

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var identities: IdentityRepository
    private lateinit var contacts: ContactRepository
    private lateinit var scope: CoroutineScope
    private lateinit var transport: FakeTransport
    private lateinit var manager: DirectMessageManager

    private var now = 1_000L

    private val me = MessagingPublicKey.parse("b".repeat(64))!!
    private val alice = MessagingPublicKey.parse("a".repeat(64))!!
    private val carol = MessagingPublicKey.parse("c".repeat(64))!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "dm-test-${UUID.randomUUID()}.db"
        identities = IdentityRepository(
            context = context,
            databaseName = databaseName,
            clockMillis = { now },
            storageCipher = InMemoryConversationStorageCipher()
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        contacts = ContactRepository(identities, scope)
        transport = FakeTransport()
        manager = DirectMessageManager(me, transport, contacts, clockMillis = { now })
    }

    @After
    fun tearDown() {
        scope.cancel()
        identities.close()
        context.deleteDatabase(databaseName)
    }

    // MARK: - Sending

    @Test
    fun `a sent message is addressed to the intended identity`() {
        val result = manager.send(alice, "see you at 4")

        assertTrue(result is DirectMessageManager.SendResult.Accepted)
        assertEquals(1, transport.sent.size)
        assertEquals(alice, transport.sent.first().first)
    }

    @Test
    fun `the wire envelope names both parties`() {
        manager.send(alice, "see you at 4")

        // Decoded as Alice would: the sender comes from the transport, the recipient from the wire.
        val decoded = MessagingEnvelope.decode(
            content = transport.sent.first().second,
            messageId = "m1",
            timestampEpochMillis = 1_700_000_000_000L,
            sender = me,
            localKey = alice
        )
        assertNotNull(decoded)
        assertEquals(me, decoded?.senderPublicKey)
        assertEquals(alice, decoded?.recipientPublicKey)
        assertEquals("see you at 4", decoded?.payload)
    }

    @Test
    fun `an empty message is not sent`() {
        assertEquals(DirectMessageManager.SendResult.EmptyMessage, manager.send(alice, "   "))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `a message to oneself is refused`() {
        assertEquals(DirectMessageManager.SendResult.CannotMessageSelf, manager.send(me, "hi"))
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `a blocked recipient is refused at the send path not merely hidden`() = runTest {
        contacts.save(MessagingIdentity.of(alice, "Alice"))
        contacts.setBlocked(alice, true)

        assertEquals(DirectMessageManager.SendResult.BlockedRecipient, manager.send(alice, "hi"))
        assertTrue("nothing may reach the transport", transport.sent.isEmpty())
    }

    // MARK: - Delivery states

    @Test
    fun `a message sent over the mesh is marked sent`() {
        transport.outcome = SendOutcome.SENT_LOCAL
        manager.send(alice, "hi")

        assertEquals(DeliveryState.SENT, lastOutgoing().deliveryState)
    }

    @Test
    fun `an unreachable recipient produces a waiting message not a failure`() {
        transport.outcome = SendOutcome.QUEUED
        manager.send(alice, "hi")

        // Offline is the ordinary case for this product, not an error.
        assertEquals(DeliveryState.PENDING_OFFLINE, lastOutgoing().deliveryState)
        assertEquals("Waiting to deliver", DeliveryState.PENDING_OFFLINE.label)
    }

    @Test
    fun `a rejected send is marked failed`() {
        transport.outcome = SendOutcome.REJECTED
        manager.send(alice, "hi")

        assertEquals(DeliveryState.FAILED, lastOutgoing().deliveryState)
    }

    @Test
    fun `delivery state advances as the transport learns more`() {
        transport.outcome = SendOutcome.QUEUED
        manager.send(alice, "hi")
        val id = lastOutgoing().messageId

        assertTrue(manager.updateDeliveryState(id, DeliveryState.SENT))
        assertEquals(DeliveryState.SENT, lastOutgoing().deliveryState)

        assertTrue(manager.updateDeliveryState(id, DeliveryState.DELIVERED))
        assertEquals(DeliveryState.DELIVERED, lastOutgoing().deliveryState)
    }

    @Test
    fun `a queued message advances once a route appears`() {
        // The transition that matters most offline: held with no route, then delivered when the
        // recipient comes back into range. It must not be treated as a regression.
        transport.outcome = SendOutcome.QUEUED
        manager.send(alice, "hi")
        val id = lastOutgoing().messageId
        assertEquals(DeliveryState.PENDING_OFFLINE, lastOutgoing().deliveryState)

        assertTrue(manager.updateDeliveryState(id, DeliveryState.SENT))
        assertEquals(DeliveryState.SENT, lastOutgoing().deliveryState)
    }

    @Test
    fun `a sent message is not pushed back into waiting`() {
        transport.outcome = SendOutcome.SENT_LOCAL
        manager.send(alice, "hi")
        val id = lastOutgoing().messageId

        // It has already left the device; a later "no route" report must not undo that.
        assertFalse(manager.updateDeliveryState(id, DeliveryState.PENDING_OFFLINE))
        assertEquals(DeliveryState.SENT, lastOutgoing().deliveryState)
    }

    @Test
    fun `progress ranking orders the states by how far the message has got`() {
        // Guards the ranking itself: declaration order is not progress order, and relying on it
        // silently blocked the queued-to-sent transition.
        assertTrue(DeliveryState.PENDING_OFFLINE.progress > DeliveryState.SENDING.progress)
        assertTrue(DeliveryState.SENT.progress > DeliveryState.PENDING_OFFLINE.progress)
        assertTrue(DeliveryState.DELIVERED.progress > DeliveryState.SENT.progress)
        assertTrue(DeliveryState.READ.progress > DeliveryState.DELIVERED.progress)
    }

    @Test
    fun `delivery state never moves backwards`() {
        manager.send(alice, "hi")
        val id = lastOutgoing().messageId
        manager.updateDeliveryState(id, DeliveryState.DELIVERED)

        // A late acknowledgement from an earlier hop must not undo a known delivery.
        assertFalse(manager.updateDeliveryState(id, DeliveryState.SENDING))
        assertEquals(DeliveryState.DELIVERED, lastOutgoing().deliveryState)
    }

    @Test
    fun `a failure can still be recorded after a send`() {
        manager.send(alice, "hi")
        val id = lastOutgoing().messageId

        assertTrue(manager.updateDeliveryState(id, DeliveryState.FAILED))
        assertEquals(DeliveryState.FAILED, lastOutgoing().deliveryState)
    }

    // MARK: - Receiving and the privacy rule

    @Test
    fun `a message addressed to me appears in its conversation`() {
        val envelope = MessagingEnvelope.directText(alice, me, "notes are ready")

        val result = deliver(envelope, alice)

        assertTrue(result is EnvelopeAdmission.Result.Admitted)
        val conversation = manager.conversationWith(alice)
        assertNotNull(conversation)
        assertEquals("notes are ready", conversation?.messages?.last()?.content)
    }

    @Test
    fun `a message for someone else never appears in any conversation`() {
        // Alice to Carol, relayed through this device.
        val envelope = MessagingEnvelope.directText(alice, carol, "private plans")

        val result = deliver(envelope, alice)

        assertEquals(EnvelopeAdmission.Result.NotForThisRecipient, result)
        assertTrue("no conversation may be created", manager.conversations.value.isEmpty())
        assertNull(manager.conversationWith(alice))
        assertNull(manager.conversationWith(carol))
    }

    @Test
    fun `a relayed message is attributed to whoever the transport proved`() {
        // The sender is no longer carried on the wire, so it cannot be claimed. Carol relaying
        // Alice's bytes is Carol's message, filed in Carol's conversation, never Alice's.
        val fromAlice = MessagingEnvelope.directText(alice, me, "lend me your notes")

        deliver(fromAlice, carol)

        assertNull("it must not appear as Alice", manager.conversationWith(alice))
        assertNotNull(manager.conversationWith(carol))
    }

    @Test
    fun `an unauthenticated sender does not create a conversation`() {
        val envelope = MessagingEnvelope.directText(alice, me, "hi")

        val result = deliver(envelope, null)

        assertEquals(EnvelopeAdmission.Result.UnauthenticatedSender, result)
        assertTrue(manager.conversations.value.isEmpty())
    }

    @Test
    fun `ordinary chat text does not create a conversation`() {
        val result = manager.receive("Is the lecture at 4?", "m-plain", 1_700_000_000_000L, alice)

        assertEquals(EnvelopeAdmission.Result.NotAnEnvelope, result)
        assertTrue(manager.conversations.value.isEmpty())
    }

    @Test
    fun `a blocked sender's message is discarded`() = runTest {
        contacts.save(MessagingIdentity.of(alice, "Alice"))
        contacts.setBlocked(alice, true)
        val envelope = MessagingEnvelope.directText(alice, me, "hello anyway")

        deliver(envelope, alice)

        assertTrue(manager.conversations.value.isEmpty())
    }

    @Test
    fun `a re-delivered message is not duplicated`() {
        val envelope = MessagingEnvelope.directText(alice, me, "hi")

        deliver(envelope, alice)
        deliver(envelope, alice)

        assertEquals(1, manager.conversationWith(alice)?.messages?.size)
    }

    // MARK: - Delivery receipts

    @Test
    fun `receiving a message sends a delivered receipt back`() {
        val envelope = MessagingEnvelope.directText(alice, me, "notes are ready")
        transport.sent.clear()

        deliver(envelope, alice)

        // Only the recipient can state that a message arrived, so this is the only way the sender
        // can ever learn it.
        val receipt = sentReceipts().single()
        assertEquals(DeliveryState.DELIVERED to envelope.messageId, receipt)
        assertEquals(alice, transport.sent.last().first)
    }

    @Test
    fun `a delivered receipt advances the sender's message`() {
        manager.send(alice, "are you coming?")
        val messageId = lastOutgoing().messageId
        assertEquals(DeliveryState.SENT, lastOutgoing().deliveryState)

        val receipt = MessagingEnvelope(
            messageId = "r1",
            conversationId = ConversationId.direct(alice, me),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = me,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = DeliveryReceipt.encode(DeliveryState.DELIVERED, messageId)!!
        )
        deliver(receipt, alice)

        assertEquals(DeliveryState.DELIVERED, lastOutgoing().deliveryState)
    }

    @Test
    fun `a read receipt advances past delivered`() {
        manager.send(alice, "hi")
        val messageId = lastOutgoing().messageId

        listOf(DeliveryState.DELIVERED, DeliveryState.READ).forEach { state ->
            deliver(
                MessagingEnvelope(
                    messageId = "r-${state.name}",
                    conversationId = ConversationId.direct(alice, me),
                    kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
                    senderPublicKey = alice,
                    recipientPublicKey = me,
                    timestampEpochMillis = 1_700_000_000_000L,
                    payload = DeliveryReceipt.encode(state, messageId)!!
                ),
                alice
            )
        }

        assertEquals(DeliveryState.READ, lastOutgoing().deliveryState)
    }

    @Test
    fun `opening a conversation sends read receipts for its messages`() {
        deliver(MessagingEnvelope.directText(alice, me, "one"), alice)
        transport.sent.clear()

        manager.markRead(alice)

        assertEquals(listOf(DeliveryState.READ), sentReceipts().map { it.first })
    }

    @Test
    fun `read receipts are not re-sent for messages already acknowledged`() {
        deliver(MessagingEnvelope.directText(alice, me, "one"), alice)
        manager.markRead(alice)

        now = 5_000L
        deliver(MessagingEnvelope.directText(alice, me, "two", timestampEpochMillis = now), alice)
        transport.sent.clear()
        manager.markRead(alice)

        // Only the new message needs acknowledging; re-sending for the old one would grow with the
        // length of the conversation every time it is opened.
        assertEquals(1, sentReceipts().size)
    }

    @Test
    fun `a receipt never provokes another receipt`() {
        val receipt = MessagingEnvelope(
            messageId = "r1",
            conversationId = ConversationId.direct(alice, me),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = me,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = DeliveryReceipt.encode(DeliveryState.DELIVERED, "whatever")!!
        )
        transport.sent.clear()

        deliver(receipt, alice)

        // Otherwise two devices would acknowledge each other's acknowledgements indefinitely.
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `a receipt from the wrong person cannot advance a message`() {
        manager.send(alice, "hi")
        val messageId = lastOutgoing().messageId

        // Carol confirms a message she was never sent.
        val forged = MessagingEnvelope(
            messageId = "r1",
            conversationId = ConversationId.direct(carol, me),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = carol,
            recipientPublicKey = me,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = DeliveryReceipt.encode(DeliveryState.READ, messageId)!!
        )
        deliver(forged, carol)

        // Admission proves Carol sent it, but the message she is confirming is not hers to confirm.
        assertEquals(DeliveryState.SENT, lastOutgoing().deliveryState)
    }

    @Test
    fun `a receipt only applies to a message actually sent to that person`() {
        // Two messages to two people. A receipt from one must not touch the other's message even
        // though both ids are known to this device.
        manager.send(alice, "for alice")
        val aliceMessageId = manager.conversationWith(alice)!!.messages.last().messageId
        manager.send(carol, "for carol")
        val carolMessageId = manager.conversationWith(carol)!!.messages.last().messageId

        fun receiptFrom(sender: MessagingPublicKey, messageId: String) = MessagingEnvelope(
            messageId = "r-$messageId",
            conversationId = ConversationId.direct(sender, me),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = sender,
            recipientPublicKey = me,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = DeliveryReceipt.encode(DeliveryState.DELIVERED, messageId)!!
        ).encode()

        // Carol confirms Alice's message: refused.
        manager.receive(receiptFrom(carol, aliceMessageId), "r-${System.nanoTime()}", 1_700_000_000_000L, carol)
        assertEquals(
            DeliveryState.SENT,
            manager.conversationWith(alice)!!.messages.last().deliveryState
        )

        // Alice confirms her own: accepted.
        manager.receive(receiptFrom(alice, aliceMessageId), "r-${System.nanoTime()}", 1_700_000_000_000L, alice)
        assertEquals(
            DeliveryState.DELIVERED,
            manager.conversationWith(alice)!!.messages.last().deliveryState
        )
        // Carol's own message is untouched throughout.
        assertEquals(
            DeliveryState.SENT,
            manager.conversationWith(carol)!!.messages.last().deliveryState
        )
        assertTrue(carolMessageId.isNotEmpty())
    }

    @Test
    fun `a receipt cannot move a message backwards`() {
        manager.send(alice, "hi")
        val messageId = lastOutgoing().messageId

        fun receipt(state: DeliveryState) = MessagingEnvelope(
            messageId = "r-${state.name}",
            conversationId = ConversationId.direct(alice, me),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = me,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = DeliveryReceipt.encode(state, messageId)!!
        ).encode()

        manager.receive(receipt(DeliveryState.READ), "r-${System.nanoTime()}", 1_700_000_000_000L, alice)
        assertEquals(DeliveryState.READ, lastOutgoing().deliveryState)

        // A delivered receipt arriving late, out of order, must not undo a known read.
        manager.receive(receipt(DeliveryState.DELIVERED), "r-${System.nanoTime()}", 1_700_000_000_000L, alice)
        assertEquals(DeliveryState.READ, lastOutgoing().deliveryState)
    }

    @Test
    fun `receipt encoding round trips and rejects nonsense`() {
        val encoded = DeliveryReceipt.encode(DeliveryState.DELIVERED, "abc")!!
        assertEquals(DeliveryState.DELIVERED to "abc", DeliveryReceipt.decode(encoded))

        assertNull(DeliveryReceipt.decode("not a receipt"))
        assertNull(DeliveryReceipt.decode(DeliveryReceipt.PREFIX + "!!!"))
        // States the recipient is in no position to assert are not encodable.
        assertNull(DeliveryReceipt.encode(DeliveryState.SENT, "abc"))
        assertNull(DeliveryReceipt.encode(DeliveryState.PENDING_OFFLINE, "abc"))
    }

    @Test
    fun `a message re-examined does not acknowledge itself again`() {
        val envelope = MessagingEnvelope.directText(alice, me, "notes are ready")
        deliver(envelope)
        val afterFirst = transport.sent.size

        // The stored chat list is re-emitted in full whenever anything changes, so the same message
        // passes through admission repeatedly. Acknowledging each time put traffic on the network
        // that made the other device re-examine its own history and reply in kind — the two devices
        // then generated messages for each other indefinitely.
        repeat(5) { deliver(envelope) }

        assertEquals("only the first sighting may be acknowledged", afterFirst, transport.sent.size)
    }

    @Test
    fun `each distinct message is acknowledged exactly once`() {
        deliver(MessagingEnvelope.directText(alice, me, "one", messageId = "m1"))
        deliver(MessagingEnvelope.directText(alice, me, "two", messageId = "m2"))

        val receipts = sentReceipts()
        assertEquals(2, receipts.size)
        assertEquals(setOf("m1", "m2"), receipts.map { it.second }.toSet())
    }

    // MARK: - Control traffic

    @Test
    fun `control traffic is sent but never shown as a message`() {
        manager.sendControl(alice, "GKEY1:opaque-machine-payload")

        assertEquals("it must actually be transmitted", 1, transport.sent.size)
        assertTrue(
            "but must not appear in any conversation",
            manager.conversations.value.isEmpty()
        )
    }

    @Test
    fun `arriving control traffic is admitted without becoming a message`() {
        val control = MessagingEnvelope(
            messageId = "c1",
            conversationId = ConversationId.direct(alice, me),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = me,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = "GKEY1:opaque-machine-payload"
        )

        val result = deliver(control, alice)

        // Admitted so the caller can act on it, but never filed — otherwise a group key would
        // appear in the chat looking like somebody typing gibberish.
        assertTrue(result is EnvelopeAdmission.Result.Admitted)
        assertTrue(manager.conversations.value.isEmpty())
    }

    @Test
    fun `control traffic is still subject to the recipient check`() {
        val control = MessagingEnvelope(
            messageId = "c1",
            conversationId = ConversationId.direct(alice, carol),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = carol,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = "GKEY1:not-for-me"
        )

        assertEquals(
            EnvelopeAdmission.Result.NotForThisRecipient,
            deliver(control, alice)
        )
    }

    @Test
    fun `control traffic is attributed to the proven sender`() {
        val control = MessagingEnvelope(
            messageId = "c1",
            conversationId = ConversationId.direct(alice, me),
            kind = com.bitchat.android.messaging.protocol.MessageKind.SYSTEM,
            senderPublicKey = alice,
            recipientPublicKey = me,
            timestampEpochMillis = 1_700_000_000_000L,
            payload = "GKEY1:opaque"
        )

        val result = deliver(control, carol)

        // Admitted, but as Carol's control traffic — the caller decides whether Carol is entitled
        // to send it, which for a group key means checking her standing in that group.
        assertTrue(result is EnvelopeAdmission.Result.Admitted)
        assertEquals(
            carol,
            (result as EnvelopeAdmission.Result.Admitted).envelope.senderPublicKey
        )
    }

    @Test
    fun `control traffic to a blocked recipient is refused`() = runTest {
        contacts.save(MessagingIdentity.of(alice, "Alice"))
        contacts.setBlocked(alice, true)

        assertEquals(
            DirectMessageManager.SendResult.BlockedRecipient,
            manager.sendControl(alice, "GKEY1:payload")
        )
        assertTrue(transport.sent.isEmpty())
    }

    // MARK: - Conversations

    @Test
    fun `both directions land in the same conversation`() {
        manager.send(alice, "are you coming?")
        now = 2_000L
        deliver(MessagingEnvelope.directText(alice, me, "on my way", timestampEpochMillis = now), alice)

        val conversation = manager.conversationWith(alice)!!
        assertEquals(1, manager.conversations.value.size)
        assertEquals(2, conversation.messages.size)
        assertEquals(ConversationId.direct(me, alice), conversation.id)
    }

    @Test
    fun `messages are ordered oldest first`() {
        manager.send(alice, "first")
        now = 5_000L
        manager.send(alice, "second")

        assertEquals(
            listOf("first", "second"),
            manager.conversationWith(alice)!!.messages.map { it.content }
        )
    }

    @Test
    fun `conversations are listed most recently active first`() {
        manager.send(alice, "hi alice")
        now = 9_000L
        manager.send(carol, "hi carol")

        assertEquals(
            listOf(carol, alice),
            manager.conversations.value.map { it.counterparty }
        )
    }

    @Test
    fun `incoming messages count as unread until the conversation is read`() {
        deliver(MessagingEnvelope.directText(alice, me, "one"), alice)
        now = 2_000L
        deliver(MessagingEnvelope.directText(alice, me, "two", timestampEpochMillis = now), alice)

        assertEquals(2, manager.conversationWith(alice)?.unreadCount)

        manager.markRead(alice)
        assertEquals(0, manager.conversationWith(alice)?.unreadCount)
    }

    @Test
    fun `a new arrival makes a read conversation unread again`() {
        deliver(MessagingEnvelope.directText(alice, me, "one"), alice)
        manager.markRead(alice)
        assertEquals(0, manager.conversationWith(alice)?.unreadCount)

        now = 3_000L
        deliver(MessagingEnvelope.directText(alice, me, "two", timestampEpochMillis = now), alice)
        assertTrue(manager.conversationWith(alice)!!.hasUnread)
    }

    @Test
    fun `outgoing messages are never counted as unread`() {
        manager.send(alice, "hi")
        assertEquals(0, manager.conversationWith(alice)?.unreadCount)
    }

    /**
     * Deliver an envelope as the transport would, supplying the message id and timestamp it
     * preserves rather than re-sending them inside the envelope.
     */
    private fun deliver(
        envelope: MessagingEnvelope,
        from: MessagingPublicKey? = alice
    ): EnvelopeAdmission.Result = manager.receive(
        content = envelope.encode(),
        messageId = envelope.messageId,
        timestampEpochMillis = envelope.timestampEpochMillis,
        authenticatedSender = from
    )

    /** Decode something this device transmitted, as its recipient would. */
    private fun decodeSent(index: Int = 0, recipient: MessagingPublicKey = alice) =
        MessagingEnvelope.decode(
            content = transport.sent[index].second,
            messageId = "m-$index",
            timestampEpochMillis = 1_700_000_000_000L,
            sender = me,
            localKey = recipient
        )

    /** Every receipt this device transmitted. */
    private fun sentReceipts(recipient: MessagingPublicKey = alice) =
        transport.sent.indices.mapNotNull { decodeSent(it, recipient) }
            .mapNotNull { DeliveryReceipt.decode(it.payload) }

    private fun lastOutgoing(): DirectMessage =
        manager.conversations.value.flatMap { it.messages }.last { it.isOutgoing }
}
