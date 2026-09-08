package com.bitchat.android.campus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Exercises the transfer engine by wiring a sender and a receiver to each other in memory, so the
 * windowing, acknowledgement, resume and integrity paths are all driven end to end without a radio.
 *
 * Every transfer is addressed, so the harness names both ends: the sender only ever addresses
 * [PEER_B], the receiver only ever addresses [PEER_A], and a third identity is available for the
 * tests that check an outsider cannot interfere with a transfer between the two.
 */
class CampusFileTransferTest {

    private companion object {
        const val PEER_A = "peerA"
        const val PEER_B = "peerB"
        const val STRANGER = "peerZ"
    }

    /**
     * Couples two services. [receiverReachable] and [senderReachable] decide whether an envelope
     * reaches the far side, which is how the out-of-range and packet-loss scenarios are simulated.
     */
    private class Link {
        lateinit var sender: CampusFileTransferService
        lateinit var receiver: CampusFileTransferService
        var senderReachable = true
        var receiverReachable = true
        val senderSent = mutableListOf<String>()
        val senderRecipients = mutableListOf<String>()
        val receiverRecipients = mutableListOf<String>()

        fun build() {
            sender = CampusFileTransferService(transport = { recipient, envelope ->
                senderSent.add(envelope)
                senderRecipients.add(recipient)
                if (!receiverReachable) false else { receiver.handleIncoming(PEER_A, envelope); true }
            })
            receiver = CampusFileTransferService(transport = { recipient, envelope ->
                receiverRecipients.add(recipient)
                if (!senderReachable) false else { sender.handleIncoming(PEER_B, envelope); true }
            })
        }
    }

    private val pdfMime = "application/pdf"

    private fun link(): Link = Link().apply { build() }

    @Test
    fun `small file transfers end to end and verifies its hash`() {
        val link = link()
        var received: ByteArray? = null
        var receivedName: String? = null
        link.receiver.onFileReceived = { _, name, _, bytes -> receivedName = name; received = bytes }

        val data = Random(1).nextBytes(5000)
        val id = link.sender.offerFile(PEER_B, "Syllabus.pdf", pdfMime, data)

        assertNotNull(id)
        assertArrayEquals(data, received)
        assertEquals("Syllabus.pdf", receivedName)
    }

    @Test
    fun `multi chunk file reassembles in order`() {
        val link = link()
        var received: ByteArray? = null
        link.receiver.onFileReceived = { _, _, _, bytes -> received = bytes }

        // 10 chunks plus a partial one, so the final short chunk is exercised too.
        val data = Random(2).nextBytes(CampusFileMessage.CHUNK_SIZE * 10 + 123)
        link.sender.offerFile(PEER_B, "Notes.pdf", pdfMime, data)

        assertArrayEquals(data, received)
    }

    @Test
    fun `sender never exceeds the sliding window before acknowledgement`() {
        // Receiver accepts the offer but its ACKs never come back, so the window must stall.
        val sent = mutableListOf<String>()
        lateinit var receiver: CampusFileTransferService
        val sender = CampusFileTransferService(transport = { _, envelope ->
            sent.add(envelope)
            receiver.handleIncoming(PEER_A, envelope)
            true
        })
        receiver = CampusFileTransferService(transport = { _, envelope ->
            // Deliver the ACCEPT but swallow every ACK.
            if (CampusFileMessage.decode(envelope) is CampusFileMessage.Accept) {
                sender.handleIncoming(PEER_B, envelope)
            }
            true
        })

        val data = Random(3).nextBytes(CampusFileMessage.CHUNK_SIZE * 40)
        sender.offerFile(PEER_B, "Big.pdf", pdfMime, data)

        val chunksSent = sent.count { CampusFileMessage.decode(it) is CampusFileMessage.Chunk }
        assertEquals(CampusFileMessage.WINDOW_SIZE, chunksSent)
    }

    @Test
    fun `transfer pauses out of range and resumes without restarting`() {
        val link = link()
        var received: ByteArray? = null
        link.receiver.onFileReceived = { _, _, _, bytes -> received = bytes }

        val data = Random(4).nextBytes(CampusFileMessage.CHUNK_SIZE * 12)

        // Drop the peer partway through the first window.
        link.receiverReachable = false
        link.sender.offerFile(PEER_B, "Paper.pdf", pdfMime, data)
        assertNull("nothing should arrive while out of range", received)

        // Peer comes back into range.
        link.receiverReachable = true
        link.sender.resumePausedTransfers()

        val chunkIndices = link.senderSent
            .mapNotNull { CampusFileMessage.decode(it) as? CampusFileMessage.Chunk }
            .map { it.index }
        assertArrayEquals(data, received)
        // Resume restarts from the last cumulative ACK, so no chunk is sent more than twice
        // (once into the void, once after reconnection) rather than the file restarting repeatedly.
        chunkIndices.groupingBy { it }.eachCount().forEach { (index, count) ->
            assertTrue("chunk $index sent $count times", count <= 2)
        }
    }

