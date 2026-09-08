package com.bitchat.android.messaging.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bitchat.android.messaging.identity.MessagingIdentity

/**
 * First-run setup.
 *
 * Exists because the app previously assigned a random name like "anon4821" and never asked. That is
 * fine for an anonymous mesh client and wrong for a messenger: the name is what everyone else sees,
 * and being labelled by a number nobody chose makes every contact list unreadable.
 *
 * Nothing here is verified and nothing is sent anywhere yet. It is the user describing themselves
 * to their own device.
 */
@Composable
fun MessengerOnboardingScreen(
    onComplete: (displayName: String, phoneNumber: String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    val trimmedName = MessagingIdentity.sanitizeDisplayName(name)
    val canContinue = trimmedName.isNotEmpty()

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
            text = "Set up your profile",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This is how people nearby will see you. You can change it later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your name") },
            placeholder = { Text("e.g. Muthuraj") },
            singleLine = true,
            isError = name.isNotEmpty() && !canContinue
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

        Spacer(Modifier.height(8.dp))

        // Stated plainly, because the honest description is also the reassuring one: the number
        // buys recognition, not reachability, and it is not how anyone finds or contacts you.
        Text(
            text = "Your number is stored on this phone. It is never broadcast, and it is not how " +
                "people reach you — messages always travel to your device directly. Sharing it " +
                "with a contact simply helps them still recognise you if you ever reinstall the " +
                "app and your device gets a new identity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        Button(
            onClick = { onComplete(trimmedName, phone.trim().takeIf { it.isNotEmpty() }) },
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "No account, no sign-in, and no internet needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
