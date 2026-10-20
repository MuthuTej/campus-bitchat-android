package com.bitchat.android.campus

/**
 * Fail-closed gate for anything that claims to be a campus bulletin.
 *
 * Only a fully verified, unexpired alert is retained or shown. Everything else is rejected, and
 * rejected text is never turned into a normal chat message.
 */
object CampusBulletinAdmission {

    sealed interface Decision {
        data class Retain(
            val alert: CampusAlert,
            val publisher: CampusCredential
        ) : Decision

        data class Reject(val reason: CampusAlertVerifier.Result) : Decision
        data object Ignore : Decision
    }

    fun decide(
        content: String,
        trustAnchorsHex: List<String> = CampusConfig.activeTrustAnchors,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        revokedKeysHex: Set<String> = CampusConfig.revokedSigningKeys
    ): Decision {
        if (!CampusAlert.looksLikeAlert(content)) return Decision.Ignore
        return when (
            val result = CampusAlertVerifier.verify(
                content,
                trustAnchorsHex,
                nowEpochSeconds,
                revokedKeysHex
            )
        ) {
            is CampusAlertVerifier.Result.Verified ->
                Decision.Retain(result.alert, result.publisher)
            else -> Decision.Reject(result)
        }
    }
}
