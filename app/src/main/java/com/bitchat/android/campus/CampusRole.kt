package com.bitchat.android.campus

/**
 * Campus roles, ordered by authority.
 *
 * Role is never self-asserted: it is carried inside an institution-signed credential and is only
 * trusted after that signature verifies. See [CampusCredential].
 */
enum class CampusRole(val wireValue: String, val displayLabel: String) {
    STUDENT("STUDENT", "Student"),
    PROFESSOR("PROFESSOR", "Professor"),
    ADMIN("ADMIN", "Admin");

    /** Only these roles may publish institutional alerts. */
    val canPublishAlerts: Boolean get() = this == PROFESSOR || this == ADMIN

    /** Admins may additionally see institutional configuration surfaces. */
    val canManageConfiguration: Boolean get() = this == ADMIN

    companion object {
        fun fromWire(value: String): CampusRole? = entries.firstOrNull { it.wireValue == value }
    }
}
