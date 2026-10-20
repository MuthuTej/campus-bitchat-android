package com.bitchat.android.campus

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Result of asking the mail provider to deliver a verification code. */
sealed class CampusEmailSendResult {
    data object Sent : CampusEmailSendResult()
    data class Failed(val message: String) : CampusEmailSendResult()
}

/**
 * Sends the college-email code through Brevo.
 *
 * Brevo accepts mail only from a sender verified on the account. The visible name can be the
 * campus office; the address itself has to be that verified sender. The API key is supplied by
 * the build and is never written into source.
 */
class CampusBrevoMailer(
    private val apiKey: String,
    private val senderEmail: String,
    private val senderName: String,
    private val post: (body: String, apiKey: String) -> Pair<Int, String> = ::postBrevo
) {

    fun sendVerificationCode(to: String, code: String): CampusEmailSendResult {
        if (apiKey.isBlank() || senderEmail.isBlank()) {
            return CampusEmailSendResult.Failed("Email delivery is not configured on this build")
        }
        val normalized = CampusEmailAddress.normalize(to)
            ?: return CampusEmailSendResult.Failed("That email address could not be used")
        return try {
            val (status, response) = post(payload(normalized, code), apiKey)
            if (status in 200..299) {
                CampusEmailSendResult.Sent
            } else {
                CampusEmailSendResult.Failed(
                    brevoMessage(response) ?: "Could not send the code ($status)"
                )
            }
        } catch (_: Exception) {
            CampusEmailSendResult.Failed(
                "Could not reach the email service. Check your internet connection."
            )
        }
    }

    fun payload(to: String, code: String): String {
        val text = "Your Campus Mesh verification code is $code.\n\n" +
            "It expires in 10 minutes. If you did not ask to join, ignore this email.\n\n" +
            "Chennai Institute of Technology"
        return buildString {
            append("{\"sender\":{\"name\":")
            append(jsonString(senderName.ifBlank { "Campus Mesh" }))
            append(",\"email\":")
            append(jsonString(senderEmail))
            append("},\"to\":[{\"email\":")
            append(jsonString(to))
            append("}],\"subject\":")
            append(jsonString("Your Campus Mesh code"))
            append(",\"textContent\":")
            append(jsonString(text))
            append("}")
        }
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    private fun brevoMessage(response: String): String? {
        val marker = "\"message\":\""
        val start = response.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = response.indexOf('"', from)
        if (end <= from) return null
        return response.substring(from, end).replace("\\n", " ").take(180)
    }
}

private fun postBrevo(body: String, apiKey: String): Pair<Int, String> {
    val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    val request = Request.Builder()
        .url("https://api.brevo.com/v3/smtp/email")
        .addHeader("api-key", apiKey)
        .addHeader("accept", "application/json")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    client.newCall(request).execute().use { response ->
        return response.code to response.body.string()
    }
}
