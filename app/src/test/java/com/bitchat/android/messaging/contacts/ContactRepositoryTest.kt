package com.bitchat.android.messaging.contacts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.messaging.identity.IdentityRepository
import com.bitchat.android.messaging.identity.MessagingIdentity
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceState
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
 * Covers contacts as a view over stored identities — saving, blocking, naming, and the composition
 * of live presence onto durable records.
 */
@RunWith(RobolectricTestRunner::class)
class ContactRepositoryTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var identities: IdentityRepository
    private lateinit var contacts: ContactRepository
    private lateinit var scope: CoroutineScope
    private var now = 1_000L

    private val keyA = MessagingPublicKey.parse("a".repeat(64))!!
    private val keyB = MessagingPublicKey.parse("b".repeat(64))!!
    private val keyC = MessagingPublicKey.parse("c".repeat(64))!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "contacts-test-${UUID.randomUUID()}.db"
        identities = IdentityRepository(
            context = context,
            databaseName = databaseName,
            clockMillis = { now },
            storageCipher = InMemoryConversationStorageCipher()
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        contacts = ContactRepository(identities, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        identities.close()
        context.deleteDatabase(databaseName)
    }

    /** Let the eagerly-shared flows observe the latest repository write. */
    private suspend fun settle() = withContext(Dispatchers.Unconfined) { }

    @Test
    fun `an encountered identity is known but not yet a contact`() = runTest {
        identities.remember(MessagingIdentity.of(keyA, "Priya"))
        settle()

        assertEquals(1, contacts.all.value.size)
        assertTrue(contacts.saved.value.isEmpty())
        assertFalse(contacts.all.value.first().isSaved)
    }

    @Test
    fun `saving an identity puts it in the contact list`() = runTest {
        assertTrue(contacts.save(MessagingIdentity.of(keyA, "Priya")))
        settle()

        assertEquals(1, contacts.saved.value.size)
        assertEquals("Priya", contacts.saved.value.first().displayName)
    }

    @Test
    fun `unsaving keeps the identity so history stays attributable`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        settle()
        assertTrue(contacts.unsave(keyA))
        settle()

        assertTrue("removed from contacts", contacts.saved.value.isEmpty())
        assertNotNull("but still known", contacts.find(keyA))
        assertEquals("Priya", contacts.find(keyA)?.displayName)
    }

    @Test
    fun `forgetting removes the person entirely`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        settle()
        assertTrue(contacts.forget(keyA))
        settle()

        assertNull(contacts.find(keyA))
        assertTrue(contacts.all.value.isEmpty())
    }

    @Test
    fun `renaming a contact updates what is displayed`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "anon4821"))
        settle()
        assertTrue(contacts.rename(keyA, "Priya Sharma"))
        settle()

        assertEquals("Priya Sharma", contacts.find(keyA)?.displayName)
    }

    @Test
    fun `an unnamed contact shows its key rather than an invented name`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, ""))
        settle()

        val contact = contacts.find(keyA)!!
        assertFalse(contact.isNamed)
        assertEquals(keyA.shortForm(), contact.displayName)
    }

    // MARK: - Blocking

    @Test
    fun `blocking is recorded and does not remove the contact`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        settle()
        assertTrue(contacts.setBlocked(keyA, true))
        settle()

        assertTrue(contacts.isBlocked(keyA))
        assertEquals("a blocked contact stays listed", 1, contacts.saved.value.size)
    }

    @Test
    fun `a blocked contact is never reachable regardless of transport state`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        contacts.setBlocked(keyA, true)
        contacts.attachPresence(mapOf(keyA to PresenceState.DIRECTLY_REACHABLE))
        settle()

        val contact = contacts.find(keyA)!!
        assertEquals(PresenceState.DIRECTLY_REACHABLE, contact.presence)
        assertFalse("a block overrides transport reachability", contact.canReachNow)
    }

    @Test
    fun `unblocking restores reachability`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        contacts.setBlocked(keyA, true)
        contacts.attachPresence(mapOf(keyA to PresenceState.DIRECTLY_REACHABLE))
        settle()
        assertFalse(contacts.find(keyA)!!.canReachNow)

        contacts.setBlocked(keyA, false)
        settle()
        assertTrue(contacts.find(keyA)!!.canReachNow)
    }

    // MARK: - Superseded identities

    @Test
    fun `a reinstalled contact supersedes their previous identity`() = runTest {
        // Same person, same number, new device identity — which is exactly what a reinstall looks
        // like from the outside.
        contacts.save(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))
        now = 5_000L
        contacts.save(MessagingIdentity.of(keyB, "Priya", phoneNumber = "+911111"))
        settle()

        assertEquals(
            "only the newest identity should be offered",
            listOf(keyB),
            contacts.saved.value.map { it.publicKey }
        )
        assertTrue(contacts.find(keyA)!!.isSuperseded)
        assertFalse(contacts.find(keyB)!!.isSuperseded)
    }

    @Test
    fun `a superseded contact is never reachable`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))
        now = 5_000L
        contacts.save(MessagingIdentity.of(keyB, "Priya", phoneNumber = "+911111"))
        // Even if the stale key somehow still looked present on the network.
        contacts.attachPresence(mapOf(keyA to PresenceState.DIRECTLY_REACHABLE))
        settle()

        assertFalse(
            "a dead key must not advertise itself as reachable",
            contacts.find(keyA)!!.canReachNow
        )
    }

    @Test
    fun `a superseded record is hidden but not destroyed`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))
        now = 5_000L
        contacts.save(MessagingIdentity.of(keyB, "Priya", phoneNumber = "+911111"))
        settle()

        // Kept so older messages stay attributable, and so a wrong match loses nothing.
        assertNotNull(contacts.find(keyA))
        assertEquals("Priya", contacts.find(keyA)?.displayName)
    }

    @Test
    fun `people who share a name but no number are left alone`() = runTest {
        // Matching on names would let anyone displace a contact by copying their display name.
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        now = 5_000L
        contacts.save(MessagingIdentity.of(keyB, "Priya"))
        settle()

        assertEquals(2, contacts.saved.value.size)
        assertFalse(contacts.find(keyA)!!.isSuperseded)
        assertFalse(contacts.find(keyB)!!.isSuperseded)
    }

    @Test
    fun `different people with different numbers both remain`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+911111"))
        contacts.save(MessagingIdentity.of(keyB, "Rahul", phoneNumber = "+922222"))
        settle()

        assertEquals(2, contacts.saved.value.size)
        assertFalse(contacts.find(keyA)!!.isSuperseded)
        assertFalse(contacts.find(keyB)!!.isSuperseded)
    }

    @Test
    fun `phone numbers match regardless of spacing`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya", phoneNumber = "+91 11 11"))
        now = 5_000L
        contacts.save(MessagingIdentity.of(keyB, "Priya", phoneNumber = "+911111"))
        settle()

        assertTrue(contacts.find(keyA)!!.isSuperseded)
    }

    // MARK: - Presence composition

    @Test
    fun `presence defaults to unknown rather than to offline`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        settle()

        // Unknown and offline are different claims; asserting "offline" about someone never seen
        // would be inventing information.
        assertEquals(PresenceState.UNKNOWN, contacts.find(keyA)?.presence)
    }

    @Test
    fun `attached presence appears on the contact`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        contacts.save(MessagingIdentity.of(keyB, "Rahul"))
        contacts.attachPresence(
            mapOf(keyA to PresenceState.MESH_REACHABLE, keyB to PresenceState.OFFLINE)
        )
        settle()

        assertEquals(PresenceState.MESH_REACHABLE, contacts.find(keyA)?.presence)
        assertEquals(PresenceState.OFFLINE, contacts.find(keyB)?.presence)
        assertTrue(contacts.find(keyA)!!.canReachNow)
        assertFalse(contacts.find(keyB)!!.canReachNow)
    }

    @Test
    fun `presence updates without rewriting the database`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        contacts.attachPresence(mapOf(keyA to PresenceState.DIRECTLY_REACHABLE))
        settle()
        assertEquals(PresenceState.DIRECTLY_REACHABLE, contacts.find(keyA)?.presence)

        contacts.attachPresence(mapOf(keyA to PresenceState.OFFLINE))
        settle()
        assertEquals(PresenceState.OFFLINE, contacts.find(keyA)?.presence)

        // The stored record is untouched by reachability changing.
        assertEquals(1_000L, identities.get(keyA)?.firstSeenAtMillis)
    }

    // MARK: - Ordering

    @Test
    fun `reachable contacts sort above unreachable ones`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Zara"))
        contacts.save(MessagingIdentity.of(keyB, "Anand"))
        contacts.attachPresence(mapOf(keyA to PresenceState.DIRECTLY_REACHABLE))
        settle()

        // Zara sorts after Anand alphabetically but is the one who can actually be reached.
        assertEquals(listOf("Zara", "Anand"), contacts.saved.value.map { it.displayName })
    }

    @Test
    fun `named contacts sort above anonymous ones at equal reachability`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, ""))
        contacts.save(MessagingIdentity.of(keyB, "Anand"))
        settle()

        assertEquals("Anand", contacts.saved.value.first().displayName)
    }

    @Test
    fun `saved list excludes identities that were never saved`() = runTest {
        contacts.save(MessagingIdentity.of(keyA, "Priya"))
        identities.remember(MessagingIdentity.of(keyB, "Passerby"))
        identities.remember(MessagingIdentity.of(keyC, "Another"))
        settle()

        assertEquals(3, contacts.all.value.size)
        assertEquals(listOf("Priya"), contacts.saved.value.map { it.displayName })
    }

    @Test
    fun `saving a key that was only encountered promotes it`() = runTest {
        identities.remember(MessagingIdentity.of(keyA, "Priya"))
        settle()
        assertTrue(contacts.saved.value.isEmpty())

        assertTrue(contacts.save(keyA))
        settle()
        assertEquals(listOf("Priya"), contacts.saved.value.map { it.displayName })
    }

    @Test
    fun `saving an unknown key alone does not fabricate a contact`() = runTest {
        // No identity has ever been recorded for this key.
        assertFalse(contacts.save(keyC))
        settle()
        assertTrue(contacts.all.value.isEmpty())
    }
}
