package com.bitchat.android.messaging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Institutional alerts.
 *
 * Given a deliberately different presentation from chats — bordered cards on a tinted ground rather
 * than conversation rows — because an alert is not a message from a person and must not be mistaken
 * for one at a glance.
 *
 * Only alerts that passed the full two-signature chain appear. Anything that failed is counted and
 * mentioned as a footnote, never rendered: displaying unverifiable text that reads like an official
 * instruction, even beside a warning, is precisely the outcome the signature chain exists to
 * prevent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(viewModel: MessengerViewModel, onBack: () -> Unit) {
    val alerts by viewModel.alerts.collectAsState()
    val rejected by viewModel.rejectedAlertCount.collectAsState()
    var composing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Campus bulletins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            // Present only for a credential whose role permits publishing. The signing path
            // re-checks regardless, so hiding this is convenience rather than the control.
            if (viewModel.canPublishAlerts()) {
                FloatingActionButton(onClick = { composing = true }) {
                    Icon(Icons.Filled.Campaign, contentDescription = "Publish alert")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "Signed staff notices. Nearby phones keep them until they expire, so " +
                    "someone who arrives later can still pick one up. This is best-effort, not " +
                    "an emergency system.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            if (com.bitchat.android.campus.CampusConfig.demoIssuerAvailable &&
                com.bitchat.android.campus.CampusConfig.isDemoDeployment
            ) {
                Text(
                    text = "Demo trust root. Signatures are checked, but this is not a real " +
                        "institution key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            if (alerts.isEmpty()) {
                EmptyState(
                    title = "No bulletins",
                    detail = "Class changes, exam rooms, bus timings and safety notes from " +
                        "approved staff appear here."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(alerts, key = { it.id }) { alert -> AlertCard(alert) }
                }
            }

            if (rejected > 0) {
                Text(
                    text = if (rejected == 1) {
                        "1 message claiming to be an alert could not be verified and was not shown."
                    } else {
                        "$rejected messages claiming to be alerts could not be verified and " +
                            "were not shown."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }

    if (composing) {
        AlertComposer(
            onDismiss = { composing = false },
            onPublish = { title, body ->
                if (viewModel.publishAlert(title, body)) composing = false
            }
        )
    }
}

@Composable
private fun AlertCard(alert: MessengerViewModel.VerifiedAlert) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Filled.Campaign,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = alert.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }

        Text(
            text = alert.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )

        Text(
            text = "Issued by ${alert.publisherName}" +
                alert.publisherSubtitle.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Verified signature",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = formatTimestamp(alert.timestampMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            if (alert.expiresAtMillis > 0L) {
                Text(
                    text = "Until ${formatTimestamp(alert.expiresAtMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun AlertComposer(
    onDismiss: () -> Unit,
    onPublish: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish alert") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Nearby phones relay this and keep it until it expires. It is signed " +
                        "with your staff credential. Delivery is best-effort.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 80) title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    supportingText = { Text("${title.length}/80") }
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { if (it.length <= 500) body = it },
                    label = { Text("Message") },
                    maxLines = 6,
                    supportingText = { Text("${body.length}/500") }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && body.isNotBlank(),
                onClick = { onPublish(title, body) }
            ) { Text("Publish") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
