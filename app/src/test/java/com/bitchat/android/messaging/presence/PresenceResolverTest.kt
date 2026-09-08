package com.bitchat.android.messaging.presence

import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.transport.FakePeerIdentitySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the translation from transport state to the reachability shown to the user, including the
 * cases where the honest answer is "unknown" rather than a guess.
 */
class PresenceResolverTest {

    private val keyA = MessagingPublicKey.parse("a".repeat(64))!!
    private val keyB = MessagingPublicKey.parse("b".repeat(64))!!

    private var now = 1_000_000L
    private val source = FakePeerIdentitySource()
    private val resolver = PresenceResolver(source, clockMillis = { now })

    @Test
    fun `a directly linked peer is directly reachable`() {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, direct = true))
        source.setConnected("p1")

        assertEquals(PresenceState.DIRECTLY_REACHABLE, resolver.presenceForPeer("p1"))
    }

    @Test
    fun `a relayed peer is reported as mesh reachable not as directly reachable`() {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, direct = false))
        source.setConnected("p1")

        // The distinction matters: a relayed route depends on a third device that can walk away.
        assertEquals(PresenceState.MESH_REACHABLE, resolver.presenceForPeer("p1"))
    }

    @Test
    fun `both connected states count as reachable now`() {
        assertTrue(PresenceState.DIRECTLY_REACHABLE.isReachableNow)
        assertTrue(PresenceState.MESH_REACHABLE.isReachableNow)
        assertFalse(PresenceState.RECENTLY_SEEN.isReachableNow)
        assertFalse(PresenceState.OFFLINE.isReachableNow)
        assertFalse(PresenceState.UNKNOWN.isReachableNow)
    }

    @Test
    fun `a peer seen inside the window is recently seen`() {
        source.put(
            FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, lastSeenMillis = now - 60_000)
        )
        // Not connected.
        assertEquals(PresenceState.RECENTLY_SEEN, resolver.presenceForPeer("p1"))
    }

    @Test
    fun `a peer seen long ago is offline rather than recently seen`() {
        source.put(
            FakePeerIdentitySource.Peer(
                "p1", authenticatedKey = keyA,
                lastSeenMillis = now - PresenceResolver.DEFAULT_RECENTLY_SEEN_WINDOW_MILLIS - 1
            )
        )
        assertEquals(PresenceState.OFFLINE, resolver.presenceForPeer("p1"))
    }

    @Test
    fun `a peer never seen is unknown rather than offline`() {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, lastSeenMillis = null))

        // "Offline" asserts the device checked and found nothing; "unknown" is the truthful answer
        // when there is no observation at all.
        assertEquals(PresenceState.UNKNOWN, resolver.presenceForPeer("p1"))
    }

    @Test
    fun `a future timestamp does not read as recently seen`() {
        source.put(
            FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, lastSeenMillis = now + 60_000)
        )
        // A clock skewed forward must not manufacture presence.
        assertEquals(PresenceState.OFFLINE, resolver.presenceForPeer("p1"))
    }

    @Test
    fun `an unconnected unknown peer id is unknown`() {
        assertEquals(PresenceState.UNKNOWN, resolver.presenceForPeer("never-heard-of"))
    }

    // MARK: - Snapshot

    @Test
    fun `snapshot is keyed by identity and omits unproven peers`() {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, direct = true))
        // p2 has a nickname but never proved a key.
        source.put(FakePeerIdentitySource.Peer("p2", authenticatedKey = null, nickname = "Mallory"))
        source.setConnected("p1", "p2")

        val snapshot = resolver.snapshot()

        assertEquals(setOf(keyA), snapshot.keys)
        assertEquals(PresenceState.DIRECTLY_REACHABLE, snapshot[keyA])
    }

    @Test
    fun `one identity on two peer ids keeps the stronger reachability`() {
        // Mid-reconnect the same person can briefly appear twice, once relayed and once direct.
        source.put(FakePeerIdentitySource.Peer("old", authenticatedKey = keyA, direct = false))
        source.put(FakePeerIdentitySource.Peer("new", authenticatedKey = keyA, direct = true))
        source.setConnected("old", "new")

        assertEquals(PresenceState.DIRECTLY_REACHABLE, resolver.snapshot()[keyA])
    }

    @Test
    fun `snapshot covers every proven connected identity`() {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA, direct = true))
        source.put(FakePeerIdentitySource.Peer("p2", authenticatedKey = keyB, direct = false))
        source.setConnected("p1", "p2")

        val snapshot = resolver.snapshot()
        assertEquals(PresenceState.DIRECTLY_REACHABLE, snapshot[keyA])
        assertEquals(PresenceState.MESH_REACHABLE, snapshot[keyB])
    }

    @Test
    fun `an empty network yields an empty snapshot rather than stale entries`() {
        source.put(FakePeerIdentitySource.Peer("p1", authenticatedKey = keyA))
        source.setConnected("p1")
        assertEquals(1, resolver.snapshot().size)

        source.setConnected()
        assertTrue(resolver.snapshot().isEmpty())
    }

    @Test
    fun `labels avoid claiming internet connectivity`() {
        // The app cannot verify internet reachability, so it must not say "online".
        PresenceState.entries.forEach { state ->
            assertFalse(
                "${state.name} label must not claim online status",
                state.label.contains("online", ignoreCase = true)
            )
        }
    }
}
