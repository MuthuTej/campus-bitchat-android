package com.bitchat.android.campus

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * College-email check, staff promotion, bulletin retention and late pickup.
 * These are the campus behaviours that sit above the mesh transport.
 */
class CampusMeshFlowTest {

    @After
    fun clearDemoHook() {
        CampusDemoHooks.clear()
    }

    @Test
    fun `only institutional email addresses are accepted`() {
        assertTrue(CampusEmailAddress.isInstitutional("Ada.cse2024@Citchennai.net"))
        assertTrue(CampusEmailAddress.isInstitutional("ada@cs.citchennai.net"))
        assertFalse(CampusEmailAddress.isInstitutional("ada@gmail.com"))
        assertFalse(CampusEmailAddress.isInstitutional("ada@notcitchennai.net"))
        assertNull(CampusEmailAddress.normalize("not-an-email"))
    }

    @Test
    fun `email code matches only the addressed student before it expires`() {
        val challenge = CampusEmailChallenge.issue(
            "ada.cse2024@citchennai.net",
            nowEpochMillis = 1_000L,
            code = "123456"
        )!!

        assertTrue(challenge.matches("Ada.cse2024@Citchennai.net", "123456", 1_500L))
        assertFalse(challenge.matches("ada.cse2024@citchennai.net", "000000", 1_500L))
        assertFalse(challenge.matches("other@citchennai.net", "123456", 1_500L))
        assertFalse(challenge.matches("ada.cse2024@citchennai.net", "123456", challenge.expiresAtEpochMillis + 1))
    }

    @Test
    fun `issuer binds role to the device key and a student still cannot publish`() {
        val institution = CampusTestKeys.generate()
        val student = CampusTestKeys.generate()
        val issuer = CampusCredentialIssuer(institution.privateKey.encoded) { 1_700_000_000L }
        val credential = issuer.issue(
            displayName = "Ada Lovelace",
            role = CampusRole.STUDENT,
            department = "Student",
            signingPublicKeyHex = student.publicHex
        )!!

        val verified = CampusCredentialVerifier.verify(
            credential,
            listOf(issuer.publicKeyHex),
            nowEpochSeconds = 1_700_000_100L
        )
        assertTrue(verified is CampusCredentialVerifier.Result.Verified)

        val envelope = CampusTestKeys.signedAlertEnvelope(student, credential, "Cancelled", "Go home")
        assertEquals(
            CampusAlertVerifier.Result.UnauthorizedRole(CampusRole.STUDENT),
            CampusAlertVerifier.verify(envelope, listOf(issuer.publicKeyHex), 1_700_000_100L)
        )
    }

    @Test
    fun `promotion replaces a student credential and an older copy cannot demote it`() {
        val institution = CampusTestKeys.generate()
        val holder = CampusTestKeys.generate()
        val anchors = listOf(institution.publicHex)
        val registry = CampusIdentityRegistry(anchors) { 1_700_000_500L }
        val student = CampusTestKeys.issueCredential(
            institution, holder, "Ada Lovelace", CampusRole.STUDENT, issuedAt = 1_700_000_000L
        )
        val professor = CampusTestKeys.issueCredential(
            institution, holder, "Ada Lovelace", CampusRole.PROFESSOR, issuedAt = 1_700_000_400L
        )

        registry.learn(professor)
        registry.learn(student)

        val identity = registry.identityForSigningKey(holder.publicHex)
        assertEquals(CampusRole.PROFESSOR, (identity as CampusIdentityRegistry.Identity.Verified).role)

        val envelope = CampusTestKeys.signedAlertEnvelope(
            holder, professor, "Room change", "Exam 2 is in Hall B"
        )
        assertTrue(
            CampusAlertVerifier.verify(envelope, anchors, 1_700_000_500L)
                is CampusAlertVerifier.Result.Verified
        )
    }

    @Test
    fun `a newer credential may change the role including a demotion`() {
        val institution = CampusTestKeys.generate()
        val holder = CampusTestKeys.generate()
        val registry = CampusIdentityRegistry(listOf(institution.publicHex)) { 1_700_000_800L }
        registry.learn(
            CampusTestKeys.issueCredential(
                institution, holder, "Ada Lovelace", CampusRole.PROFESSOR, issuedAt = 1_700_000_100L
            )
        )
        registry.learn(
            CampusTestKeys.issueCredential(
                institution, holder, "Ada Lovelace", CampusRole.STUDENT, issuedAt = 1_700_000_700L
            )
        )

        val identity = registry.identityForSigningKey(holder.publicHex)
        assertEquals(CampusRole.STUDENT, (identity as CampusIdentityRegistry.Identity.Verified).role)
    }

