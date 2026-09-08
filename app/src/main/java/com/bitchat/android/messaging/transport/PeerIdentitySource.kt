package com.bitchat.android.messaging.transport

import com.bitchat.android.messaging.identity.MessagingPublicKey
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the application layer needs to know about who is currently on the network.
 *
 * This is the boundary. The transport below it decides how packets travel; this interface reports
 * only what it observed, and nothing above it may reach past it into mesh, Noise or Nostr classes.
 * Its existence is what keeps discovery and presence testable without a radio, and what keeps the
 * networking implementation free of application concerns.
 *
 * Nothing here can change transport behaviour — every member is a read.
 */
interface PeerIdentitySource {

    /** Transport-level peer IDs currently connected. Not identities: see [authenticatedKeyFor]. */
    val connectedPeerIds: StateFlow<List<String>>

    /**
     * The peer's messaging identity, but only when the transport has cryptographically proven it.
     *
     * Returns null for a peer whose signing key is merely asserted in an unverified announcement.
     * That distinction is the whole point of this method: a peer can broadcast any key and any name
     * they like, so adopting an unproven key would let anyone appear in the contact list as
     * anybody. A peer with no proven key is still visible as a nearby device — it simply has no
     * identity yet, which is the honest description of that state.
     */
    fun authenticatedKeyFor(peerId: String): MessagingPublicKey?

    /** The peer's self-chosen nickname. Untrusted: a display hint, never an identity. */
    fun nicknameFor(peerId: String): String?

    /** True when the peer is reachable over a direct link rather than through relays. */
    fun isDirectLink(peerId: String): Boolean

    /** True when a secure session is ready, so a message can be sent immediately. */
    fun hasEstablishedSession(peerId: String): Boolean

    /** When this peer was last observed, epoch millis, or null if never. */
    fun lastSeenMillis(peerId: String): Long?

    /**
     * The transport peer currently carrying this identity, if any.
     *
     * Only proven peers can match, so this never resolves an identity to a peer that merely claimed
     * it — which would route a private message to an impersonator.
     */
    fun peerIdFor(key: MessagingPublicKey): String? =
        connectedPeerIds.value.firstOrNull { authenticatedKeyFor(it) == key }
}