    @Test
    fun `duplicate chunks are tolerated`() {
        val link = link()
        var completions = 0
        link.receiver.onFileReceived = { _, _, _, _ -> completions++ }

        val data = Random(5).nextBytes(CampusFileMessage.CHUNK_SIZE * 3)
        link.sender.offerFile(PEER_B, "Dup.pdf", pdfMime, data)

        // Replay every chunk the sender emitted; a flooding mesh does this routinely.
        link.senderSent
            .filter { CampusFileMessage.decode(it) is CampusFileMessage.Chunk }
            .forEach { link.receiver.handleIncoming(PEER_A, it) }

        assertEquals("file must complete exactly once", 1, completions)
    }

    @Test
    fun `missing chunk prevents completion`() {
        lateinit var receiver: CampusFileTransferService
        var completed = false
        val sender = CampusFileTransferService(transport = { _, envelope ->
            val message = CampusFileMessage.decode(envelope)
            // Drop chunk 1 permanently.
            if (message is CampusFileMessage.Chunk && message.index == 1) return@CampusFileTransferService true
            receiver.handleIncoming(PEER_A, envelope)
            true
        })
        receiver = CampusFileTransferService(transport = { _, envelope ->
            sender.handleIncoming(PEER_B, envelope); true
        })
        receiver.onFileReceived = { _, _, _, _ -> completed = true }

        sender.offerFile(PEER_B, "Gap.pdf", pdfMime, Random(6).nextBytes(CampusFileMessage.CHUNK_SIZE * 4))

        assertEquals(false, completed)
    }

    @Test
    fun `corrupted chunk fails the integrity check and is never delivered`() {
        lateinit var receiver: CampusFileTransferService
        var completed = false
        var failure: CampusFileTransferService.Progress? = null

        val sender = CampusFileTransferService(transport = { _, envelope ->
            val message = CampusFileMessage.decode(envelope)
            val toSend = if (message is CampusFileMessage.Chunk && message.index == 0) {
                // Flip the payload in flight, leaving the framing intact.
                CampusFileMessage.encode(
                    message.copy(bytes = ByteArray(message.bytes.size) { 0x41 })
                )
            } else envelope
            receiver.handleIncoming(PEER_A, toSend)
            true
        })
        receiver = CampusFileTransferService(transport = { _, envelope ->
            sender.handleIncoming(PEER_B, envelope); true
        })
        receiver.onFileReceived = { _, _, _, _ -> completed = true }
        receiver.onProgress = { if (it.state == CampusFileTransferService.State.FAILED) failure = it }

        sender.offerFile(PEER_B, "Corrupt.pdf", pdfMime, Random(7).nextBytes(CampusFileMessage.CHUNK_SIZE * 2))

        assertEquals("corrupted file must never be delivered", false, completed)
        assertNotNull(failure)
        assertEquals("File integrity check failed", failure?.detail)
    }

    @Test
    fun `unsupported mime type is refused before sending`() {
        val link = link()
        assertNull(link.sender.offerFile(PEER_B, "malware.apk", "application/vnd.android.package-archive", ByteArray(10)))
        assertNull(link.sender.offerFile(PEER_B, "photo.jpg", "image/jpeg", ByteArray(10)))
    }

