package com.bitchat.android.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The impersonation surface. Every test here is a thing a student might try in order to be rendered
 * as a professor.
 */
class CampusIdentityTest {

    private val institution = CampusTestKeys.generate()
    private val anchors = listOf(institution.publicHex)

    @Test
    fun `valid institutional credential is accepted`() {
        val professor = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, professor, "Priya Sharma", CampusRole.PROFESSOR
        )

        val result = CampusCredentialVerifier.verify(credential, anchors, nowEpochSeconds = 1_700_000_500L)

        assertTrue(result is CampusCredentialVerifier.Result.Verified)
        assertEquals("Priya Sharma", (result as CampusCredentialVerifier.Result.Verified).credential.displayName)
    }

    @Test
    fun `credential signed by an untrusted key is rejected`() {
        val impostorInstitution = CampusTestKeys.generate()
        val holder = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            impostorInstitution, holder, "Priya Sharma", CampusRole.PROFESSOR
        )

        assertEquals(
            CampusCredentialVerifier.Result.UntrustedIssuer,
            CampusCredentialVerifier.verify(credential, anchors, 1_700_000_500L)
        )
    }

    @Test
    fun `changing the name after issuance invalidates the credential`() {
        val holder = CampusTestKeys.generate()
        val genuine = CampusTestKeys.issueCredential(
            institution, holder, "Arjun Kumar", CampusRole.STUDENT
        )
        val tampered = genuine.copy(displayName = "Priya Sharma")

        assertEquals(
            CampusCredentialVerifier.Result.UntrustedIssuer,
            CampusCredentialVerifier.verify(tampered, anchors, 1_700_000_500L)
        )
    }

    @Test
    fun `promoting a student credential to professor invalidates it`() {
        val student = CampusTestKeys.generate()
        val genuine = CampusTestKeys.issueCredential(
            institution, student, "Arjun Kumar", CampusRole.STUDENT
        )
        val promoted = genuine.copy(role = CampusRole.PROFESSOR)

        assertEquals(
            CampusCredentialVerifier.Result.UntrustedIssuer,
            CampusCredentialVerifier.verify(promoted, anchors, 1_700_000_500L)
        )
    }

    @Test
    fun `stealing a professor credential for your own key invalidates it`() {
        val professor = CampusTestKeys.generate()
        val attacker = CampusTestKeys.generate()
        val genuine = CampusTestKeys.issueCredential(
            institution, professor, "Priya Sharma", CampusRole.PROFESSOR
        )
        val rebound = genuine.copy(signingPublicKeyHex = attacker.publicHex)

        assertEquals(
            CampusCredentialVerifier.Result.UntrustedIssuer,
            CampusCredentialVerifier.verify(rebound, anchors, 1_700_000_500L)
        )
    }

    @Test
    fun `expired credential is rejected`() {
        val holder = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, holder, "Priya Sharma", CampusRole.PROFESSOR, expiresAt = 1_700_000_200L
        )

        assertEquals(
            CampusCredentialVerifier.Result.Expired,
            CampusCredentialVerifier.verify(credential, anchors, nowEpochSeconds = 1_700_000_999L)
        )
    }

    @Test
    fun `empty trust anchor list verifies nothing`() {
        val holder = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, holder, "Priya Sharma", CampusRole.PROFESSOR
        )

        assertEquals(
            CampusCredentialVerifier.Result.UntrustedIssuer,
            CampusCredentialVerifier.verify(credential, emptyList(), 1_700_000_500L)
        )
    }

    @Test
    fun `credential survives an encode decode round trip`() {
        val holder = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, holder, "Meera Raj", CampusRole.ADMIN, department = "IT"
        )

        val decoded = CampusCredential.decode(credential.encode())

        assertEquals(credential, decoded)
    }

    @Test
    fun `registry resolves verified identities and refuses unverified ones`() {
        val professor = CampusTestKeys.generate()
        val stranger = CampusTestKeys.generate()
        val registry = CampusIdentityRegistry(anchors) { 1_700_000_500L }
        registry.learn(
            CampusTestKeys.issueCredential(institution, professor, "Priya Sharma", CampusRole.PROFESSOR)
        )

        val known = registry.identityForSigningKey(professor.publicHex)
        val unknown = registry.identityForSigningKey(stranger.publicHex, claimedNickname = "Professor Priya")

        assertTrue(known is CampusIdentityRegistry.Identity.Verified)
        assertEquals(CampusRole.PROFESSOR, (known as CampusIdentityRegistry.Identity.Verified).role)
        assertTrue(unknown is CampusIdentityRegistry.Identity.Unverified)
    }

    @Test
    fun `canonical encoding is injective across field boundaries`() {
        // Without length prefixing these two would canonicalise identically and one credential's
        // signature would validate the other.
        assertNotEquals(
            CampusCanonical.encode("ab", "c").toList(),
            CampusCanonical.encode("a", "bc").toList()
        )
    }

    @Test
    fun `roles gate alert publication`() {
        assertTrue(CampusRole.PROFESSOR.canPublishAlerts)
        assertTrue(CampusRole.ADMIN.canPublishAlerts)
        assertEquals(false, CampusRole.STUDENT.canPublishAlerts)
        assertTrue(CampusRole.ADMIN.canManageConfiguration)
        assertEquals(false, CampusRole.PROFESSOR.canManageConfiguration)
    }
}
