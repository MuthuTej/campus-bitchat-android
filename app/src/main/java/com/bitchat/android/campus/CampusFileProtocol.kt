package com.bitchat.android.campus

import java.security.MessageDigest

/**
 * Application-layer file transfer messages.
 *
 * These sit ABOVE the existing BitChat transport. A chunk is handed to the stock message pipeline,
 * which encrypts it with the existing channel/Noise crypto and hands it to the existing BLE
 * fragmenter. Nothing here touches MTU negotiation, the 13-byte packet header, or reassembly:
 *
 *     file → 32 KB app chunk → existing encryption → existing transport → existing BLE fragments
 *
 * The reason this layer exists at all is that a multi-megabyte PDF pushed into the transport as one
 * payload would sit in memory and monopolise the radio queue. Chunking with a bounded window makes
 * the transfer interruptible, resumable, and cheap to buffer — which is what a mesh built out of
 * students walking between buildings actually requires.
 */
sealed interface CampusFileMessage {

    val transferId: String

    /** Announces a transfer. The receiver decides whether to accept before any bytes are sent. */
    data class Offer(
        override val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val sha256Hex: String,
        val totalChunks: Int
    ) : CampusFileMessage

    data class Accept(override val transferId: String) : CampusFileMessage

    data class Reject(override val transferId: String, val reason: String) : CampusFileMessage

    data class Chunk(
        override val transferId: String,
        val index: Int,
        val offset: Long,
        val bytes: ByteArray
    ) : CampusFileMessage {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return transferId == other.transferId && index == other.index &&
                offset == other.offset && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = transferId.hashCode()
            result = 31 * result + index
            result = 31 * result + offset.hashCode()
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    /**
     * Cumulative acknowledgement.
     *
     * [highestContiguousIndex] is the highest index below which nothing is missing, so a single ACK
     * both slides the sender's window and tells a resuming sender exactly where to restart. -1 means
     * nothing has been received yet.
     */
    data class Ack(
        override val transferId: String,
        val highestContiguousIndex: Int
    ) : CampusFileMessage

    data class Complete(override val transferId: String, val sha256Hex: String) : CampusFileMessage

    data class Cancel(override val transferId: String, val reason: String) : CampusFileMessage

    companion object {
        const val ENVELOPE_PREFIX = "BCF1:"

        /**
         * 32 KB. Large enough that a 5 MB syllabus is ~160 chunks rather than thousands, small
         * enough that a chunk plus base64 expansion stays well inside the transport's payload cap.
         */
        const val CHUNK_SIZE = 32 * 1024

        /** Chunks permitted in flight before the sender must wait for an ACK. */
        const val WINDOW_SIZE = 8

        fun looksLikeFileMessage(content: String): Boolean = content.startsWith(ENVELOPE_PREFIX)

        fun encode(message: CampusFileMessage): String {
            val fields: Array<String> = when (message) {
                is Offer -> arrayOf(
                    "OFFER", message.transferId, message.fileName, message.fileSize.toString(),
                    message.mimeType, message.sha256Hex, message.totalChunks.toString()
                )
                is Accept -> arrayOf("ACCEPT", message.transferId)
                is Reject -> arrayOf("REJECT", message.transferId, message.reason)
                is Chunk -> arrayOf(
                    "CHUNK", message.transferId, message.index.toString(),
                    message.offset.toString(), CampusCanonical.b64(message.bytes)
                )
                is Ack -> arrayOf("ACK", message.transferId, message.highestContiguousIndex.toString())
                is Complete -> arrayOf("COMPLETE", message.transferId, message.sha256Hex)
                is Cancel -> arrayOf("CANCEL", message.transferId, message.reason)
            }
            return ENVELOPE_PREFIX + CampusCanonical.b64(CampusCanonical.encode(*fields))
        }

        fun decode(content: String): CampusFileMessage? {
            if (!looksLikeFileMessage(content)) return null
            val raw = CampusCanonical.unb64(content.removePrefix(ENVELOPE_PREFIX)) ?: return null
            // Field count varies by type, so parse leniently here and validate per-type below.
            val fields = decodeVariable(raw) ?: return null
            if (fields.isEmpty()) return null
            return when (fields[0]) {
                "OFFER" -> if (fields.size != 7) null else Offer(
                    transferId = fields[1],
                    fileName = fields[2],
                    fileSize = fields[3].toLongOrNull() ?: return null,
                    mimeType = fields[4],
                    sha256Hex = fields[5],
                    totalChunks = fields[6].toIntOrNull() ?: return null
                )
                "ACCEPT" -> if (fields.size != 2) null else Accept(fields[1])
                "REJECT" -> if (fields.size != 3) null else Reject(fields[1], fields[2])
                "CHUNK" -> if (fields.size != 5) null else Chunk(
                    transferId = fields[1],
                    index = fields[2].toIntOrNull() ?: return null,
                    offset = fields[3].toLongOrNull() ?: return null,
                    bytes = CampusCanonical.unb64(fields[4]) ?: return null
                )
                "ACK" -> if (fields.size != 3) null else Ack(
                    fields[1], fields[2].toIntOrNull() ?: return null
                )
                "COMPLETE" -> if (fields.size != 3) null else Complete(fields[1], fields[2])
                "CANCEL" -> if (fields.size != 3) null else Cancel(fields[1], fields[2])
                else -> null
            }
        }

        private fun decodeVariable(bytes: ByteArray): List<String>? {
            val fields = mutableListOf<String>()
            var offset = 0
            while (offset < bytes.size) {
                if (offset + 4 > bytes.size) return null
                val length = java.nio.ByteBuffer.wrap(bytes, offset, 4)
                    .order(java.nio.ByteOrder.BIG_ENDIAN).int
                if (length < 0) return null
                offset += 4
                if (offset + length > bytes.size) return null
                fields.add(String(bytes, offset, length, Charsets.UTF_8))
                offset += length
                if (fields.size > 16) return null
            }
            return fields
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun chunkCountFor(size: Long): Int =
            if (size <= 0) 0 else ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }
}
