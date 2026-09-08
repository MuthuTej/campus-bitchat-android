package com.bitchat.android.campus

import com.bitchat.android.messaging.identity.AttestationProvider
import com.bitchat.android.messaging.identity.IdentityAttestation
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.identity.VerificationState

/**
 * A campus credential, expressed in the terms the messenger understands.
 *
 * Nothing institutional escapes this class: the messaging layer receives a name, a subtitle and a
 * verification state, and never learns what a role or a department is.
 */
data class CampusAttestation(
    override val verifiedName: String,
    override val subtitle: String,
    override val state: VerificationState,
    override val issuerLabel: String,
    /** Retained for campus-specific surfaces such as alert publishing. */
    val role: CampusRole?
) : IdentityAttestation

/**
 * Supplies campus attestations to the messenger.
 *
 * ## Why this is an attachment rather than a field
 *
 * A credential is bound to an Ed25519 signing key, and a messaging identity *is* that key, so the
 * two need no mapping table — they share a key space. That makes the campus layer a lookup by
 * public key rather than something embedded in the identity, which is what allows a user with no
 * credential to be a complete, unremarkable contact rather than a half-populated record.
 *
 * ## What it will and will not say
 *
 * A name and a role are reported only when an institutional anchor signed for them. Anything else —
 * no credential, a forged one, an expired one, a withdrawn one — yields either "nothing is known"
 * or "something was presented and failed", and never a role. This is the distinction the whole
 * credential mechanism exists to enforce: a device may claim to be a professor, but only an
 * institution can make that claim true.
 */
class CampusAttestationProvider(
    private val registry: CampusIdentityRegistry,
    private val institutionName: String = CampusConfig.institutionName
) : AttestationProvider {

    /**
     * @return null when nothing has ever been claimed for this key — the ordinary case — so the
     *         messenger renders a plain contact rather than a badge announcing an absence.
     */
    override fun attestationFor(publicKey: MessagingPublicKey): IdentityAttestation? {
        // The registry owns the trust anchors and performs the check. Re-verifying here with this
        // class's own defaults is what previously made a genuine credential read as INVALID
        // whenever the registry was configured with anything other than the compiled-in anchors.
        val verdict = registry.verificationFor(publicKey.hex) ?: return null

        return when (verdict) {
            is CampusCredentialVerifier.Result.Verified -> CampusAttestation(
                verifiedName = verdict.credential.displayName,
                subtitle = subtitleFor(verdict.credential),
                state = VerificationState.VERIFIED,
                issuerLabel = institutionName,
                role = verdict.credential.role
            )

            CampusCredentialVerifier.Result.Revoked -> withdrawn(VerificationState.REVOKED)

            // Expiry, a bad signature, an unknown issuer and malformed bytes all mean the same
            // thing to a reader: a claim was presented and did not hold up. None of them may carry
            // a name or a role through, because those are exactly the fields being claimed.
            else -> withdrawn(VerificationState.INVALID)
        }
    }

    private fun withdrawn(state: VerificationState) = CampusAttestation(
        // Deliberately carries no name from the failed credential.
        verifiedName = "",
        subtitle = when (state) {
            VerificationState.REVOKED -> "Campus identity withdrawn"
            else -> "Campus identity could not be verified"
        },
        state = state,
        issuerLabel = institutionName,
        role = null
    )

    private fun subtitleFor(credential: CampusCredential): String =
        listOf(credential.role.displayLabel, credential.department)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}
