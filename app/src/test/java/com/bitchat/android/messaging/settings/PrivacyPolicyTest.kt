package com.bitchat.android.messaging.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers privacy settings as enforcement rather than presentation.
 *
 * Each test drives the decision the application layer actually makes, so a setting that stopped
 * affecting behaviour would fail here rather than silently becoming decorative.
 */
class PrivacyPolicyTest {

    private val knownContact = "contact-key"
    private val stranger = "stranger-key"

    private var settings = PrivacySettings.DEFAULT

    private val policy = PrivacyPolicy(
        settingsProvider = { settings },
        isKnownContact = { it == knownContact }
    )

    // MARK: - Messages from unknown people

    @Test
    fun `by default messages from anyone are accepted`() {
        assertTrue(policy.mayAcceptMessageFrom(stranger))
        assertTrue(policy.mayAcceptMessageFrom(knownContact))
    }

    @Test
    fun `refusing unknown senders blocks strangers but not contacts`() {
        settings = settings.copy(allowMessagesFromUnknown = false)

        assertFalse("a stranger's message must be refused", policy.mayAcceptMessageFrom(stranger))
        assertTrue("a saved contact is still allowed", policy.mayAcceptMessageFrom(knownContact))
    }

    // MARK: - Group invitations

    @Test
    fun `by default anyone may add the user to a group`() {
        assertTrue(policy.mayBeAddedToGroupBy(stranger))
    }

    @Test
    fun `refusing invitations blocks strangers but not contacts`() {
        settings = settings.copy(allowGroupInvitations = false)

        assertFalse(policy.mayBeAddedToGroupBy(stranger))
        // Being added by someone already saved is not an unsolicited invitation.
        assertTrue(policy.mayBeAddedToGroupBy(knownContact))
    }

    // MARK: - File transfers

    @Test
    fun `by default file offers are accepted`() {
        assertTrue(policy.mayAcceptFileFrom(stranger))
    }

    @Test
    fun `disabling file transfers refuses everyone including contacts`() {
        settings = settings.copy(allowFileTransfers = false)

        assertFalse(policy.mayAcceptFileFrom(stranger))
        assertFalse(
            "the setting is about files, not about who is trusted",
            policy.mayAcceptFileFrom(knownContact)
        )
    }

    @Test
    fun `refusing unknown senders also refuses their files`() {
        settings = settings.copy(allowMessagesFromUnknown = false)

        // Otherwise a stranger blocked from messaging could still push a document.
        assertFalse(policy.mayAcceptFileFrom(stranger))
        assertTrue(policy.mayAcceptFileFrom(knownContact))
    }

    // MARK: - Phone number

    @Test
    fun `the phone number is not shared unless explicitly enabled`() {
        // Off by default: the key is always sufficient to reach someone, so the number is an extra
        // disclosure the user opts into.
        assertFalse(PrivacySettings.DEFAULT.sharePhoneNumberWithContacts)
        assertFalse(policy.mayShareOwnPhoneNumber())

        settings = settings.copy(sharePhoneNumberWithContacts = true)
        assertTrue(policy.mayShareOwnPhoneNumber())
    }

    // MARK: - Defaults

    @Test
    fun `defaults leave the app usable without disclosing anything extra`() {
        val defaults = PrivacySettings.DEFAULT
        assertTrue(defaults.allowMessagesFromUnknown)
        assertTrue(defaults.allowGroupInvitations)
        assertTrue(defaults.allowFileTransfers)
        assertFalse(defaults.sharePhoneNumberWithContacts)
    }

    @Test
    fun `settings are read live so a change takes effect immediately`() {
        assertTrue(policy.mayAcceptMessageFrom(stranger))

        settings = settings.copy(allowMessagesFromUnknown = false)

        // No re-construction: turning a setting off must apply to the very next decision.
        assertFalse(policy.mayAcceptMessageFrom(stranger))
    }
}
