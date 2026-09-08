package com.bitchat.android.messaging.transport

import com.bitchat.android.messaging.identity.MessagingPublicKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A transport stand-in for the application layer's tests.
 *
 * Exists so discovery and presence can be driven through every combination of proven, unproven,
 * direct, relayed and departed peers without a radio — the situations that are hardest to produce
 * on real hardware are exactly the ones that need covering.
 */
class FakePeerIdentitySource : PeerIdentitySource {

    /**
     * How a peer's identity came to be known, mirroring the two ways the real transport establishes
     * one.
     *
     * Modelled explicitly because collapsing them into a single "authenticated key" field is what
     * previously hid a real bug: the production adapter consulted only [HANDSHAKE], which does not
     * happen until someone is messaged, so no peer could ever be identified in order to be messaged
     * in the first place. A fake that simply handed over a key could never surface that.
     */
    enum class ProofSource {
        /** Ed25519 signature over the announce, checked at discovery. Available immediately. */
        VERIFIED_ANNOUNCEMENT,

        /** Key learned from a completed secure session. Only after someone is messaged. */
        HANDSHAKE,

        /** Present on the network, nothing proven. */
        NONE
    }

    data class Peer(
        val peerId: String,
        val authenticatedKey: MessagingPublicKey? = null,
        val nickname: String? = null,
        val direct: Boolean = true,
        val session: Boolean = true,
        val lastSeenMillis: Long? = null,
        /**
         * Which proof this peer has. Defaults to a verified announcement when a key is supplied,
         * since that is what an ordinary peer in range actually has.
         */
        val proofSource: ProofSource =
            if (authenticatedKey != null) ProofSource.VERIFIED_ANNOUNCEMENT else ProofSource.NONE
    )

    private val peers = mutableMapOf<String, Peer>()
    private val _connected = MutableStateFlow<List<String>>(emptyList())

    override val connectedPeerIds: StateFlow<List<String>> = _connected

    fun put(peer: Peer) {
        peers[peer.peerId] = peer
    }

    /** Set the connected set; peers not listed remain known but disconnected. */
    fun setConnected(vararg peerIds: String) {
        _connected.value = peerIds.toList()
    }

    /**
     * A key is returned for either genuine proof, and never without one.
     *
     * Mirrors the production adapter, which accepts a signature-verified announcement as well as a
     * handshake-derived key. A fake that only honoured one of them would let the adapter regress to
     * requiring the other without any test noticing.
     */
    override fun authenticatedKeyFor(peerId: String): MessagingPublicKey? {
        val peer = peers[peerId] ?: return null
        return when (peer.proofSource) {
            ProofSource.VERIFIED_ANNOUNCEMENT, ProofSource.HANDSHAKE -> peer.authenticatedKey
            ProofSource.NONE -> null
        }
    }

    override fun nicknameFor(peerId: String): String? = peers[peerId]?.nickname

    override fun isDirectLink(peerId: String): Boolean = peers[peerId]?.direct == true

    override fun hasEstablishedSession(peerId: String): Boolean = peers[peerId]?.session == true

    override fun lastSeenMillis(peerId: String): Long? = peers[peerId]?.lastSeenMillis
}