    @Test
    fun `retained bulletins survive a restart until they expire`() {
        val directory = File.createTempFile("bulletins", "").apply {
            delete()
            mkdirs()
        }
        val alert = sampleAlert(expiresAt = 1_700_000_200L)
        val envelope = alert.encode(sampleCredential())
        CampusBulletinStore(File(directory, "bulletins.tsv")) { 1_700_000_100L }
            .retain(alert, envelope)

        val reloaded = CampusBulletinStore(File(directory, "bulletins.tsv")) { 1_700_000_150L }
        assertEquals(listOf(envelope), reloaded.activeEnvelopes())

        val expired = CampusBulletinStore(File(directory, "bulletins.tsv")) { 1_700_000_201L }
        assertTrue(expired.activeEnvelopes().isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun `invalid bulletins are not admitted for retention`() {
        val institution = CampusTestKeys.generate()
        val student = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, student, "Ada Lovelace", CampusRole.STUDENT
        )
        val envelope = CampusTestKeys.signedAlertEnvelope(student, credential, "Fake", "Go home")

        val decision = CampusBulletinAdmission.decide(
            envelope,
            listOf(institution.publicHex),
            nowEpochSeconds = 1_700_000_500L
        )
        assertTrue(decision is CampusBulletinAdmission.Decision.Reject)
        assertTrue(CampusBulletinAdmission.decide("hello") is CampusBulletinAdmission.Decision.Ignore)
    }

    @Test
    fun `late pickup is offered when a new peer appears and not to the same set again`() {
        val relay = CampusBulletinRelay()
        val bulletins = listOf("BCA2:one")

        assertTrue(relay.onPeers(emptySet(), bulletins).isEmpty())
        assertEquals(bulletins, relay.onPeers(setOf("peer-a"), bulletins))
        assertTrue(relay.onPeers(setOf("peer-a"), bulletins).isEmpty())
        assertEquals(bulletins, relay.onPeers(setOf("peer-a", "peer-b"), bulletins))
    }

    @Test
    fun `a peer who arrived before any bulletin is still offered the later copy`() {
        val relay = CampusBulletinRelay()
        assertTrue(relay.onPeers(setOf("peer-a"), emptyList()).isEmpty())
        assertEquals(listOf("BCA2:later"), relay.onPeers(setOf("peer-a"), listOf("BCA2:later")))
    }

    @Test
    fun `demo installer public key matches the private seed and can issue a credential`() {
        CampusDemoInstaller.install()
        val issuer = CampusDemoHooks.issuer
        assertTrue(issuer != null)
        assertEquals(CampusDemoInstaller.PUBLIC_HEX, issuer!!.publicKeyHex)
        assertTrue(CampusConfig.activeTrustAnchors.any { it.equals(issuer.publicKeyHex, true) })

        val holder = CampusTestKeys.generate()
        val credential = issuer.issue("Ada Lovelace", CampusRole.PROFESSOR, "Physics", holder.publicHex)!!
        assertTrue(
            CampusCredentialVerifier.verify(credential, CampusConfig.activeTrustAnchors)
                is CampusCredentialVerifier.Result.Verified
        )
    }

    @Test
    fun `brevo mail includes the code and does not treat a refused send as success`() {
        val mailer = CampusBrevoMailer(
            apiKey = "test-key",
            senderEmail = "office@example.com",
            senderName = "Principal, Chennai Institute of Technology",
            post = { body, _ ->
                assertTrue(body.contains("student.cse2024@citchennai.net"))
                assertTrue(body.contains("482913"))
                assertTrue(body.contains("Principal, Chennai Institute of Technology"))
                assertFalse(body.contains("test-key"))
                400 to """{"message":"sender is not valid"}"""
            }
        )
        val result = mailer.sendVerificationCode("student.cse2024@citchennai.net", "482913")
        assertEquals(
            CampusEmailSendResult.Failed("sender is not valid"),
            result
        )
    }

    @Test
    fun `brevo mail without a key does not claim the code was sent`() {
        val mailer = CampusBrevoMailer("", "", "") { _, _ ->
            error("network must not be called")
        }
        assertTrue(mailer.sendVerificationCode("ada@citchennai.net", "123456") is CampusEmailSendResult.Failed)
    }

    @Test
    fun `promotion codes map only to staff roles`() {
        assertEquals(CampusRole.PROFESSOR, CampusPromotion.roleFor("staff-demo"))
        assertEquals(CampusRole.ADMIN, CampusPromotion.roleFor("ADMIN-DEMO"))
        assertNull(CampusPromotion.roleFor("STUDENT"))
    }

    @Test
    fun `a professor bulletin signs with the key the credential names`() {
        val institution = CampusTestKeys.generate()
        val professor = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, professor, "Dr Rao", CampusRole.PROFESSOR
        )
        val signer = CampusAlertSigner(
            context = null,
            clockSeconds = { 1_700_000_100L },
            signingKeyProvider = { professor.privateKey.encoded to CampusCanonical.unhex(professor.publicHex)!! }
        )

        val outcome = signer.publish("Lab moved", "Room 204", credential)
        assertTrue(outcome is CampusAlertSigner.Outcome.Signed)
        val envelope = (outcome as CampusAlertSigner.Outcome.Signed).envelope
        assertTrue(
            CampusAlertVerifier.verify(envelope, listOf(institution.publicHex), 1_700_000_100L)
                is CampusAlertVerifier.Result.Verified
        )
    }

    @Test
    fun `signing with a different device key is refused even for a professor`() {
        val institution = CampusTestKeys.generate()
        val professor = CampusTestKeys.generate()
        val otherDevice = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, professor, "Dr Rao", CampusRole.PROFESSOR
        )
        val signer = CampusAlertSigner(
            context = null,
            clockSeconds = { 1_700_000_100L },
            signingKeyProvider = {
                otherDevice.privateKey.encoded to CampusCanonical.unhex(otherDevice.publicHex)!!
            }
        )

        assertEquals(
            CampusAlertSigner.Outcome.NotAuthorised,
            signer.publish("Lab moved", "Room 204", credential)
        )
    }

    private fun sampleCredential(): CampusCredential {
        val institution = CampusTestKeys.generate()
        val holder = CampusTestKeys.generate()
        return CampusTestKeys.issueCredential(institution, holder, "Ada Lovelace", CampusRole.PROFESSOR)
    }

    private fun sampleAlert(expiresAt: Long): CampusAlert = CampusAlert(
        alertId = "alert-retained",
        title = "Bus",
        body = "Shuttle at 4",
        timestampEpochSeconds = 1_700_000_050L,
        expiresAtEpochSeconds = expiresAt,
        signerPublicKeyHex = "ab".repeat(32),
        signature = ByteArray(64)
    )
}
