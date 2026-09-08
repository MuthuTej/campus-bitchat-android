package com.bitchat.android.campus

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * An institution-issued statement binding a real campus name and role to a cryptographic identity.
 *
 * This is the whole anti-impersonation mechanism. A device cannot claim to be "Priya Sharma,
 * Professor" by typing it: the claim is only rendered once a credential signed by an institutional
 * root key is verified, and that credential names the exact Ed25519 signing key the device must
 * prove control of.
 *
 * The credential is public data. It is safe to bundle in the APK, relay across the mesh, or hand out
 * on a USB stick — it discloses nothing that is not already visible in a message header, and it
 * cannot be forged without the institution's private key, which never leaves campus IT.
 */
data class CampusCredential(
    val displayName: String,
    val role: CampusRole,
    val department: String,
    /** Ed25519 signing public key of the holder, hex. Bound to the device's mesh identity. */
    val signingPublicKeyHex: String,
    val issuedAtEpochSeconds: Long,
    /** 0 means "never expires". */
    val expiresAtEpochSeconds: Long,
    /** Institution's Ed25519 signature over [canonicalBytes]. */
    val institutionSignature: ByteArray
) {

    /**
     * The exact bytes the institution signed. Excludes the signature itself, obviously, and includes
     * every field a forger might want to alter — notably [role], so a student cannot lift a valid
     * student credential and promote themselves to PROFESSOR.
     */
    fun canonicalBytes(): ByteArray = CampusCanonical.encode(
        CREDENTIAL_VERSION,
        displayName,
        role.wireValue,
        department,
        signingPublicKeyHex.lowercase(),
        issuedAtEpochSeconds.toString(),
        expiresAtEpochSeconds.toString()
    )

    fun isExpiredAt(nowEpochSeconds: Long): Boolean =
        expiresAtEpochSeconds != 0L && nowEpochSeconds > expiresAtEpochSeconds

    /** Short human-checkable fingerprint of the holder's key, for the identity details sheet. */
    fun shortFingerprint(): String =
        signingPublicKeyHex.lowercase().take(16).chunked(4).joinToString(" ")

    /** Wire form: a single base64url token, embeddable in a message body. */
    fun encode(): String = CampusCanonical.b64(
        CampusCanonical.encode(
            CREDENTIAL_VERSION,
            displayName,
            role.wireValue,
            department,
            signingPublicKeyHex.lowercase(),
            issuedAtEpochSeconds.toString(),
            expiresAtEpochSeconds.toString(),
            CampusCanonical.b64(institutionSignature)
        )
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CampusCredential) return false
        return displayName == other.displayName &&
            role == other.role &&
            department == other.department &&
            signingPublicKeyHex.equals(other.signingPublicKeyHex, ignoreCase = true) &&
            issuedAtEpochSeconds == other.issuedAtEpochSeconds &&
            expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
            institutionSignature.contentEquals(other.institutionSignature)
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + department.hashCode()
        result = 31 * result + signingPublicKeyHex.lowercase().hashCode()
        result = 31 * result + issuedAtEpochSeconds.hashCode()
        result = 31 * result + expiresAtEpochSeconds.hashCode()
        result = 31 * result + institutionSignature.contentHashCode()
        return result
    }

    companion object {
        const val CREDENTIAL_VERSION = "CAMPUS-CRED-V1"

        fun decode(token: String): CampusCredential? {
            val raw = CampusCanonical.unb64(token) ?: return null
            val fields = CampusCanonical.decodeFields(raw, expected = 8) ?: return null
            if (fields[0] != CREDENTIAL_VERSION) return null
            val role = CampusRole.fromWire(fields[2]) ?: return null
            val issuedAt = fields[5].toLongOrNull() ?: return null
            val expiresAt = fields[6].toLongOrNull() ?: return null
            val signature = CampusCanonical.unb64(fields[7]) ?: return null
            return CampusCredential(
                displayName = fields[1],
                role = role,
                department = fields[3],
                signingPublicKeyHex = fields[4],
                issuedAtEpochSeconds = issuedAt,
                expiresAtEpochSeconds = expiresAt,
                institutionSignature = signature
            )
        }
    }
}

/**
 * Verifies credentials against the institution's trust anchors.
 *
 * Fails closed in every ambiguous case: an empty trust-anchor list trusts nothing, an expired
 * credential is rejected, and a credential whose signature verifies under no anchor is rejected.
 */
object CampusCredentialVerifier {

    sealed interface Result {
        data class Verified(val credential: CampusCredential, val anchorKeyHex: String) : Result
        data object UntrustedIssuer : Result
        data object Expired : Result
        data object Malformed : Result

        /**
         * The institution has withdrawn this credential before its expiry.
         *
         * Distinct from [Expired] because the two mean different things to a reader: an expired
         * credential lapsed on schedule, while a revoked one was actively cancelled — typically
         * because the key was compromised or the holder left. Only the second is a reason to
         * distrust past messages from that key.
         */
        data object Revoked : Result
    }

    fun verify(
        credential: CampusCredential,
        trustAnchorsHex: List<String> = CampusConfig.trustedInstitutionKeys,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
        revokedKeysHex: Set<String> = CampusConfig.revokedSigningKeys
    ): Result {
        if (credential.signingPublicKeyHex.length != 64) return Result.Malformed
        if (CampusCanonical.unhex(credential.signingPublicKeyHex) == null) return Result.Malformed
        if (credential.institutionSignature.size != 64) return Result.Malformed
        if (credential.displayName.isBlank()) return Result.Malformed
        // Checked before the signature: a revoked credential is one whose signature is still
        // perfectly valid, so verifying first and revoking after would leave a window where a
        // withdrawn identity still reads as genuine.
        if (credential.signingPublicKeyHex.lowercase() in revokedKeysHex) return Result.Revoked
        if (credential.isExpiredAt(nowEpochSeconds)) return Result.Expired

        val message = credential.canonicalBytes()
        for (anchorHex in trustAnchorsHex) {
            val anchor = CampusCanonical.unhex(anchorHex)?.takeIf { it.size == 32 } ?: continue
            if (verifyEd25519(credential.institutionSignature, message, anchor)) {
                return Result.Verified(credential, anchorHex)
            }
        }
        return Result.UntrustedIssuer
    }

    /**
     * Mirrors EncryptionService.verifyEd25519Signature. Kept separate because EncryptionService owns
     * device private key material and requires a Context; this path only ever handles public keys.
     */
    fun verifyEd25519(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean = try {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(data, 0, data.size)
        verifier.verifySignature(signature)
    } catch (e: Exception) {
        // Silent by design: detailed failure logs would reveal which anchor nearly matched.
        false
    }
}
