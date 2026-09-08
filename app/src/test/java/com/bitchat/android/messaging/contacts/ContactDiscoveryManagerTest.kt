package com.bitchat.android.messaging.contacts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.messaging.identity.IdentityRepository
import com.bitchat.android.messaging.identity.MessagingIdentity
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceResolver
import com.bitchat.android.messaging.presence.PresenceState
import com.bitchat.android.messaging.transport.FakePeerIdentitySource
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Covers turning network sightings into people — and, more importantly, refusing to do so when the
 * peer has not proven who they are.
 */
@RunWith(RobolectricTestRunner::class)
class ContactDiscoveryManagerTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var identities: IdentityRepository
    private lateinit var contacts: ContactRepository
    private lateinit var discovery: ContactDiscoveryManager
    private lateinit var scope: CoroutineScope

    private val source = FakePeerIdentitySource()
    private var now = 1_000_000L

    /** Peers the discovery layer asked the transport to prove. */
    private val proofRequests = mutableListOf<String>()

    private val keyA = MessagingPublicKey.parse("a".repeat(64))!!
    private val keyB = MessagingPublicKey.parse("b".repeat(64))!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "discovery-test-${UUID.randomUUID()}.db"
        identities = IdentityRepository(
            context = context,
            databaseName = databaseName,
            clockMillis = { now },
            storageCipher = InMemoryConversationStorageCipher()
        )
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        contacts = ContactRepository(identities, scope)
        discovery = ContactDiscoveryManager(
            source = source,
            identities = identities,
            contacts = contacts,
            presence = PresenceResolver(source, clockMillis = { now }),
            proofRequester = { peerId -> proofRequests.add(peerId) },
            clockMillis = { now }
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        identities.close()
        context.deleteDatabase(databaseName)
    }

    // MARK: - How an identity becomes known

    @Test
    fun `a peer proven by its signed announcement alone is identified`() = runTest {
        // The case that matters for a first conversation: someone has just come into range and has
        // never been messaged, so no handshake has occurred. Their signed announce is proof enough,
        // and requiring more than that made every new peer permanently unidentifiable.
        source.put(
            FakePeerIdentitySource.Peer(
                "p1",
                authenticatedKey = keyA,
                nickname = "Priya",
                proofSource = FakePeerIdentitySource.ProofSource.VERIFIED_ANNOUNCEMENT
            )
        )
        source.setConnected("p1")

        discovery.refresh()

        val peer = discovery.nearby.value.single()
        assertTrue("a signed announcement must be enough to name someone", peer is NearbyPeer.Identified)
        assertEquals("Priya", (peer as NearbyPeer.Identified).displayName)
    }

    @Test
    fun `a peer proven by a completed handshake is identified`() = runTest {
        source.put(
            FakePeerIdentitySource.Peer(
                "p1",
                authenticatedKey = keyA,
                nickname = "Priya",
                proofSource = FakePeerIdentitySource.ProofSource.HANDSHAKE
            )
        )
        source.setConnected("p1")

        discovery.refresh()

        assertTrue(discovery.nearby.value.single() is NearbyPeer.Identified)
    }

    @Test
    fun `a peer with a key but no proof is not identified`() = runTest {
        // A device can publish any key it likes; only a checked signature makes it an identity.
        source.put(
            FakePeerIdentitySource.Peer(
                "p1",
                authenticatedKey = keyA,
                nickname = "Priya",
                proofSource = FakePeerIdentitySource.ProofSource.NONE
            )
        )
        source.setConnected("p1")

        discovery.refresh()

        assertTrue(discovery.nearby.value.single() is NearbyPeer.Unidentified)
        assertTrue(identities.identities.value.isEmpty())
    }

    // MARK: - Breaking the identity deadlock

    @Test
    fun `an unidentified peer is asked to prove who it is`() = runTest {
        // Without this the peer stays anonymous forever: proof only happens during a handshake, a
        // handshake only happens when messaging, and messaging needs a name. Nothing else in the
        // app breaks that cycle.
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = null))
        source.setConnected("p1")

        discovery.refresh()

        assertEquals(listOf("p1"), proofRequests)
    }

    @Test
    fun `a peer that already proved its key is not asked again`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA))
        source.setConnected("p1")

        discovery.refresh()

        assertTrue("no handshake is needed for a known identity", proofRequests.isEmpty())
    }

    @Test
    fun `proof is not requested repeatedly on every refresh`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = null))
        source.setConnected("p1")

        // Discovery re-runs on every change to the connected set; asking each time would flood
        // both the radio and the peer.
        repeat(5) { discovery.refresh() }

        assertEquals(1, proofRequests.size)
    }

    @Test
    fun `proof is requested again after the retry interval`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = null))
        source.setConnected("p1")
        discovery.refresh()

        // A handshake can fail or be missed, so the request must eventually repeat.
        now += 31_000L
        discovery.refresh()

        assertEquals(2, proofRequests.size)
    }

    @Test
    fun `a peer that becomes identified stops being asked`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = null))
        source.setConnected("p1")
        discovery.refresh()
        assertEquals(1, proofRequests.size)

        // The handshake completed.
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        now += 31_000L
        discovery.refresh()
        now += 31_000L
        discovery.refresh()

        assertEquals("no further requests once proven", 1, proofRequests.size)
    }

    @Test
    fun `each unidentified peer is asked separately`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = null))
        source.put(FakePeerIdentitySource.Peer("p2", authenticatedKey = null))
        source.setConnected("p1", "p2")

        discovery.refresh()

        assertEquals(setOf("p1", "p2"), proofRequests.toSet())
    }

    @Test
    fun `a peer that proved its key becomes a remembered identity`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        source.setConnected("p1")

        discovery.refresh()

        val stored = identities.get(keyA)
        assertNotNull("a proven peer must be remembered", stored)
        assertEquals("Priya", stored?.identity?.displayName)
    }

    @Test
    fun `an unproven peer is never recorded as an identity`() = runTest {
        // Broadcasts a convincing nickname but has proven no key.
        source.put(
            FakePeerIdentitySource.Peer("p2", authenticatedKey = null, nickname = "Dr Arun Kumar")
        )
        source.setConnected("p2")

        discovery.refresh()

        assertTrue("nothing may be stored for an unproven peer", identities.identities.value.isEmpty())
    }

    @Test
    fun `an unproven peer is shown without a name`() = runTest {
        source.put(
            FakePeerIdentitySource.Peer("p2", authenticatedKey = null, nickname = "Dr Arun Kumar")
        )
        source.setConnected("p2")

        discovery.refresh()

        val peer = discovery.nearby.value.single()
        assertTrue(peer is NearbyPeer.Unidentified)
        // The claimed nickname must not survive anywhere on the rendered value.
        assertFalse(peer.toString().contains("Arun"))
    }

    @Test
    fun `a proven peer is shown by name`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        source.setConnected("p1")

        discovery.refresh()

        val peer = discovery.nearby.value.single()
        assertTrue(peer is NearbyPeer.Identified)
        assertEquals(keyA, (peer as NearbyPeer.Identified).publicKey)
        assertEquals("Priya", peer.displayName)
    }

    @Test
    fun `a proven peer with no nickname is shown by key rather than unnamed`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = null))
        source.setConnected("p1")

        discovery.refresh()

        val peer = discovery.nearby.value.single() as NearbyPeer.Identified
        assertEquals(keyA.shortForm(), peer.displayName)
    }

    @Test
    fun `a hostile nickname is sanitised before it is stored`() = runTest {
        source.put(
            FakePeerIdentitySource.Peer(
                "p1", authenticatedKey = keyA, nickname = "Priya\u202E\nAdmin"
            )
        )
        source.setConnected("p1")

        discovery.refresh()

        val name = identities.get(keyA)!!.identity.displayName
        assertFalse(name.contains('\n'))
        assertFalse(name.contains('\u202E'))
    }

    @Test
    fun `discovery publishes presence onto contacts`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, direct = true))
        source.put(FakePeerIdentitySource.Peer("p2", authenticatedKey = keyB, direct = false))
        source.setConnected("p1", "p2")

        discovery.refresh()

        assertEquals(PresenceState.DIRECTLY_REACHABLE, contacts.find(keyA)?.presence)
        assertEquals(PresenceState.MESH_REACHABLE, contacts.find(keyB)?.presence)
    }

    @Test
    fun `a departed peer stops being reachable`() = runTest {
        source.put(
            FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, lastSeenMillis = now)
        )
        source.setConnected("p1")
        discovery.refresh()
        assertTrue(contacts.find(keyA)!!.canReachNow)

        source.setConnected()
        discovery.refresh()

        assertFalse("a peer out of range must not stay reachable", contacts.find(keyA)!!.canReachNow)
        assertTrue(discovery.nearby.value.isEmpty())
    }

    @Test
    fun `a name learned once survives the peer leaving`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        source.setConnected("p1")
        discovery.refresh()

        source.setConnected()
        discovery.refresh()

        // The person is gone from the network but must not become a stranger again.
        assertEquals("Priya", identities.get(keyA)?.identity?.displayName)
    }

    @Test
    fun `addable nearby excludes people already saved`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        source.put(FakePeerIdentitySource.Peer("p2", authenticatedKey = keyB, nickname = "Rahul"))
        source.setConnected("p1", "p2")
        discovery.refresh()

        contacts.save(keyA)
        discovery.refresh()

        assertEquals(listOf(keyB), discovery.addableNearby().map { it.publicKey })
    }

    @Test
    fun `addable nearby excludes unidentified devices`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p2", authenticatedKey = null, nickname = "Ghost"))
        source.setConnected("p2")
        discovery.refresh()

        // There is no key to save, so it cannot be offered as a contact.
        assertTrue(discovery.addableNearby().isEmpty())
    }

    @Test
    fun `a peer switching to a proven key is then recorded`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = null, nickname = "Priya"))
        source.setConnected("p1")
        discovery.refresh()
        assertNull(identities.get(keyA))

        // Handshake completes and the key is now proven.
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        discovery.refresh()

        assertNotNull(identities.get(keyA))
    }

    @Test
    fun `re-seeing a saved contact does not unsave them`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        source.setConnected("p1")
        discovery.refresh()
        contacts.save(keyA)

        now = 2_000_000L
        discovery.refresh()

        assertTrue(contacts.find(keyA)!!.isSaved)
    }

    @Test
    fun `discovery does not save people automatically`() = runTest {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, nickname = "Priya"))
        source.setConnected("p1")

        discovery.refresh()

        // Meeting someone records who they are; it does not add them to the contact list.
        assertTrue(contacts.saved.value.isEmpty())
        assertEquals(1, contacts.all.value.size)
    }
}
