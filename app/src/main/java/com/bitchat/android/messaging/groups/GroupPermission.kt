package com.bitchat.android.messaging.groups

/**
 * A thing a member may or may not do in a group.
 *
 * Permissions are checked in the group logic itself, never only in the interface. A member without
 * [CAN_SEND_MESSAGES] does not merely lose the compose field — an attempt to publish is refused,
 * and a message they publish anyway is refused on arrival at everyone else. A permission enforced
 * only by hiding a button is not a permission, because the button is not what sends the message.
 */
enum class GroupPermission {
    CAN_SEND_MESSAGES,
    CAN_SEND_FILES,
    CAN_ADD_MEMBERS,
    CAN_EDIT_GROUP,
    CAN_MANAGE_ADMINS;

    companion object {
        /** What an ordinary member of a normal conversational group can do. */
        val ORDINARY_MEMBER: Set<GroupPermission> = setOf(CAN_SEND_MESSAGES, CAN_SEND_FILES)

        /** What a member of an announcement group can do: read, and nothing else. */
        val READ_ONLY: Set<GroupPermission> = emptySet()

        val ALL: Set<GroupPermission> = entries.toSet()
    }
}

/**
 * Standing in a group.
 *
 * [OWNER] is distinguished from [ADMIN] so a group cannot be taken from the person who created it:
 * admins manage members, but only the owner's own standing is beyond an admin's reach.
 */
enum class GroupRole {
    MEMBER,
    ADMIN,
    OWNER;

    val isAdministrative: Boolean get() = this == ADMIN || this == OWNER

    val label: String
        get() = when (this) {
            MEMBER -> "Member"
            ADMIN -> "Admin"
            OWNER -> "Owner"
        }
}
