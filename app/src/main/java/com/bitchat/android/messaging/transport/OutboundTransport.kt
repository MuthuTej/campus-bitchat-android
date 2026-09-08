package com.bitchat.android.messaging.transport

import com.bitchat.android.messaging.conversation.SendOutcome
import com.bitchat.android.messaging.identity.MessagingPublicKey

/**
 * The application layer's one way of putting a message onto the network.
 *
 * Addressed by messaging identity, never by transport peer ID: resolving a key to a route is the
 * transport's job, and every caller above this line works in identities only. That is what makes it
 * impossible for an application-layer bug to send a private message to "whichever conversation is
 * currently open", which is precisely how file chunks previously ended up broadcast to a channel.
 *
 * There is no broadcast method here, deliberately. A message with no named recipient is not
 * something this layer can express.
 */
interface OutboundTransport {

    /**
     * Send one already-encoded envelope to one identity.
     *
     * Returns what the transport did rather than a boolean, because "queued for later" is a normal
     * and useful outcome on a mesh, not a failure — the caller shows a waiting state instead of an
     * error, and the transport retries on its own.
     */
    fun sendDirect(
        recipient: MessagingPublicKey,
        encodedEnvelope: String,
        messageId: String
    ): SendOutcome

    /** True when a message to this identity would go out immediately rather than being held. */
    fun canReachNow(recipient: MessagingPublicKey): Boolean
}
