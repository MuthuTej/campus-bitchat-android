package com.bitchat.android.messaging.transport

import android.content.Context
import com.bitchat.android.messaging.conversation.SendOutcome
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.MessageRouter

/**
 * Sends application messages through the existing private-message router.
 *
 * Uses `MessageRouter.sendPrivate`, which is addressed by peer and carries no dependency on which
 * conversation the user currently has open. That property is the reason this path exists at all:
 * the alternative entry point routes to the selected chat, so a send from anywhere else in the app
 * would silently go to the wrong place.
 *
 * Everything below is the transport as it already stands — mesh and internet-relay selection,
 * store-and-forward, handshake retry and outbox eviction are all the router's existing behaviour,
 * reused rather than reimplemented.
 */
class MeshOutboundTransport(
    private val context: Context,
    private val source: PeerIdentitySource,
    private val meshProvider: () -> MeshService,
    private val nicknameProvider: (String) -> String
) : OutboundTransport {

    override fun sendDirect(
        recipient: MessagingPublicKey,
        encodedEnvelope: String,
        messageId: String
    ): SendOutcome {
        // Without a proven peer for this identity there is nothing safe to address. Reporting
        // "queued" rather than "rejected" is the honest answer: the person exists and may well come
        // back into range, so the message waits rather than being declared undeliverable.
        val peerId = source.peerIdFor(recipient) ?: return SendOutcome.QUEUED

        val route = MessageRouter
            .getInstance(context.applicationContext, meshProvider())
            .sendPrivate(encodedEnvelope, peerId, nicknameProvider(peerId), messageId)

        return when (route) {
            MessageRouter.RouteResult.MESH -> SendOutcome.SENT_LOCAL
            MessageRouter.RouteResult.NOSTR -> SendOutcome.SENT_REMOTE
            MessageRouter.RouteResult.QUEUED -> SendOutcome.QUEUED
            MessageRouter.RouteResult.DROPPED -> SendOutcome.REJECTED
        }
    }

    override fun canReachNow(recipient: MessagingPublicKey): Boolean {
        val peerId = source.peerIdFor(recipient) ?: return false
        return source.hasEstablishedSession(peerId)
    }
}
