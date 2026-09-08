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
     * Keys that presented a credential which did not verify.
     *
     * Kept so that "claimed nothing" and "claimed something that failed" stay distinguishable. A
     * failed credential used to be dropped silently, which made a forger indistinguishable from an
     * ordinary user with no affiliation — and those two warrant very different treatment on screen.
     */
    private val rejectedKeys = ConcurrentHashMap.newKeySet<String>()

    /**
     * Credentials that were offered but did not verify, kept only so their standing can be
     * reported. Never consulted for a name or a role.
     */
    private val offeredByKey = ConcurrentHashMap<String, CampusCredential>()

    /**
     * Offer a credential to the registry.
     *
     * @return the verification result, so callers can distinguish "learned a new professor" from
     *         "someone relayed a forged credential".
     */
    fun learn(credential: CampusCredential): CampusCredentialVerifier.Result {
        val result = CampusCredentialVerifier.verify(credential, trustAnchorsHex, clockSeconds())
        val key = credential.signingPublicKeyHex.lowercase()
        if (result is CampusCredentialVerifier.Result.Verified) {
            verifiedByKey[key] = credential
            offeredByKey.remove(key)
            rejectedKeys.remove(key)
        } else {
            // A failed credential must never displace one that verified, or anyone could demote a
            // genuine professor by relaying a forgery naming their key.
            if (!verifiedByKey.containsKey(key)) offeredByKey[key] = credential
            rejectedKeys.add(key)
        }
        return result
    }

    /** True when this key has ever offered a credential, whether or not it verified. */
    fun hasSeenCredentialFor(signingKeyHex: String?): Boolean {
        val key = signingKeyHex?.lowercase() ?: return false
        return verifiedByKey.containsKey(key) || rejectedKeys.contains(key)
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

    /**
     * The current verification standing of a key, or null if it has never offered a credential.
     *
     * Re-runs verification against *this registry's* trust anchors rather than leaving callers to
     * repeat the check themselves. A caller that re-verified with its own defaults would silently
     * disagree with the registry whenever the two were configured differently — which is exactly
     * the bug this method exists to prevent, since the disagreement renders a genuine credential as
     * a forged one.
     *
     * Re-checked on every read so expiry and revocation take effect without anything invalidating
     * a cache.
     */
    fun verificationFor(signingKeyHex: String?): CampusCredentialVerifier.Result? {
        val key = signingKeyHex?.lowercase() ?: return null
        val credential = verifiedByKey[key] ?: offeredByKey[key] ?: return null
        return CampusCredentialVerifier.verify(credential, trustAnchorsHex, clockSeconds())
    }

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

    fun clear() {
        verifiedByKey.clear()
        offeredByKey.clear()
        rejectedKeys.clear()
    }
}
