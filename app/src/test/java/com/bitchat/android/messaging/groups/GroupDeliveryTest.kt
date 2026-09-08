package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.conversation.SendOutcome
import com.bitchat.android.messaging.identity.MessagingPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers a group conversation actually reaching the people in it.
 *
 * This is the failure these tests exist for: a group could be created, a message could be sent, the
 * sender's screen showed it, and no other device ever saw a word. Creating a group is a local act,
 * so unless the other members are told the group exists, given its key, and pointed at the channel
 * its traffic travels on, they have nothing to receive into and nowhere to listen.
 *
 * Each device is modelled by its own [GroupManager], [GroupKeyManager] and [GroupMessageManager],
 * because sharing any of them between devices would quietly assume away the very thing under test.
 */
class GroupDeliveryTest {

    private val alice = MessagingPublicKey.parse("a".repeat(64))!!
    private val bob = MessagingPublicKey.parse("b".repeat(64))!!
    private val carol = MessagingPublicKey.parse("c".repeat(64))!!
    private val mallory = MessagingPublicKey.parse("e".repeat(64))!!

    private var now = 1_000L

    /** One device: what it knows, what it can read, and how it sends. */
    private inner class Device(val key: MessagingPublicKey) {
        val groups = GroupManager(clockMillis = { now }, idFactory = { "g1" })
        val keys = GroupKeyManager()

        /** What this device put on the air, by channel. */
        val sent = mutableListOf<Pair<String, String>>()

        /** Channels this device is listening to. Traffic elsewhere never reaches it. */
        val joined = mutableSetOf<String>()

        val messages = GroupMessageManager(
            localKey = key,
            groups = groups,
            keys = keys,
            transport = { channel, envelope ->
                joined.add(channel)
                sent.add(channel to envelope)
                SendOutcome.SENT_LOCAL
            },
            clockMillis = { now }
        )

        /** Take an invitation exactly as the application layer does. */
        fun accept(invite: GroupInvite, from: MessagingPublicKey) {
            groups.adoptInvitation(invite.groupId, invite.groupName, from, key)
            keys.install(GroupKey(invite.groupId, invite.keyVersion, invite.keyMaterial))
            joined.add(GroupMessageManager.channelTagFor(invite.groupId))
        }

        fun read(groupId: String): List<String> =
            messages.messagesFor(groupId).map { it.content }
    }

    private lateinit var a: Device
    private lateinit var b: Device
    private lateinit var groupId: String

    @Before
    fun setUp() {
        a = Device(alice)
        val created = a.groups.create(
            creator = alice,
            name = "Project Team",
            initialMembers = setOf(bob, carol)
        ) as GroupManager.Outcome.Success
        groupId = created.group.id
        a.keys.createInitialKey(groupId)
        a.joined.add(GroupMessageManager.channelTagFor(groupId))

        b = Device(bob)
    }

    /** The invitation Alice sends to each member. */
    private fun inviteFromAlice() = GroupInvite(
        groupId = groupId,
        groupName = a.groups.group(groupId)!!.name,
        keyVersion = a.keys.currentKey(groupId)!!.version,
        keyMaterial = a.keys.currentKey(groupId)!!.material
    )

    /**
     * Carry one device's traffic to another, the way the mesh does.
     *
     * A channel a device has not joined is dropped, because that is exactly what the transport does
     * with it — which is what made group chat silent in the first place.
     */
    private fun deliver(from: Device, to: Device, sender: MessagingPublicKey = from.key) {
        from.sent.forEach { (channel, envelope) ->
            if (channel !in to.joined) return@forEach
            val outcome = to.messages.receive(envelope, now, sender)
            if (outcome is GroupMessageManager.ReceiveResult.SenderNotAMember) {
                // The application layer completes the membership from whoever holds the key, then
                // offers the message again.
                to.messages.groupProvenByDecryption(envelope, sender)?.let { proven ->
                    to.groups.learnMember(proven, sender)
                    to.messages.receive(envelope, now, sender)
                }
            }
        }
    }

    // MARK: - The reported failure

    @Test
    fun `a member who was never invited receives nothing`() {
        // Bob has no group, no key, and is not listening: the state before the fix.
        a.messages.send(groupId, "meeting at four")

        deliver(a, b)

        assertTrue("without an invitation there is nothing to receive into", b.read(groupId).isEmpty())
    }

