package com.bitchat.android.messaging.identity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Covers the durable identity store — in particular that a restart does not lose what the device
 * learned, which is the whole reason this layer exists.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityRepositoryTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: IdentityRepository
    private var now = 1_000L

    /**
     * One cipher for the whole test, so reopening the database models a restart of the app rather
     * than a restart with a different Keystore key.
     */
    private lateinit var cipher: InMemoryConversationStorageCipher

    private val keyA = MessagingPublicKey.parse("a".repeat(64))!!
    private val keyB = MessagingPublicKey.parse("b".repeat(64))!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "messaging-test-${UUID.randomUUID()}.db"
        cipher = InMemoryConversationStorageCipher()
        repository = newRepository()
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    /** A fresh repository over the same file, standing in for an app restart. */
    private fun newRepository() = IdentityRepository(
        context = context,
        databaseName = databaseName,
        clockMillis = { now },
        storageCipher = cipher
    )

    @Test
    fun `an identity survives a restart`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))
        repository.close()

        val reopened = newRepository()
        try {
            reopened.load()
            val stored = reopened.get(keyA)
            assertNotNull(stored)
            assertEquals("Priya", stored?.identity?.displayName)
            assertEquals("+911111", stored?.identity?.phoneNumber)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `remembering the same key twice updates rather than duplicating`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        now = 2_000L
        repository.remember(MessagingIdentity.of(keyA, "Priya S"))

        assertEquals(1, repository.identities.value.size)
        assertEquals("Priya S", repository.get(keyA)?.identity?.displayName)
    }

    @Test
    fun `first seen is preserved while last seen advances`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        now = 5_000L
        val updated = repository.remember(MessagingIdentity.of(keyA, "Priya"))

        assertEquals(1_000L, updated.firstSeenAtMillis)
        assertEquals(5_000L, updated.lastSeenAtMillis)
    }

    @Test
    fun `an announced blank name does not erase a name already known`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        // A peer announcing an empty nickname must not be able to blank themselves out of the list.
        repository.remember(MessagingIdentity.of(keyA, ""))

        assertEquals("Priya", repository.get(keyA)?.identity?.displayName)
    }

    @Test
    fun `an absent phone number does not erase a stored one`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))
        repository.remember(MessagingIdentity.of(keyA, "Priya", phoneNumber = null))

        assertEquals("+911111", repository.get(keyA)?.identity?.phoneNumber)
    }

    @Test
    fun `a known identity is not a contact until it is saved`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        assertFalse(repository.get(keyA)!!.isContact)

        assertTrue(repository.setContact(keyA, true))
        assertTrue(repository.get(keyA)!!.isContact)

        assertTrue(repository.setContact(keyA, false))
        assertFalse(repository.get(keyA)!!.isContact)
    }

    @Test
    fun `contact and blocked flags survive a later sighting`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        repository.setContact(keyA, true)
        repository.setBlocked(keyA, true)

        now = 9_000L
        repository.remember(MessagingIdentity.of(keyA, "Priya"))

        val stored = repository.get(keyA)!!
        assertTrue("saving a contact must not be undone by seeing them again", stored.isContact)
        assertTrue("a block must not be undone by seeing them again", stored.isBlocked)
    }

    @Test
    fun `flags cannot be set on an unknown identity`() = runTest {
        assertFalse(repository.setContact(keyB, true))
        assertFalse(repository.setBlocked(keyB, true))
        assertFalse(repository.setDisplayName(keyB, "Ghost"))
    }

    @Test
    fun `renaming replaces the stored name`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "anon1234"))
        assertTrue(repository.setDisplayName(keyA, "Priya Sharma"))
        assertEquals("Priya Sharma", repository.get(keyA)?.identity?.displayName)
    }

    @Test
    fun `a rename is sanitised like any other name`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "anon"))
        repository.setDisplayName(keyA, "Priya\nAdmin")
        assertEquals("PriyaAdmin", repository.get(keyA)?.identity?.displayName)
    }

    @Test
    fun `forgetting removes the identity`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        assertTrue(repository.forget(keyA))
        assertNull(repository.get(keyA))
        assertFalse(repository.forget(keyA))
    }

    @Test
    fun `identities are listed most recently seen first`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        now = 3_000L
        repository.remember(MessagingIdentity.of(keyB, "Rahul"))

        assertEquals(
            listOf(keyB, keyA),
            repository.identities.value.map { it.publicKey }
        )
    }

    @Test
    fun `two identities are stored independently`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))
        repository.remember(MessagingIdentity.of(keyB, "Rahul", phoneNumber = "+922222"))

        assertEquals("Priya", repository.get(keyA)?.identity?.displayName)
        assertEquals("Rahul", repository.get(keyB)?.identity?.displayName)
        assertEquals("+911111", repository.get(keyA)?.identity?.phoneNumber)
        assertEquals("+922222", repository.get(keyB)?.identity?.phoneNumber)
    }

    @Test
    fun `personal fields are not stored in the clear`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))

        // Read the raw column: the on-disk bytes must not contain the plaintext.
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            database.query(
                "identities", arrayOf("display_name_ciphertext", "phone_number_ciphertext"),
                "public_key = ?", arrayOf(keyA.hex), null, null, null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val name = String(cursor.getBlob(0), Charsets.UTF_8)
                val phone = String(cursor.getBlob(1), Charsets.UTF_8)
                assertFalse("display name must not be stored in the clear", name.contains("Priya"))
                assertFalse("phone number must not be stored in the clear", phone.contains("+911111"))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `a stored value cannot be moved to another identity`() = runTest {
        repository.remember(MessagingIdentity.of(keyA, "Priya"))
        repository.remember(MessagingIdentity.of(keyB, "Rahul"))

        // Copy A's encrypted name onto B's row, as a tampering attempt would.
        val database = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            val stolen = database.query(
                "identities", arrayOf("display_name_ciphertext"),
                "public_key = ?", arrayOf(keyA.hex), null, null, null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst()); cursor.getBlob(0)
            }
            val values = android.content.ContentValues().apply {
                put("display_name_ciphertext", stolen)
            }
            database.update("identities", values, "public_key = ?", arrayOf(keyB.hex))
        } finally {
            database.close()
        }

        val reopened = newRepository()
        try {
            reopened.load()
            // The value is bound to A's key, so it must not open under B's — B loses its name
            // rather than inheriting A's.
            assertEquals("", reopened.get(keyB)?.identity?.displayName)
        } finally {
            reopened.close()
        }
    }
}
