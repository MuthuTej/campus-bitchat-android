package com.bitchat.android.campus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusIdentityRegistry

/**
 * Who is in a class, with verified names and roles where credentials are known.
 *
 * Verified professors sort first so the person who can issue authoritative instructions is easy to
 * find, and unverified participants are visibly separated rather than blended into the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusParticipantsSheet(
    classTitle: String,
    participants: List<CampusIdentityRegistry.Identity>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = classTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${participants.size} participant(s) seen in this class",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            HorizontalDivider()

            if (participants.isEmpty()) {
                Text(
                    text = "No messages yet, so no participants have been seen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(participants) { index, identity ->
                        CampusIdentityLabel(
                            identity = identity,
                            modifier = Modifier.padding(top = if (index == 0) 12.dp else 0.dp)
                        )
                    }
                }
            }
        }
    }
}
