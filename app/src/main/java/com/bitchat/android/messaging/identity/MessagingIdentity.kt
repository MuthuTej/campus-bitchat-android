package com.bitchat.android.messaging.identity

/**
 * A messaging public key: the stable identity of one participant.
 *
 * A dedicated type rather than a bare [String] because this codebase already carries four different
 * hex-ish identifiers — mesh peer IDs, Noise static keys, Noise fingerprints and Ed25519 signing
 * keys — and passing one where another is expected fails silently rather than loudly. Anything
 * addressed, signed or looked up by identity should take this type.
 *
 * The value is an Ed25519 signing public key, lowercase hex, 32 bytes. It is public data: it
 * appears in identity announcements and is safe to display, store and compare.
 */
@JvmInline
value class MessagingPublicKey private constructor(val hex: String) {

    /** Short, human-comparable form for identity confirmation screens. */
    fun shortForm(): String = hex.take(16).chunked(4).joinToString(" ")

    override fun toString(): String = hex

    companion object {
        private const val HEX_LENGTH = 64
        private val HEX = Regex("^[0-9a-f]{$HEX_LENGTH}$")

        /**
         * Parse and normalise a key, or return null when [raw] is not a well-formed Ed25519 public
         * key. Rejecting here means no downstream consumer has to re-check length or alphabet.
         */
        fun parse(raw: String?): MessagingPublicKey? {
            val normalised = raw?.trim()?.lowercase() ?: return null
            return if (HEX.matches(normalised)) MessagingPublicKey(normalised) else null
        }
    }
}

/**
 * Who someone is, for messaging purposes.
 *
 * The [publicKey] is the identity. Everything else on this type is an attribute hanging off it, and
 * every attribute is weaker than the key:
 *
 *  - [displayName] is self-asserted. It is what a person typed about themselves, so it may be
 *    absent, duplicated, or deliberately misleading, and it must never be an input to an
 *    authorization decision. Institutional verification of a name is a separate, optional concern
 *    layered on top of this type — never a field of it.
 *  - [phoneNumber] is local discovery metadata. It is stored only on this device, is never part of
 *    addressing, and is never placed on the wire: the key is always sufficient to reach someone.
 *
 * This type deliberately knows nothing about campus credentials, roles or departments. A user with
 * no institutional affiliation has a complete, fully functional messaging identity, and the campus
 * layer attaches to it by public key rather than being embedded in it.
 */
data class MessagingIdentity(
    val publicKey: MessagingPublicKey,
    val displayName: String,
    val phoneNumber: String? = null
) {
    /**
     * What to show when no name is known.
     *
     * Falls back to the short key rather than to something friendlier-sounding but false: an
     * unnamed contact is genuinely unidentified, and inventing a placeholder name would be the
     * beginning of trusting one.
     */
    val displayNameOrKey: String
        get() = displayName.trim().ifEmpty { publicKey.shortForm() }

    companion object {
        /** Longest display name accepted from any source, local or remote. */
        const val MAX_DISPLAY_NAME_LENGTH = 64

        /**
         * Build an identity, normalising the display name.
         *
         * Remote-supplied names are bounded and stripped of control characters and bidirectional
         * overrides here, so a name cannot smuggle newlines into a list row or use RTL marks to
         * make one key's name render as another's.
         */
        fun of(
            publicKey: MessagingPublicKey,
            displayName: String?,
            phoneNumber: String? = null
        ): MessagingIdentity = MessagingIdentity(
            publicKey = publicKey,
            displayName = sanitizeDisplayName(displayName),
            phoneNumber = phoneNumber?.trim()?.takeIf { it.isNotEmpty() }
        )

        fun sanitizeDisplayName(raw: String?): String {
            if (raw == null) return ""
            val cleaned = buildString(raw.length) {
                for (character in raw) {
                    when {
                        // Control characters, including the newlines that would break a list row.
                        character.isISOControl() -> Unit
                        // Bidirectional embedding and override marks (U+202A..U+202E). These can
                        // reorder rendering so one person's name displays as another's.
                        character in '\u202A'..'\u202E' -> Unit
                        // Bidirectional isolates (U+2066..U+2069), same problem.
                        character in '\u2066'..'\u2069' -> Unit
                        // Zero-width space, and the left/right-to-right marks: invisible, so two
                        // different names could otherwise look identical in a contact list.
                        character == '\u200B' -> Unit
                        character == '\u200E' || character == '\u200F' -> Unit
                        // Zero-width no-break space / BOM.
                        character == '\uFEFF' -> Unit
                        else -> append(character)
                    }
                }
            }
            return cleaned.trim().take(MAX_DISPLAY_NAME_LENGTH)
        }
    }
}