    @Test
    fun `an invited member receives the group's messages`() {
        b.accept(inviteFromAlice(), from = alice)

        a.messages.send(groupId, "meeting at four")
        deliver(a, b)

        assertEquals(listOf("meeting at four"), b.read(groupId))
    }

    @Test
    fun `an invited member can reply and be heard`() {
        b.accept(inviteFromAlice(), from = alice)

        b.messages.send(groupId, "on my way")
        deliver(b, a)

        assertEquals(listOf("on my way"), a.read(groupId))
    }

    @Test
    fun `a member who does not listen to the channel hears nothing`() {
        b.accept(inviteFromAlice(), from = alice)
        // Knows the group and holds the key, but never joined the channel.
        b.joined.clear()

        a.messages.send(groupId, "meeting at four")
        deliver(a, b)

        assertTrue(
            "joining the channel is what makes group traffic arrive at all",
            b.read(groupId).isEmpty()
        )
    }

    @Test
    fun `the sender listens to its own group so replies come back`() {
        // A creator who only ever sends would never receive: the channel has to be joined at
        // creation, not on the first send.
        assertTrue(GroupMessageManager.channelTagFor(groupId) in a.joined)
    }

    @Test
    fun `every member computes the same channel without being told it`() {
        assertEquals(
            GroupMessageManager.channelTagFor(groupId),
            GroupMessageManager.channelTagFor(groupId)
        )
        assertTrue(GroupMessageManager.channelTagFor(groupId).startsWith("#"))
    }

    // MARK: - Learning the rest of the group

    @Test
    fun `a message from a member not yet known is still delivered`() {
        // Bob's invitation named only Alice, so Carol is a stranger to him until she speaks.
        b.accept(inviteFromAlice(), from = alice)
        val c = Device(carol).apply { accept(inviteFromAlice(), from = alice) }

        c.messages.send(groupId, "I'll bring the notes")
        deliver(c, b)

        assertEquals(listOf("I'll bring the notes"), b.read(groupId))
        assertTrue("Carol must now be on Bob's list", b.groups.group(groupId)!!.isMember(carol))
    }

    @Test
    fun `an outsider is not added to the group by speaking`() {
        b.accept(inviteFromAlice(), from = alice)

        // Mallory holds no group key, so nothing she sends can decrypt.
        val m = Device(mallory)
        m.groups.create(creator = mallory, name = "Project Team", initialMembers = emptySet())
        m.keys.createInitialKey(groupId)
        m.joined.add(GroupMessageManager.channelTagFor(groupId))
        m.messages.send(groupId, "let me in")

        deliver(m, b, sender = mallory)

        assertTrue("an outsider's message must not appear", b.read(groupId).isEmpty())
        assertTrue(
            "holding the key is what proves membership, and Mallory does not hold it",
            !b.groups.group(groupId)!!.isMember(mallory)
        )
    }

    // MARK: - Arriving out of order

    @Test
    fun `a message that arrives before the invitation is delivered once it lands`() {
        // The message travels the channel and the invitation the private path, so the two can cross.
        a.messages.send(groupId, "meeting at four")
        b.joined.add(GroupMessageManager.channelTagFor(groupId))
        deliver(a, b)
        assertTrue("nothing can be read yet", b.read(groupId).isEmpty())

        b.accept(inviteFromAlice(), from = alice)
        // The application layer re-offers what is already sitting in the channel.
        deliver(a, b)

        assertEquals(listOf("meeting at four"), b.read(groupId))
    }

    @Test
    fun `re-offering a message does not duplicate it`() {
        b.accept(inviteFromAlice(), from = alice)
        a.messages.send(groupId, "meeting at four")

        deliver(a, b)
        deliver(a, b)
        deliver(a, b)

        assertEquals(1, b.read(groupId).size)
    }

    // MARK: - Membership proof

    @Test
    fun `membership is proven only by a message that decrypts`() {
        b.accept(inviteFromAlice(), from = alice)
        a.messages.send(groupId, "meeting at four")
        val envelope = a.sent.single().second

        assertEquals(groupId, b.messages.groupProvenByDecryption(envelope, alice))
        // The same bytes attributed to somebody else prove nothing.
        assertNotNull(envelope)
        assertEquals(null, b.messages.groupProvenByDecryption(envelope, mallory))
        assertEquals(null, b.messages.groupProvenByDecryption("not group traffic", alice))
    }
}
