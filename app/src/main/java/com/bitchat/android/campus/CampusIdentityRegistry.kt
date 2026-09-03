package com.bitchat.android.campus

import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a cryptographic identity to a campus name and role.
 *
 * Credentials arrive two ways: bundled with the build (institutional roster) and attached to alerts
 * as they are relayed across the mesh. Both go through the same verification, so a credential
 * learned from a stranger's packet is trusted exactly as much as one shipped in the APK — which is
 * to say, only if an institutional anchor signed it.
 *
 * Thread-safe: credentials are learned from mesh callbacks and read from Compose recomposition.
 */
class CampusIdentityRegistry(
    private val trustAnchorsHex: List<String> = CampusConfig.trustedInstitutionKeys,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    /** What the UI is allowed to render for a given peer. */
    sealed interface Identity {
        data class Verified(
            val displayName: String,
            val role: CampusRole,
            val department: String,
            val shortFingerprint: String
        ) : Identity

        /**
         * The peer is cryptographically real but has no institution-signed campus credential.
         * Their self-chosen nickname is shown as untrusted, never as a name plus a role.
         */
        data class Unverified(val claimedNickname: String?) : Identity
    }

    private val verifiedByKey = ConcurrentHashMap<String, CampusCredential>()

    /**
     * Offer a credential to the registry.
     *
     * @return the verification result, so callers can distinguish "learned a new professor" from
     *         "someone relayed a forged credential".
     */
    fun learn(credential: CampusCredential): CampusCredentialVerifier.Result {
        val result = CampusCredentialVerifier.verify(credential, trustAnchorsHex, clockSeconds())
        if (result is CampusCredentialVerifier.Result.Verified) {
            verifiedByKey[credential.signingPublicKeyHex.lowercase()] = credential
        }
        return result
    }

    fun learnEncoded(token: String): CampusCredentialVerifier.Result {
        val credential = CampusCredential.decode(token)
            ?: return CampusCredentialVerifier.Result.Malformed
        return learn(credential)
    }

    /**
     * Resolve by the peer's Ed25519 signing key.
     *
     * Re-checks expiry on every read rather than trusting the cache: a credential that was valid
     * when learned this morning must stop rendering as verified the moment it lapses.
     */
    fun identityForSigningKey(signingKeyHex: String?, claimedNickname: String? = null): Identity {
        val key = signingKeyHex?.lowercase() ?: return Identity.Unverified(claimedNickname)
        val credential = verifiedByKey[key] ?: return Identity.Unverified(claimedNickname)
        if (credential.isExpiredAt(clockSeconds())) {
            verifiedByKey.remove(key)
            return Identity.Unverified(claimedNickname)
        }
        return Identity.Verified(
            displayName = credential.displayName,
            role = credential.role,
            department = credential.department,
            shortFingerprint = credential.shortFingerprint()
        )
    }

    fun credentialForSigningKey(signingKeyHex: String?): CampusCredential? =
        signingKeyHex?.lowercase()?.let { verifiedByKey[it] }

    /** Every verified identity currently known, for the class participant list. */
    fun allVerifiedIdentities(): List<Identity.Verified> {
        val now = clockSeconds()
        return verifiedByKey.values
            .filterNot { it.isExpiredAt(now) }
            .map {
                Identity.Verified(
                    displayName = it.displayName,
                    role = it.role,
                    department = it.department,
                    shortFingerprint = it.shortFingerprint()
                )
            }
            .sortedWith(compareByDescending<Identity.Verified> { it.role.canPublishAlerts }
                .thenBy { it.displayName })
    }

    fun clear() = verifiedByKey.clear()
}
