package com.bitchat.android.campus

/**
 * College-email check used before any credential is issued.
 *
 * A matching domain only proves the user typed an institutional address. It does not grant a role.
 * Bulletin rights still require a separate, institution-signed credential.
 */
object CampusEmailAddress {

    fun normalize(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        val at = trimmed.lastIndexOf('@')
        if (at <= 0 || at >= trimmed.lastIndex) return null
        val local = trimmed.substring(0, at)
        val domain = trimmed.substring(at + 1)
        if (local.isEmpty() || domain.isEmpty() || '.' !in domain) return null
        if (local.any { it.isWhitespace() || it == '\n' || it == '\r' }) return null
        if (domain.any { it.isWhitespace() || it == '\n' || it == '\r' }) return null
        return "$local@$domain"
    }

    fun isInstitutional(
        email: String,
        domains: List<String> = CampusConfig.institutionalEmailDomains
    ): Boolean {
        val normalized = normalize(email) ?: return false
        val domain = normalized.substringAfter('@')
        return domains.any { allowed ->
            val root = allowed.trim().lowercase()
            root.isNotEmpty() && (domain == root || domain.endsWith(".$root"))
        }
    }
}
