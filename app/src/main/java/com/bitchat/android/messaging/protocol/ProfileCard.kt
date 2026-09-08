package com.bitchat.android.messaging.protocol

/**
 * What one person tells another about themselves.
 *
 * ## Why this exists
 *
 * A messaging identity is a key, and a key is not something a person recognises. The transport
 * announces a nickname, but a nickname is broadcast to everyone in radio range, so it is a poor
 * place for anything the user would rather only their contacts knew.
 *
 * More importantly, a key is not stable across a reinstall or a wipe. When someone's device
 * regenerates its identity they become, from everyone else's point of view, a brand new stranger —
 * while their old entry lingers as a contact that can never be reached again. Messages sent to it
 * are addressed to a key nobody holds, so they leave the device and are correctly refused at the
 * far end, with nothing to tell the sender why.
 *
 * A phone number is the correlator that survives that. It is chosen by the person rather than the
 * device, so two keys carrying the same number are recognisably the same human, and the older one
 * can be retired instead of haunting the contact list.
 *
 * ## What it is not
 *
 * The number is **not** an address and **not** an identity. Nothing is routed by it, nothing is
 * encrypted to it, and possession of it grants nothing. It travels only inside an already
 * authenticated, already encrypted direct message, only to someone the user is actually talking to,
 * and only when they have allowed it in privacy settings. Treating it as proof of anything would be
 * a mistake — it is a hint for recognising people, in a system where the key remains the truth.
 */
data class ProfileCard(
    val displayName: String,
    /** Null when the user has not supplied one, or has chosen not to share it. */
    val phoneNumber: String?
) {

    fun encode(): String = PREFIX + Canonical.b64(
        Canonical.encode(displayName, phoneNumber.orEmpty())
    )

    companion object {
        const val PREFIX = "PROF1:"

        /** Longest values accepted from a peer, so a card cannot be used to flood storage. */
        private const val MAX_NAME = 64
        private const val MAX_PHONE = 32

        fun looksLikeProfileCard(payload: String): Boolean = payload.startsWith(PREFIX)

        /**
         * Parse a card, or null if it is not one or is malformed.
         *
         * Both fields are bounded and the phone number is restricted to characters a number can
         * actually contain, so a card cannot smuggle arbitrary text into a field the interface
         * renders next to a name.
         */
        fun decode(payload: String): ProfileCard? {
            if (!looksLikeProfileCard(payload)) return null
            val raw = Canonical.unb64(payload.removePrefix(PREFIX)) ?: return null
            val fields = Canonical.decodeFields(raw, expected = 2) ?: return null

            val name = fields[0].trim().take(MAX_NAME)
            val phone = fields[1].trim().take(MAX_PHONE).takeIf { it.isNotEmpty() }
            if (phone != null && !phone.all { it.isDigit() || it in "+-() " }) return null

            return ProfileCard(displayName = name, phoneNumber = phone)
        }
    }
}
