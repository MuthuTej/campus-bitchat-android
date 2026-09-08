package com.bitchat.android.messaging.identity

/**
 * How much is known about a claim someone makes about themselves.
 *
 * The four states are kept distinct because collapsing any pair of them would misinform the user.
 * In particular [UNVERIFIED] and [INVALID] are opposites in meaning: the first says nobody has
 * vouched for this person, which is the ordinary condition of most people; the second says someone
 * presented a vouching document that did not hold up, which is a reason for suspicion.
 */
enum class VerificationState {
    /** An institution the device trusts has vouched for this identity. */
    VERIFIED,

    /**
     * No claim has been made. The normal state for most people, and not a defect: an unaffiliated
     * user is a complete, ordinary contact.
     */
    UNVERIFIED,

    /** A claim was made and failed — bad signature, unknown issuer, malformed, or expired. */
    INVALID,

    /** The issuing institution has withdrawn this identity. */
    REVOKED;

    val isTrustworthy: Boolean get() = this == VERIFIED

    /** True when the user should be actively warned rather than simply told nothing is known. */
    val isSuspicious: Boolean get() = this == INVALID || this == REVOKED
}

/**
 * An institution's statement about someone, once verified.
 *
 * Deliberately minimal and free of any institutional vocabulary, so the messaging layer can render
 * an attestation without knowing what kind of organisation issued it or what a "department" is.
 */
interface IdentityAttestation {
    /** Verified name, which may differ from the self-chosen display name. */
    val verifiedName: String

    /** One short line, e.g. "Professor · Computer Science". Empty when there is nothing to add. */
    val subtitle: String

    val state: VerificationState

    /** Who vouched for this, for the profile screen. */
    val issuerLabel: String
}

/**
 * Supplies attestations for identities.
 *
 * This is the seam that keeps the messenger independent of any institution. The messaging layer
 * declares the hole; an optional layer fills it. With no provider installed every identity is
 * simply [VerificationState.UNVERIFIED] and the app works exactly as before — which is the whole
 * point, since institutional affiliation must never be a precondition for messaging.
 */
fun interface AttestationProvider {
    /** The attestation for this identity, or null when none has been offered. */
    fun attestationFor(publicKey: MessagingPublicKey): IdentityAttestation?
}
