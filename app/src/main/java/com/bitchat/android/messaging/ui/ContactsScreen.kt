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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.bitchat.android.messaging.contacts.NearbyPeer
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceState

/**
 * People: those already saved, and those currently within reach.
 *
 * No peer identifier appears anywhere on this screen. A device that has not proved who it is shows
 * as an unverified device with no name at all — showing the nickname it broadcast, even with a
 * warning beside it, would be repeating a claim nobody checked.
 */
@Composable
fun ContactsScreen(
    viewModel: MessengerViewModel,
    onOpenContact: (MessagingPublicKey) -> Unit
) {
    val saved by viewModel.savedContacts.collectAsState()
    val nearby by viewModel.nearby.collectAsState()
    var query by remember { mutableStateOf("") }

    val savedKeys = saved.map { it.publicKey }.toSet()
    val nearbyIdentified = nearby
        .filterIsInstance<NearbyPeer.Identified>()
        .filterNot { it.publicKey in savedKeys }
    val unidentifiedCount = nearby.count { it is NearbyPeer.Unidentified }

    val filteredSaved = saved.filter {
        query.isBlank() || it.displayName.contains(query, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search contacts") },
            singleLine = true
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (nearbyIdentified.isNotEmpty()) {
                item { SectionHeader("Nearby") }
                items(nearbyIdentified, key = { it.publicKey.hex }) { peer ->
                    NearbyRow(
                        peer = peer,
                        onOpen = { onOpenContact(peer.publicKey) },
                        onAdd = { viewModel.saveContact(peer.publicKey) }
                    )
                    HorizontalDivider()
                }
            }

            if (filteredSaved.isNotEmpty()) {
                item { SectionHeader("Contacts") }
                items(filteredSaved, key = { it.publicKey.hex }) { contact ->
                    ContactRow(contact) { onOpenContact(contact.publicKey) }
                    HorizontalDivider()
                }
            }

            if (unidentifiedCount > 0) {
                item {
                    Text(
                        text = if (unidentifiedCount == 1) {
                            "1 nearby device has not verified who it is"
                        } else {
                            "$unidentifiedCount nearby devices have not verified who they are"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (saved.isEmpty() && nearbyIdentified.isEmpty()) {
                item {
                    EmptyState(
                        title = "No contacts yet",
                        detail = "People near you appear here automatically once their device " +
                            "confirms who they are."
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(
    peer: NearbyPeer.Identified,
    onOpen: () -> Unit,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(peer.displayName)
        Column(modifier = Modifier.weight(1f)) {
            NameWithPresence(peer.displayName, peer.presence)
        }
        TextButton(onClick = onAdd) { Text("Add") }
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(contact.displayName)
        Column(modifier = Modifier.weight(1f)) {
            NameWithPresence(
                name = contact.displayName,
                presence = contact.presence,
                subtitle = if (contact.isBlocked) "Blocked" else null
            )
        }
    }
}
