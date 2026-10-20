package com.bitchat.android.campus

/**
 * Optional demo trust root.
 *
 * Empty unless a debug installer explicitly arms it, and only when the build has no real
 * institutional anchor. Release builds leave this unset, so verification stays fail-closed.
 */
object CampusDemoHooks {

    @Volatile
    private var installed: CampusCredentialIssuer? = null

    val issuer: CampusCredentialIssuer?
        get() = installed

    fun install(issuer: CampusCredentialIssuer) {
        installed = issuer
    }

    fun clear() {
        installed = null
    }
}
