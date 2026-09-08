package com.bitchat.android.campus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusViewModel

/**
 * Chooses the single person a document is sent to.
 *
 * A file transfer is point to point, so a recipient is not optional — this sheet is what makes the
 * recipient an explicit, visible choice rather than "whoever happens to be in the room".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusRecipientPicker(
    fileName: String,
    recipients: List<CampusViewModel.Recipient>,
    onSelect: (CampusViewModel.Recipient) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = "Send $fileName to", style = MaterialTheme.typography.titleMedium)

            if (recipients.isEmpty()) {
                Text(
                    text = "Nobody is in range right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Text(
                    text = "Documents are sent directly to one person, so the recipient needs to " +
                        "be reachable before the transfer can start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(recipients, key = { it.peerId }) { recipient ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(recipient) }
                            .padding(vertical = 10.dp)
                    ) {
                        CampusIdentityLabel(identity = recipient.identity)
                    }
                }
            }
        }
    }
}
