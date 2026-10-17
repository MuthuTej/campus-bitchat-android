package com.bitchat.android.campus

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.UUID

/**
 * Publishes institutional alerts, signed with this device's existing BitChat identity key.
 *
 * No new key material is introduced. The Ed25519 signing key already generated for identity
 * announcements is reused, loaded from the app's EncryptedSharedPreferences-backed identity store —
 * so the private key is never held in plaintext, never copied into the campus module's own storage,
 * and never leaves the device.
 *
 * The device cannot make itself a professor by signing: authority comes from the institution-signed
 * [CampusCredential] naming this key, which only campus IT can issue.
 */
class CampusAlertSigner(
    private val context: Context,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    sealed interface Outcome {
        /** Ready to transmit. [envelope] goes into the message body verbatim. */
        data class Signed(val envelope: String, val alert: CampusAlert) : Outcome

        /** This device holds no institution-issued credential authorising alert publication. */
        data object NotAuthorised : Outcome

        /** The device identity key is missing — identity has not been initialised yet. */
        data object NoIdentityKey : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /** This device's Ed25519 signing public key, hex, or null before identity initialisation. */
    fun localSigningPublicKeyHex(): String? =
        SecureIdentityStateManager(context).loadSigningKey()?.second?.let(CampusCanonical::hex)

    /**
     * Build and sign an alert.
     *
     * @param credential this device's institution-issued credential. Required: an alert is only
     *        meaningful alongside the credential that proves the signer may publish one, and it is
     *        transmitted with the alert so offline receivers can verify without a directory lookup.
     */
    fun publish(
        title: String,
        body: String,
        credential: CampusCredential,
        lifetimeSeconds: Long = CampusConfig.defaultAlertLifetimeSeconds
    ): Outcome {
        if (title.isBlank() || body.isBlank()) return Outcome.Failed("Title and message are required")
        if (!credential.role.canPublishAlerts) return Outcome.NotAuthorised

        val keyPair = SecureIdentityStateManager(context).loadSigningKey() ?: return Outcome.NoIdentityKey
        val (privateKeyBytes, publicKeyBytes) = keyPair
        val publicKeyHex = CampusCanonical.hex(publicKeyBytes)

        // Refuse to sign with a key the credential does not name; the result would never verify
        // anywhere, and silently emitting an unverifiable alert is worse than failing loudly.
        if (!credential.signingPublicKeyHex.equals(publicKeyHex, ignoreCase = true)) {
            return Outcome.NotAuthorised
        }

        val now = clockSeconds()
        val unsigned = CampusAlert(
            alertId = UUID.randomUUID().toString(),
            title = title.trim(),
            body = body.trim(),
            timestampEpochSeconds = now,
            expiresAtEpochSeconds = if (lifetimeSeconds <= 0) 0L else now + lifetimeSeconds,
            signerPublicKeyHex = publicKeyHex,
            signature = ByteArray(0)
        )

        val signature = try {
            val signer = Ed25519Signer()
            signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes, 0))
            val message = unsigned.canonicalBytes()
            signer.update(message, 0, message.size)
            signer.generateSignature()
        } catch (e: Exception) {
            return Outcome.Failed("Could not sign alert")
        }

        val signed = unsigned.copy(signature = signature)
        return Outcome.Signed(signed.encode(credential), signed)
    }
}
