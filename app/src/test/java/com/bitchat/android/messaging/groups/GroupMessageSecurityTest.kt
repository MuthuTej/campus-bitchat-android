package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.conversation.SendOutcome
import com.bitchat.android.messaging.identity.MessagingPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers group message security.
 *
 * The central property: being excluded from a group must mean being *unable* to read it, not merely
 * being asked not to. Several tests below therefore hand an outsider the exact bytes that went over
 * the air and check they still get nothing.
 */
class GroupMessageSecurityTest {

    private val owner = MessagingPublicKey.parse("0".repeat(63) + "1")!!
    private val member = MessagingPublicKey.parse("0".repeat(63) + "2")!!
    private val muted = MessagingPublicKey.parse("0".repeat(63) + "3")!!
    private val outsider = MessagingPublicKey.parse("0".repeat(63) + "4")!!

    private var now = 1_000L
    private var counter = 0

    private lateinit var groups: GroupManager
    private lateinit var groupId: String

    /** Captures what actually went onto the air, so an outsider can be handed the same bytes. */
    private val wire = mutableListOf<String>()
    private var outcome = SendOutcome.SENT_LOCAL

    private lateinit var ownerKeys: GroupKeyManager
    private lateinit var ownerMessages: GroupMessageManager

    @Before
    fun setUp() {
        groups = GroupManager(clockMillis = { now }, idFactory = { "g${++counter}" })
        val created = groups.create(
            creator = owner,
            name = "Project Team",
            initialMembers = setOf(member, muted)
        ) as GroupManager.Outcome.Success
        groupId = created.group.id
        groups.setMemberPermissions(owner, groupId, muted, GroupPermission.READ_ONLY)

        ownerKeys = GroupKeyManager()
        ownerKeys.createInitialKey(groupId)
        ownerMessages = managerFor(owner, ownerKeys)
    }

    private fun managerFor(
        who: MessagingPublicKey,
        keys: GroupKeyManager,
        /**
         * Membership as *that device* sees it. Defaults to the shared view, but a test can supply a
         * stale one to model a device that has not learned about a change yet.
         */
        view: GroupManager = groups
    ) = GroupMessageManager(
        localKey = who,
        groups = view,
        keys = keys,
        transport = { _, envelope -> wire.add(envelope); outcome },
        clockMillis = { now }
    )

    /**
     * A snapshot of the current membership, frozen so later changes do not reach it.
     *
     * Models the realistic case that matters most for removal: the device being cut off does not
     * cooperate by noticing. It still believes it is a member, so nothing but the key rotation can
     * stop it reading.
     */
    private fun frozenView(): GroupManager {
        val snapshot = groups.group(groupId)!!
        val stale = GroupManager(clockMillis = { now }, idFactory = { "frozen" })
        stale.restore(listOf(snapshot))
        return stale
    }

    /** A member who has been given the current key, as distribution would do. */
    private fun memberWithKey(who: MessagingPublicKey): GroupMessageManager {
        val keys = GroupKeyManager()
        ownerKeys.distributionFor(groupId, listOf(who)).forEach { (_, encoded) ->
            keys.install(GroupKeyManager.decodeKey(encoded)!!)
        }
        return managerFor(who, keys)
    }

    // MARK: - Sending permission

    @Test
    fun `a permitted member can send`() {
        val result = memberWithKey(member).send(groupId, "on my way")
        assertTrue(result is GroupMessageManager.SendResult.Accepted)
    }

    @Test
    fun `a muted member cannot send and nothing reaches the network`() {
        wire.clear()
        val result = memberWithKey(muted).send(groupId, "let me through")

        assertEquals(GroupMessageManager.SendResult.NotPermitted, result)
        assertTrue("a refused message must not be transmitted", wire.isEmpty())
    }

    @Test
    fun `an outsider cannot send to the group`() {
        val result = managerFor(outsider, GroupKeyManager()).send(groupId, "hello")
        assertEquals(GroupMessageManager.SendResult.NotAMember, result)
    }

