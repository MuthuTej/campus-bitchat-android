package com.bitchat.android.messaging.contacts

import com.bitchat.android.messaging.identity.MessagingIdentity
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.identity.StoredIdentity
import com.bitchat.android.messaging.presence.PresenceState

/**
 * Someone the user can message.
 *
 * Composed rather than stored as a single row: the durable half comes from the identity store, and
 * [presence] is attached live by the presence layer. Keeping them in one type is what lets a
 * contact list render a name and a reachability dot together, while keeping the reachability value
 * out of the database where it would go stale.
 *
 * This type knows nothing about institutions. Roles, departments and verification are an optional
 * layer that attaches by public key, so a contact with no affiliation is an ordinary, complete
 * contact rather than a degraded one.
 */
data class Contact(
    val identity: MessagingIdentity,
    /** The user explicitly saved this person, as opposed to merely having encountered them. */
    val isSaved: Boolean,
    val isBlocked: Boolean,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
    val presence: PresenceState = PresenceState.UNKNOWN,
    /**
     * True when this record has been replaced by a newer identity for the same person.
     *
     * A device that is reinstalled or wiped comes back with a new key, and from every other device
     * it is a brand new stranger. The old record stays behind looking perfectly ordinary while
     * being permanently unreachable: messages sent to it leave successfully and are correctly
     * refused at the far end, because they name a key nobody holds any more.
     *
     * Recognising the replacement is only possible when the two records share something the person
     * chose rather than something the device generated — which is what the phone number is for.
     */
    val isSuperseded: Boolean = false
) {
    val publicKey: MessagingPublicKey get() = identity.publicKey

    /** What to render. Falls back to the short key rather than inventing a name. */
    val displayName: String get() = identity.displayNameOrKey

    /** True when the user has a name for this person rather than only a key. */
    val isNamed: Boolean get() = identity.displayName.isNotEmpty()

    /**
     * Whether a message to this contact can be expected to move immediately.
     *
     * Blocked contacts are never reachable regardless of transport state: the block is a local
     * decision the network has no say in.
     */
    val canReachNow: Boolean get() = !isBlocked && !isSuperseded && presence.isReachableNow

    companion object {
        fun from(stored: StoredIdentity, presence: PresenceState = PresenceState.UNKNOWN): Contact =
            Contact(
                identity = stored.identity,
                isSaved = stored.isContact,
                isBlocked = stored.isBlocked,
                firstSeenAtMillis = stored.firstSeenAtMillis,
                lastSeenAtMillis = stored.lastSeenAtMillis,
                presence = presence
            )
    }
}
