package com.bitchat.android.campus

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.UUID

/**
 * Publishes institutional alerts, signed with this device's announced Ed25519 key.
 *
 * That is the key other phones learn, and the key a campus credential names. The Noise identity
 * store holds a second Ed25519 key. Signing a bulletin with that one makes a genuine professor
 * credential fail the key check and surface as "not authorised".
 *
 * No new key material is introduced. The private key stays in the encryption service's encrypted
 * preferences and is wiped from the temporary buffer after signing.
 */
class CampusAlertSigner(
    private val context: Context?,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    signingKeyProvider: (() -> Pair<ByteArray, ByteArray>?)? = null
) {
    private val signingKeyProvider: () -> Pair<ByteArray, ByteArray>? =
        signingKeyProvider ?: { loadAnnouncedSigningKey() }

    sealed interface Outcome {
        /** Ready to transmit. [envelope] goes into the message body verbatim. */
        data class Signed(val envelope: String, val alert: CampusAlert) : Outcome

        /** This device holds no institution-issued credential authorising alert publication. */
        data object NotAuthorised : Outcome

        /** The device identity key is missing — identity has not been initialised yet. */
        data object NoIdentityKey : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /** This device's announced Ed25519 signing public key, hex, or null before it exists. */
    fun localSigningPublicKeyHex(): String? =
        signingKeyProvider()?.second?.let(CampusCanonical::hex)

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
        if (title.trim().length > MAX_TITLE_CHARS) return Outcome.Failed("Title is too long")
        if (body.trim().length > MAX_BODY_CHARS) return Outcome.Failed("Message is too long")
        if (!credential.role.canPublishAlerts) return Outcome.NotAuthorised

        val loaded = signingKeyProvider() ?: return Outcome.NoIdentityKey
        val privateKeyBytes = loaded.first.copyOf()
        val publicKeyBytes = loaded.second
        try {
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
        } finally {
            privateKeyBytes.fill(0)
        }
    }

    /**
     * The Ed25519 key the mesh announces. Same store as [com.bitchat.android.crypto.EncryptionService].
     */
    private fun loadAnnouncedSigningKey(): Pair<ByteArray, ByteArray>? {
        val appContext = context?.applicationContext ?: return null
        return try {
            val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                appContext,
                ANNOUNCED_KEY_PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val stored = prefs.getString(ANNOUNCED_KEY_PREF, null) ?: return null
            val privateKeyBytes = Base64.decode(stored, Base64.DEFAULT)
            try {
                if (privateKeyBytes.size != 32) return null
                val publicKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)
                    .generatePublicKey()
                    .encoded
                privateKeyBytes.copyOf() to publicKey
            } finally {
                privateKeyBytes.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val MAX_TITLE_CHARS: Int = 80
        const val MAX_BODY_CHARS: Int = 500

        /** Owned by the encryption service. The credential is bound to this key, not the Noise one. */
        private const val ANNOUNCED_KEY_PREFS = "bitchat_crypto_secure"
        private const val ANNOUNCED_KEY_PREF = "ed25519_signing_private_key"
    }
}
