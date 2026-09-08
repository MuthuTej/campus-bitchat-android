package com.bitchat.android.messaging.contacts

import com.bitchat.android.messaging.identity.IdentityRepository
import com.bitchat.android.messaging.identity.MessagingIdentity
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The user's people: everyone saved, plus everyone merely encountered.
 *
 * Owns no storage of its own. Contacts are a view over the identity store — "a contact" is an
 * identity the user chose to keep — so saving someone cannot lose the history already recorded
 * against their key, and unsaving them does not throw away a name the device legitimately learned.
 *
 * Presence is layered on top rather than stored. [attachPresence] takes a live map from the
 * presence layer, which keeps this class free of any transport dependency and keeps reachability
 * out of the database.
 */
class ContactRepository(
    private val identities: IdentityRepository,
    /**
     * Scope the derived lists are shared in. Eager sharing keeps [all] and [saved] populated even
     * with no collector, so a lookup from a send path never races an empty first emission.
     */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val presenceByKey = MutableStateFlow<Map<MessagingPublicKey, PresenceState>>(emptyMap())

    /**
     * Everyone the device knows about, saved or not, with live presence attached.
     *
     * Blocked people are included: filtering a list for display is a different concern from
     * checking whether a message may be delivered, and silently omitting blocked identities here
     * would make the second check impossible.
     */
    val all: StateFlow<List<Contact>> = combine(
        identities.identities,
        presenceByKey
    ) { stored, presence ->
        stored
            .map { Contact.from(it, presence[it.publicKey] ?: PresenceState.UNKNOWN) }
            .markSuperseded()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Only the people the user explicitly saved, ordered for display.
     *
     * Records replaced by a newer identity for the same person are left out. Showing them would
     * offer the user a contact that looks ordinary and quietly cannot receive anything.
     */
    val saved: StateFlow<List<Contact>> = all
        .map { contacts ->
            contacts.filter { it.isSaved && !it.isSuperseded }.sortedForDisplay()
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Publish the current reachability picture. Called by the presence layer. */
    fun attachPresence(presence: Map<MessagingPublicKey, PresenceState>) {
        presenceByKey.value = presence
    }

    fun find(publicKey: MessagingPublicKey): Contact? =
        all.value.firstOrNull { it.publicKey == publicKey }

    /**
     * Save someone to the contact list.
     *
     * Remembers the identity first, so saving a person met moments ago works without requiring the
     * caller to have persisted them separately.
     */
    suspend fun save(identity: MessagingIdentity): Boolean {
        identities.remember(identity)
        return identities.setContact(identity.publicKey, true)
    }

    suspend fun save(publicKey: MessagingPublicKey): Boolean =
        identities.setContact(publicKey, true)

    /**
     * Remove someone from the contact list.
     *
     * The identity is kept, not deleted: messages already exchanged with that key remain
     * attributable, and the person reappearing later should not look like a stranger.
     */
    suspend fun unsave(publicKey: MessagingPublicKey): Boolean =
        identities.setContact(publicKey, false)

    suspend fun setBlocked(publicKey: MessagingPublicKey, blocked: Boolean): Boolean =
        identities.setBlocked(publicKey, blocked)

    suspend fun rename(publicKey: MessagingPublicKey, displayName: String): Boolean =
        identities.setDisplayName(publicKey, displayName)

    /** Forget someone entirely, including the personal fields stored for them. */
    suspend fun forget(publicKey: MessagingPublicKey): Boolean = identities.forget(publicKey)

    fun isBlocked(publicKey: MessagingPublicKey): Boolean = find(publicKey)?.isBlocked == true

    private companion object {
        /**
         * Flag records that a newer identity has replaced.
         *
         * Two records naming the same phone number are the same person, so only the most recently
         * seen of them is live. The comparison is deliberately confined to the phone number: it is
         * the one field the person chose rather than their device generated, and matching on a
         * display name instead would let anyone displace a contact by copying their name.
         *
         * Nothing is deleted. The older record is kept so past messages stay attributable, and so
         * that a mistaken match is a hidden row rather than lost history.
         */
        fun List<Contact>.markSuperseded(): List<Contact> {
            val newestByPhone = asSequence()
                .mapNotNull { contact ->
                    contact.identity.phoneNumber
                        ?.filter { !it.isWhitespace() }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { it to contact }
                }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }
                .mapValues { (_, sharing) -> sharing.maxBy { it.lastSeenAtMillis }.publicKey }

            if (newestByPhone.isEmpty()) return this

            return map { contact ->
                val phone = contact.identity.phoneNumber
                    ?.filter { !it.isWhitespace() }
                    ?.takeIf { it.isNotEmpty() }
                val winner = phone?.let(newestByPhone::get)
                if (winner != null && winner != contact.publicKey) {
                    contact.copy(isSuperseded = true)
                } else {
                    contact
                }
            }
        }

        /**
         * Reachable first, then named before anonymous, then alphabetically.
         *
         * A contact list exists to start a conversation, so the people who can actually be reached
         * right now belong at the top rather than whoever happens to sort first.
         */
        fun List<Contact>.sortedForDisplay(): List<Contact> = sortedWith(
            compareByDescending<Contact> { it.canReachNow }
                .thenByDescending { it.isNamed }
                .thenBy { it.displayName.lowercase() }
        )
    }
}
