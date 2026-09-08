package com.bitchat.android.campus

import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.identity.VerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers credential revocation and how it surfaces to the messenger.
 *
 * Revocation exists because a withdrawn credential still carries a perfectly valid signature. Every
 * test here is therefore about a credential that would verify if nobody had cancelled it.
 */
class CampusRevocationTest {

    private val institution = CampusTestKeys.generate()
    private val professor = CampusTestKeys.generate()
    private val student = CampusTestKeys.generate()

    private val anchors = listOf(institution.publicHex)

    private val professorCredential = CampusTestKeys.issueCredential(
        institution = institution,
        holder = professor,
        displayName = "Dr Arun Kumar",
        role = CampusRole.PROFESSOR,
        department = "Computer Science"
    )

    // MARK: - Credential verification

    @Test
    fun `a credential verifies while it is not revoked`() {
        val result = CampusCredentialVerifier.verify(
            professorCredential, anchors, revokedKeysHex = emptySet()
        )
        assertTrue(result is CampusCredentialVerifier.Result.Verified)
    }

    @Test
    fun `a revoked credential is refused even though its signature is still valid`() {
        val result = CampusCredentialVerifier.verify(
            professorCredential, anchors, revokedKeysHex = setOf(professor.publicHex)
        )
        assertEquals(CampusCredentialVerifier.Result.Revoked, result)
    }

    @Test
    fun `revocation is checked before the signature so there is no window of validity`() {
        // The signature is genuine; only the revocation list stands between it and acceptance.
        val revoked = CampusCredentialVerifier.verify(
            professorCredential, anchors, revokedKeysHex = setOf(professor.publicHex)
        )
        assertEquals(CampusCredentialVerifier.Result.Revoked, revoked)
    }

    @Test
    fun `revocation matching is case insensitive`() {
        val result = CampusCredentialVerifier.verify(
            professorCredential, anchors,
            revokedKeysHex = setOf(professor.publicHex.uppercase().lowercase())
        )
        assertEquals(CampusCredentialVerifier.Result.Revoked, result)
    }

    @Test
    fun `revoking one key does not affect another`() {
        val studentCredential = CampusTestKeys.issueCredential(
            institution, student, "Priya", CampusRole.STUDENT
        )
        val result = CampusCredentialVerifier.verify(
            studentCredential, anchors, revokedKeysHex = setOf(professor.publicHex)
        )
        assertTrue(result is CampusCredentialVerifier.Result.Verified)
    }

    @Test
    fun `an empty revocation list revokes nothing`() {
        assertTrue(
            CampusCredentialVerifier.verify(professorCredential, anchors, revokedKeysHex = emptySet())
                is CampusCredentialVerifier.Result.Verified
        )
    }

    // MARK: - Alerts

    @Test
    fun `an otherwise genuine alert verifies when nothing is revoked`() {
        // Establishes the baseline the revocation test below depends on: without revocation this
        // exact envelope is accepted, so a later rejection can only be the revocation acting.
        val envelope = CampusTestKeys.signedAlertEnvelope(
            holder = professor,
            credential = professorCredential,
            title = "Campus evacuation",
            body = "Leave the building immediately."
        )

        val result = CampusAlertVerifier.verify(
            envelope, anchors, nowEpochSeconds = 1_700_000_200L
        )

        assertTrue(result is CampusAlertVerifier.Result.Verified)
    }

    @Test
    fun `a revoked publisher cannot publish once the key is listed`() {
        val envelope = CampusTestKeys.signedAlertEnvelope(
            holder = professor,
            credential = professorCredential,
            title = "Campus evacuation",
            body = "Leave the building immediately."
        )

        // Drives the real verifier, not a re-derived chain: a stolen professor key is exactly what
        // someone would use to send a false evacuation notice, so this path must be the one that
        // production runs.
        val result = CampusAlertVerifier.verify(
            envelope,
            anchors,
            nowEpochSeconds = 1_700_000_200L,
            revokedKeysHex = setOf(professor.publicHex)
        )

        assertEquals(CampusAlertVerifier.Result.PublisherRevoked, result)
    }

    @Test
    fun `revoking an unrelated key leaves a genuine alert publishable`() {
        val envelope = CampusTestKeys.signedAlertEnvelope(
            holder = professor,
            credential = professorCredential,
            title = "Library closing early",
            body = "The library closes at 6pm today."
        )

        val result = CampusAlertVerifier.verify(
            envelope,
            anchors,
            nowEpochSeconds = 1_700_000_200L,
            revokedKeysHex = setOf(student.publicHex)
        )

        assertTrue(result is CampusAlertVerifier.Result.Verified)
    }

    // MARK: - How it surfaces to the messenger

