package com.bitchat.android.campus

/**
 * An institutional broadcast.
 *
 * Two independent signatures gate display, and both must pass:
 *
 *  1. [signature] — made by the publishing device's own Ed25519 key over [canonicalBytes]. Proves
 *     the alert body was authored by the holder of that key and has not been altered in relay.
 *  2. The publisher's [CampusCredential] — signed by an institutional trust anchor, naming that same
 *     key and carrying a role. Proves the key belongs to a real professor or admin.
 *
 * Neither alone is sufficient. A student can sign their own alert perfectly well (1 passes, 2 fails
 * on role), and anyone can replay a professor's credential (2 passes, 1 fails on the body).
 */
data class CampusAlert(
    val alertId: String,
    val title: String,
    val body: String,
    val timestampEpochSeconds: Long,
    /** 0 means "does not expire". */
    val expiresAtEpochSeconds: Long,
    val signerPublicKeyHex: String,
    val signature: ByteArray
) {

    /**
     * Bytes covered by [signature].
     *
     * Includes the timestamp and expiry as well as the body, so shifting an alert's stated time or
     * extending its validity window invalidates the signature exactly as editing the text does.
     */
    fun canonicalBytes(): ByteArray = CampusCanonical.encode(
        ALERT_VERSION,
        alertId,
        title,
        body,
        timestampEpochSeconds.toString(),
        expiresAtEpochSeconds.toString(),
        signerPublicKeyHex.lowercase()
    )

    fun isExpiredAt(nowEpochSeconds: Long): Boolean =
        expiresAtEpochSeconds != 0L && nowEpochSeconds > expiresAtEpochSeconds

    /**
     * Wire form. The publisher's credential travels with the alert so a receiver that has never met
     * this professor can still establish trust from the bundled institutional anchors alone — which
     * matters on a mesh where there is no server to fetch a directory from.
     */
    fun encode(credential: CampusCredential): String {
        val payload = CampusCanonical.encode(
            ALERT_VERSION,
            alertId,
            title,
            body,
            timestampEpochSeconds.toString(),
            expiresAtEpochSeconds.toString(),
            signerPublicKeyHex.lowercase(),
            CampusCanonical.b64(signature),
            credential.encode()
        )
        return ENVELOPE_PREFIX + CampusCanonical.b64(payload)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CampusAlert) return false
        return alertId == other.alertId &&
            title == other.title &&
            body == other.body &&
            timestampEpochSeconds == other.timestampEpochSeconds &&
            expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
            signerPublicKeyHex.equals(other.signerPublicKeyHex, ignoreCase = true) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = alertId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + timestampEpochSeconds.hashCode()
        result = 31 * result + expiresAtEpochSeconds.hashCode()
        result = 31 * result + signerPublicKeyHex.lowercase().hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        const val ALERT_VERSION = "CAMPUS-ALERT-V1"
        const val ENVELOPE_PREFIX = "BCA2:"

        /** True when [content] looks like an alert envelope, without asserting it is valid. */
        fun looksLikeAlert(content: String): Boolean = content.startsWith(ENVELOPE_PREFIX)

        /** Parses the wire form. Does NOT verify — see [CampusAlertVerifier]. */
        fun decode(content: String): Pair<CampusAlert, CampusCredential>? {
            if (!looksLikeAlert(content)) return null
            val raw = CampusCanonical.unb64(content.removePrefix(ENVELOPE_PREFIX)) ?: return null
            val fields = CampusCanonical.decodeFields(raw, expected = 9) ?: return null
            if (fields[0] != ALERT_VERSION) return null
            val timestamp = fields[4].toLongOrNull() ?: return null
            val expiresAt = fields[5].toLongOrNull() ?: return null
            val signature = CampusCanonical.unb64(fields[7]) ?: return null
            val credential = CampusCredential.decode(fields[8]) ?: return null
            val alert = CampusAlert(
                alertId = fields[1],
                title = fields[2],
                body = fields[3],
                timestampEpochSeconds = timestamp,
                expiresAtEpochSeconds = expiresAt,
                signerPublicKeyHex = fields[6],
                signature = signature
            )
            return alert to credential
        }
    }
}
