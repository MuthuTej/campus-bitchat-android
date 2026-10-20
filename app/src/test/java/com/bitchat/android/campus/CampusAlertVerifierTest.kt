package com.bitchat.android.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-signature trust chain. A student relaying alert packets must not be able to author one.
 */
class CampusAlertVerifierTest {

    private val institution = CampusTestKeys.generate()
    private val anchors = listOf(institution.publicHex)
    private val now = 1_700_000_500L

    private fun professorEnvelope(
        title: String = "Venue Change",
        body: String = "Physics 201 has moved to Room 302.",
        timestamp: Long = 1_700_000_100L
    ): Pair<CampusTestKeys.KeyPair, String> {
        val professor = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, professor, "Priya Sharma", CampusRole.PROFESSOR
        )
        return professor to CampusTestKeys.signedAlertEnvelope(
            professor, credential, title, body, timestamp
        )
    }

    @Test
    fun `alert from a verified professor is accepted`() {
        val (_, envelope) = professorEnvelope()

        val result = CampusAlertVerifier.verify(envelope, anchors, now)

        assertTrue(result is CampusAlertVerifier.Result.Verified)
        result as CampusAlertVerifier.Result.Verified
        assertEquals("Priya Sharma", result.publisher.displayName)
        assertEquals("Physics 201 has moved to Room 302.", result.alert.body)
    }

    @Test
    fun `student cannot publish an alert even with a genuine credential`() {
        val student = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, student, "Arjun Kumar", CampusRole.STUDENT
        )
        val envelope = CampusTestKeys.signedAlertEnvelope(
            student, credential, "All classes cancelled", "Go home"
        )

        val result = CampusAlertVerifier.verify(envelope, anchors, now)

        assertEquals(CampusAlertVerifier.Result.UnauthorizedRole(CampusRole.STUDENT), result)
    }

    @Test
    fun `body edited after signing is rejected`() {
        val (_, envelope) = professorEnvelope(body = "Exam starts at 09:00")
        val (alert, credential) = CampusAlert.decode(envelope)!!
        val tampered = alert.copy(body = "Exam starts at 15:00").encode(credential)

        assertEquals(
            CampusAlertVerifier.Result.BadAlertSignature,
            CampusAlertVerifier.verify(tampered, anchors, now)
        )
    }

    @Test
    fun `timestamp edited after signing is rejected`() {
        val (_, envelope) = professorEnvelope()
        val (alert, credential) = CampusAlert.decode(envelope)!!
        val tampered = alert.copy(timestampEpochSeconds = alert.timestampEpochSeconds + 3600)
            .encode(credential)

        assertEquals(
            CampusAlertVerifier.Result.BadAlertSignature,
            CampusAlertVerifier.verify(tampered, anchors, now)
        )
    }

    @Test
    fun `title edited after signing is rejected`() {
        val (_, envelope) = professorEnvelope()
        val (alert, credential) = CampusAlert.decode(envelope)!!
        val tampered = alert.copy(title = "Emergency Evacuation").encode(credential)

        assertEquals(
            CampusAlertVerifier.Result.BadAlertSignature,
            CampusAlertVerifier.verify(tampered, anchors, now)
        )
    }

    @Test
    fun `stapling a professor credential onto your own alert is rejected`() {
        // The classic forgery: sign your own alert, then attach a real professor's credential
        // harvested from a legitimate broadcast.
        val professor = CampusTestKeys.generate()
        val professorCredential = CampusTestKeys.issueCredential(
            institution, professor, "Priya Sharma", CampusRole.PROFESSOR
        )
        val attacker = CampusTestKeys.generate()
        val forged = CampusTestKeys.signedAlertEnvelope(
            attacker, professorCredential, "Class cancelled", "No lecture today"
        )

        assertEquals(
            CampusAlertVerifier.Result.IdentityMismatch,
            CampusAlertVerifier.verify(forged, anchors, now)
        )
    }

    @Test
    fun `alert whose credential comes from an untrusted issuer is rejected`() {
        val fakeInstitution = CampusTestKeys.generate()
        val holder = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            fakeInstitution, holder, "Priya Sharma", CampusRole.PROFESSOR
        )
        val envelope = CampusTestKeys.signedAlertEnvelope(holder, credential, "Fake", "Fake alert")

        assertEquals(
            CampusAlertVerifier.Result.UntrustedPublisher,
            CampusAlertVerifier.verify(envelope, anchors, now)
        )
    }

    @Test
    fun `expired alert is not displayed`() {
        val professor = CampusTestKeys.generate()
        val credential = CampusTestKeys.issueCredential(
            institution, professor, "Priya Sharma", CampusRole.PROFESSOR
        )
        val envelope = CampusTestKeys.signedAlertEnvelope(
            professor, credential, "Old news", "Yesterday's notice",
            timestamp = 1_700_000_000L, expiresAt = 1_700_000_100L
        )

        assertEquals(
            CampusAlertVerifier.Result.AlertExpired,
            CampusAlertVerifier.verify(envelope, anchors, now)
        )
    }

    @Test
    fun `an alert dated too far in the future is rejected`() {
        val (_, envelope) = professorEnvelope(timestamp = now + CampusConfig.maxAlertClockSkewSeconds + 60)

        assertEquals(
            CampusAlertVerifier.Result.NotYetValid,
            CampusAlertVerifier.verify(envelope, anchors, now)
        )
    }

    @Test
    fun `plain text is not treated as an alert`() {
        assertEquals(
            CampusAlertVerifier.Result.NotAnAlert,
            CampusAlertVerifier.verify("Fire drill at noon", anchors, now)
        )
    }

    @Test
    fun `malformed envelopes are rejected rather than crashing`() {
        listOf(
            CampusAlert.ENVELOPE_PREFIX,
            CampusAlert.ENVELOPE_PREFIX + "not-base64!!",
            CampusAlert.ENVELOPE_PREFIX + CampusCanonical.b64(byteArrayOf(1, 2, 3)),
            CampusAlert.ENVELOPE_PREFIX + CampusCanonical.b64(CampusCanonical.encode("WRONG", "x"))
        ).forEach { input ->
            val result = CampusAlertVerifier.verify(input, anchors, now)
            assertEquals("input=$input", CampusAlertVerifier.Result.Malformed, result)
        }
    }

    @Test
    fun `truncated signature is rejected`() {
        val (_, envelope) = professorEnvelope()
        val (alert, credential) = CampusAlert.decode(envelope)!!
        val truncated = alert.copy(signature = alert.signature.copyOf(32)).encode(credential)

        assertEquals(
            CampusAlertVerifier.Result.Malformed,
            CampusAlertVerifier.verify(truncated, anchors, now)
        )
    }
}
