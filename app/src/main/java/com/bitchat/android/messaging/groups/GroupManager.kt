package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.identity.MessagingPublicKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Group administration.
 *
 * Every operation names the identity performing it and is authorized against that identity's
 * standing in the group. There is no method that mutates a group without an actor, so a caller
 * cannot accidentally perform an administrative action "as the app" and bypass the checks — the
 * interface makes the authorization question unavoidable rather than optional.
 *
 * Holds no cryptography. Membership decides who is *allowed* to do what; the group key decides who
 * is *able* to read, and lives in the key manager alongside the rotation that has to accompany a
 * removal.
 */
class GroupManager(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {

    private val groupsById = mutableMapOf<String, Group>()
    private val _groups = MutableStateFlow<List<Group>>(emptyList())

    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    /** Why an operation was refused. Each case is a distinct reason a caller may want to surface. */
    sealed interface Outcome {
        data class Success(val group: Group) : Outcome
        data object NotAMember : Outcome
        data object NotPermitted : Outcome
        data object NoSuchGroup : Outcome
        data object NoSuchMember : Outcome
        data object AlreadyAMember : Outcome
        data object CannotActOnOwner : Outcome
        data object InvalidRequest : Outcome
    }

    fun group(groupId: String): Group? = groupsById[groupId]

    /** Groups this identity belongs to. */
    fun groupsFor(key: MessagingPublicKey): List<Group> =
        _groups.value.filter { it.isMember(key) }

    /**
     * Create a group.
     *
     * The creator becomes its owner, which is not merely the first admin: ownership is what
     * prevents an admin they later appoint from removing them.
     */
    fun create(
        creator: MessagingPublicKey,
        name: String,
        description: String = "",
        initialMembers: Set<MessagingPublicKey> = emptySet(),
        defaultMemberPermissions: Set<GroupPermission> = GroupPermission.ORDINARY_MEMBER
    ): Outcome {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return Outcome.InvalidRequest

        val now = clockMillis()
        val members = buildList {
            add(GroupMember(creator, GroupRole.OWNER, joinedAtMillis = now))
            initialMembers
                .filter { it != creator }
                .distinct()
                .forEach { add(GroupMember(it, GroupRole.MEMBER, joinedAtMillis = now)) }
        }
        val group = Group(
            id = idFactory(),
            name = trimmedName,
            description = description.trim(),
            createdBy = creator,
            createdAtMillis = now,
            members = members,
            defaultMemberPermissions = defaultMemberPermissions
        )
        return store(group)
    }

    fun addMember(
        actor: MessagingPublicKey,
        groupId: String,
        newMember: MessagingPublicKey
    ): Outcome = mutate(actor, groupId, GroupPermission.CAN_ADD_MEMBERS) { group ->
        if (group.isMember(newMember)) return@mutate Outcome.AlreadyAMember
        group.copy(
            members = group.members + GroupMember(
                publicKey = newMember,
                role = GroupRole.MEMBER,
                joinedAtMillis = clockMillis()
            )
        ).let(::store)
    }

    /**
     * Remove someone from a group.
     *
     * The owner cannot be removed by anyone. Callers must rotate the group key after this returns
     * successfully — membership alone stops a removed person being *authorized*, but only a new key
     * stops them continuing to *read*.
     */
    fun removeMember(
        actor: MessagingPublicKey,
        groupId: String,
        member: MessagingPublicKey
    ): Outcome = mutate(actor, groupId, GroupPermission.CAN_ADD_MEMBERS) { group ->
        val target = group.memberFor(member) ?: return@mutate Outcome.NoSuchMember
        if (target.role == GroupRole.OWNER) return@mutate Outcome.CannotActOnOwner
        store(group.copy(members = group.members.filterNot { it.publicKey == member }))
    }

    /** Leave a group voluntarily. The owner must transfer ownership first. */
    fun leave(actor: MessagingPublicKey, groupId: String): Outcome {
        val group = groupsById[groupId] ?: return Outcome.NoSuchGroup
        val member = group.memberFor(actor) ?: return Outcome.NotAMember
        if (member.role == GroupRole.OWNER) return Outcome.CannotActOnOwner
        return store(group.copy(members = group.members.filterNot { it.publicKey == actor }))
    }

    fun promoteToAdmin(
        actor: MessagingPublicKey,
        groupId: String,
        member: MessagingPublicKey
    ): Outcome = mutate(actor, groupId, GroupPermission.CAN_MANAGE_ADMINS) { group ->
        val target = group.memberFor(member) ?: return@mutate Outcome.NoSuchMember
        if (target.role.isAdministrative) return@mutate Outcome.InvalidRequest
        store(group.replacing(member) { it.copy(role = GroupRole.ADMIN) })
    }

    fun demoteFromAdmin(
        actor: MessagingPublicKey,
        groupId: String,
        member: MessagingPublicKey
    ): Outcome = mutate(actor, groupId, GroupPermission.CAN_MANAGE_ADMINS) { group ->
        val target = group.memberFor(member) ?: return@mutate Outcome.NoSuchMember
        // The owner's standing is not an admin appointment and cannot be revoked.
        if (target.role == GroupRole.OWNER) return@mutate Outcome.CannotActOnOwner
        if (target.role != GroupRole.ADMIN) return@mutate Outcome.InvalidRequest
        store(group.replacing(member) { it.copy(role = GroupRole.MEMBER) })
    }

    fun editInfo(
        actor: MessagingPublicKey,
        groupId: String,
        name: String? = null,
        description: String? = null
    ): Outcome = mutate(actor, groupId, GroupPermission.CAN_EDIT_GROUP) { group ->
        val newName = name?.trim() ?: group.name
        if (newName.isEmpty()) return@mutate Outcome.InvalidRequest
        store(group.copy(name = newName, description = description?.trim() ?: group.description))
    }

    /** Change what ordinary members of this group may do. */
    fun setDefaultMemberPermissions(
        actor: MessagingPublicKey,
        groupId: String,
        permissions: Set<GroupPermission>
    ): Outcome = mutate(actor, groupId, GroupPermission.CAN_EDIT_GROUP) { group ->
        store(group.copy(defaultMemberPermissions = permissions))
    }

    /**
     * Grant or restrict one individual, independently of the group default.
     *
     * Passing null removes the exception and returns them to whatever the group grants. An
     * administrative member cannot be restricted this way; their permissions come from their role.
     */
    fun setMemberPermissions(
        actor: MessagingPublicKey,
        groupId: String,
        member: MessagingPublicKey,
        permissions: Set<GroupPermission>?
    ): Outcome = mutate(actor, groupId, GroupPermission.CAN_EDIT_GROUP) { group ->
        val target = group.memberFor(member) ?: return@mutate Outcome.NoSuchMember
        if (target.role.isAdministrative) return@mutate Outcome.CannotActOnOwner
        store(group.replacing(member) { it.copy(permissionOverride = permissions) })
    }

    /**
     * May this identity publish here?
     *
     * The single question the message path asks. Non-membership and lack of permission both answer
     * false, and the caller does not need to distinguish them to refuse the message.
     */
    fun canPublish(key: MessagingPublicKey, groupId: String): Boolean =
        groupsById[groupId]?.can(key, GroupPermission.CAN_SEND_MESSAGES) == true

    fun canSendFiles(key: MessagingPublicKey, groupId: String): Boolean =
        groupsById[groupId]?.can(key, GroupPermission.CAN_SEND_FILES) == true

    /**
     * Accept a group someone invited this device into.
     *
     * The inviter becomes its owner here, because they are the only participant this device can
     * currently verify — the transport proved who sent the invitation, and nothing else about the
     * membership arrived with it.
     *
     * An invitation naming a group this device already belongs to is refused. Otherwise anyone who
     * learned a group's identifier could re-invite its members and, in doing so, install themselves
     * as its owner.
     *
     * @return the group as adopted, or null when one of that identity is already known.
     */
    fun adoptInvitation(
        groupId: String,
        name: String,
        inviter: MessagingPublicKey,
        self: MessagingPublicKey
    ): Group? {
        if (groupsById.containsKey(groupId)) return null
        val now = clockMillis()
        val group = Group(
            id = groupId,
            name = name,
            description = "",
            createdBy = inviter,
            createdAtMillis = now,
            members = listOf(
                GroupMember(inviter, GroupRole.OWNER, joinedAtMillis = now),
                GroupMember(self, GroupRole.MEMBER, joinedAtMillis = now)
            ),
            defaultMemberPermissions = GroupPermission.ORDINARY_MEMBER
        )
        groupsById[groupId] = group
        publish()
        return group
    }

    /**
     * Note that someone spoke in a group, so they are known to belong to it.
     *
     * An invitation cannot carry a full membership list, so members are learned as they appear.
     * Only additions happen this way and only for people whose messages already decrypted with the
     * group key — holding the key is itself evidence of having been let in.
     */
    fun learnMember(groupId: String, member: MessagingPublicKey): Boolean {
        val group = groupsById[groupId] ?: return false
        if (group.isMember(member)) return false
        groupsById[groupId] = group.copy(
            members = group.members + GroupMember(
                publicKey = member,
                role = GroupRole.MEMBER,
                joinedAtMillis = clockMillis()
            )
        )
        publish()
        return true
    }

    /** Replace a group wholesale, for restoring persisted state. */
    fun restore(groups: List<Group>) {
        groupsById.clear()
        groups.forEach { groupsById[it.id] = it }
        publish()
    }

    private inline fun mutate(
        actor: MessagingPublicKey,
        groupId: String,
        required: GroupPermission,
        operation: (Group) -> Outcome
    ): Outcome {
        val group = groupsById[groupId] ?: return Outcome.NoSuchGroup
        // Membership is checked before permission so a stranger is told they are not in the group
        // rather than that they lack a permission in a group they cannot see.
        if (!group.isMember(actor)) return Outcome.NotAMember
        if (!group.can(actor, required)) return Outcome.NotPermitted
        return operation(group)
    }

    private fun store(group: Group): Outcome {
        groupsById[group.id] = group
        publish()
        return Outcome.Success(group)
    }

    private fun publish() {
        _groups.value = groupsById.values.sortedBy { it.createdAtMillis }
    }

    private fun Group.replacing(
        key: MessagingPublicKey,
        transform: (GroupMember) -> GroupMember
    ): Group = copy(
        members = members.map { if (it.publicKey == key) transform(it) else it }
    )
}
