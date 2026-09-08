package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.MessagingEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Covers being told a group exists.
 *
 * The invitation is the only thing that turns a group from one device's private note into a
 * conversation, and it has to survive a 255-byte body to do it. Both halves are tested here: that
 * it carries enough to take part, and that it always fits.
 */
class GroupInviteTest {

    private val alice = MessagingPublicKey.parse("a".repeat(64))!!
    private val bob = MessagingPublicKey.parse("b".repeat(64))!!
    private val mallory = MessagingPublicKey.parse("e".repeat(64))!!

    private val material = ByteArray(GroupKey.KEY_BYTES) { it.toByte() }

    private fun invite(
        groupId: String = UUID.randomUUID().toString(),
        name: String = "Project Team",
        version: Int = 1
    ) = GroupInvite(groupId, name, version, material)

    // MARK: - Round trip

    @Test
    fun `an invitation survives the wire intact`() {
        val original = invite()

        val decoded = GroupInvite.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(original.groupId, decoded!!.groupId)
        assertEquals("Project Team", decoded.groupName)
        assertEquals(1, decoded.keyVersion)
        assertTrue("the key must arrive byte for byte", material.contentEquals(decoded.keyMaterial))
    }

    @Test
    fun `an invitation is recognisable before it is parsed`() {
        assertTrue(GroupInvite.looksLikeInvite(invite().encode()))
        assertFalse(GroupInvite.looksLikeInvite("Is the lecture at 4?"))
        assertFalse(GroupInvite.looksLikeInvite("GKEY1:something-else"))
    }

    @Test
    fun `ordinary chat text is not mistaken for an invitation`() {
        assertNull(GroupInvite.decode("see you at the library"))
    }

    // MARK: - Fitting the private path

    @Test
    fun `an invitation fits the private message body`() {
        val encoded = invite(name = "Distributed Systems Study Group").encode()

        assertTrue(
            "an invitation that does not fit is silently refused, and the group never appears",
            encoded.toByteArray(Charsets.UTF_8).size <= MessagingEnvelope.MAX_PAYLOAD_BYTES
        )
    }

    @Test
    fun `a very long name is shortened rather than making the invitation unsendable`() {
        val encoded = invite(name = "A".repeat(400)).encode()

        assertTrue(
            encoded.toByteArray(Charsets.UTF_8).size <= MessagingEnvelope.MAX_PAYLOAD_BYTES
        )
        // Still usable: the parts that matter are intact.
        val decoded = GroupInvite.decode(encoded)!!
        assertEquals(1, decoded.keyVersion)
        assertTrue(material.contentEquals(decoded.keyMaterial))
    }

    @Test
    fun `a name of multi byte characters is measured in bytes not characters`() {
        // Forty emoji are forty characters and a hundred and sixty bytes. Trimming by character
        // count alone would produce an invitation that looks short and does not fit.
        val encoded = invite(name = "🎉".repeat(60)).encode()

        assertTrue(
            encoded.toByteArray(Charsets.UTF_8).size <= MessagingEnvelope.MAX_PAYLOAD_BYTES
        )
        assertNotNull(GroupInvite.decode(encoded))
    }

    @Test
    fun `an invitation carrying no name is still usable`() {
        val original = invite(name = "")

        val decoded = GroupInvite.decode(original.encode())

        assertNotNull(decoded)
        assertEquals(original.groupId, decoded!!.groupId)
        assertTrue(material.contentEquals(decoded.keyMaterial))
    }

    // MARK: - Rejecting malformed input

    @Test
    fun `a truncated invitation is refused rather than half applied`() {
        val encoded = invite().encode()

        assertNull(GroupInvite.decode(encoded.dropLast(10)))
    }

    @Test
    fun `an invitation carrying the wrong size of key is refused`() {
        val bad = GroupInvite("g1", "Team", 1, ByteArray(8)).encode()

        // A short key would either fail to decrypt or, worse, be installed and make every later
        // message unreadable.
        assertNull(GroupInvite.decode(bad))
    }

    @Test
    fun `an invitation with no group is refused`() {
        assertNull(GroupInvite.decode(GroupInvite("", "Team", 1, material).encode()))
    }

    @Test
    fun `an invitation with a nonsense version is refused`() {
        assertNull(GroupInvite.decode(GroupInvite("g1", "Team", 0, material).encode()))
        assertNull(GroupInvite.decode(GroupInvite("g1", "Team", -3, material).encode()))
    }

    @Test
    fun `key material never appears in a log line`() {
        val readable = invite().toString()

        assertFalse(
            "an invitation must not print the key it carries",
            readable.contains(java.util.Base64.getEncoder().encodeToString(material).take(8))
        )
    }

    // MARK: - Adoption

    @Test
    fun `adopting an invitation makes the group usable`() {
        val manager = GroupManager(clockMillis = { 1_000L })
        val received = invite(name = "Project Team")

        val group = manager.adoptInvitation(received.groupId, received.groupName, alice, bob)

        assertNotNull(group)
        assertTrue("the recipient must be a member", group!!.isMember(bob))
        assertTrue("the inviter must be a member", group.isMember(alice))
        assertEquals(GroupRole.OWNER, group.memberFor(alice)?.role)
        assertEquals(GroupRole.MEMBER, group.memberFor(bob)?.role)
        // Being able to reply is the point of joining.
        assertTrue(manager.canPublish(bob, received.groupId))
    }

    @Test
    fun `a second invitation cannot take over a group already joined`() {
        val manager = GroupManager(clockMillis = { 1_000L })
        val id = UUID.randomUUID().toString()
        manager.adoptInvitation(id, "Project Team", alice, bob)

        // Otherwise anyone who learned the identifier could re-invite the members and install
        // themselves as the group's owner.
        assertNull(manager.adoptInvitation(id, "Project Team", mallory, bob))
        assertEquals(GroupRole.OWNER, manager.group(id)?.memberFor(alice)?.role)
        assertFalse(manager.group(id)!!.isMember(mallory))
    }

    @Test
    fun `a member who speaks is added to the list`() {
        val manager = GroupManager(clockMillis = { 1_000L })
        val id = UUID.randomUUID().toString()
        manager.adoptInvitation(id, "Project Team", alice, bob)
        val carol = MessagingPublicKey.parse("c".repeat(64))!!

        assertTrue(manager.learnMember(id, carol))
        assertTrue(manager.group(id)!!.isMember(carol))
        assertEquals(GroupRole.MEMBER, manager.group(id)?.memberFor(carol)?.role)

        // Learning the same person twice changes nothing.
        assertFalse(manager.learnMember(id, carol))
        assertEquals(3, manager.group(id)!!.members.size)
    }

    @Test
    fun `learning a member never promotes anyone`() {
        val manager = GroupManager(clockMillis = { 1_000L })
        val id = UUID.randomUUID().toString()
        manager.adoptInvitation(id, "Project Team", alice, bob)

        assertFalse("the owner is already known", manager.learnMember(id, alice))
        assertEquals(GroupRole.OWNER, manager.group(id)?.memberFor(alice)?.role)
    }

    @Test
    fun `a member cannot be learned for a group this device does not have`() {
        val manager = GroupManager(clockMillis = { 1_000L })

        assertFalse(manager.learnMember("never-heard-of-it", alice))
        assertNull(manager.group("never-heard-of-it"))
    }
}
