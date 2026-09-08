package com.bitchat.android.campus

import com.bitchat.android.messaging.protocol.Canonical

/**
 * Deterministic serialization for everything the campus overlay signs.
 *
 * Signature security depends on the verifier hashing exactly the bytes the signer hashed. Ad-hoc
 * string concatenation ("$name|$role") is not sufficient: a display name containing the delimiter
 * would let one field bleed into another and allow two different credentials to canonicalise
 * identically. Every field is therefore length-prefixed, so the encoding is injective.
 *
 * Format: for each field, a 4-byte big-endian length followed by that many UTF-8 bytes.
 *
 * The implementation now lives in [Canonical], shared with the general messaging layer. This object
 * remains as the campus-facing name so credential and alert code reads unchanged, but there is only
 * one encoder: two copies that must agree byte for byte would eventually stop agreeing, and the
 * failure would be silently unverifiable signatures.
 */
object CampusCanonical {

    fun encode(vararg fields: String): ByteArray = Canonical.encode(*fields)

    fun decodeFields(bytes: ByteArray, expected: Int): List<String>? =
        Canonical.decodeFields(bytes, expected)

    /** Base64url without padding: safe to embed in a chat message body. */
    fun b64(bytes: ByteArray): String = Canonical.b64(bytes)

    fun unb64(text: String): ByteArray? = Canonical.unb64(text)

    fun hex(bytes: ByteArray): String = Canonical.hex(bytes)

    fun unhex(text: String): ByteArray? = Canonical.unhex(text)
}
