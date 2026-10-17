package com.bitchat.android.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Class segregation depends entirely on every enrolled device deriving byte-identical passwords
 * while different classes derive different ones. These tests pin that behaviour.
 */
class CampusConfigTest {

    @Test
    fun `password derivation is deterministic across calls`() {
        assertEquals(CampusConfig.passwordFor("CS101"), CampusConfig.passwordFor("CS101"))
    }

    @Test
    fun `different classes derive different keys`() {
        val codes = CampusConfig.classes.map { CampusConfig.passwordFor(it.code) }
        assertEquals("derived passwords must be unique per class", codes.size, codes.toSet().size)
    }

    @Test
    fun `derived password is a full length sha256 hex digest`() {
        val password = CampusConfig.passwordFor("CS101")
        assertEquals(64, password.length)
        assertTrue(password.all { it in "0123456789abcdef" })
    }

    @Test
    fun `derivation is bound to the institutional secret`() {
        val expected = CampusConfig.sha256Hex("CS101" + CampusConfig.INSTITUTIONAL_SECRET)
        assertEquals(expected, CampusConfig.passwordFor("CS101"))
        assertNotEquals(CampusConfig.sha256Hex("CS101"), CampusConfig.passwordFor("CS101"))
    }

    @Test
    fun `channel tags are hash prefixed and resolve back to their class`() {
        CampusConfig.classes.forEach { definition ->
            assertTrue(definition.channelTag.startsWith("#"))
            assertEquals(definition, CampusConfig.classFor(definition.channelTag))
        }
    }

    @Test
    fun `unknown channel tag resolves to null`() {
        assertNull(CampusConfig.classFor("#NotAClass"))
    }

    @Test
    fun `alerts channel is distinct from every class channel`() {
        assertTrue(CampusConfig.classes.none { it.channelTag == CampusConfig.ALERTS_CHANNEL })
    }

    @Test
    fun `an incorrect class code derives a different key`() {
        // The isolation property: a device that guesses the wrong course code cannot derive the key
        // that decrypts that class, even holding the institutional secret.
        assertNotEquals(CampusConfig.passwordFor("CS101"), CampusConfig.passwordFor("CS102"))
        assertNotEquals(CampusConfig.passwordFor("CS101"), CampusConfig.passwordFor("cs101"))
    }

    @Test
    fun `every configured class resolves and carries display metadata`() {
        CampusConfig.classes.forEach { definition ->
            assertTrue(definition.code.isNotBlank())
            assertTrue(definition.title.isNotBlank())
            assertTrue(definition.department.isNotBlank())
            assertTrue(definition.memberCount > 0)
        }
    }

    @Test
    fun `supported file types cover the required academic formats`() {
        listOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ).forEach { assertTrue(it, CampusConfig.isSupportedFileType(it)) }
    }

    @Test
    fun `unsupported file types are refused`() {
        listOf(
            "application/vnd.android.package-archive",
            "image/jpeg",
            "application/x-executable"
        ).forEach { assertEquals(it, false, CampusConfig.isSupportedFileType(it)) }
    }

    @Test
    fun `mime matching is case insensitive`() {
        assertTrue(CampusConfig.isSupportedFileType("APPLICATION/PDF"))
    }

    @Test
    fun `unprovisioned build reports itself as a demo deployment`() {
        // Guards against shipping a build that looks trustworthy but verifies nothing.
        assertEquals(CampusConfig.trustedInstitutionKeys.isEmpty(), CampusConfig.isDemoDeployment)
    }

    @Test
    fun `no institutional private key is present in configuration`() {
        // A 64-hex trust anchor is a public key; anything Ed25519-private-shaped must never appear.
        CampusConfig.trustedInstitutionKeys.forEach { key ->
            assertEquals("trust anchors must be 32-byte public keys", 64, key.length)
        }
    }
}