    @Test
    fun `supported academic types are accepted`() {
        listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ).forEach { mime ->
            val link = link()
            assertNotNull("$mime should be accepted", link.sender.offerFile(PEER_B, "doc", mime, ByteArray(64)))
        }
    }

    @Test
    fun `oversized file is refused`() {
        val service = CampusFileTransferService(transport = { _, _ -> true }, maxFileSizeBytes = 1000)
        assertNull(service.offerFile(PEER_B, "Huge.pdf", pdfMime, ByteArray(1001)))
        assertNotNull(service.offerFile(PEER_B, "Fine.pdf", pdfMime, ByteArray(1000)))
    }

    @Test
    fun `receiver rejects an oversized offer without allocating`() {
        var rejection: CampusFileMessage.Reject? = null
        val receiver = CampusFileTransferService(
            transport = { _, envelope ->
                (CampusFileMessage.decode(envelope) as? CampusFileMessage.Reject)?.let { rejection = it }
                true
            },
            maxFileSizeBytes = 1000
        )

        receiver.handleIncoming(
            PEER_A,
            CampusFileMessage.encode(
                CampusFileMessage.Offer("t1", "Huge.pdf", 50_000_000L, pdfMime, "a".repeat(64), 1526)
            )
        )

        assertEquals("File too large", rejection?.reason)
    }

    @Test
    fun `offer with an inconsistent chunk count is rejected`() {
        var rejection: CampusFileMessage.Reject? = null
        val receiver = CampusFileTransferService(transport = { _, envelope ->
            (CampusFileMessage.decode(envelope) as? CampusFileMessage.Reject)?.let { rejection = it }
            true
        })

        receiver.handleIncoming(
            PEER_A,
            CampusFileMessage.encode(
                CampusFileMessage.Offer("t2", "a.pdf", 100_000L, pdfMime, "a".repeat(64), totalChunks = 1)
            )
        )

        assertEquals("Inconsistent offer", rejection?.reason)
    }

    @Test
    fun `stale acknowledgement cannot rewind the window`() {
        val link = link()
        val data = Random(8).nextBytes(CampusFileMessage.CHUNK_SIZE * 5)
        link.sender.offerFile(PEER_B, "Ack.pdf", pdfMime, data)
        val before = link.senderSent.size

        // Replay an ancient ACK; it must not cause retransmission from index 0.
        link.sender.handleIncoming(PEER_B, CampusFileMessage.encode(CampusFileMessage.Ack("nope", 0)))

        assertEquals(before, link.senderSent.size)
    }

    // MARK: - Addressing

    @Test
    fun `a transfer is refused without a recipient`() {
        val service = CampusFileTransferService(transport = { _, _ -> true })
        assertNull(service.offerFile("", "Notes.pdf", pdfMime, ByteArray(64)))
        assertNull(service.offerFile("   ", "Notes.pdf", pdfMime, ByteArray(64)))
    }

    @Test
    fun `every envelope of a transfer is addressed to the chosen recipient`() {
        val link = link()
        link.sender.offerFile(PEER_B, "Addressed.pdf", pdfMime, Random(9).nextBytes(CampusFileMessage.CHUNK_SIZE * 3))

        assertTrue("sender emitted nothing", link.senderRecipients.isNotEmpty())
        assertEquals(
            "the sender must address only the chosen recipient",
            setOf(PEER_B),
            link.senderRecipients.toSet()
        )
        // Acknowledgements go back to the offering peer, not to a channel or to everyone.
        assertEquals(
            "the receiver must reply only to the offering peer",
            setOf(PEER_A),
            link.receiverRecipients.toSet()
        )
    }

    @Test
    fun `chunks for a transfer that was never opened are dropped`() {
        val link = link()
        val data = Random(10).nextBytes(CampusFileMessage.CHUNK_SIZE * 4)
        link.sender.offerFile(PEER_B, "Target.pdf", pdfMime, data)

        // Replay the whole conversation at a service that never saw the offer. Without an accepted
        // offer there is no file to assemble, so nothing may be surfaced.
        var surfaced = false
        val bystander = CampusFileTransferService(transport = { _, _ -> true })
        bystander.onFileReceived = { _, _, _, _ -> surfaced = true }
        link.senderSent
            .filter { CampusFileMessage.decode(it) is CampusFileMessage.Chunk }
            .forEach { bystander.handleIncoming(PEER_A, it) }

        assertEquals(false, surfaced)
    }

    @Test
    fun `chunks from the wrong peer cannot poison an open transfer`() {
        var delivered: ByteArray? = null
        val receiver = CampusFileTransferService(transport = { _, _ -> true })
        receiver.onFileReceived = { _, _, _, bytes -> delivered = bytes }

        val payload = ByteArray(CampusFileMessage.CHUNK_SIZE) { 0x11 }
        val offer = CampusFileMessage.Offer(
            transferId = "t-open",
            fileName = "Real.pdf",
            fileSize = payload.size.toLong(),
            mimeType = pdfMime,
            sha256Hex = CampusFileMessage.sha256Hex(payload),
            totalChunks = 1
        )
        receiver.handleIncoming(PEER_A, CampusFileMessage.encode(offer))

        // A stranger supplies a chunk for the transfer PEER_A opened.
        receiver.handleIncoming(
            STRANGER,
            CampusFileMessage.encode(
                CampusFileMessage.Chunk("t-open", 0, 0L, ByteArray(payload.size) { 0x41 })
            )
        )
        assertNull("a stranger's chunk must not complete the transfer", delivered)

        // The real peer's chunk still completes it, with the genuine bytes.
        receiver.handleIncoming(
            PEER_A,
            CampusFileMessage.encode(CampusFileMessage.Chunk("t-open", 0, 0L, payload))
        )
        assertArrayEquals(payload, delivered)
    }

    @Test
    fun `acknowledgement from a third party cannot advance the window`() {
        val sent = mutableListOf<String>()
        val sender = CampusFileTransferService(transport = { _, envelope ->
            sent.add(envelope); true
        })
        val id = sender.offerFile(PEER_B, "Win.pdf", pdfMime, Random(11).nextBytes(CampusFileMessage.CHUNK_SIZE * 40))
        assertNotNull(id)
        // Accept from the real recipient so chunks start flowing and the window fills.
        sender.handleIncoming(PEER_B, CampusFileMessage.encode(CampusFileMessage.Accept(id!!)))
        val afterWindow = sent.count { CampusFileMessage.decode(it) is CampusFileMessage.Chunk }
        assertEquals(CampusFileMessage.WINDOW_SIZE, afterWindow)

        // A stranger acknowledges; the window must not slide.
        sender.handleIncoming(STRANGER, CampusFileMessage.encode(CampusFileMessage.Ack(id, 7)))

        assertEquals(
            "an outsider must not be able to advance the window",
            afterWindow,
            sent.count { CampusFileMessage.decode(it) is CampusFileMessage.Chunk }
        )
    }

    @Test
    fun `accept from a third party cannot start a transfer`() {
        val sent = mutableListOf<String>()
        val sender = CampusFileTransferService(transport = { _, envelope ->
            sent.add(envelope); true
        })
        val id = sender.offerFile(PEER_B, "Start.pdf", pdfMime, Random(12).nextBytes(CampusFileMessage.CHUNK_SIZE * 3))
        assertNotNull(id)

        sender.handleIncoming(STRANGER, CampusFileMessage.encode(CampusFileMessage.Accept(id!!)))

        assertEquals(
            "only the addressed recipient may accept",
            0,
            sent.count { CampusFileMessage.decode(it) is CampusFileMessage.Chunk }
        )
    }

    @Test
    fun `cancel from a third party cannot kill a transfer`() {
        val link = link()
        var received: ByteArray? = null
        link.receiver.onFileReceived = { _, _, _, bytes -> received = bytes }

        val data = Random(13).nextBytes(CampusFileMessage.CHUNK_SIZE * 2)
        link.receiverReachable = false
        val id = link.sender.offerFile(PEER_B, "Alive.pdf", pdfMime, data)
        assertNotNull(id)

        // An outsider tries to tear the parked transfer down.
        link.sender.handleIncoming(STRANGER, CampusFileMessage.encode(CampusFileMessage.Cancel(id!!, "go away")))

        link.receiverReachable = true
        link.sender.resumePausedTransfers()

        assertArrayEquals("an outsider must not be able to cancel a transfer", data, received)
    }

    @Test
    fun `an offer reusing a live transfer id from another peer is rejected`() {
        val rejections = mutableListOf<CampusFileMessage.Reject>()
        val receiver = CampusFileTransferService(transport = { _, envelope ->
            (CampusFileMessage.decode(envelope) as? CampusFileMessage.Reject)?.let { rejections.add(it) }
            true
        })

        val payload = ByteArray(64)
        fun offerFrom(name: String) = CampusFileMessage.Offer(
            transferId = "shared-id",
            fileName = name,
            fileSize = payload.size.toLong(),
            mimeType = pdfMime,
            sha256Hex = CampusFileMessage.sha256Hex(payload),
            totalChunks = 1
        )

        receiver.handleIncoming(PEER_A, CampusFileMessage.encode(offerFrom("First.pdf")))
        receiver.handleIncoming(STRANGER, CampusFileMessage.encode(offerFrom("Hijack.pdf")))

        assertEquals(1, rejections.size)
        assertEquals("Transfer already in use", rejections.first().reason)
    }

    @Test
    fun `every message type survives an encode decode round trip`() {
        val messages = listOf(
            CampusFileMessage.Offer("t", "a.pdf", 10L, pdfMime, "b".repeat(64), 1),
            CampusFileMessage.Accept("t"),
            CampusFileMessage.Reject("t", "nope"),
            CampusFileMessage.Chunk("t", 3, 96L, byteArrayOf(1, 2, 3, 0, -1)),
            CampusFileMessage.Ack("t", 7),
            CampusFileMessage.Complete("t", "c".repeat(64)),
            CampusFileMessage.Cancel("t", "bye")
        )
        messages.forEach { original ->
            assertEquals(original, CampusFileMessage.decode(CampusFileMessage.encode(original)))
        }
    }

    @Test
    fun `non protocol text is not consumed by the transfer engine`() {
        val service = CampusFileTransferService(transport = { _, _ -> true })
        assertEquals(false, service.handleIncoming(PEER_A, "Is today's assignment submitted?"))
    }

    @Test
    fun `hash helper matches known sha256`() {
        // SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            CampusFileMessage.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun `chunk count arithmetic covers exact and partial boundaries`() {
        assertEquals(0, CampusFileMessage.chunkCountFor(0))
        assertEquals(1, CampusFileMessage.chunkCountFor(1))
        assertEquals(1, CampusFileMessage.chunkCountFor(CampusFileMessage.CHUNK_SIZE.toLong()))
        assertEquals(2, CampusFileMessage.chunkCountFor(CampusFileMessage.CHUNK_SIZE + 1L))
    }
}
