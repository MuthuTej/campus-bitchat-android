package com.bitchat.android.messaging.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusConfig
import com.bitchat.android.campus.CampusEmailAddress
import com.bitchat.android.campus.CampusEmailSendResult
import com.bitchat.android.messaging.identity.MessagingIdentity

/**
 * First-run setup for a college-linked identity.
 *
 * The name is what nearby people see. The college email is how this device joins the institution.
 * Entering the code from the college inbox issues a student credential only. Staff bulletin
 * rights are a later step.
 */
@Composable
fun MessengerOnboardingScreen(
    demoVerification: Boolean,
    onRequestCode: suspend (email: String) -> CampusEmailSendResult,
    onConfirmCode: (email: String, code: String) -> Boolean,
    onComplete: (
        displayName: String,
        phoneNumber: String?,
        email: String,
        emailVerified: Boolean
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val trimmedName = MessagingIdentity.sanitizeDisplayName(name)
    val normalizedEmail = CampusEmailAddress.normalize(email)
    val emailOk = normalizedEmail != null && CampusEmailAddress.isInstitutional(normalizedEmail)
    val domainHint = CampusConfig.institutionalEmailDomains.joinToString(" or ") { "@$it" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Join ${CampusConfig.institutionName}",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Use your college email so people nearby know you belong here. " +
                "Bulletin publishing is a separate staff approval.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your name") },
            placeholder = { Text("e.g. Priya Sharma") },
            singleLine = true,
            isError = name.isNotEmpty() && trimmedName.isEmpty()
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                codeSent = false
                code = ""
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("College email") },
            placeholder = { Text("name.dept2024$domainHint") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            isError = email.isNotEmpty() && !emailOk,
            supportingText = {
                Text(
                    if (email.isNotEmpty() && !emailOk) {
                        "Use an address at $domainHint"
                    } else {
                        "Messages still travel over the nearby mesh, not over email."
                    }
                )
            }
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Phone number (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )

        if (demoVerification && codeSent && normalizedEmail != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Check your inbox",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A 6-digit code was sent to $normalizedEmail. It expires in 10 minutes. " +
                    "Look in spam if it is not in the inbox.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Verification code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(28.dp))

        if (demoVerification && !codeSent) {
            Button(
                onClick = {
                    val address = normalizedEmail ?: return@Button
                    scope.launch {
                        sending = true
                        error = null
                        when (val result = onRequestCode(address)) {
                            CampusEmailSendResult.Sent -> codeSent = true
                            is CampusEmailSendResult.Failed -> error = result.message
                        }
                        sending = false
                    }
                },
                enabled = !sending && trimmedName.isNotEmpty() && emailOk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (sending) "Sending code…" else "Email me a code")
            }
        } else if (demoVerification) {
            Button(
                onClick = {
                    val address = normalizedEmail ?: return@Button
                    if (!onConfirmCode(address, code)) {
                        error = "That code does not match"
                        return@Button
                    }
                    onComplete(trimmedName, phone.trim().takeIf { it.isNotEmpty() }, address, true)
                },
                enabled = trimmedName.isNotEmpty() && emailOk && code.length == 6,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Verify and continue")
            }
        } else {
            Button(
                onClick = {
                    val address = normalizedEmail
                    if (address == null || !emailOk) {
                        error = "Enter a college email at $domainHint"
                        return@Button
                    }
                    onComplete(
                        trimmedName,
                        phone.trim().takeIf { it.isNotEmpty() },
                        address,
                        false
                    )
                },
                enabled = trimmedName.isNotEmpty() && emailOk,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "This build cannot confirm the email with campus IT. You can message " +
                    "nearby, but you will not be shown as verified and you cannot publish bulletins.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
