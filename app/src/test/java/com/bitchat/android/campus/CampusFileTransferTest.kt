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
 */
class CampusFileTransferTest {

    /**
     * Couples two services. [deliver] decides whether an envelope reaches the far side, which is how
     * the out-of-range and packet-loss scenarios are simulated.
     */
    private class Link {
        lateinit var sender: CampusFileTransferService
        lateinit var receiver: CampusFileTransferService
        var senderReachable = true
        var receiverReachable = true
        val senderSent = mutableListOf<String>()

        fun build() {
            sender = CampusFileTransferService(transport = { envelope ->
                senderSent.add(envelope)
                if (!receiverReachable) false else { receiver.handleIncoming(envelope); true }
            })
            receiver = CampusFileTransferService(transport = { envelope ->
                if (!senderReachable) false else { sender.handleIncoming(envelope); true }
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
        val id = link.sender.offerFile("Syllabus.pdf", pdfMime, data)

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
        link.sender.offerFile("Notes.pdf", pdfMime, data)

        assertArrayEquals(data, received)
    }

    @Test
    fun `sender never exceeds the sliding window before acknowledgement`() {
        // Receiver accepts the offer but its ACKs never come back, so the window must stall.
        val sent = mutableListOf<String>()
        lateinit var receiver: CampusFileTransferService
        val sender = CampusFileTransferService(transport = { envelope ->
            sent.add(envelope)
            receiver.handleIncoming(envelope)
            true
        })
        receiver = CampusFileTransferService(transport = { envelope ->
            // Deliver the ACCEPT but swallow every ACK.
            if (CampusFileMessage.decode(envelope) is CampusFileMessage.Accept) {
                sender.handleIncoming(envelope)
            }
            true
        })

        val data = Random(3).nextBytes(CampusFileMessage.CHUNK_SIZE * 40)
        sender.offerFile("Big.pdf", pdfMime, data)

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
        link.sender.offerFile("Paper.pdf", pdfMime, data)
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
        link.sender.offerFile("Dup.pdf", pdfMime, data)

        // Replay every chunk the sender emitted; a flooding mesh does this routinely.
        link.senderSent
            .filter { CampusFileMessage.decode(it) is CampusFileMessage.Chunk }
            .forEach { link.receiver.handleIncoming(it) }

        assertEquals("file must complete exactly once", 1, completions)
    }

    @Test
    fun `missing chunk prevents completion`() {
        lateinit var receiver: CampusFileTransferService
        var completed = false
        val sender = CampusFileTransferService(transport = { envelope ->
            val message = CampusFileMessage.decode(envelope)
            // Drop chunk 1 permanently.
            if (message is CampusFileMessage.Chunk && message.index == 1) return@CampusFileTransferService true
            receiver.handleIncoming(envelope)
            true
        })
        receiver = CampusFileTransferService(transport = { envelope ->
            sender.handleIncoming(envelope); true
        })
        receiver.onFileReceived = { _, _, _, _ -> completed = true }

        sender.offerFile("Gap.pdf", pdfMime, Random(6).nextBytes(CampusFileMessage.CHUNK_SIZE * 4))

        assertEquals(false, completed)
    }

    @Test
    fun `corrupted chunk fails the integrity check and is never delivered`() {
        lateinit var receiver: CampusFileTransferService
        var completed = false
        var failure: CampusFileTransferService.Progress? = null

        val sender = CampusFileTransferService(transport = { envelope ->
            val message = CampusFileMessage.decode(envelope)
            val toSend = if (message is CampusFileMessage.Chunk && message.index == 0) {
                // Flip the payload in flight, leaving the framing intact.
                CampusFileMessage.encode(
                    message.copy(bytes = ByteArray(message.bytes.size) { 0x41 })
                )
            } else envelope
            receiver.handleIncoming(toSend)
            true
        })
        receiver = CampusFileTransferService(transport = { envelope ->
            sender.handleIncoming(envelope); true
        })
        receiver.onFileReceived = { _, _, _, _ -> completed = true }
        receiver.onProgress = { if (it.state == CampusFileTransferService.State.FAILED) failure = it }

        sender.offerFile("Corrupt.pdf", pdfMime, Random(7).nextBytes(CampusFileMessage.CHUNK_SIZE * 2))

        assertEquals("corrupted file must never be delivered", false, completed)
        assertNotNull(failure)
        assertEquals("File integrity check failed", failure?.detail)
    }

    @Test
    fun `unsupported mime type is refused before sending`() {
        val link = link()
        assertNull(link.sender.offerFile("malware.apk", "application/vnd.android.package-archive", ByteArray(10)))
        assertNull(link.sender.offerFile("photo.jpg", "image/jpeg", ByteArray(10)))
    }

    @Test
    fun `supported academic types are accepted`() {
        listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ).forEach { mime ->
            val link = link()
            assertNotNull("$mime should be accepted", link.sender.offerFile("doc", mime, ByteArray(64)))
        }
    }

    @Test
    fun `oversized file is refused`() {
        val service = CampusFileTransferService(transport = { true }, maxFileSizeBytes = 1000)
        assertNull(service.offerFile("Huge.pdf", pdfMime, ByteArray(1001)))
        assertNotNull(service.offerFile("Fine.pdf", pdfMime, ByteArray(1000)))
    }

    @Test
    fun `receiver rejects an oversized offer without allocating`() {
        var rejection: CampusFileMessage.Reject? = null
        val receiver = CampusFileTransferService(
            transport = { envelope ->
                (CampusFileMessage.decode(envelope) as? CampusFileMessage.Reject)?.let { rejection = it }
                true
            },
            maxFileSizeBytes = 1000
        )

        receiver.handleIncoming(
            CampusFileMessage.encode(
                CampusFileMessage.Offer("t1", "Huge.pdf", 50_000_000L, pdfMime, "a".repeat(64), 1526)
            )
        )

        assertEquals("File too large", rejection?.reason)
    }

    @Test
    fun `offer with an inconsistent chunk count is rejected`() {
        var rejection: CampusFileMessage.Reject? = null
        val receiver = CampusFileTransferService(transport = { envelope ->
            (CampusFileMessage.decode(envelope) as? CampusFileMessage.Reject)?.let { rejection = it }
            true
        })

        receiver.handleIncoming(
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
        link.sender.offerFile("Ack.pdf", pdfMime, data)
        val before = link.senderSent.size

        // Replay an ancient ACK; it must not cause retransmission from index 0.
        link.sender.handleIncoming(CampusFileMessage.encode(CampusFileMessage.Ack("nope", 0)))

        assertEquals(before, link.senderSent.size)
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
        val service = CampusFileTransferService(transport = { true })
        assertEquals(false, service.handleIncoming("Is today's assignment submitted?"))
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
