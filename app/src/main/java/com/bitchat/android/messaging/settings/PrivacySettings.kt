package com.bitchat.android.messaging.settings

/**
 * Choices the user has made about what other people may do with them.
 *
 * Every field here is enforced in application logic — each one is checked on a path that can refuse
 * an action, not merely on a screen that can hide a button. A setting that only changed what was
 * displayed would be worse than no setting, because it would report a protection the user does not
 * actually have.
 *
 * Settings that could not be enforced honestly are deliberately absent rather than present and
 * inert; see [PrivacyPolicy] for what is and is not decidable at this layer.
 */
data class PrivacySettings(
    /**
     * Accept direct messages from people who are not saved contacts.
     *
     * When false, a message from an unknown identity is discarded on arrival rather than shown and
     * then hidden — the sender gets no acknowledgement and no conversation is created.
     */
    val allowMessagesFromUnknown: Boolean = true,

    /** Allow other people to add this user to their groups. */
    val allowGroupInvitations: Boolean = true,

    /** Accept incoming file offers. When false, offers are refused before any bytes are accepted. */
    val allowFileTransfers: Boolean = true,

    /**
     * Include the user's phone number when sharing their own contact details.
     *
     * Off by default. The number is never required to reach anyone — the public key is the address —
     * so sharing it is always an extra disclosure the user opts into rather than out of.
     */
    val sharePhoneNumberWithContacts: Boolean = false
) {
    companion object {
        /** The defaults a new install starts with: usable, and disclosing nothing extra. */
        val DEFAULT = PrivacySettings()
    }
}

/**
 * Applies [PrivacySettings] to the decisions that can honestly be made here.
 *
 * ## What this layer can and cannot enforce
 *
 * Everything below is a decision about what *this device* does with something that has already
 * arrived, or about what it chooses to send. Those are enforceable, and are enforced.
 *
 * What this layer cannot do is make the device invisible. Being discoverable, announcing a nickname,
 * and answering a handshake all happen in the transport, below this code and before any of it runs.
 * A "hide me from nearby people" switch therefore cannot be honoured here, and is not offered: a
 * toggle that left the device announcing itself exactly as before would be a false promise, and this
 * file deliberately has no such field. Supporting it would mean changing announcement behaviour in
 * the mesh service itself, which is out of scope for the application layer.
 *
 * For the same reason there is no "hide my last seen" switch. When someone is in radio range, their
 * presence is observable by anyone in range; suppressing it in one app's interface would not conceal
 * it, only misreport it.
 */
class PrivacyPolicy(
    private val settingsProvider: () -> PrivacySettings,
    private val isKnownContact: (String) -> Boolean
) {

    private val settings: PrivacySettings get() = settingsProvider()

    /**
     * Whether an arriving direct message may be accepted.
     *
     * [senderKeyHex] is the *verified* sender; this check runs after admission, so it never has to
     * consider whether the claim was genuine.
     */
    fun mayAcceptMessageFrom(senderKeyHex: String): Boolean =
        settings.allowMessagesFromUnknown || isKnownContact(senderKeyHex)

    /** Whether this user may be added to a group by [inviterKeyHex]. */
    fun mayBeAddedToGroupBy(inviterKeyHex: String): Boolean = when {
        settings.allowGroupInvitations -> true
        // A saved contact adding the user is not an unsolicited invitation.
        else -> isKnownContact(inviterKeyHex)
    }

    /** Whether an incoming file offer from [senderKeyHex] may be accepted. */
    fun mayAcceptFileFrom(senderKeyHex: String): Boolean = when {
        !settings.allowFileTransfers -> false
        settings.allowMessagesFromUnknown -> true
        else -> isKnownContact(senderKeyHex)
    }

    /** Whether the user's phone number may be included when sharing their own details. */
    fun mayShareOwnPhoneNumber(): Boolean = settings.sharePhoneNumberWithContacts
}
