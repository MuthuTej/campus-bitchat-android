package com.bitchat.android.messaging.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.ConversationId
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Covers conversation history surviving a restart — the property whose absence made the app forget
 * every chat the moment it was closed.
 */
@RunWith(RobolectricTestRunner::class)
class DirectMessageRepositoryTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var cipher: InMemoryConversationStorageCipher
    private lateinit var repository: DirectMessageRepository

    private val me = MessagingPublicKey.parse("b".repeat(64))!!
    private val alice = MessagingPublicKey.parse("a".repeat(64))!!

    private val conversation = ConversationId.direct(me, alice)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "history-test-${UUID.randomUUID()}.db"
        cipher = InMemoryConversationStorageCipher()
        repository = newRepository()
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    /** A fresh repository over the same file, standing in for an app restart. */
    private fun newRepository() = DirectMessageRepository(
        context = context,
        databaseName = databaseName,
        storageCipher = cipher
    )

    private fun message(
        id: String,
        text: String,
        outgoing: Boolean,
        at: Long = 1_000L,
        state: DeliveryState = DeliveryState.SENT
    ) = DirectMessage(
        messageId = id,
        conversationId = conversation,
        senderPublicKey = if (outgoing) me else alice,
        recipientPublicKey = if (outgoing) alice else me,
        content = text,
        timestampEpochMillis = at,
        isOutgoing = outgoing,
        deliveryState = state
    )

    /** Persistence is fire-and-forget on a background thread; let it land. */
    private fun settle() = Thread.sleep(150)

    @Test
    fun `both sides of a conversation survive a restart`() = runTest {
        repository.persist(message("m1", "are you coming?", outgoing = true))
        repository.persist(message("m2", "on my way", outgoing = false, at = 2_000L))
        settle()
        repository.close()

        val reopened = newRepository()
        try {
            val restored = reopened.loadMessages()
            assertEquals(2, restored.size)
            // Previously only the other person's messages came back, because outgoing ones were
            // never stored anywhere.
            assertEquals(listOf("are you coming?", "on my way"), restored.map { it.content })
            assertEquals(listOf(true, false), restored.map { it.isOutgoing })
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `delivery state survives a restart`() = runTest {
        repository.persist(message("m1", "hi", outgoing = true, state = DeliveryState.SENT))
        repository.persistDeliveryState("m1", DeliveryState.DELIVERED)
        settle()

        val reopened = newRepository()
        try {
            assertEquals(DeliveryState.DELIVERED, reopened.loadMessages().single().deliveryState)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `read conversations survive a restart`() = runTest {
        repository.persistRead(conversation)
        settle()

        val reopened = newRepository()
        try {
            assertTrue(conversation in reopened.loadReadConversations())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `message text is not stored in the clear`() = runTest {
        repository.persist(message("m1", "meet me at the back gate", outgoing = true))
        settle()

        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            db.query(
                "direct_messages", arrayOf("content_ciphertext"), null, null, null, null, null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val stored = String(cursor.getBlob(0), Charsets.UTF_8)
                assertFalse(stored.contains("meet me at the back gate"))
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a message cannot be relabelled into another conversation`() = runTest {
        repository.persist(message("m1", "private", outgoing = true))
        settle()

        // Rewrite the row's id, as tampering would, leaving the ciphertext in place.
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("UPDATE direct_messages SET message_id = 'm2'")
        } finally {
            db.close()
        }

        val reopened = newRepository()
        try {
            // Bound by associated data to its own message id, so the moved row will not open.
            assertTrue(reopened.loadMessages().isEmpty())
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `re-persisting the same message does not duplicate it`() = runTest {
        repository.persist(message("m1", "hi", outgoing = true))
        repository.persist(message("m1", "hi", outgoing = true))
        settle()

        assertEquals(1, newRepository().let { r -> try { r.loadMessages().size } finally { r.close() } })
    }

    @Test
    fun `history reloads into a manager as a usable conversation`() = runTest {
        repository.persist(message("m1", "are you coming?", outgoing = true))
        repository.persist(message("m2", "on my way", outgoing = false, at = 2_000L))
        repository.persistRead(conversation)
        settle()

        val reopened = newRepository()
        try {
            val restored = reopened.loadMessages()
            val read = reopened.loadReadConversations()
            assertEquals(2, restored.size)
            assertTrue(conversation in read)
        } finally {
            reopened.close()
        }
    }
}
