package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.identity.MessagingPublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers group administration and per-member permissions.
 *
 * The property under test throughout: authorization is decided in the group logic, so an
 * unauthorized action fails even when it is attempted directly rather than through a screen that
 * would have hidden the control.
 */
class GroupManagerTest {

    private val owner = MessagingPublicKey.parse("0".repeat(63) + "1")!!
    private val admin = MessagingPublicKey.parse("0".repeat(63) + "2")!!
    private val member = MessagingPublicKey.parse("0".repeat(63) + "3")!!
    private val other = MessagingPublicKey.parse("0".repeat(63) + "4")!!
    private val stranger = MessagingPublicKey.parse("0".repeat(63) + "5")!!

    private var now = 1_000L
    private var counter = 0
    private val manager = GroupManager(clockMillis = { now }, idFactory = { "g${++counter}" })

    private fun newGroup(
        defaults: Set<GroupPermission> = GroupPermission.ORDINARY_MEMBER
    ): Group {
        val outcome = manager.create(
            creator = owner,
            name = "Study Group",
            initialMembers = setOf(admin, member),
            defaultMemberPermissions = defaults
        )
        val group = (outcome as GroupManager.Outcome.Success).group
        manager.promoteToAdmin(owner, group.id, admin)
        return manager.group(group.id)!!
    }

    // MARK: - Creation

    @Test
    fun `the creator becomes the owner`() {
        val group = newGroup()
        assertEquals(GroupRole.OWNER, group.memberFor(owner)?.role)
        assertEquals(owner, group.owner?.publicKey)
    }

    @Test
    fun `initial members join as ordinary members`() {
        val group = newGroup()
        assertEquals(GroupRole.MEMBER, group.memberFor(member)?.role)
    }

    @Test
    fun `a group cannot be created without a name`() {
        assertEquals(GroupManager.Outcome.InvalidRequest, manager.create(owner, "   "))
    }

    @Test
    fun `the creator is not duplicated when also listed as a member`() {
        val outcome = manager.create(owner, "Team", initialMembers = setOf(owner, member))
        val group = (outcome as GroupManager.Outcome.Success).group
        assertEquals(2, group.members.size)
    }

    @Test
    fun `groups are listed for their members only`() {
        val group = newGroup()
        assertEquals(listOf(group.id), manager.groupsFor(member).map { it.id })
        assertTrue(manager.groupsFor(stranger).isEmpty())
    }

    // MARK: - Membership

    @Test
    fun `an admin can add a member`() {
        val group = newGroup()
        val outcome = manager.addMember(admin, group.id, other)
        assertTrue(outcome is GroupManager.Outcome.Success)
        assertTrue(manager.group(group.id)!!.isMember(other))
    }

