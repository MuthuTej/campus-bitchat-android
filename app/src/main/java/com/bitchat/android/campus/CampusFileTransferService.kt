package com.bitchat.android.campus

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends and receives files as a bounded, resumable stream of application-layer chunks.
 *
 * Deliberately free of Android and coroutine types so the whole protocol — windowing, cumulative
 * acknowledgement, resume, integrity failure — is exercisable in ordinary unit tests. The Android
 * binding supplies a [Transport] and drives it from the existing message pipeline.
 */
class CampusFileTransferService(
    private val transport: Transport,
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE,
    private val windowSize: Int = CampusFileMessage.WINDOW_SIZE
) {

    /** Anything that can put an encoded envelope onto the existing BitChat transport. */
    fun interface Transport {
        fun send(envelope: String): Boolean
    }

    enum class State { OFFERED, ACTIVE, PAUSED, COMPLETE, FAILED, REJECTED, CANCELLED }

    data class Progress(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val state: State,
        val chunksDone: Int,
        val totalChunks: Int,
        val detail: String? = null
    ) {
        val fraction: Float get() = if (totalChunks == 0) 0f else chunksDone.toFloat() / totalChunks
    }

    // MARK: - Sender

    private inner class Outgoing(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val data: ByteArray,
        val sha256Hex: String
    ) {
        val totalChunks = CampusFileMessage.chunkCountFor(data.size.toLong())
        var accepted = false
        var nextIndex = 0
        var highestAcked = -1
        var state = State.OFFERED

        // Re-entrancy guards. A transport that delivers synchronously (a unit-test harness, or a
        // future in-process loopback) lets an ACK arrive *inside* the send that produced it, so
        // pump() would call itself once per chunk and overflow the stack on a large file. Nested
        // calls therefore record that more work is pending and unwind; the outermost pump loops.
        private var pumping = false
        private var pumpAgain = false

        val inFlight: Int get() = nextIndex - highestAcked - 1

        fun chunkAt(index: Int): CampusFileMessage.Chunk {
            val start = index * CampusFileMessage.CHUNK_SIZE
            val end = minOf(start + CampusFileMessage.CHUNK_SIZE, data.size)
            return CampusFileMessage.Chunk(
                transferId = transferId,
                index = index,
                offset = start.toLong(),
                bytes = data.copyOfRange(start, end)
            )
        }

        /**
         * Emit chunks until the window is full or the file is exhausted.
         *
         * A send failure (peer out of range) stops the pump and parks the transfer in PAUSED rather
         * than failing it: the courier topology means the peer may simply be walking, and the
         * cumulative ACK lets us restart from exactly where we stopped.
         */
        fun pump() {
            if (!accepted || state == State.COMPLETE || state == State.FAILED) return
            if (pumping) {
                pumpAgain = true
                return
            }
            pumping = true
            try {
                do {
                    pumpAgain = false
                    while (inFlight < windowSize && nextIndex < totalChunks) {
                        val ok = transport.send(CampusFileMessage.encode(chunkAt(nextIndex)))
                        if (!ok) {
                            state = State.PAUSED
                            return
                        }
                        // A successful send clears a previous pause. Without this the transfer would
                        // stay PAUSED forever and every incoming ACK would rewind nextIndex and
                        // retransmit the same chunk indefinitely.
                        state = State.ACTIVE
                        nextIndex++
                    }
                } while (pumpAgain)
            } finally {
                pumping = false
            }
        }
    }

    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val progressByTransfer = ConcurrentHashMap<String, Progress>()

    /** Called whenever a transfer's progress changes, on the caller's thread. */
    var onProgress: ((Progress) -> Unit)? = null

    /** Called with the assembled bytes once a received file passes its hash check. */
    var onFileReceived: ((transferId: String, fileName: String, mimeType: String, bytes: ByteArray) -> Unit)? = null

    fun progressSnapshot(): List<Progress> = progressByTransfer.values.sortedBy { it.fileName }

    /**
     * Begin sending [data].
     *
     * @return the transferId, or null when the file is rejected locally (unsupported type or too
     *         large) — checked here so an unusable transfer never reaches the radio.
     */
    fun offerFile(fileName: String, mimeType: String, data: ByteArray): String? {
        if (!CampusConfig.isSupportedFileType(mimeType)) return null
        if (data.isEmpty() || data.size > maxFileSizeBytes) return null

        val transferId = UUID.randomUUID().toString()
        val sha = CampusFileMessage.sha256Hex(data)
        val send = Outgoing(transferId, fileName, mimeType, data, sha)
        outgoing[transferId] = send

        publish(
            Progress(
                transferId, fileName, data.size.toLong(), mimeType,
                State.OFFERED, 0, send.totalChunks
            )
        )

        // A lost offer must not strand the transfer: if the peer is already out of range the
        // transfer parks in PAUSED and resumePausedTransfers re-offers when they return.
        if (!sendOffer(send)) {
            send.state = State.PAUSED
            publishOutgoing(send)
        }
        return transferId
    }

    private fun sendOffer(send: Outgoing): Boolean = transport.send(
        CampusFileMessage.encode(
            CampusFileMessage.Offer(
                transferId = send.transferId,
                fileName = send.fileName,
                fileSize = send.data.size.toLong(),
                mimeType = send.mimeType,
                sha256Hex = send.sha256Hex,
                totalChunks = send.totalChunks
            )
        )
    )

    /**
     * Retry every parked outgoing transfer.
     *
     * Called when peers reappear. A transfer whose offer never landed is re-offered; one that was
     * mid-flight restarts from the last cumulative ACK, so a transfer that stalled at chunk 40 of
     * 160 resumes at 41 rather than restarting the file.
     */
    fun resumePausedTransfers() {
        outgoing.values
            .filter { it.state == State.PAUSED || (it.state == State.OFFERED && !it.accepted) }
            .forEach { send ->
                if (!send.accepted) {
                    // Re-offer. A synchronous transport may complete the whole transfer inside this
                    // call, so only record OFFERED if the peer still has not accepted.
                    val delivered = sendOffer(send)
                    if (delivered && !send.accepted) send.state = State.OFFERED
                } else {
                    send.nextIndex = send.highestAcked + 1
                    send.state = State.ACTIVE
                    send.pump()
                }
                publishOutgoing(send)
            }
    }

    fun cancel(transferId: String, reason: String = "Cancelled") {
        outgoing.remove(transferId)?.let {
            it.state = State.CANCELLED
            transport.send(CampusFileMessage.encode(CampusFileMessage.Cancel(transferId, reason)))
            publishOutgoing(it, reason)
        }
        incoming.remove(transferId)
    }

    // MARK: - Receiver

    private class Incoming(
        val offer: CampusFileMessage.Offer
    ) {
        val chunks = HashMap<Int, ByteArray>()
        var highestContiguous = -1

        /** Recompute the contiguous prefix after inserting a chunk. Duplicates are harmless. */
        fun advance() {
            var index = highestContiguous + 1
            while (chunks.containsKey(index)) index++
            highestContiguous = index - 1
        }

        fun isComplete(): Boolean = highestContiguous == offer.totalChunks - 1

        fun assemble(): ByteArray {
            val out = ByteArray(offer.fileSize.toInt())
            var position = 0
            for (index in 0 until offer.totalChunks) {
                val chunk = chunks[index] ?: return ByteArray(0)
                System.arraycopy(chunk, 0, out, position, chunk.size)
                position += chunk.size
            }
            return out
        }
    }

    /**
     * Feed an inbound message body to the transfer engine.
     *
     * @return true when the content was a file-protocol envelope and was consumed, so the caller
     *         knows not to render it as chat text.
     */
    fun handleIncoming(content: String): Boolean {
        val message = CampusFileMessage.decode(content) ?: return false
        when (message) {
            is CampusFileMessage.Offer -> handleOffer(message)
            is CampusFileMessage.Accept -> handleAccept(message)
            is CampusFileMessage.Reject -> handleReject(message)
            is CampusFileMessage.Chunk -> handleChunk(message)
            is CampusFileMessage.Ack -> handleAck(message)
            is CampusFileMessage.Complete -> handleComplete(message)
            is CampusFileMessage.Cancel -> handleCancel(message)
        }
        return true
    }

    private fun handleOffer(offer: CampusFileMessage.Offer) {
        // Validate before allocating anything: an attacker on the mesh must not be able to make us
        // reserve memory for a 2 GB "offer" or accept an executable dressed as coursework.
        val rejection = when {
            !CampusConfig.isSupportedFileType(offer.mimeType) -> "Unsupported file type"
            offer.fileSize <= 0 || offer.fileSize > maxFileSizeBytes -> "File too large"
            offer.totalChunks != CampusFileMessage.chunkCountFor(offer.fileSize) -> "Inconsistent offer"
            offer.sha256Hex.length != 64 -> "Malformed offer"
            else -> null
        }
        if (rejection != null) {
            transport.send(
                CampusFileMessage.encode(CampusFileMessage.Reject(offer.transferId, rejection))
            )
            publish(
                Progress(
                    offer.transferId, offer.fileName, offer.fileSize, offer.mimeType,
                    State.REJECTED, 0, offer.totalChunks, rejection
                )
            )
            return
        }

        incoming[offer.transferId] = Incoming(offer)
        publish(
            Progress(
                offer.transferId, offer.fileName, offer.fileSize, offer.mimeType,
                State.ACTIVE, 0, offer.totalChunks
            )
        )
        transport.send(CampusFileMessage.encode(CampusFileMessage.Accept(offer.transferId)))
    }

    private fun handleAccept(accept: CampusFileMessage.Accept) {
        val send = outgoing[accept.transferId] ?: return
        send.accepted = true
        send.pump()
        publishOutgoing(send)
    }

    private fun handleReject(reject: CampusFileMessage.Reject) {
        outgoing.remove(reject.transferId)?.let {
            it.state = State.REJECTED
            publishOutgoing(it, reject.reason)
        }
    }

    private fun handleChunk(chunk: CampusFileMessage.Chunk) {
        val receive = incoming[chunk.transferId] ?: return
        if (chunk.index < 0 || chunk.index >= receive.offer.totalChunks) return

        // Duplicates are expected on a flooding mesh; keep the first copy and re-ACK so a sender
        // that missed our previous ACK still makes progress.
        if (!receive.chunks.containsKey(chunk.index)) {
            receive.chunks[chunk.index] = chunk.bytes
            receive.advance()
        }

        transport.send(
            CampusFileMessage.encode(
                CampusFileMessage.Ack(chunk.transferId, receive.highestContiguous)
            )
        )

        publish(
            Progress(
                chunk.transferId, receive.offer.fileName, receive.offer.fileSize,
                receive.offer.mimeType, State.ACTIVE,
                receive.highestContiguous + 1, receive.offer.totalChunks
            )
        )

        if (receive.isComplete()) finishIncoming(chunk.transferId, receive)
    }

    private fun finishIncoming(transferId: String, receive: Incoming) {
        val assembled = receive.assemble()
        val actualHash = CampusFileMessage.sha256Hex(assembled)

        // Integrity is checked before the file is ever surfaced. A hash mismatch means corruption or
        // tampering in relay, and opening the document anyway would be the wrong failure mode.
        if (assembled.size.toLong() != receive.offer.fileSize ||
            !actualHash.equals(receive.offer.sha256Hex, ignoreCase = true)
        ) {
            incoming.remove(transferId)
            publish(
                Progress(
                    transferId, receive.offer.fileName, receive.offer.fileSize,
                    receive.offer.mimeType, State.FAILED,
                    receive.offer.totalChunks, receive.offer.totalChunks,
                    "File integrity check failed"
                )
            )
            transport.send(
                CampusFileMessage.encode(
                    CampusFileMessage.Cancel(transferId, "Integrity check failed")
                )
            )
            return
        }

        incoming.remove(transferId)
        publish(
            Progress(
                transferId, receive.offer.fileName, receive.offer.fileSize,
                receive.offer.mimeType, State.COMPLETE,
                receive.offer.totalChunks, receive.offer.totalChunks
            )
        )
        transport.send(
            CampusFileMessage.encode(CampusFileMessage.Complete(transferId, actualHash))
        )
        onFileReceived?.invoke(
            transferId, receive.offer.fileName, receive.offer.mimeType, assembled
        )
    }

    private fun handleAck(ack: CampusFileMessage.Ack) {
        val send = outgoing[ack.transferId] ?: return
        // Never let a stale or malicious ACK move the window backwards.
        if (ack.highestContiguousIndex > send.highestAcked) {
            send.highestAcked = ack.highestContiguousIndex
        }
        // Deliberately does NOT rewind nextIndex. An ACK can arrive re-entrantly from inside the
        // very send that produced it, and mutating the cursor mid-pump made the enclosing loop skip
        // the next chunk entirely. Rewinding is the resume path's job, where no pump is in flight.
        send.pump()
        publishOutgoing(send)
    }

    private fun handleComplete(complete: CampusFileMessage.Complete) {
        val send = outgoing.remove(complete.transferId) ?: return
        send.state = if (complete.sha256Hex.equals(send.sha256Hex, ignoreCase = true)) {
            State.COMPLETE
        } else {
            State.FAILED
        }
        publishOutgoing(send)
    }

    private fun handleCancel(cancel: CampusFileMessage.Cancel) {
        outgoing.remove(cancel.transferId)?.let {
            it.state = State.CANCELLED
            publishOutgoing(it, cancel.reason)
        }
        incoming.remove(cancel.transferId)
    }

    // MARK: - Progress plumbing

    private fun publishOutgoing(send: Outgoing, detail: String? = null) {
        publish(
            Progress(
                transferId = send.transferId,
                fileName = send.fileName,
                fileSize = send.data.size.toLong(),
                mimeType = send.mimeType,
                state = send.state,
                chunksDone = send.highestAcked + 1,
                totalChunks = send.totalChunks,
                detail = detail
            )
        )
    }

    private fun publish(progress: Progress) {
        progressByTransfer[progress.transferId] = progress
        onProgress?.invoke(progress)
    }

    companion object {
        /** Mirrors the transport's own per-payload ceiling; see AppConstants.Media. */
        const val DEFAULT_MAX_FILE_SIZE: Long = (10L * 1024 * 1024) - (132L * 1024)
    }
}