    @Test
    fun `a member without the key cannot send in the clear`() {
        // Rather than falling back to plaintext, which would expose the content to every device.
        val result = managerFor(member, GroupKeyManager()).send(groupId, "hi")
        assertEquals(GroupMessageManager.SendResult.NoGroupKey, result)
        assertTrue(wire.isEmpty())
    }

    // MARK: - The content is genuinely unreadable to outsiders

    @Test
    fun `the plaintext never appears on the wire`() {
        wire.clear()
        ownerMessages.send(groupId, "the exam is on friday")

        assertEquals(1, wire.size)
        assertFalse("group content must not be transmitted in the clear",
            wire.single().contains("the exam is on friday"))
    }

    @Test
    fun `an outsider handed the exact wire bytes cannot read them`() {
        wire.clear()
        ownerMessages.send(groupId, "the exam is on friday")
        val transmitted = wire.single()

        // The outsider belongs to no group and holds no key, but sees the packet on the air.
        val eavesdropper = managerFor(outsider, GroupKeyManager())
        val result = eavesdropper.receive(transmitted, 1_700_000_000_000L, owner)

        // Refused for not being in the group, before the content is even considered.
        assertEquals(GroupMessageManager.ReceiveResult.NotAMember, result)
        assertTrue(eavesdropper.messagesFor(groupId).isEmpty())
    }

    @Test
    fun `an outsider who holds the ciphertext and the key still cannot post as the group`() {
        // Even with everything an eavesdropper could capture, they are not a member and so cannot
        // originate group traffic.
        val keys = GroupKeyManager()
        ownerKeys.distributionFor(groupId, listOf(outsider)).forEach { (_, encoded) ->
            keys.install(GroupKeyManager.decodeKey(encoded)!!)
        }
        wire.clear()

        val result = managerFor(outsider, keys).send(groupId, "class is cancelled")

        assertEquals(GroupMessageManager.SendResult.NotAMember, result)
        assertTrue(wire.isEmpty())
    }

    @Test
    fun `even knowing the group an outsider without the key reads nothing`() {
        wire.clear()
        ownerMessages.send(groupId, "the exam is on friday")
        val transmitted = wire.single()

        // Give the eavesdropper a correct membership view, so only the key is missing. This
        // isolates encryption as the thing doing the work.
        val eavesdropper = managerFor(member, GroupKeyManager())
        val result = eavesdropper.receive(transmitted, 1_700_000_000_000L, owner)

        assertEquals(GroupMessageManager.ReceiveResult.Undecryptable, result)
        assertTrue(eavesdropper.messagesFor(groupId).isEmpty())
    }

    @Test
    fun `a member with the key reads the message`() {
        wire.clear()
        ownerMessages.send(groupId, "the exam is on friday")

        val recipient = memberWithKey(member)
        val result = recipient.receive(wire.single(), 1_700_000_000_000L, owner)

        assertTrue(result is GroupMessageManager.ReceiveResult.Accepted)
        assertEquals("the exam is on friday", recipient.messagesFor(groupId).single().content)
    }

    // MARK: - Receive-side authorization

    @Test
    fun `a muted member's message is rejected on arrival even if they bypassed their own check`() {
        // Compose with the muted member's own manager by temporarily granting, then revoking, so
        // the ciphertext is genuine and only the receiver's check stands between it and display.
        groups.setMemberPermissions(owner, groupId, muted, GroupPermission.ORDINARY_MEMBER)
        val mutedSender = memberWithKey(muted)
        wire.clear()
        mutedSender.send(groupId, "ignoring the mute")
        val transmitted = wire.single()
        groups.setMemberPermissions(owner, groupId, muted, GroupPermission.READ_ONLY)

        val recipient = memberWithKey(member)
        val result = recipient.receive(transmitted, 1_700_000_000_000L, muted)

        assertEquals(GroupMessageManager.ReceiveResult.SenderNotPermitted, result)
        assertTrue(recipient.messagesFor(groupId).isEmpty())
    }

    @Test
    fun `a removed member's message is rejected on arrival`() {
        val removedSender = memberWithKey(member)
        wire.clear()
        removedSender.send(groupId, "still here")
        val transmitted = wire.single()

        groups.removeMember(owner, groupId, member)

        val recipient = memberWithKey(owner)
        assertEquals(
            GroupMessageManager.ReceiveResult.SenderNotAMember,
            recipient.receive(transmitted, 1_700_000_000_000L, member)
        )
    }

