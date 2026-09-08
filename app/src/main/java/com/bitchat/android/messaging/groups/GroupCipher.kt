package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.protocol.Canonical
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A group's shared secret, and the version it belongs to.
 *
 * Versioned because a group key has to be replaced whenever someone is removed. Without a version
 * a member who missed the rotation could not tell "I cannot read this" from "this is corrupt", and
 * a sender could not tell which key a message was encrypted under.
 */
data class GroupKey(
    val groupId: String,
    val version: Int,
    val material: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupKey) return false
        return groupId == other.groupId &&
            version == other.version &&
            material.contentEquals(other.material)
    }

    override fun hashCode(): Int =
        (groupId.hashCode() * 31 + version) * 31 + material.contentHashCode()

    /** Never let key material reach a log through an accidental interpolation. */
    override fun toString(): String = "GroupKey(groupId=$groupId, version=$version)"

    companion object {
        const val KEY_BYTES = 32

        fun random(groupId: String, version: Int, random: SecureRandom = SecureRandom()): GroupKey =
            GroupKey(groupId, version, ByteArray(KEY_BYTES).also(random::nextBytes))
    }
}

/**
 * Encrypts group content so that possession of the group key, rather than good behaviour, is what
 * decides who can read.
 *
 * ## Why this exists
 *
 * The membership list says who is *allowed* in a group. It cannot stop a device that is within
 * radio range from observing the traffic, because on a mesh every nearby device sees and relays
 * packets it is not the destination for. Only encryption under a key that non-members do not have
 * turns "not permitted to read" into "not able to read".
 *
 * ## What it is
 *
 * AES-256-GCM under a random 256-bit key — the same primitive and the same JCA usage already
 * present in this codebase for conversation storage. No new construction is introduced: the group
 * key is generated randomly rather than derived, so there is no password, no KDF, and nothing an
 * attacker can recompute from a value shipped inside the app.
 *
 * That last point is the substantive change from deriving channel keys from a constant compiled
 * into the binary, where anyone who could unzip the app could derive every group's key.
 *
 * The group id and key version are authenticated as associated data, so a ciphertext cannot be
 * replayed into a different group or presented as belonging to a different key generation.
 */
object GroupCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val ENVELOPE_VERSION: Byte = 1

    private val random = SecureRandom()

    /** Encrypt [plaintext] for the group, returning a base64url token safe to put in a payload. */
    fun seal(key: GroupKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.material, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(associatedData(key))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Canonical.b64(byteArrayOf(ENVELOPE_VERSION) + iv + ciphertext)
    }

    /**
     * Decrypt group content, or null when this key cannot open it.
     *
     * Null covers every failure — wrong key, wrong group, wrong version, tampering, truncation —
     * deliberately without distinguishing them. A caller that learned *why* decryption failed could
     * be used as an oracle, and no caller has a legitimate use for the difference: the message is
     * unreadable either way.
     */
    fun open(key: GroupKey, token: String): String? = runCatching {
        val envelope = Canonical.unb64(token) ?: return null
        if (envelope.size <= 1 + IV_BYTES) return null
        if (envelope[0] != ENVELOPE_VERSION) return null
        val iv = envelope.copyOfRange(1, 1 + IV_BYTES)
        val ciphertext = envelope.copyOfRange(1 + IV_BYTES, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.material, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        cipher.updateAAD(associatedData(key))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    /** Binds a ciphertext to its group and key generation. */
    private fun associatedData(key: GroupKey): ByteArray =
        Canonical.encode("group", key.groupId, key.version.toString())
}
