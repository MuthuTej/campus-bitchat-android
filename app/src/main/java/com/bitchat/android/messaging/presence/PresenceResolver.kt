package com.bitchat.android.messaging.presence

import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.transport.PeerIdentitySource

/**
 * Translates transport observations into the reachability the user is shown.
 *
 * Implements no presence protocol of its own — it announces nothing, asks nothing, and adds no
 * traffic to the network. Every value is derived from state the transport already maintains for
 * routing, which is why a second presence mechanism was not needed to answer the question.
 */
class PresenceResolver(
    private val source: PeerIdentitySource,
    /**
     * How long after losing a route someone is still described as recently seen.
     *
     * Bounded deliberately: past this point the device genuinely does not know whether the person
     * is nearby, and continuing to imply they might be would be a guess presented as information.
     */
    private val recentlySeenWindowMillis: Long = DEFAULT_RECENTLY_SEEN_WINDOW_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {

    companion object {
        const val DEFAULT_RECENTLY_SEEN_WINDOW_MILLIS: Long = 5 * 60 * 1000
    }

    /**
     * Reachability for one transport peer.
     *
     * A directly linked peer and a relayed one are reported differently because the difference is
     * real and the user can act on it: a direct link is fast and depends on nobody, while a relayed
     * route depends on a third device that may walk away mid-conversation.
     */
    fun presenceForPeer(peerId: String): PresenceState {
        val connected = source.connectedPeerIds.value.contains(peerId)
        if (connected) {
            return if (source.isDirectLink(peerId)) {
                PresenceState.DIRECTLY_REACHABLE
            } else {
                PresenceState.MESH_REACHABLE
            }
        }
        val lastSeen = source.lastSeenMillis(peerId) ?: return PresenceState.UNKNOWN
        val age = clockMillis() - lastSeen
        return if (age in 0..recentlySeenWindowMillis) {
            PresenceState.RECENTLY_SEEN
        } else {
            PresenceState.OFFLINE
        }
    }

    /**
     * Current reachability of every identity the transport can currently account for.
     *
     * Keyed by messaging identity rather than by transport peer ID, so callers never have to handle
     * a peer ID. Peers with no proven identity are absent: there is no key to attribute their
     * reachability to, and guessing would attach a stranger's presence to someone else's name.
     */
    fun snapshot(): Map<MessagingPublicKey, PresenceState> =
        source.connectedPeerIds.value
            .mapNotNull { peerId ->
                val key = source.authenticatedKeyFor(peerId) ?: return@mapNotNull null
                key to presenceForPeer(peerId)
            }
            // A key could appear under two peer IDs mid-reconnect; keep the stronger claim so a
            // stale relayed entry cannot mask a live direct link.
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, states) -> states.minByOrNull { it.ordinal } ?: PresenceState.UNKNOWN }
}
