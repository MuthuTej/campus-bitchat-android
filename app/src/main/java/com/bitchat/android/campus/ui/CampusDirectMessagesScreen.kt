package com.bitchat.android.campus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusViewModel

/**
 * One-to-one conversations, listed the way a consumer messenger lists them.
 *
 * Direct messages continue to ride the upstream Noise XX private-session path; this screen only
 * changes how the conversation list is presented.
 */
@Composable
fun CampusDirectMessagesScreen(viewModel: CampusViewModel, signalBars: Int) {
    val privateChats by viewModel.privateChats.collectAsState()

    val conversations = privateChats.entries
        .filter { it.value.isNotEmpty() }
        .sortedByDescending { it.value.last().timestamp.time }

    Column(modifier = Modifier.fillMaxSize()) {
        CampusHeader(title = "Messages", signalBars = signalBars)

        if (conversations.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No conversations yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Direct messages from classmates nearby will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(conversations, key = { it.key }) { (peerId, messages) ->
                val last = messages.last()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Verified campus name and role where a credential is known; otherwise the
                        // conversation is labelled as an unverified user.
                        CampusIdentityLabel(identity = viewModel.identityForMessage(last))
                        Text(
                            text = last.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
