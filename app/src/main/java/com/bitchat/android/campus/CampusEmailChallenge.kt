package com.bitchat.android.campus

import java.security.SecureRandom

/**
 * One-time code for a college-email check.
 *
 * The code is delivered by email. This check only decides whether the typed code matches.
 */
data class CampusEmailChallenge(
    val email: String,
    val code: String,
    val expiresAtEpochMillis: Long
) {

    fun matches(email: String, submittedCode: String, nowEpochMillis: Long): Boolean {
        if (nowEpochMillis > expiresAtEpochMillis) return false
        val normalized = CampusEmailAddress.normalize(email) ?: return false
        if (normalized != this.email) return false
        val submitted = submittedCode.trim()
        if (submitted.length != code.length) return false
        var difference = 0
        for (index in code.indices) {
            difference = difference or (submitted[index].code xor code[index].code)
        }
        return difference == 0
    }

    companion object {
        const val TTL_MILLIS: Long = 10 * 60 * 1000L

        fun issue(
            email: String,
            nowEpochMillis: Long = System.currentTimeMillis(),
            code: String = randomCode()
        ): CampusEmailChallenge? {
            val normalized = CampusEmailAddress.normalize(email) ?: return null
            if (!CampusEmailAddress.isInstitutional(normalized)) return null
            if (code.length != 6 || code.any { !it.isDigit() }) return null
            return CampusEmailChallenge(normalized, code, nowEpochMillis + TTL_MILLIS)
        }

        private fun randomCode(): String {
            val value = SecureRandom().nextInt(900_000) + 100_000
            return value.toString()
        }
    }
}
