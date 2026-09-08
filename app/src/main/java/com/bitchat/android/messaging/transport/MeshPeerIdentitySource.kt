package com.bitchat.android.messaging.transport

import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * The application layer's read-only window onto the existing BitChat mesh.
 *
 * Adapts what is already there and changes nothing: no transport class is modified, no new
 * discovery or presence traffic is introduced, and every value below is state the mesh already
 * maintains in order to route.
 *
 * ## Why the signing key comes from the identity store and not from the peer record
 *
 * A peer record's signing key can arrive from an announcement that was never signature-checked, so
 * treating it as an identity would let any device claim any key and appear in the contact list as
 * somebody else. The authenticated store is populated only from a Noise handshake proof, which is
 * the point at which the peer has actually demonstrated control of that key. Reading from there
 * means an unauthenticated peer resolves to no identity at all rather than to a plausible one.
 */
class MeshPeerIdentitySource(
    private val chatViewModel: ChatViewModel,
    private val identityStore: SecureIdentityStateManager
) : PeerIdentitySource, IdentityProofRequester {

    /**
     * Ask the mesh to complete a handshake with this peer.
     *
     * Uses the existing public handshake entry point; no transport behaviour is changed. The
     * handshake was always available, it simply had no caller until a person could be identified
     * before being messaged rather than only after.
     */
    override fun requestIdentityProof(peerId: String) {
        runCatching { chatViewModel.initiateMeshHandshake(peerId) }
    }

    override val connectedPeerIds: StateFlow<List<String>>
        get() = chatViewModel.connectedPeers

    /**
     * The peer's proven signing key, from either of the two places the transport establishes one.
     *
     * **A signature-verified announcement.** The transport checks an Ed25519 signature over the
     * announce, bound to the peer id, before setting `hasVerifiedAnnouncement`. Passing that check
     * means the peer demonstrably holds the private half of the key they published, which is
     * exactly the property an identity needs. This is available as soon as someone comes into
     * range.
     *
     * **A completed handshake.** Stronger, since it also proves a live session, but it only happens
     * once someone is messaged.
     *
     * Only the second was consulted originally. Because a person cannot be messaged before they
     * have a name, and had no name until they were messaged, nobody could ever start a first
     * conversation — every peer sat permanently as an unverified device. The announcement path is
     * checked first now: it is real proof and it is the one that exists at discovery time.
     *
     * The flag is what makes this safe. Peer records can also be refreshed through a path that
     * carries an *unchecked* signing key, and trusting that would let any device claim any key.
     */
    override fun authenticatedKeyFor(peerId: String): MessagingPublicKey? {
        val info = chatViewModel.getMeshPeerInfo(peerId)
        val announced = info
            ?.takeIf { it.hasVerifiedAnnouncement }
            ?.signingPublicKey
        if (announced != null) {
            MessagingPublicKey.parse(announced.joinToString("") { "%02x".format(it) })
                ?.let { return it }
        }

        val fingerprint = chatViewModel.getMeshPeerFingerprint(peerId) ?: return null
        val signingKey = identityStore.getAuthenticatedSigningKey(fingerprint) ?: return null
        return MessagingPublicKey.parse(signingKey.joinToString("") { "%02x".format(it) })
    }

    override fun nicknameFor(peerId: String): String? =
        chatViewModel.peerNicknames.value[peerId]

    override fun isDirectLink(peerId: String): Boolean =
        chatViewModel.peerDirect.value[peerId] == true

    override fun hasEstablishedSession(peerId: String): Boolean =
        chatViewModel.meshServiceFacade.hasEstablishedSession(peerId)

    override fun lastSeenMillis(peerId: String): Long? =
        chatViewModel.getMeshPeerInfo(peerId)?.lastSeen
}
