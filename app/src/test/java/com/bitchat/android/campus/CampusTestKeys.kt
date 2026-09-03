package com.bitchat.android.campus

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Test-only key material and issuance helpers.
 *
 * Deliberately confined to the test source set: the institutional private key that signs credentials
 * must never exist in the app's source or APK, so the only place one is generated is here.
 */
object CampusTestKeys {

    data class KeyPair(val privateKey: Ed25519PrivateKeyParameters, val publicHex: String)

    fun generate(): KeyPair {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val pair = generator.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub = pair.public as Ed25519PublicKeyParameters
        return KeyPair(priv, CampusCanonical.hex(pub.encoded))
    }

    fun sign(key: Ed25519PrivateKeyParameters, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Issue a credential the way campus IT would, signing the canonical bytes with the root key. */
    fun issueCredential(
        institution: KeyPair,
        holder: KeyPair,
        displayName: String,
        role: CampusRole,
        department: String = "Physics",
        issuedAt: Long = 1_700_000_000L,
        expiresAt: Long = 0L
    ): CampusCredential {
        val unsigned = CampusCredential(
            displayName = displayName,
            role = role,
            department = department,
            signingPublicKeyHex = holder.publicHex,
            issuedAtEpochSeconds = issuedAt,
            expiresAtEpochSeconds = expiresAt,
            institutionSignature = ByteArray(64)
        )
        return unsigned.copy(
            institutionSignature = sign(institution.privateKey, unsigned.canonicalBytes())
        )
    }

    /** Build a signed alert envelope exactly as CampusAlertSigner would on a real device. */
    fun signedAlertEnvelope(
        holder: KeyPair,
        credential: CampusCredential,
        title: String,
        body: String,
        timestamp: Long = 1_700_000_100L,
        expiresAt: Long = 0L,
        alertId: String = "alert-1"
    ): String {
        val unsigned = CampusAlert(
            alertId = alertId,
            title = title,
            body = body,
            timestampEpochSeconds = timestamp,
            expiresAtEpochSeconds = expiresAt,
            signerPublicKeyHex = holder.publicHex,
            signature = ByteArray(0)
        )
        val signature = sign(holder.privateKey, unsigned.canonicalBytes())
        return unsigned.copy(signature = signature).encode(credential)
    }
}
