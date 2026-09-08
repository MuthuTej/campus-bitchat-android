package com.bitchat.android.campus

import com.bitchat.android.BuildConfig
import java.security.MessageDigest

/**
 * Single source of institutional configuration for the campus overlay.
 *
 * Everything an institution would need to change to deploy this at their own campus lives here, so
 * a deployment is a diff to one file plus a signing ceremony rather than a hunt through the UI.
 *
 * This layer contains no cryptographic primitives. It derives deterministic channel passwords and
 * holds public trust anchors; all signing, verification and channel encryption is performed by the
 * existing BitChat crypto stack.
 */
object CampusConfig {

    const val institutionName: String = "Example Institute of Technology"
    const val academicYear: String = "2026"

    /**
     * DEMO vs PRODUCTION.
     *
     * [isDemoDeployment] is true whenever no real institutional trust anchor has been provisioned.
     * In that state the app is a functional UI demonstration but NOT an authentication system: no
     * credential can verify, so every peer renders as unverified and no alert can ever display as
     * institutional. This is the honest failure mode, and it is deliberate — see the class doc on
     * [CampusCredential] for why fail-closed is the only safe default here.
     */
    val isDemoDeployment: Boolean get() = trustedInstitutionKeys.isEmpty()

    /** True when this build presents the campus overlay rather than the stock BitChat UI. */
    val campusModeEnabled: Boolean get() = BuildConfig.CAMPUS_MODE

    // MARK: - Trust anchors

    /**
     * Institutional root Ed25519 PUBLIC keys, hex encoded (32 bytes each).
     *
     * These are trust anchors, not secrets: bundling them in the APK is the point, since a device
     * with no network must still be able to decide whether a credential is genuine. The matching
     * PRIVATE keys are held by campus IT and MUST NEVER appear in this repository or the APK.
     *
     * An empty list means nothing verifies. That is the correct default for an unprovisioned build:
     * it is better to show every user as unverified than to invent a trust decision.
     */
    val trustedInstitutionKeys: List<String> = emptyList()

    /**
     * Signing keys whose credentials the institution has withdrawn, hex, lowercase.
     *
     * Checked before a credential's signature, because a revoked credential still carries a valid
     * signature — that is precisely why revocation has to exist separately from expiry. A key
     * appears here when it was compromised or its holder left before the credential lapsed.
     *
     * Distributed with the app for now, which bounds how quickly a revocation can take effect to
     * the release cadence. A deployment needing faster withdrawal would serve this list separately
     * and signed by the same institutional anchor.
     */
    val revokedSigningKeys: Set<String> = emptySet()

    // MARK: - Classes

    data class CampusClass(
        val code: String,
        val title: String,
        val department: String,
        val instructor: String,
        val memberCount: Int
    ) {
        /** Mesh channel tag. The stock ChannelManager normalises to a leading '#'. */
        val channelTag: String get() = "#$code"
    }

    /**
     * Enrolled classes.
     *
     * Static demo data. A production deployment replaces this with a roster delivered during
     * onboarding and signed by the same institutional key that signs identity credentials.
     */
    val classes: List<CampusClass> = listOf(
        CampusClass("CS101", "Computer Science", "Computer Science", "Dr. Rao", 48),
        CampusClass("PHYS200", "Physics", "Physics", "Dr. Iyer", 52),
        CampusClass("ENG300", "English", "Humanities", "Dr. Menon", 35),
        CampusClass("MATH210", "Linear Algebra", "Mathematics", "Dr. Fernandes", 41),
        CampusClass("BIO150", "Cell Biology", "Life Sciences", "Dr. Chandra", 29)
    )

    const val ALERTS_CHANNEL: String = "#CampusAlerts"

    // MARK: - Channel key derivation

    /**
     * Institutional secret mixed into every derived class password.
     *
     * SCOPE OF PROTECTION: this segregates class traffic from the wider student body — a device
     * without it sees class packets as opaque ciphertext while still relaying them. It is NOT an
     * enrolment check: the value ships inside the APK, so anyone who unzips the APK can derive any
     * class key. Per-student enrolment gating requires roster provisioning.
     */
    const val INSTITUTIONAL_SECRET: String = "InstitutionalSecret2026"

    /**
     * Deterministic password for a class channel: SHA-256(code + institutional secret), hex.
     *
     * Every enrolled device derives the identical string with no user interaction, which is what
     * makes the class group feel passwordless while still producing segregated ciphertext. The
     * result is fed to the stock ChannelManager, which performs the real PBKDF2-HMAC-SHA256
     * derivation (100k iterations) and AES-256-GCM encryption.
     */
    fun passwordFor(classCode: String): String = sha256Hex(classCode + INSTITUTIONAL_SECRET)

    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun classFor(channelTag: String): CampusClass? =
        classes.firstOrNull { it.channelTag.equals(channelTag, ignoreCase = true) }

    // MARK: - Files

    /**
     * Academic document formats offered in the attachment picker.
     *
     * Extensible by design: adding a format is a one-line change here, and the picker, the transfer
     * validator and the tests all read from this single list.
     */
    val supportedFileMimeTypes: List<String> = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/plain"
    )

    fun isSupportedFileType(mimeType: String): Boolean =
        supportedFileMimeTypes.any { it.equals(mimeType, ignoreCase = true) }

    // MARK: - Alerts

    /** Alerts stop displaying this long after publication unless the publisher sets otherwise. */
    const val defaultAlertLifetimeSeconds: Long = 24 * 60 * 60

    /** Reject alerts claiming to be published further than this into the future (clock-skew guard). */
    const val maxAlertClockSkewSeconds: Long = 10 * 60
}
