package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.ConversationId

/**
 * One person's membership of one group.
 *
 * [permissionOverride] is null for the normal case, meaning "whatever this group grants people of
 * my role". A non-null value is a deliberate exception for this individual — muting one person in
 * an otherwise open group, or letting one student post in an announcement group — and is stored
 * separately from the group default so that changing the group's policy does not silently discard
 * the exception, or vice versa.
 */
data class GroupMember(
    val publicKey: MessagingPublicKey,
    val role: GroupRole,
    val permissionOverride: Set<GroupPermission>? = null,
    val joinedAtMillis: Long = 0L
)

/**
 * A group conversation.
 *
 * Membership here is the authoritative list for *authorization*: who may publish, who may
 * administer, who may add others. It is not by itself what keeps a non-member from reading — that
 * is the group key, which only members are given. The two work together, and neither is sufficient
 * alone: the list without the key would be advisory, and the key without the list would say nothing
 * about what a member is allowed to do once inside.
 */
data class Group(
    val id: String,
    val name: String,
    val description: String,
    val createdBy: MessagingPublicKey,
    val createdAtMillis: Long,
    val members: List<GroupMember>,
    /**
     * What an ordinary member may do here.
     *
     * This is the difference between a normal group and an announcement group, expressed as data
     * rather than as a separate type: an announcement group is one whose members simply have no
     * permission to send.
     */
    val defaultMemberPermissions: Set<GroupPermission> = GroupPermission.ORDINARY_MEMBER
) {

    val conversationId: ConversationId get() = ConversationId.group(id)

    val memberKeys: Set<MessagingPublicKey> get() = members.map { it.publicKey }.toSet()

    val owner: GroupMember? get() = members.firstOrNull { it.role == GroupRole.OWNER }

    val admins: List<GroupMember> get() = members.filter { it.role.isAdministrative }

    fun memberFor(key: MessagingPublicKey): GroupMember? =
        members.firstOrNull { it.publicKey == key }

    fun isMember(key: MessagingPublicKey): Boolean = memberFor(key) != null

    /**
     * What this person may actually do here.
     *
     * Administrative roles hold every permission unconditionally, so an admin cannot be locked out
     * of their own group by a permission edit. For everyone else an explicit override wins over the
     * group default, and a non-member has no permissions at all rather than the default set — the
     * distinction matters, because a message from a non-member must be refused for that reason
     * rather than evaluated against a policy that was never meant to apply to them.
     */
    fun permissionsFor(key: MessagingPublicKey): Set<GroupPermission> {
        val member = memberFor(key) ?: return emptySet()
        if (member.role.isAdministrative) return GroupPermission.ALL
        return member.permissionOverride ?: defaultMemberPermissions
    }

    fun can(key: MessagingPublicKey, permission: GroupPermission): Boolean =
        permission in permissionsFor(key)

    /** True when this group is configured so only its administrators can publish. */
    val isAnnouncementOnly: Boolean
        get() = GroupPermission.CAN_SEND_MESSAGES !in defaultMemberPermissions &&
            members.none {
                !it.role.isAdministrative &&
                    it.permissionOverride?.contains(GroupPermission.CAN_SEND_MESSAGES) == true
            }
}