    @Test
    fun `an ordinary member cannot add a member by default`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.addMember(member, group.id, other)
        )
        assertFalse(manager.group(group.id)!!.isMember(other))
    }

    @Test
    fun `a stranger cannot add themselves to a group`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.NotAMember,
            manager.addMember(stranger, group.id, stranger)
        )
        assertFalse(manager.group(group.id)!!.isMember(stranger))
    }

    @Test
    fun `adding someone twice is reported rather than duplicating them`() {
        val group = newGroup()
        manager.addMember(admin, group.id, other)
        assertEquals(
            GroupManager.Outcome.AlreadyAMember,
            manager.addMember(admin, group.id, other)
        )
        assertEquals(1, manager.group(group.id)!!.members.count { it.publicKey == other })
    }

    @Test
    fun `an admin can remove an ordinary member`() {
        val group = newGroup()
        assertTrue(manager.removeMember(admin, group.id, member) is GroupManager.Outcome.Success)
        assertFalse(manager.group(group.id)!!.isMember(member))
    }

    @Test
    fun `nobody can remove the owner`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.CannotActOnOwner,
            manager.removeMember(admin, group.id, owner)
        )
        assertTrue(manager.group(group.id)!!.isMember(owner))
    }

    @Test
    fun `a member cannot remove another member`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.removeMember(member, group.id, admin)
        )
    }

    @Test
    fun `a member can leave voluntarily`() {
        val group = newGroup()
        assertTrue(manager.leave(member, group.id) is GroupManager.Outcome.Success)
        assertFalse(manager.group(group.id)!!.isMember(member))
    }

    @Test
    fun `the owner cannot simply leave`() {
        val group = newGroup()
        assertEquals(GroupManager.Outcome.CannotActOnOwner, manager.leave(owner, group.id))
    }

    // MARK: - Admin management

    @Test
    fun `the owner can promote and demote admins`() {
        val group = newGroup()
        assertTrue(manager.promoteToAdmin(owner, group.id, member) is GroupManager.Outcome.Success)
        assertEquals(GroupRole.ADMIN, manager.group(group.id)!!.memberFor(member)?.role)

        assertTrue(manager.demoteFromAdmin(owner, group.id, member) is GroupManager.Outcome.Success)
        assertEquals(GroupRole.MEMBER, manager.group(group.id)!!.memberFor(member)?.role)
    }

    @Test
    fun `an ordinary member cannot promote themselves`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.promoteToAdmin(member, group.id, member)
        )
        assertEquals(GroupRole.MEMBER, manager.group(group.id)!!.memberFor(member)?.role)
    }

    @Test
    fun `an admin cannot demote the owner`() {
        val group = newGroup()
        // Otherwise an appointed admin could take the group from the person who created it.
        assertEquals(
            GroupManager.Outcome.CannotActOnOwner,
            manager.demoteFromAdmin(admin, group.id, owner)
        )
        assertEquals(GroupRole.OWNER, manager.group(group.id)!!.memberFor(owner)?.role)
    }

    @Test
    fun `promoting a non member is refused`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.NoSuchMember,
            manager.promoteToAdmin(owner, group.id, stranger)
        )
    }

    // MARK: - Group info

    @Test
    fun `an admin can edit group information`() {
        val group = newGroup()
        manager.editInfo(admin, group.id, name = "Renamed", description = "New purpose")
        val updated = manager.group(group.id)!!
        assertEquals("Renamed", updated.name)
        assertEquals("New purpose", updated.description)
    }

    @Test
    fun `an ordinary member cannot edit group information by default`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.editInfo(member, group.id, name = "Hijacked")
        )
        assertEquals("Study Group", manager.group(group.id)!!.name)
    }

    @Test
    fun `a group cannot be renamed to nothing`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.InvalidRequest,
            manager.editInfo(owner, group.id, name = "   ")
        )
    }

    // MARK: - Permissions

    @Test
    fun `an ordinary member of a normal group may send`() {
        val group = newGroup()
        assertTrue(manager.canPublish(member, group.id))
        assertTrue(manager.canSendFiles(member, group.id))
    }

    @Test
    fun `members of an announcement group may not send but admins may`() {
        val group = newGroup(defaults = GroupPermission.READ_ONLY)

        assertFalse("a member must not be able to publish", manager.canPublish(member, group.id))
        assertTrue("an admin must still be able to publish", manager.canPublish(admin, group.id))
        assertTrue("the owner must still be able to publish", manager.canPublish(owner, group.id))
        assertTrue(group.isAnnouncementOnly)
    }

    @Test
    fun `a stranger may never publish`() {
        val group = newGroup()
        assertFalse(manager.canPublish(stranger, group.id))
        // A non-member has no permissions at all, not the group's defaults.
        assertTrue(manager.group(group.id)!!.permissionsFor(stranger).isEmpty())
    }

    @Test
    fun `one member can be muted without affecting the others`() {
        val group = newGroup()
        manager.setMemberPermissions(owner, group.id, member, GroupPermission.READ_ONLY)

        assertFalse(manager.canPublish(member, group.id))
        assertTrue(manager.canPublish(admin, group.id))
    }

    @Test
    fun `one member can be granted permission to post in an announcement group`() {
        val group = newGroup(defaults = GroupPermission.READ_ONLY)
        manager.setMemberPermissions(
            owner, group.id, member, setOf(GroupPermission.CAN_SEND_MESSAGES)
        )

        assertTrue(manager.canPublish(member, group.id))
        assertFalse("the group default still applies to everyone else", manager.canPublish(other, group.id))
        assertFalse(manager.group(group.id)!!.isAnnouncementOnly)
    }

    @Test
    fun `clearing an override returns a member to the group default`() {
        val group = newGroup()
        manager.setMemberPermissions(owner, group.id, member, GroupPermission.READ_ONLY)
        assertFalse(manager.canPublish(member, group.id))

        manager.setMemberPermissions(owner, group.id, member, null)
        assertTrue(manager.canPublish(member, group.id))
    }

    @Test
    fun `changing the group default does not discard an individual exception`() {
        val group = newGroup(defaults = GroupPermission.READ_ONLY)
        manager.setMemberPermissions(
            owner, group.id, member, setOf(GroupPermission.CAN_SEND_MESSAGES)
        )
        manager.setDefaultMemberPermissions(owner, group.id, GroupPermission.ORDINARY_MEMBER)

        // The exception is stored separately from the default, so it survives.
        assertTrue(manager.canPublish(member, group.id))
        assertEquals(
            setOf(GroupPermission.CAN_SEND_MESSAGES),
            manager.group(group.id)!!.memberFor(member)?.permissionOverride
        )
    }

    @Test
    fun `an admin cannot be restricted by a permission override`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.CannotActOnOwner,
            manager.setMemberPermissions(owner, group.id, admin, GroupPermission.READ_ONLY)
        )
        assertTrue(manager.canPublish(admin, group.id))
    }

    @Test
    fun `an ordinary member cannot change permissions`() {
        val group = newGroup()
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.setMemberPermissions(member, group.id, other, GroupPermission.ALL)
        )
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.setDefaultMemberPermissions(member, group.id, GroupPermission.ALL)
        )
    }

    @Test
    fun `permissions granted to a member do not extend to administration`() {
        val group = newGroup()
        manager.setMemberPermissions(
            owner, group.id, member, setOf(GroupPermission.CAN_SEND_MESSAGES)
        )
        // Being able to speak is not being able to administer.
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.addMember(member, group.id, other)
        )
        assertEquals(
            GroupManager.Outcome.NotPermitted,
            manager.promoteToAdmin(member, group.id, other)
        )
    }

    // MARK: - Unknown groups

    @Test
    fun `operations on an unknown group are refused`() {
        assertEquals(GroupManager.Outcome.NoSuchGroup, manager.addMember(owner, "nope", other))
        assertEquals(GroupManager.Outcome.NoSuchGroup, manager.editInfo(owner, "nope", "x"))
        assertEquals(GroupManager.Outcome.NoSuchGroup, manager.leave(owner, "nope"))
        assertFalse(manager.canPublish(owner, "nope"))
    }

    @Test
    fun `restoring groups replaces the current set`() {
        val group = newGroup()
        manager.restore(emptyList())
        assertTrue(manager.groups.value.isEmpty())

        manager.restore(listOf(group))
        assertNotNull(manager.group(group.id))
    }
}
