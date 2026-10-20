package com.bitchat.android.campus

/**
 * Enforces the two-signature trust chain described on [CampusAlert].
 *
 * Every path that is not a full success returns a distinct rejection reason. The UI renders none of
 * them as an institutional alert; the distinction exists so the app can tell a student "this looked
 * like an alert but we could not verify it" rather than silently discarding evidence of a forgery
 * attempt on campus.
 */
object CampusAlertVerifier {

    sealed interface Result {
        data class Verified(
            val alert: CampusAlert,
            val publisher: CampusCredential
        ) : Result

        /** Body, title, timestamp or expiry changed after signing, or the signature is not the signer's. */
        data object BadAlertSignature : Result

        /** Signature is good but the publisher's credential is not institution-issued. */
        data object UntrustedPublisher : Result

        /** Credential is valid but the role may not publish alerts (e.g. a student). */
        data class UnauthorizedRole(val role: CampusRole) : Result

        /** Credential names a different key than the one that signed the alert. */
        data object IdentityMismatch : Result

        data object CredentialExpired : Result
        data object AlertExpired : Result

        /** Timestamp is further ahead than the allowed clock skew. */
        data object NotYetValid : Result

        /**
         * The publisher's credential has been withdrawn by the institution.
         *
         * Kept separate from [CredentialExpired] because it is the more serious case: a lapsed
         * credential is routine, whereas a withdrawn one usually means the signing key is
         * compromised — and an alert is exactly what someone holding a stolen professor key would
         * want to send.
         */
        data object PublisherRevoked : Result

        /** Not an alert envelope at all, or structurally broken. */
        data object Malformed : Result
        data object NotAnAlert : Result
    }

    fun verify(
        content: String,
        trustAnchorsHex: List<String> = CampusConfig.activeTrustAnchors,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        revokedKeysHex: Set<String> = CampusConfig.revokedSigningKeys
    ): Result {
        if (!CampusAlert.looksLikeAlert(content)) return Result.NotAnAlert
        val (alert, credential) = CampusAlert.decode(content) ?: return Result.Malformed

        if (alert.signature.size != 64) return Result.Malformed

        // The credential must name exactly the key that signed the alert. Without this check a
        // forger could staple any professor's genuine credential onto an alert they signed themselves.
        if (!credential.signingPublicKeyHex.equals(alert.signerPublicKeyHex, ignoreCase = true)) {
            return Result.IdentityMismatch
        }

        val signerKey = CampusCanonical.unhex(alert.signerPublicKeyHex)?.takeIf { it.size == 32 }
            ?: return Result.Malformed

        // Check the alert signature BEFORE trusting anything the credential asserts.
        if (!CampusCredentialVerifier.verifyEd25519(alert.signature, alert.canonicalBytes(), signerKey)) {
            return Result.BadAlertSignature
        }

        when (val credentialResult = CampusCredentialVerifier.verify(
            credential, trustAnchorsHex, nowEpochSeconds, revokedKeysHex
        )) {
            is CampusCredentialVerifier.Result.Verified -> Unit
            CampusCredentialVerifier.Result.Expired -> return Result.CredentialExpired
            CampusCredentialVerifier.Result.Revoked -> return Result.PublisherRevoked
            CampusCredentialVerifier.Result.UntrustedIssuer -> return Result.UntrustedPublisher
            CampusCredentialVerifier.Result.Malformed -> return Result.Malformed
        }

        if (!credential.role.canPublishAlerts) return Result.UnauthorizedRole(credential.role)
        if (alert.timestampEpochSeconds > nowEpochSeconds + CampusConfig.maxAlertClockSkewSeconds) {
            return Result.NotYetValid
        }
        if (alert.isExpiredAt(nowEpochSeconds)) return Result.AlertExpired

        return Result.Verified(alert, credential)
    }
}
