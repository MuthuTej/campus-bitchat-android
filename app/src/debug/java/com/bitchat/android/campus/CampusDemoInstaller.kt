package com.bitchat.android.campus

/**
 * Arms a demo institutional key for debug builds that have no real trust anchor.
 *
 * The private key lives only in the debug source set, so release APKs cannot issue credentials.
 * Verification of demo credentials still uses ordinary Ed25519 checks. This is a simulation of
 * campus IT, not a production root: anyone with this source tree can mint demo credentials.
 */
object CampusDemoInstaller {

    private const val SEED_HEX: String =
        "570a54cf3fcc938c8c5d0b1a26d3fe6071bc2e3fec5e964e6191ed459b6d6564"

    const val PUBLIC_HEX: String =
        "e74132cadecc8e458e2b31ddfb5f1b587ef3dff14ec77ebbdd5e5a6019e63a27"

    @JvmStatic
    fun install() {
        if (CampusConfig.trustedInstitutionKeys.isNotEmpty()) return
        val seed = CampusCanonical.unhex(SEED_HEX) ?: return
        val issuer = CampusCredentialIssuer(seed)
        if (!issuer.publicKeyHex.equals(PUBLIC_HEX, ignoreCase = true)) return
        CampusDemoHooks.install(issuer)
    }
}