    @Test
    fun `a forged sender is rejected before membership is even consulted`() {
        wire.clear()
        ownerMessages.send(groupId, "hello")
        val transmitted = wire.single()

        val recipient = memberWithKey(member)
        // The transport proves the packet came from the outsider, but it claims to be from owner.
        assertEquals(
            GroupMessageManager.ReceiveResult.Rejected,
            recipient.receive(transmitted, 1_700_000_000_000L, outsider)
        )
    }

    @Test
    fun `ordinary text is not treated as a group message`() {
        val recipient = memberWithKey(member)
        assertEquals(
            GroupMessageManager.ReceiveResult.NotAnEnvelope,
            recipient.receive("just chatting", 1_700_000_000_000L, owner)
        )
    }

    // MARK: - Key rotation

    @Test
    fun `a removed member cannot read messages sent after rotation`() {
        // Their device keeps the pre-removal membership view and never learns it was removed, so
        // the member list on their side still says they belong. Only the key rotation can stop
        // them — which is the entire reason removal must rotate.
        val staleView = frozenView()
        val keys = GroupKeyManager()
        ownerKeys.distributionFor(groupId, listOf(member)).forEach { (_, encoded) ->
            keys.install(GroupKeyManager.decodeKey(encoded)!!)
        }
        val removed = managerFor(member, keys, view = staleView)

        // They can read what came before, which is correct: they were a member then.
        wire.clear()
        ownerMessages.send(groupId, "before removal")
        assertTrue(
            removed.receive(wire.single(), 1_700_000_000_000L, owner) is GroupMessageManager.ReceiveResult.Accepted
        )

        groups.removeMember(owner, groupId, member)
        ownerKeys.rotate(groupId)

        wire.clear()
        ownerMessages.send(groupId, "after removal")
        val result = removed.receive(wire.single(), 1_700_000_000_000L, owner)

        // Their stale view still admits them; their old key cannot open the new generation.
        assertTrue("the stale view must still consider them a member",
            staleView.group(groupId)!!.isMember(member))
        assertEquals(GroupMessageManager.ReceiveResult.Undecryptable, result)
        assertFalse(removed.messagesFor(groupId).any { it.content == "after removal" })
    }

    @Test
    fun `a removed member whose device knows it was removed is refused outright`() {
        val removed = memberWithKey(member)
        groups.removeMember(owner, groupId, member)
        ownerKeys.rotate(groupId)

        wire.clear()
        ownerMessages.send(groupId, "after removal")

        // With an up-to-date view the membership check refuses it before decryption is attempted.
        assertEquals(
            GroupMessageManager.ReceiveResult.NotAMember,
            removed.receive(wire.single(), 1_700_000_000_000L, owner)
        )
    }

    @Test
    fun `remaining members keep reading after rotation`() {
        groups.removeMember(owner, groupId, member)
        ownerKeys.rotate(groupId)

        // The owner distributes the new key to who is left.
        val stillHere = memberWithKey(muted)
        wire.clear()
        ownerMessages.send(groupId, "after removal")

        assertTrue(
            stillHere.receive(wire.single(), 1_700_000_000_000L, owner) is GroupMessageManager.ReceiveResult.Accepted
        )
    }

    @Test
    fun `history stays readable across a rotation`() {
        wire.clear()
        ownerMessages.send(groupId, "old message")
        val old = wire.single()
        ownerKeys.rotate(groupId)

        // The owner retains superseded keys, so earlier messages do not become unreadable.
        val result = ownerMessages.receive(old, 1_700_000_000_000L, owner)
        assertTrue(result is GroupMessageManager.ReceiveResult.Accepted)
    }

    @Test
    fun `rotation produces a different key each time`() {
        val first = ownerKeys.currentKey(groupId)!!
        val second = ownerKeys.rotate(groupId)

        assertNotEquals(first.version, second.version)
        assertFalse(first.material.contentEquals(second.material))
    }

