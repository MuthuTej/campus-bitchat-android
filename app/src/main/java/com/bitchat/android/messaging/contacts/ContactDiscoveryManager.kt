package com.bitchat.android.messaging.contacts

import com.bitchat.android.messaging.identity.IdentityRepository
import com.bitchat.android.messaging.identity.MessagingIdentity
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceResolver
import com.bitchat.android.messaging.presence.PresenceState
import com.bitchat.android.messaging.transport.PeerIdentitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Someone visible on the network right now.
 *
 * Split into two cases rather than one nullable-key type, because the difference decides what may
 * be rendered. An [Identified] peer has proven their key and may be shown by name; an [Unidentified]
 * one has not, and may only ever be shown as an unverified device.
 */
sealed interface NearbyPeer {
    val presence: PresenceState

    data class Identified(
        val publicKey: MessagingPublicKey,
        val displayName: String,
        override val presence: PresenceState
    ) : NearbyPeer

    /**
     * A device that is present but has not proven any identity.
     *
     * Carries no name at all. A nickname from an unauthenticated announcement is exactly the input
     * an impersonator controls, so it is dropped rather than displayed with a warning beside it.
     */
    data class Unidentified(
        val peerId: String,
        override val presence: PresenceState
    ) : NearbyPeer
}

/**
 * Turns network sightings into durable knowledge of people.
 *
 * The chain is: a peer appears on the transport, the transport proves which signing key it
 * controls, that key becomes a messaging identity, and the identity is remembered so the person is
 * still recognisable tomorrow. Only the middle step involves trust, and it is delegated entirely to
 * the transport's existing authentication rather than re-implemented here.
 *
 * A peer whose key is not proven is reported as present but never recorded and never named, so
 * broadcasting someone else's nickname achieves nothing.
 */
class ContactDiscoveryManager(
    private val source: PeerIdentitySource,
    private val identities: IdentityRepository,
    private val contacts: ContactRepository,
    private val presence: PresenceResolver = PresenceResolver(source),
    /**
     * Asks the transport to prove an unknown peer's identity.
     *
     * Optional so the resolution logic stays testable without a transport, but in the app it is
     * always supplied: without it a peer that has never been messaged is never proven, and a peer
     * that is never proven can never be messaged.
     */
    private val proofRequester: com.bitchat.android.messaging.transport.IdentityProofRequester? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {

    /**
     * When proof was last requested for a peer.
     *
     * Discovery re-runs on every change to the connected set, which on a busy mesh is often. Asking
     * for a handshake each time would flood the radio and the peer, so each one is asked at most
     * once per [PROOF_RETRY_INTERVAL_MILLIS] — often enough to recover from a handshake that failed
     * or was missed, rare enough not to be abusive.
     */
    private val proofRequestedAt = mutableMapOf<String, Long>()

    private val _nearby = MutableStateFlow<List<NearbyPeer>>(emptyList())

    /** Everyone visible right now, identified or not. */
    val nearby: StateFlow<List<NearbyPeer>> = _nearby.asStateFlow()

    /**
     * Begin following the transport.
     *
     * Every change in the connected set re-resolves identities and republishes presence, so a
     * contact list dot goes grey when someone walks out of range rather than when the screen is
     * next opened.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            source.connectedPeerIds.collect { peerIds ->
                refresh(peerIds)
            }
        }
    }

    /** Resolve the current peer set. Exposed for tests and for an explicit refresh. */
    suspend fun refresh(peerIds: List<String> = source.connectedPeerIds.value) {
        val resolved = peerIds.map { peerId ->
            val key = source.authenticatedKeyFor(peerId)
            val state = presence.presenceForPeer(peerId)
            if (key == null) {
                // Nobody knows who this is yet. Ask, so they can become someone the user is able
                // to message rather than a permanent "unverified device".
                requestProofIfDue(peerId)
                NearbyPeer.Unidentified(peerId, state)
            } else {
                proofRequestedAt.remove(peerId)
                // The nickname is a hint only; it is sanitised and stored against the proven key,
                // never used to decide who this is.
                val identity = MessagingIdentity.of(key, source.nicknameFor(peerId))
                identities.remember(identity)
                NearbyPeer.Identified(
                    publicKey = key,
                    displayName = identity.displayNameOrKey,
                    presence = state
                )
            }
        }
        _nearby.value = resolved
        contacts.attachPresence(presence.snapshot())
    }

    private fun requestProofIfDue(peerId: String) {
        val requester = proofRequester ?: return
        val now = clockMillis()
        val last = proofRequestedAt[peerId]
        if (last != null && now - last < PROOF_RETRY_INTERVAL_MILLIS) return
        proofRequestedAt[peerId] = now
        requester.requestIdentityProof(peerId)
    }

    /**
     * Nearby people who are not already saved contacts.
     *
     * This is the "add someone you just met" list: everyone else on it is already in the contact
     * list, so offering to add them again would be noise.
     */
    fun addableNearby(): List<NearbyPeer.Identified> {
        val saved = contacts.saved.value.map { it.publicKey }.toSet()
        return _nearby.value
            .filterIsInstance<NearbyPeer.Identified>()
            .filterNot { it.publicKey in saved }
    }

    private companion object {
        /** How long before a peer that still has no identity is asked again. */
        const val PROOF_RETRY_INTERVAL_MILLIS = 30_000L
    }
}
