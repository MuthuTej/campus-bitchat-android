package com.bitchat.android.campus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusAlert
import com.bitchat.android.campus.CampusConfig
import com.bitchat.android.campus.CampusFileMessage
import com.bitchat.android.campus.CampusFileTransferService
import com.bitchat.android.campus.CampusIdentityRegistry
import com.bitchat.android.campus.CampusViewModel
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType

/**
 * Conversation view for a single class.
 *
 * Intentionally has no command surface. The compositor is a plain text field plus an attachment
 * button; anything beginning with '/' is escaped by the view model rather than dispatched, so the
 * IRC layer is unreachable from the academic build.
 */
@Composable
fun CampusClassChatScreen(
    definition: CampusConfig.CampusClass,
    viewModel: CampusViewModel,
    onBack: () -> Unit
) {
    val channelMessages by viewModel.channelMessages.collectAsState()
    val messages = channelMessages[definition.channelTag].orEmpty()
    // Protocol envelopes (file chunks, alerts) are machine traffic and must never render as text.
    val visibleMessages = messages.filterNot {
        CampusFileMessage.looksLikeFileMessage(it.content) || CampusAlert.looksLikeAlert(it.content)
    }
    val transfers by viewModel.transferProgress.collectAsState()
    var pendingDocument by remember { mutableStateOf<SelectedDocument?>(null) }
    var choosingRecipientFor by remember { mutableStateOf<SelectedDocument?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var showParticipants by remember { mutableStateOf(false) }
    val downloads by viewModel.downloads.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val myNickname by viewModel.nickname.collectAsState()

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) listState.animateScrollToItem(visibleMessages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to classes")
            }
            if (showParticipants) {
                CampusParticipantsSheet(
                    classTitle = definition.title,
                    participants = viewModel.participantsFor(definition.channelTag),
                    onDismiss = { showParticipants = false }
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = definition.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${definition.code} · ${definition.memberCount} members",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showParticipants = true }) {
                Icon(Icons.Filled.People, contentDescription = "View participants")
            }
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(visibleMessages, key = { it.id }) { message ->
                ClassMessageBubble(
                    message = message,
                    isOwn = message.sender == myNickname,
                    identity = viewModel.identityForMessage(message)
                )
            }
        }

        HorizontalDivider()

        transfers.filter { it.state != CampusFileTransferService.State.COMPLETE }.forEach { progress ->
            CampusTransferRow(progress)
        }

        downloads.take(3).forEach { document ->
            CampusDownloadRow(
                fileName = document.fileName,
                onOpen = {
                    // Launching through the FileProvider intent keeps the internal path hidden and
                    // grants the viewer only temporary read access.
                    try {
                        context.startActivity(viewModel.openIntentFor(document))
                    } catch (e: android.content.ActivityNotFoundException) {
                        errorText = "No app installed can open this document"
                    }
                }
            )
        }

        errorText?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        pendingDocument?.let { document ->
            CampusDocumentPreview(
                document = document,
                onCancel = { pendingDocument = null },
                onSend = { choosingRecipientFor = document }
            )
        }

        // A document goes to one named person, never to the channel.
        choosingRecipientFor?.let { document ->
            CampusRecipientPicker(
                fileName = document.fileName,
                recipients = viewModel.availableRecipients(),
                onSelect = { recipient ->
                    viewModel.sendFileToPeer(
                        recipient.peerId,
                        document.fileName,
                        document.mimeType,
                        document.bytes
                    )
                    choosingRecipientFor = null
                    pendingDocument = null
                },
                onDismiss = { choosingRecipientFor = null }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CampusDocumentPickerButton(
                onDocumentSelected = { pendingDocument = it; errorText = null },
                onError = { errorText = it }
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message ${definition.code}") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp)
            )
            IconButton(
                onClick = {
                    viewModel.sendToOpenClass(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank()
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun ClassMessageBubble(
    message: BitchatMessage,
    isOwn: Boolean,
    identity: CampusIdentityRegistry.Identity
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isOwn) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!isOwn) {
                    // Name and role come from the verified credential, never from the sender's
                    // self-chosen nickname. See CampusIdentityLabel.
                    CampusIdentityLabel(identity = identity, compact = true)
                }
                Text(
                    text = when (message.type) {
                        BitchatMessageType.File -> "📎 ${message.content}"
                        BitchatMessageType.Image -> "🖼 Image"
                        BitchatMessageType.Audio -> "🎤 Voice note"
                        BitchatMessageType.Message -> message.content
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = formatCampusTime(message.timestamp.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
