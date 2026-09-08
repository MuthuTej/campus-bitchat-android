package com.bitchat.android.messaging.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the identity value types, which are the boundary every remote-supplied name and key
 * crosses before it reaches storage or the screen.
 */
class MessagingIdentityTest {

    private val validKey = "a".repeat(64)

    // MARK: - Public key parsing

    @Test
    fun `a well formed key parses`() {
        val key = MessagingPublicKey.parse(validKey)
        assertNotNull(key)
        assertEquals(validKey, key?.hex)
    }

    @Test
    fun `keys are normalised to lowercase so the same key compares equal`() {
        val upper = MessagingPublicKey.parse("AB".repeat(32))
        val lower = MessagingPublicKey.parse("ab".repeat(32))
        assertEquals(lower, upper)
        assertEquals("ab".repeat(32), upper?.hex)
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(validKey, MessagingPublicKey.parse("  $validKey\n")?.hex)
    }

    @Test
    fun `malformed keys are refused rather than truncated or padded`() {
        assertNull("null", MessagingPublicKey.parse(null))
        assertNull("empty", MessagingPublicKey.parse(""))
        assertNull("too short", MessagingPublicKey.parse("a".repeat(63)))
        assertNull("too long", MessagingPublicKey.parse("a".repeat(65)))
        assertNull("non hex", MessagingPublicKey.parse("g".repeat(64)))
        assertNull("hex with spaces inside", MessagingPublicKey.parse("a".repeat(32) + " " + "a".repeat(31)))
    }

    @Test
    fun `short form is a readable prefix of the key`() {
        val key = MessagingPublicKey.parse("0123456789abcdef".repeat(4))
        assertEquals("0123 4567 89ab cdef", key?.shortForm())
    }

    // MARK: - Display names

    @Test
    fun `display name keeps ordinary text intact`() {
        assertEquals("Priya Sharma", MessagingIdentity.sanitizeDisplayName("Priya Sharma"))
        assertEquals("Dr. Arun Kumar", MessagingIdentity.sanitizeDisplayName("  Dr. Arun Kumar  "))
    }

    @Test
    fun `display name strips newlines that would break a list row`() {
        assertEquals(
            "PriyaAdmin",
            MessagingIdentity.sanitizeDisplayName("Priya\nAdmin")
        )
        assertEquals("ab", MessagingIdentity.sanitizeDisplayName("a\tb"))
    }

    @Test
    fun `display name strips bidirectional and invisible marks`() {
        // A right-to-left override can make one name render as another.
        assertEquals("admin", MessagingIdentity.sanitizeDisplayName("\u202Eadmin"))
        assertEquals("ab", MessagingIdentity.sanitizeDisplayName("a\u200Bb"))
        assertEquals("ab", MessagingIdentity.sanitizeDisplayName("a\u200Eb"))
        assertEquals("ab", MessagingIdentity.sanitizeDisplayName("a\u2066b"))
        assertEquals("ab", MessagingIdentity.sanitizeDisplayName("a\uFEFFb"))
    }

    @Test
    fun `display name is bounded so a remote peer cannot flood the list`() {
        val long = "x".repeat(500)
        val sanitized = MessagingIdentity.sanitizeDisplayName(long)
        assertEquals(MessagingIdentity.MAX_DISPLAY_NAME_LENGTH, sanitized.length)
    }

    @Test
    fun `a name made only of invisible characters becomes empty`() {
        assertEquals("", MessagingIdentity.sanitizeDisplayName("\u200B\u202E\uFEFF"))
        assertEquals("", MessagingIdentity.sanitizeDisplayName(null))
    }

    // MARK: - Identity

    @Test
    fun `an identity with no name falls back to the key rather than inventing one`() {
        val key = MessagingPublicKey.parse(validKey)!!
        val identity = MessagingIdentity.of(key, displayName = "   ")
        assertEquals("", identity.displayName)
        assertEquals(key.shortForm(), identity.displayNameOrKey)
    }

    @Test
    fun `a named identity shows its name`() {
        val key = MessagingPublicKey.parse(validKey)!!
        assertEquals("Rahul", MessagingIdentity.of(key, "Rahul").displayNameOrKey)
    }

    @Test
    fun `a blank phone number is stored as absent`() {
        val key = MessagingPublicKey.parse(validKey)!!
        assertNull(MessagingIdentity.of(key, "A", phoneNumber = "   ").phoneNumber)
        assertNull(MessagingIdentity.of(key, "A", phoneNumber = null).phoneNumber)
        assertEquals("+911234", MessagingIdentity.of(key, "A", phoneNumber = " +911234 ").phoneNumber)
    }

    @Test
    fun `identity equality is driven by the key not the display name`() {
        val key = MessagingPublicKey.parse(validKey)!!
        val other = MessagingPublicKey.parse("b".repeat(64))!!
        // Same key, same name: equal.
        assertEquals(MessagingIdentity.of(key, "A"), MessagingIdentity.of(key, "A"))
        // Two different people cannot collide by choosing the same display name.
        assertTrue(MessagingIdentity.of(key, "A") != MessagingIdentity.of(other, "A"))
    }

    @Test
    fun `a display name cannot impersonate by carrying an override into storage`() {
        val key = MessagingPublicKey.parse(validKey)!!
        val hostile = MessagingIdentity.of(key, "Priya\u202E\u200Bnimda")
        assertTrue(
            "sanitised name must not retain formatting marks",
            hostile.displayName.none { it == '\u202E' || it == '\u200B' }
        )
    }
}