    @Test
    fun `a replayed key distribution cannot roll a group back`() {
        val original = ownerKeys.currentKey(groupId)!!
        val encodedOld = GroupKeyManager.encodeKey(original)
        ownerKeys.rotate(groupId)
        val currentVersion = ownerKeys.currentKey(groupId)!!.version

        // Replaying the old distribution message must not make the old key current again.
        assertFalse(ownerKeys.install(GroupKeyManager.decodeKey(encodedOld)!!))
        assertEquals(currentVersion, ownerKeys.currentKey(groupId)!!.version)
    }

    // MARK: - Key distribution encoding

    @Test
    fun `a distributed key round trips`() {
        val key = ownerKeys.currentKey(groupId)!!
        val decoded = GroupKeyManager.decodeKey(GroupKeyManager.encodeKey(key))
        assertEquals(key, decoded)
    }

    @Test
    fun `a malformed key distribution is rejected`() {
        assertNull(GroupKeyManager.decodeKey("not a key"))
        assertNull(GroupKeyManager.decodeKey(GroupKeyManager.KEY_PREFIX + "!!!"))
        // Wrong key length must not be accepted as material.
        val shortKey = GroupKeyManager.KEY_PREFIX + com.bitchat.android.messaging.protocol.Canonical.b64(
            com.bitchat.android.messaging.protocol.Canonical.encode(
                "g1", "1", com.bitchat.android.messaging.protocol.Canonical.b64(ByteArray(8))
            )
        )
        assertNull(GroupKeyManager.decodeKey(shortKey))
    }

    @Test
    fun `distribution targets exactly the requested members`() {
        val distribution = ownerKeys.distributionFor(groupId, listOf(member, muted, member))
        assertEquals(setOf(member, muted), distribution.map { it.first }.toSet())
        assertEquals("duplicates must not produce duplicate sends", 2, distribution.size)
    }

    @Test
    fun `a key never appears in its own string representation`() {
        val key = ownerKeys.currentKey(groupId)!!
        val rendered = key.toString()
        // Guards against key material reaching a log through an accidental interpolation.
        assertFalse(rendered.contains(com.bitchat.android.messaging.protocol.Canonical.hex(key.material)))
        assertTrue(rendered.contains(groupId))
    }

    // MARK: - Cipher properties

    @Test
    fun `a ciphertext cannot be replayed into another group`() {
        val keyA = GroupKey.random("groupA", 1)
        val keyB = GroupKey(groupId = "groupB", version = 1, material = keyA.material)
        val sealed = GroupCipher.seal(keyA, "secret")

        // Same key material, different group: the group id is authenticated, so it must not open.
        assertNull(GroupCipher.open(keyB, sealed))
    }

    @Test
    fun `a ciphertext cannot be presented as another key generation`() {
        val v1 = GroupKey.random("groupA", 1)
        val v2 = GroupKey(groupId = "groupA", version = 2, material = v1.material)
        val sealed = GroupCipher.seal(v1, "secret")

        assertNull(GroupCipher.open(v2, sealed))
    }

    @Test
    fun `tampered ciphertext is rejected rather than partially decrypted`() {
        val key = GroupKey.random("groupA", 1)
        val sealed = GroupCipher.seal(key, "secret")
        val tampered = sealed.dropLast(4) + "AAAA"

        assertNull(GroupCipher.open(key, tampered))
    }

    @Test
    fun `the same plaintext encrypts differently each time`() {
        val key = GroupKey.random("groupA", 1)
        // A fresh nonce per message; identical ciphertexts would leak repetition.
        assertNotEquals(GroupCipher.seal(key, "same"), GroupCipher.seal(key, "same"))
    }

    @Test
    fun `a group key is full length and random`() {
        val a = GroupKey.random("g", 1)
        val b = GroupKey.random("g", 1)
        assertEquals(GroupKey.KEY_BYTES, a.material.size)
        assertFalse("keys must not repeat", a.material.contentEquals(b.material))
    }

    @Test
    fun `forgetting a group discards its keys`() {
        assertNotNull(ownerKeys.currentKey(groupId))
        ownerKeys.forget(groupId)
        assertNull(ownerKeys.currentKey(groupId))
        assertFalse(ownerKeys.knowsKeyFor(groupId))
    }
}
