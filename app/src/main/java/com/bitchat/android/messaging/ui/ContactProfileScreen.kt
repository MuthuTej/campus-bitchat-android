package com.bitchat.android.messaging.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.android.messaging.identity.MessagingPublicKey

/**
 * One person's profile.
 *
 * Messaging is offered regardless of whether anything about them has been institutionally verified.
 * An unaffiliated contact is an ordinary contact, and the screen says so plainly rather than
 * presenting the absence of a credential as a deficiency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    viewModel: MessengerViewModel,
    publicKey: MessagingPublicKey,
    onBack: () -> Unit,
    onMessage: (MessagingPublicKey) -> Unit
) {
    val contacts by viewModel.allKnown.collectAsState()
    val contact = contacts.firstOrNull { it.publicKey == publicKey }
    val name = contact?.displayName ?: publicKey.shortForm()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Avatar(name, sizeDp = 88)
            Text(text = name, style = MaterialTheme.typography.headlineSmall)

            contact?.let {
                Text(
                    text = it.presence.label.ifEmpty { "Not seen recently" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // The optional institutional layer. When nothing has been claimed the badge renders
            // nothing and the line below stands alone: an unaffiliated person is an ordinary
            // contact, described neutrally rather than flagged as incomplete.
            val attestation = viewModel.attestationFor(publicKey)
            AttestationBadge(attestation)
            if (attestation == null) {
                Text(
                    text = "Messaging contact",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Button(
                onClick = { onMessage(publicKey) },
                modifier = Modifier.fillMaxWidth(),
                enabled = contact?.isBlocked != true
            ) { Text("Message") }

            if (contact?.isSaved != true) {
                OutlinedButton(
                    onClick = { viewModel.saveContact(publicKey) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add to contacts") }
            }

            OutlinedButton(
                onClick = { viewModel.setBlocked(publicKey, contact?.isBlocked != true) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (contact?.isBlocked == true) "Unblock" else "Block") }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // The key is the identity, so it is available for someone who wants to compare it out
            // of band — but shown as a short readable code rather than 64 characters of hex.
            Text(
                text = "Safety code",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = publicKey.shortForm(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
