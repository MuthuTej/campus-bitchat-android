package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.protocol.Canonical

/**
 * Being told a group exists.
 *
 * ## Why this is needed
 *
 * Creating a group is a local act: one device writes down a name, a membership list and a key.
 * Nothing about that reaches the people named in it. Without an invitation the other members have
 * no group to put messages in, no key to read them with, and no reason to listen to the channel
 * they travel on — so a group conversation existed only for whoever created it.
 *
 * ## Why it carries so little
 *
 * An invitation travels the private message path, whose body is capped at 255 bytes. A full
 * membership list does not come close to fitting: one member is 64 characters of key, so three of
 * them exhaust the budget before the group is even named.
 *
 * It therefore carries the minimum a device needs to take part — which group, what it is called,
 * and the key to read it. Who sent it is established by the transport, not by the message, and that
 * person becomes the group's owner in the recipient's view. The rest of the membership is learned
 * as people speak.
 *
 * The consequence is worth being clear about: a recipient's picture of who else is in the group is
 * incomplete until they hear from them. It is enough to read and take part, and not enough to
 * administer, which is why administration stays with the device that created the group.
 */
data class GroupInvite(
    val groupId: String,
    val groupName: String,
    val keyVersion: Int,
    val keyMaterial: ByteArray
) {

    fun encode(): String = PREFIX + Canonical.b64(
        Canonical.encode(
            groupId,
            groupName.take(MAX_NAME),
            keyVersion.toString(),
            Canonical.b64(keyMaterial)
        )
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupInvite) return false
        return groupId == other.groupId &&
            groupName == other.groupName &&
            keyVersion == other.keyVersion &&
            keyMaterial.contentEquals(other.keyMaterial)
    }

    override fun hashCode(): Int =
        ((groupId.hashCode() * 31 + groupName.hashCode()) * 31 + keyVersion) * 31 +
            keyMaterial.contentHashCode()

    /** Never let key material reach a log through an accidental interpolation. */
    override fun toString(): String = "GroupInvite(groupId=$groupId, name=$groupName, v=$keyVersion)"

    companion object {
        const val PREFIX = "GINV1:"
        private const val MAX_NAME = 40

        fun looksLikeInvite(payload: String): Boolean = payload.startsWith(PREFIX)

        fun decode(payload: String): GroupInvite? {
            if (!looksLikeInvite(payload)) return null
            val raw = Canonical.unb64(payload.removePrefix(PREFIX)) ?: return null
            val fields = Canonical.decodeFields(raw, expected = 4) ?: return null

            val groupId = fields[0].takeIf { it.isNotBlank() && it.length <= 64 } ?: return null
            val name = fields[1].trim().take(MAX_NAME).takeIf { it.isNotEmpty() } ?: return null
            val version = fields[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val material = Canonical.unb64(fields[3])?.takeIf { it.size == GroupKey.KEY_BYTES }
                ?: return null

            return GroupInvite(groupId, name, version, material)
        }
    }
}
