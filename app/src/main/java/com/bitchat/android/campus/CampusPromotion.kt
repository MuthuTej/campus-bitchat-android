package com.bitchat.android.campus

/**
 * Explicit staff-promotion step for the demo issuer.
 *
 * College email alone never maps to these codes. They are ignored unless [CampusDemoHooks] is
 * armed, and a production build with a real trust anchor does not arm it.
 */
object CampusPromotion {
    const val PROFESSOR_CODE: String = "STAFF-DEMO"
    const val ADMIN_CODE: String = "ADMIN-DEMO"

    fun roleFor(code: String): CampusRole? = when (code.trim().uppercase()) {
        PROFESSOR_CODE -> CampusRole.PROFESSOR
        ADMIN_CODE -> CampusRole.ADMIN
        else -> null
    }
}
