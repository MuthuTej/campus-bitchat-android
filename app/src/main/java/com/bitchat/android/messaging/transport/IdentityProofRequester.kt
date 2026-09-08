package com.bitchat.android.messaging.transport

/**
 * Asks the transport to establish who a peer is.
 *
 * Separate from [PeerIdentitySource] because that interface is strictly read-only and this one
 * causes network activity. Keeping them apart makes it obvious at a call site which kind of thing is
 * happening.
 *
 * ## Why this is needed at all
 *
 * A peer's identity is only proven once a secure session has been established, and establishing one
 * is not something the transport does merely because a device came into range. Nothing else in the
 * application layer triggers it either: sending a message would, but a person cannot be sent a
 * message until they have a name, and they have no name until their identity is proven.
 *
 * Without this the two conditions wait on each other forever and no first conversation is possible.
 * Requesting proof on discovery is what breaks that cycle.
 */
fun interface IdentityProofRequester {
    /**
     * Begin proving the identity of [peerId].
     *
     * Fire and forget: the result arrives later as the peer acquiring an authenticated key, which
     * discovery notices on its next pass. Safe to call for a peer that is already proven or already
     * in progress — implementations are expected to ignore redundant requests.
     */
    fun requestIdentityProof(peerId: String)
}
