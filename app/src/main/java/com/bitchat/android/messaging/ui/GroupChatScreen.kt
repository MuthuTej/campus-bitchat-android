package com.bitchat.android.messaging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import com.bitchat.android.messaging.groups.GroupPermission
import com.bitchat.android.messaging.identity.MessagingPublicKey

/**
 * A group conversation.
 *
 * When the user may not post, the composer is replaced by a plain explanation rather than simply
 * removed. The refusal is enforced in the group logic regardless; this is only so the person is not
 * left wondering where the text field went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    viewModel: MessengerViewModel,
    groupId: String,
    onBack: () -> Unit
) {
    val groups by viewModel.myGroups.collectAsState()
    val messagesByGroup by viewModel.groupMessagesByGroup.collectAsState()
    val contacts by viewModel.allKnown.collectAsState()
    val localKey by viewModel.localKey.collectAsState()

    val group = groups.firstOrNull { it.id == groupId }
    val messages = messagesByGroup[groupId].orEmpty()
    val mayPost = group != null && localKey != null &&
        group.can(localKey!!, GroupPermission.CAN_SEND_MESSAGES)

    var draft by remember { mutableStateOf("") }
    var addingPeople by remember { mutableStateOf(false) }

    // Only an administrator's invitations are accepted by the other devices, so the action is
    // offered only to someone whose invitation would actually work.
    val mayAddPeople = group != null && localKey != null &&
        group.memberFor(localKey!!)?.role?.isAdministrative == true

    if (addingPeople && group != null) {
        AddPeopleDialog(
            candidates = contacts.filterNot { group.isMember(it.publicKey) },
            onDismiss = { addingPeople = false },
            onConfirm = { chosen ->
                addingPeople = false
                chosen.forEach { viewModel.addToGroup(groupId, it) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group?.name ?: "Group")
                        Text(
                            text = "${group?.members?.size ?: 0} members",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (mayAddPeople) {
                        IconButton(onClick = { addingPeople = true }) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = "Add people")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.messageId }) { message ->
                    val senderName = contacts
                        .firstOrNull { it.publicKey == message.senderPublicKey }
                        ?.displayName
                        ?: message.senderPublicKey.shortForm()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            if (message.isOutgoing) Arrangement.End else Arrangement.Start
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (message.isOutgoing) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (!message.isOutgoing) {
                                Text(
                                    text = senderName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(message.content, style = MaterialTheme.typography.bodyMedium)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = formatTimestamp(message.timestampEpochMillis),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (message.isOutgoing) DeliveryLabel(message.deliveryState)
                            }
                        }
                    }
                }
            }

            if (mayPost) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 4,
                        shape = RoundedCornerShape(22.dp)
                    )
                    IconButton(
                        onClick = {
                            if (draft.isNotBlank()) {
                                viewModel.sendToGroup(groupId, draft)
                                draft = ""
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            } else {
                Text(
                    text = "Only admins can post in this group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * Diagnostics, reachable only from Profile → Advanced.
 *
 * The technical surfaces are kept and remain useful for debugging; they are simply not on the path
 * an ordinary user walks.
 */
@Composable
fun AdvancedScreen(viewModel: MessengerViewModel) {
    val reachable by viewModel.reachableCount.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    val identity by viewModel.localIdentity.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Advanced", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Technical details for troubleshooting. Nothing here needs changing for " +
                "normal use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Reachable contacts: $reachable", style = MaterialTheme.typography.bodyMedium)
        Text("Devices in range: ${nearby.size}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Unverified devices in range: " +
                nearby.count { it is com.bitchat.android.messaging.contacts.NearbyPeer.Unidentified },
            style = MaterialTheme.typography.bodyMedium
        )
        identity?.let {
            Text("Your key: ${it.publicKey.hex}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Choose people to add to an existing group.
 *
 * Adding somebody sends them an invitation, which is what actually lets them see the conversation;
 * without this the group's membership was fixed at the moment it was created.
 */
@Composable
private fun AddPeopleDialog(
    candidates: List<com.bitchat.android.messaging.contacts.Contact>,
    onDismiss: () -> Unit,
    onConfirm: (Set<MessagingPublicKey>) -> Unit
) {
    var selected by remember { mutableStateOf(setOf<MessagingPublicKey>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add people") },
        text = {
            if (candidates.isEmpty()) {
                Text("Everyone this device knows is already in the group.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(candidates, key = { it.publicKey.hex }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (contact.publicKey in selected) {
                                        selected - contact.publicKey
                                    } else {
                                        selected + contact.publicKey
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Checkbox(
                                checked = contact.publicKey in selected,
                                onCheckedChange = null
                            )
                            Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected) }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
