package com.bitchat.android.messaging.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusConfig
import com.bitchat.android.campus.CampusPromotion

/**
 * The user's own profile and settings.
 *
 * Everything technical about the app lives behind the Advanced entry at the bottom. Someone who
 * never opens it never encounters a transport name, a diagnostic, or a key.
 */
@Composable
fun ProfileScreen(
    viewModel: MessengerViewModel,
    onOpenPrivacy: () -> Unit,
    onOpenAdvanced: () -> Unit
) {
    val identity by viewModel.localIdentity.collectAsState()
    val standing by viewModel.campusStanding.collectAsState()
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var promotionCode by remember { mutableStateOf("") }
    var credentialToken by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val name = identity?.displayNameOrKey ?: "Setting up…"
        Avatar(name, sizeDp = 88)

        if (editingName) {
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Your name") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { editingName = false }) { Text("Cancel") }
                TextButton(
                    enabled = nameDraft.isNotBlank(),
                    onClick = {
                        viewModel.setDisplayName(nameDraft)
                        editingName = false
                    }
                ) { Text("Save") }
            }
        } else {
            Text(text = name, style = MaterialTheme.typography.headlineSmall)
            TextButton(
                onClick = {
                    nameDraft = identity?.displayName.orEmpty()
                    editingName = true
                }
            ) { Text("Edit name") }
        }

        Text(
            text = when {
                standing.emailVerified && standing.roleLabel != null ->
                    "${standing.roleLabel} · ${standing.email}"
                standing.email != null ->
                    "${standing.email} · not institutionally verified"
                else -> "College email not verified"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (standing.canPublish) {
            Text(
                text = "You can publish campus bulletins.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Students can message nearby. Publishing a bulletin needs staff approval.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (standing.canRequestPromotion) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Staff bulletin rights", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Demo only. Enter ${CampusPromotion.PROFESSOR_CODE} for a professor, or " +
                    "${CampusPromotion.ADMIN_CODE} for an admin. This is not a real campus root.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = promotionCode,
                onValueChange = { promotionCode = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Promotion code") },
                singleLine = true
            )
            Button(
                onClick = {
                    if (viewModel.promoteWithCode(promotionCode)) promotionCode = ""
                },
                enabled = promotionCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply staff role")
            }
        }

        if (!standing.demoVerification || !standing.emailVerified) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Institutional credential",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Paste a credential issued for this phone by ${CampusConfig.institutionName}. " +
                    "A code for a different device is refused.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = credentialToken,
                onValueChange = { credentialToken = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Credential") },
                minLines = 2
            )
            Button(
                onClick = {
                    if (viewModel.installCampusCredential(credentialToken.trim())) {
                        credentialToken = ""
                    }
                },
                enabled = credentialToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Install credential")
            }
        }

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        SettingsRow("Privacy", "Who can message you, and what you share", onOpenPrivacy)
        SettingsRow("Advanced", "Diagnostics and technical details", onOpenAdvanced)

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        identity?.let {
            Text("Your safety code", style = MaterialTheme.typography.labelMedium)
            Text(
                text = it.publicKey.shortForm(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Privacy settings.
 *
 * Only settings that actually change behaviour appear here. Controls the app cannot honour — hiding
 * the device from nearby discovery, for instance — are deliberately absent rather than present and
 * ineffective, because a switch that promises a protection it does not deliver is worse than no
 * switch at all.
 */
@Composable
fun PrivacySettingsScreen(viewModel: MessengerViewModel) {
    val settings by viewModel.privacySettings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Privacy", style = MaterialTheme.typography.headlineSmall)

        ToggleRow(
            title = "Messages from unknown people",
            detail = "When off, only your saved contacts can start a conversation with you.",
            checked = settings.allowMessagesFromUnknown,
            onChange = { on -> viewModel.updatePrivacy { it.copy(allowMessagesFromUnknown = on) } }
        )
        ToggleRow(
            title = "Group invitations",
            detail = "When off, only your contacts can add you to a group.",
            checked = settings.allowGroupInvitations,
            onChange = { on -> viewModel.updatePrivacy { it.copy(allowGroupInvitations = on) } }
        )
        ToggleRow(
            title = "File transfers",
            detail = "When off, incoming files are refused before anything is downloaded.",
            checked = settings.allowFileTransfers,
            onChange = { on -> viewModel.updatePrivacy { it.copy(allowFileTransfers = on) } }
        )
        ToggleRow(
            title = "Share my phone number",
            detail = "Off by default. Your number is never needed to reach you.",
            checked = settings.sharePhoneNumberWithContacts,
            onChange = { on ->
                viewModel.updatePrivacy { it.copy(sharePhoneNumberWithContacts = on) }
            }
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text(
            text = "About being visible nearby",
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = "This app finds people by radio. While it is running, nearby devices can see " +
                "that a device is present. There is no setting to change that here, because the " +
                "app cannot make that promise honestly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
