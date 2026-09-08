package com.bitchat.android.messaging.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic serialization for structured application payloads.
 *
 * Ad-hoc string concatenation ("$sender|$recipient") is not sufficient for anything that will be
 * compared, signed or parsed: a field containing the delimiter lets one field bleed into the next,
 * so two different messages can encode identically. Every field is therefore length-prefixed, which
 * makes the encoding injective — one input, one output, and no two distinct inputs colliding.
 *
 * Format: for each field, a 4-byte big-endian length followed by that many UTF-8 bytes.
 *
 * This is the single implementation for the whole application layer. The campus overlay's
 * `CampusCanonical` delegates here rather than keeping a parallel copy, because two independently
 * maintained encoders that must agree byte for byte will eventually stop agreeing.
 */
object Canonical {

    fun encode(vararg fields: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /**
     * Inverse of [encode]. Returns null on any framing inconsistency — a truncated length, a length
     * that runs past the buffer, trailing bytes, or a field count other than [expected].
     *
     * Strictness matters here: a lenient parser would let an attacker append data that the verifier
     * ignores but a later consumer reads, which is how one component's view of a message comes to
     * differ from another's.
     */
    fun decodeFields(bytes: ByteArray, expected: Int): List<String>? {
        val fields = mutableListOf<String>()
        var offset = 0
        while (offset < bytes.size) {
            if (offset + 4 > bytes.size) return null
            val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length < 0) return null
            offset += 4
            if (offset + length > bytes.size) return null
            fields.add(String(bytes, offset, length, Charsets.UTF_8))
            offset += length
            if (fields.size > expected) return null
        }
        return if (fields.size == expected) fields else null
    }

    /** Base64url without padding: safe to embed in a chat message body. */
    fun b64(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun unb64(text: String): ByteArray? = try {
        java.util.Base64.getUrlDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        return try {
            ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
