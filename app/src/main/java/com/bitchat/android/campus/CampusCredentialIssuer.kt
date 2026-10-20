package com.bitchat.android.campus

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Issues an institution-signed [CampusCredential].
 *
 * The private key is injected by the caller. Production keeps it with campus IT. The debug demo
 * installer is the only in-app holder, and only when no real trust anchor is configured.
 */
class CampusCredentialIssuer(
    institutionPrivateKey: ByteArray,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    private val privateKey = Ed25519PrivateKeyParameters(institutionPrivateKey.copyOf(), 0)

    val publicKeyHex: String = CampusCanonical.hex(privateKey.generatePublicKey().encoded)

    fun issue(
        displayName: String,
        role: CampusRole,
        department: String,
        signingPublicKeyHex: String,
        lifetimeSeconds: Long = DEFAULT_LIFETIME_SECONDS
    ): CampusCredential? {
        val name = displayName.trim()
        if (name.isEmpty()) return null
        if (CampusCanonical.unhex(signingPublicKeyHex)?.size != 32) return null
        val now = clockSeconds()
        val expiresAt = if (lifetimeSeconds <= 0L) 0L else now + lifetimeSeconds
        val unsigned = CampusCredential(
            displayName = name,
            role = role,
            department = department.trim(),
            signingPublicKeyHex = signingPublicKeyHex.lowercase(),
            issuedAtEpochSeconds = now,
            expiresAtEpochSeconds = expiresAt,
            institutionSignature = ByteArray(64)
        )
        val signature = try {
            val signer = Ed25519Signer()
            signer.init(true, privateKey)
            val message = unsigned.canonicalBytes()
            signer.update(message, 0, message.size)
            signer.generateSignature()
        } catch (_: Exception) {
            return null
        }
        return unsigned.copy(institutionSignature = signature)
    }

    companion object {
        const val DEFAULT_LIFETIME_SECONDS: Long = 180L * 24 * 60 * 60
    }
}
