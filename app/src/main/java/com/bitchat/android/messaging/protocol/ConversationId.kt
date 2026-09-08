package com.bitchat.android.messaging.protocol

import com.bitchat.android.messaging.identity.MessagingPublicKey
import java.security.MessageDigest

/**
 * Identifies one conversation.
 *
 * For a direct conversation the value is derived from the two participants' keys rather than
 * assigned, which is what lets both devices arrive at the same identifier without ever negotiating
 * it. The derivation sorts the keys first, so it does not matter who started the conversation or
 * which side is computing it — a conversation identifier that depended on that would give the two
 * participants different names for the same thread.
 */
@JvmInline
value class ConversationId(val value: String) {

    val isDirect: Boolean get() = value.startsWith(DIRECT_PREFIX)
    val isGroup: Boolean get() = value.startsWith(GROUP_PREFIX)

    override fun toString(): String = value

    companion object {
        const val DIRECT_PREFIX = "dm_"
        const val GROUP_PREFIX = "grp_"

        /**
         * The conversation between two people.
         *
         * Hashed rather than concatenated so the identifier is fixed-width and does not restate both
         * participants' keys everywhere it is stored or logged. Both keys are already known to both
         * parties, so this is tidiness rather than secrecy — but an identifier that leaks the
         * membership of every conversation into every log line is worth avoiding for free.
         */
        fun direct(a: MessagingPublicKey, b: MessagingPublicKey): ConversationId {
            val (first, second) = listOf(a.hex, b.hex).sorted()
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(Canonical.encode(first, second))
            return ConversationId(DIRECT_PREFIX + Canonical.hex(digest))
        }

        /** A group's conversation. The group's own identifier is already unique. */
        fun group(groupId: String): ConversationId = ConversationId(GROUP_PREFIX + groupId)

        /** Parse a stored value, rejecting anything that is not a recognised conversation id. */
        fun parse(raw: String?): ConversationId? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty()) return null
            return when {
                trimmed.startsWith(DIRECT_PREFIX) &&
                    trimmed.length == DIRECT_PREFIX.length + 64 &&
                    trimmed.drop(DIRECT_PREFIX.length).all { it in '0'..'9' || it in 'a'..'f' } ->
                    ConversationId(trimmed)

                trimmed.startsWith(GROUP_PREFIX) && trimmed.length > GROUP_PREFIX.length ->
                    ConversationId(trimmed)

                else -> null
            }
        }
    }
}
