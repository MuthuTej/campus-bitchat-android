package com.bitchat.android.campus

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends and receives files as a bounded, resumable stream of application-layer chunks.
 *
 * Every transfer is point to point. A transfer is opened against exactly one counterparty, all of
 * its envelopes are addressed to that peer, and envelopes arriving from anyone else are discarded
 * rather than applied — so observing a transferId is not enough to inject chunks, forge an
 * acknowledgement, or cancel a transfer between two other people.
 *
 * Deliberately free of Android and coroutine types so the whole protocol — windowing, cumulative
 * acknowledgement, resume, integrity failure — is exercisable in ordinary unit tests. The Android
 * binding supplies a [Transport] and drives it from the existing private-message pipeline.
 */
class CampusFileTransferService(
    private val transport: Transport,
    private val maxFileSizeBytes: Long = DEFAULT_MAX_FILE_SIZE,
    private val windowSize: Int = CampusFileMessage.WINDOW_SIZE
) {

    /**
     * Anything that can put an encoded envelope onto the existing BitChat transport, addressed to
     * one counterparty.
     *
     * [recipient] is an opaque destination handle owned by the caller — in the Android binding a
     * mesh peer ID resolved through the existing private-message router. This layer never
     * interprets it; it only guarantees that every envelope belonging to a transfer is addressed to
     * the same counterparty the transfer was opened with.
     */
    fun interface Transport {
        fun send(recipient: String, envelope: String): Boolean
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
        /** The single peer this transfer is addressed to. Only this peer may drive it. */
        val recipient: String,
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
                        val ok = transport.send(
                            recipient,
                            CampusFileMessage.encode(chunkAt(nextIndex))
                        )
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
     * Begin sending [data] to exactly one [recipient].
     *
     * @return the transferId, or null when the file is rejected locally (unsupported type or too
     *         large) — checked here so an unusable transfer never reaches the radio.
     */
    fun offerFile(
        recipient: String,
        fileName: String,
        mimeType: String,
        data: ByteArray
    ): String? {
        if (recipient.isBlank()) return null
        if (!CampusConfig.isSupportedFileType(mimeType)) return null
        if (data.isEmpty() || data.size > maxFileSizeBytes) return null

        val transferId = UUID.randomUUID().toString()
        val sha = CampusFileMessage.sha256Hex(data)
        val send = Outgoing(transferId, recipient, fileName, mimeType, data, sha)
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
        send.recipient,
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
            transport.send(
                it.recipient,
                CampusFileMessage.encode(CampusFileMessage.Cancel(transferId, reason))
            )
            publishOutgoing(it, reason)
        }
        incoming.remove(transferId)?.let { receive ->
            transport.send(
                receive.origin,
                CampusFileMessage.encode(CampusFileMessage.Cancel(transferId, reason))
            )
        }
    }

    // MARK: - Receiver

    private class Incoming(
        val offer: CampusFileMessage.Offer,
        /** The peer that opened this transfer. Chunks from anyone else are discarded. */
        val origin: String
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
     * [origin] identifies the peer the envelope actually arrived from, as established by the
     * transport that authenticated it. Every handler checks it against the counterparty recorded
     * when the transfer opened, so a third party who observes a transferId cannot inject chunks,
     * forge an acknowledgement, or cancel someone else's transfer.
     *
     * @return true when the content was a file-protocol envelope and was consumed, so the caller
     *         knows not to render it as chat text.
     */
    fun handleIncoming(origin: String, content: String): Boolean {
        val message = CampusFileMessage.decode(content) ?: return false
        when (message) {
            is CampusFileMessage.Offer -> handleOffer(origin, message)
            is CampusFileMessage.Accept -> handleAccept(origin, message)
            is CampusFileMessage.Reject -> handleReject(origin, message)
            is CampusFileMessage.Chunk -> handleChunk(origin, message)
            is CampusFileMessage.Ack -> handleAck(origin, message)
            is CampusFileMessage.Complete -> handleComplete(origin, message)
            is CampusFileMessage.Cancel -> handleCancel(origin, message)
        }
        return true
    }

    /** The outgoing transfer [transferId], but only when [origin] is the peer it was addressed to. */
    private fun outgoingFrom(origin: String, transferId: String): Outgoing? =
        outgoing[transferId]?.takeIf { it.recipient == origin }

    /** The incoming transfer [transferId], but only when [origin] is the peer that opened it. */
    private fun incomingFrom(origin: String, transferId: String): Incoming? =
        incoming[transferId]?.takeIf { it.origin == origin }

    private fun handleOffer(origin: String, offer: CampusFileMessage.Offer) {
        // Validate before allocating anything: an attacker on the mesh must not be able to make us
        // reserve memory for a 2 GB "offer" or accept an executable dressed as coursework.
        val rejection = when {
            !CampusConfig.isSupportedFileType(offer.mimeType) -> "Unsupported file type"
            offer.fileSize <= 0 || offer.fileSize > maxFileSizeBytes -> "File too large"
            offer.totalChunks != CampusFileMessage.chunkCountFor(offer.fileSize) -> "Inconsistent offer"
            offer.sha256Hex.length != 64 -> "Malformed offer"
            // A transferId already in use by a different peer is an attempt to graft onto — or
            // clobber — a transfer in progress. The established one wins.
            incoming[offer.transferId]?.let { it.origin != origin } == true -> "Transfer already in use"
            outgoing[offer.transferId] != null -> "Transfer already in use"
            else -> null
        }
        if (rejection != null) {
            transport.send(
                origin,
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

        incoming[offer.transferId] = Incoming(offer, origin)
        publish(
            Progress(
                offer.transferId, offer.fileName, offer.fileSize, offer.mimeType,
                State.ACTIVE, 0, offer.totalChunks
            )
        )
        transport.send(
            origin,
            CampusFileMessage.encode(CampusFileMessage.Accept(offer.transferId))
        )
    }

    private fun handleAccept(origin: String, accept: CampusFileMessage.Accept) {
        val send = outgoingFrom(origin, accept.transferId) ?: return
        send.accepted = true
        send.pump()
        publishOutgoing(send)
    }

    private fun handleReject(origin: String, reject: CampusFileMessage.Reject) {
        val send = outgoingFrom(origin, reject.transferId) ?: return
        outgoing.remove(reject.transferId)
        send.state = State.REJECTED
        publishOutgoing(send, reject.reason)
    }

    private fun handleChunk(origin: String, chunk: CampusFileMessage.Chunk) {
        val receive = incomingFrom(origin, chunk.transferId) ?: return
        if (chunk.index < 0 || chunk.index >= receive.offer.totalChunks) return

        // Duplicates are expected on a flooding mesh; keep the first copy and re-ACK so a sender
        // that missed our previous ACK still makes progress.
        if (!receive.chunks.containsKey(chunk.index)) {
            receive.chunks[chunk.index] = chunk.bytes
            receive.advance()
        }

        transport.send(
            receive.origin,
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
                receive.origin,
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
            receive.origin,
            CampusFileMessage.encode(CampusFileMessage.Complete(transferId, actualHash))
        )
        onFileReceived?.invoke(
            transferId, receive.offer.fileName, receive.offer.mimeType, assembled
        )
    }

    private fun handleAck(origin: String, ack: CampusFileMessage.Ack) {
        val send = outgoingFrom(origin, ack.transferId) ?: return
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

    private fun handleComplete(origin: String, complete: CampusFileMessage.Complete) {
        val send = outgoingFrom(origin, complete.transferId) ?: return
        outgoing.remove(complete.transferId)
        send.state = if (complete.sha256Hex.equals(send.sha256Hex, ignoreCase = true)) {
            State.COMPLETE
        } else {
            State.FAILED
        }
        publishOutgoing(send)
    }

    private fun handleCancel(origin: String, cancel: CampusFileMessage.Cancel) {
        outgoingFrom(origin, cancel.transferId)?.let {
            outgoing.remove(cancel.transferId)
            it.state = State.CANCELLED
            publishOutgoing(it, cancel.reason)
        }
        if (incomingFrom(origin, cancel.transferId) != null) {
            incoming.remove(cancel.transferId)
        }
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
