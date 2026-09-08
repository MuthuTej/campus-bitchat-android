package com.bitchat.android.messaging.identity

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.ui.DataManager
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The local user's messaging identity.
 *
 * Creates no key material. The device's Ed25519 signing key already exists — it is generated and
 * persisted by the Noise encryption service the first time the app runs, and it is the key peers
 * already authenticate during a handshake. Minting a second "messaging key" here would produce two
 * identities for one person, only one of which the transport can actually prove.
 *
 * The two mutable attributes are stored where their sensitivity says they belong:
 *
 *  - The display name is the existing nickname. It is broadcast in identity announcements, so it
 *    lives in ordinary preferences alongside the rest of the announced profile, and there is
 *    exactly one of it rather than a messaging name competing with a chat nickname.
 *  - The phone number is never announced and never leaves the device, so it is held in the
 *    EncryptedSharedPreferences-backed identity store rather than in plain preferences.
 */
class MessagingIdentityManager(
    private val context: Context,
    private val identityStore: SecureIdentityStateManager = SecureIdentityStateManager(
        context.applicationContext
    ),
    private val dataManager: DataManager = DataManager(context.applicationContext)
) {

    private val _identity = MutableStateFlow<MessagingIdentity?>(null)

    /**
     * The local identity, or null before the device's signing key exists.
     *
     * Null is a real and expected state on the very first launch, for the short window before the
     * encryption service finishes initialising. Callers should treat it as "not ready yet" rather
     * than as "this user has no identity".
     */
    val identity: StateFlow<MessagingIdentity?> = _identity.asStateFlow()

    init {
        refresh()
    }

    /** Re-read the identity from storage. Safe to call repeatedly. */
    fun refresh(): MessagingIdentity? {
        val key = localPublicKey()
        _identity.value = key?.let {
            MessagingIdentity.of(
                publicKey = it,
                displayName = dataManager.loadNickname(),
                phoneNumber = storedPhoneNumber()
            )
        }
        return _identity.value
    }

    /**
     * This device's messaging public key, or null before the signing key has been generated.
     *
     * ## Which key this has to be
     *
     * The app holds two Ed25519 key pairs, and they are not the same. One belongs to the Noise
     * layer and is kept in the identity store; the other belongs to the encryption service, signs
     * identity announcements, and is therefore **the key every other device learns this one by**.
     *
     * An identity is only useful if it is the one other people can address, so this must be the
     * announced key. Reading the Noise key instead gave this device a private notion of itself that
     * nobody else shared: peers addressed messages to the announced key, the recipient compared them
     * against the Noise key, and every direct message was refused as being meant for somebody else.
     * The symptom was messages that provably crossed the mesh and then vanished — with both sides
     * behaving exactly as designed.
     *
     * Deliberately read-only: a setter here would invite a caller to "fix" an identity by replacing
     * it, silently orphaning every conversation and credential bound to the old key.
     */
    fun localPublicKey(): MessagingPublicKey? =
        announcedSigningPublicKey()
            ?.let { bytes -> MessagingPublicKey.parse(bytes.joinToString("") { "%02x".format(it) }) }

    /**
     * The public half of the key the mesh announces.
     *
     * Read from the encryption service's own store rather than obtained through it, because
     * constructing a second encryption service would bring a second Noise stack with it. Only the
     * private key is persisted, so the public half is derived; the private bytes are wiped as soon
     * as that is done and never retained, logged or returned.
     */
    private fun announcedSigningPublicKey(): ByteArray? = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context.applicationContext,
            ANNOUNCED_KEY_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val stored = prefs.getString(ANNOUNCED_KEY_PREF, null) ?: return null
        val privateBytes = Base64.decode(stored, Base64.DEFAULT)
        try {
            Ed25519PrivateKeyParameters(privateBytes, 0).generatePublicKey().encoded
        } finally {
            privateBytes.fill(0)
        }
    }.getOrNull()

    /** True once the device has an identity that can send and receive messages. */
    fun isReady(): Boolean = localPublicKey() != null

    /**
     * Whether the user has been through first-run setup.
     *
     * Tracked with its own flag rather than inferred from the display name, because the underlying
     * nickname store invents an "anon1234" value on first read. Something is therefore always
     * present, and "has a name" cannot distinguish a name the user chose from one the app made up
     * on their behalf.
     */
    fun hasCompletedSetup(): Boolean =
        identityStore.getSecureValue(KEY_SETUP_COMPLETE) == "1"

    /**
     * Record the name and optional phone number the user supplied at setup.
     *
     * The phone number is local metadata: it is never an address, never required, and is only
     * disclosed to a contact if the user separately allows it in privacy settings.
     */
    fun completeSetup(displayName: String, phoneNumber: String?): MessagingIdentity? {
        setDisplayName(displayName)
        setPhoneNumber(phoneNumber)
        identityStore.storeSecureValue(KEY_SETUP_COMPLETE, "1")
        return refresh()
    }

    /**
     * Set the name shown to other people.
     *
     * Blank input is ignored rather than accepted: an empty announced name would render every such
     * peer identically, which is precisely the ambiguity display names should not introduce.
     */
    fun setDisplayName(name: String): MessagingIdentity? {
        val sanitized = MessagingIdentity.sanitizeDisplayName(name)
        if (sanitized.isEmpty()) return _identity.value
        dataManager.saveNickname(sanitized)
        return refresh()
    }

    /**
     * Record the local user's own phone number.
     *
     * Stored to make contact matching possible on this device. It is not part of the identity, is
     * never used for addressing, and is not announced — passing null clears it.
     */
    fun setPhoneNumber(phoneNumber: String?): MessagingIdentity? {
        val trimmed = phoneNumber?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            identityStore.removeSecureValue(KEY_PHONE_NUMBER)
        } else {
            identityStore.storeSecureValue(KEY_PHONE_NUMBER, trimmed)
        }
        return refresh()
    }

    private fun storedPhoneNumber(): String? =
        identityStore.getSecureValue(KEY_PHONE_NUMBER)?.takeIf { it.isNotBlank() }

    private companion object {
        const val KEY_PHONE_NUMBER = "messaging_local_phone_number"
        const val KEY_SETUP_COMPLETE = "messaging_setup_complete"

        /** Owned by the encryption service; mirrored here only to read the announced key. */
        const val ANNOUNCED_KEY_PREFS = "bitchat_crypto_secure"
        const val ANNOUNCED_KEY_PREF = "ed25519_signing_private_key"
    }
}
