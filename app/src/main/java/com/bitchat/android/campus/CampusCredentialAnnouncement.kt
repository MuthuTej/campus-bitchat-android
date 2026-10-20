package com.bitchat.android.campus

/**
 * Public mesh carrier for a credential.
 *
 * The credential is already public. Carrying it lets a nearby phone learn a verified name without
 * waiting for that person to publish a bulletin. Receivers still verify the institutional signature
 * and drop anything that fails.
 */
object CampusCredentialAnnouncement {
    const val PREFIX: String = "BCC2:"

    fun encode(credential: CampusCredential): String = PREFIX + credential.encode()

    fun looksLike(content: String): Boolean = content.startsWith(PREFIX)

    fun decode(content: String): CampusCredential? {
        if (!looksLike(content)) return null
        return CampusCredential.decode(content.removePrefix(PREFIX))
    }
}