    @Test
    fun `a verified credential becomes a verified attestation`() {
        val registry = CampusIdentityRegistry(anchors)
        registry.learn(professorCredential)
        val provider = CampusAttestationProvider(registry, institutionName = "Example Institute")

        val attestation = provider.attestationFor(
            MessagingPublicKey.parse(professor.publicHex)!!
        )

        assertNotNull(attestation)
        assertEquals(VerificationState.VERIFIED, attestation?.state)
        assertEquals("Dr Arun Kumar", attestation?.verifiedName)
        assertTrue(attestation?.subtitle?.contains("Professor") == true)
        assertTrue(attestation?.subtitle?.contains("Computer Science") == true)
    }

    @Test
    fun `the attestation uses the registry's trust anchors not a compiled-in default`() {
        // The registry is configured with a test anchor while the compiled-in list is empty. If the
        // provider re-verified with its own defaults, this genuine credential would read as a
        // forgery — which is exactly what it did before the check was moved into the registry.
        assertTrue(
            "precondition: the compiled-in anchors must not contain the test institution",
            CampusConfig.trustedInstitutionKeys.isEmpty()
        )

        val registry = CampusIdentityRegistry(anchors)
        registry.learn(professorCredential)

        val attestation = CampusAttestationProvider(registry)
            .attestationFor(MessagingPublicKey.parse(professor.publicHex)!!)

        assertEquals(VerificationState.VERIFIED, attestation?.state)
    }

    @Test
    fun `a forged credential cannot displace one that already verified`() {
        val registry = CampusIdentityRegistry(anchors)
        registry.learn(professorCredential)

        // Anyone can relay a credential naming someone else's key; doing so must not demote them.
        val impostor = CampusTestKeys.generate()
        registry.learn(
            CampusTestKeys.issueCredential(impostor, professor, "Not A Professor", CampusRole.STUDENT)
        )

        val attestation = CampusAttestationProvider(registry)
            .attestationFor(MessagingPublicKey.parse(professor.publicHex)!!)

        assertEquals(VerificationState.VERIFIED, attestation?.state)
        assertEquals("Dr Arun Kumar", attestation?.verifiedName)
    }

    @Test
    fun `someone who claimed nothing gets no badge at all`() {
        val registry = CampusIdentityRegistry(anchors)
        val provider = CampusAttestationProvider(registry)

        // Most people are in this state; it must not render as a warning.
        assertNull(provider.attestationFor(MessagingPublicKey.parse(student.publicHex)!!))
    }

    @Test
    fun `a forged credential is reported as invalid rather than ignored`() {
        val impostor = CampusTestKeys.generate()
        val forged = CampusTestKeys.issueCredential(
            institution = impostor,           // not a trusted anchor
            holder = professor,
            displayName = "Dr Arun Kumar",
            role = CampusRole.PROFESSOR
        )
        val registry = CampusIdentityRegistry(anchors)
        registry.learn(forged)
        val provider = CampusAttestationProvider(registry)

        val attestation = provider.attestationFor(MessagingPublicKey.parse(professor.publicHex)!!)

        assertEquals(VerificationState.INVALID, attestation?.state)
        assertTrue(attestation?.state?.isSuspicious == true)
    }

    @Test
    fun `a failed credential never carries its claimed name or role through`() {
        val impostor = CampusTestKeys.generate()
        val forged = CampusTestKeys.issueCredential(
            institution = impostor,
            holder = professor,
            displayName = "Dr Arun Kumar",
            role = CampusRole.ADMIN
        )
        val registry = CampusIdentityRegistry(anchors)
        registry.learn(forged)
        val provider = CampusAttestationProvider(registry)

        val attestation = provider.attestationFor(MessagingPublicKey.parse(professor.publicHex)!!)!!

        // The name and role are the very things being claimed, so a failed claim must surrender
        // both rather than displaying them beside a warning.
        assertEquals("", attestation.verifiedName)
        assertFalse(attestation.subtitle.contains("Arun"))
        assertFalse(attestation.subtitle.contains("Admin"))
    }

    @Test
    fun `the registry distinguishes claiming nothing from claiming and failing`() {
        val impostor = CampusTestKeys.generate()
        val registry = CampusIdentityRegistry(anchors)

        assertFalse(registry.hasSeenCredentialFor(professor.publicHex))

        registry.learn(
            CampusTestKeys.issueCredential(impostor, professor, "Fake", CampusRole.PROFESSOR)
        )

        assertTrue(registry.hasSeenCredentialFor(professor.publicHex))
    }

    @Test
    fun `verification states separate the ordinary case from the suspicious ones`() {
        assertFalse(VerificationState.UNVERIFIED.isSuspicious)
        assertTrue(VerificationState.INVALID.isSuspicious)
        assertTrue(VerificationState.REVOKED.isSuspicious)
        assertTrue(VerificationState.VERIFIED.isTrustworthy)
        assertFalse(VerificationState.UNVERIFIED.isTrustworthy)
    }
}
