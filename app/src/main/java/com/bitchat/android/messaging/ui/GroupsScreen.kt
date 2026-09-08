package com.bitchat.android.messaging.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.android.messaging.contacts.Contact
import com.bitchat.android.messaging.identity.MessagingPublicKey

/** The groups this user belongs to, and the way in to making another. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    viewModel: MessengerViewModel,
    onOpenGroup: (String) -> Unit,
    onCreateGroup: () -> Unit
) {
    val groups by viewModel.myGroups.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateGroup) {
                Icon(Icons.Filled.Add, contentDescription = "Create group")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (groups.isEmpty()) {
                EmptyState(
                    title = "No groups yet",
                    detail = "Create one to talk to several people at once."
                )
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(groups, key = { it.id }) { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenGroup(group.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Avatar(group.name)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(group.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = buildString {
                                    append("${group.members.size} members")
                                    if (group.isAnnouncementOnly) append(" · Announcements only")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Creating a group.
 *
 * Members are chosen from contacts, never from a list of device identifiers — the person picking
 * should be choosing people they recognise.
 */
@Composable
fun CreateGroupScreen(
    viewModel: MessengerViewModel,
    onCreated: () -> Unit,
    onCancel: () -> Unit
) {
    // Everyone this device knows, not only people explicitly saved. Requiring a contact to be
    // saved first meant a user who had just met someone on the mesh could not put them in a group,
    // which is the most common way a group gets made in the first place. Reachable people are shown
    // first, since an invitation reaches them straight away.
    val known by viewModel.allKnown.collectAsState()
    val contacts = remember(known) {
        known.sortedWith(compareByDescending<Contact> { it.canReachNow }.thenBy { it.displayName })
    }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var announcementOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<MessagingPublicKey>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("New group", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Group name") },
            singleLine = true
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Description (optional)") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Only admins can post", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Everyone else can read and nothing more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = announcementOnly, onCheckedChange = { announcementOnly = it })
        }

        SectionHeader("Add members")

        if (contacts.isEmpty()) {
            Text(
                text = "Nobody nearby yet. People appear here once their device is in range.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(contacts, key = { it.publicKey.hex }) { contact ->
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
                    Avatar(contact.displayName, sizeDp = 36)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (!contact.canReachNow) {
                            Text(
                                text = "Not reachable now",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    viewModel.createGroup(name, description, selected, announcementOnly)
                    onCreated()
                }
            ) { Text("Create group") }
        }
    }
}
