package com.bitchat.android.messaging.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the profile card — the only thing carrying a phone number, and therefore the only thing
 * that can make a person recognisable across a change of device identity.
 */
class ProfileCardTest {

    @Test
    fun `a card round trips`() {
        val card = ProfileCard("Muthuraj", "+91 98765 43210")
        assertEquals(card, ProfileCard.decode(card.encode()))
    }

    @Test
    fun `a card without a phone number round trips`() {
        val card = ProfileCard("Priya", null)
        assertEquals(card, ProfileCard.decode(card.encode()))
    }

    @Test
    fun `an empty phone number decodes as absent rather than blank`() {
        assertNull(ProfileCard.decode(ProfileCard("Priya", "").encode())?.phoneNumber)
        assertNull(ProfileCard.decode(ProfileCard("Priya", "   ").encode())?.phoneNumber)
    }

    @Test
    fun `a card fits comfortably inside a private message`() {
        // Cards travel the same 255-byte path as messages, so an unsendable card would simply
        // vanish the way oversized messages once did.
        val card = ProfileCard("A".repeat(64), "+".repeat(31))
        val size = card.encode().toByteArray(Charsets.UTF_8).size
        assertTrue("card took $size bytes", size <= MessagingEnvelope.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `ordinary text is not mistaken for a card`() {
        assertFalse(ProfileCard.looksLikeProfileCard("Hello there"))
        assertNull(ProfileCard.decode("Hello there"))
    }

    @Test
    fun `a malformed card is rejected whole`() {
        assertNull(ProfileCard.decode(ProfileCard.PREFIX + "!!!not base64!!!"))
        assertNull(ProfileCard.decode(ProfileCard.PREFIX + Canonical.b64(Canonical.encode("only-one"))))
    }

    @Test
    fun `a phone field carrying arbitrary text is refused`() {
        // Otherwise a peer could put anything they liked into a field rendered beside a name.
        val hostile = ProfileCard.PREFIX + Canonical.b64(
            Canonical.encode("Priya", "Verified Administrator")
        )
        assertNull(ProfileCard.decode(hostile))
    }

    @Test
    fun `oversized fields are bounded rather than stored whole`() {
        val huge = ProfileCard.PREFIX + Canonical.b64(
            Canonical.encode("N".repeat(5000), "1".repeat(5000))
        )
        val decoded = ProfileCard.decode(huge)
        assertTrue(decoded == null || decoded.displayName.length <= 64)
        assertTrue(decoded == null || (decoded.phoneNumber?.length ?: 0) <= 32)
    }
}
