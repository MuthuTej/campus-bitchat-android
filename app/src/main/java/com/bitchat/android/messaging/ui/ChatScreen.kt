package com.bitchat.android.messaging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bitchat.android.messaging.conversation.Attachment
import com.bitchat.android.messaging.conversation.AttachmentKind
import com.bitchat.android.messaging.conversation.DeliveryState
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceState

/**
 * A one-to-one conversation.
 *
 * Delivery detail is present but quiet: the status under a bubble is one short phrase, and the
 * mechanics behind it are only shown if the user taps a message. Someone who never taps should
 * never learn what a hop is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: MessengerViewModel,
    counterparty: MessagingPublicKey,
    onBack: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val contacts by viewModel.allKnown.collectAsState()

    val conversation = conversations.firstOrNull { it.counterparty == counterparty }
    val contact = contacts.firstOrNull { it.publicKey == counterparty }
    val title = contact?.displayName ?: counterparty.shortForm()

    var draft by remember { mutableStateOf("") }
    var detailForMessageId by remember { mutableStateOf<String?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(counterparty) { viewModel.markRead(counterparty) }
    LaunchedEffect(conversation?.messages?.size) {
        val count = conversation?.messages?.size ?: 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    NameWithPresence(
                        name = title,
                        presence = contact?.presence ?: PresenceState.UNKNOWN
                    )
                },
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
        ) {
            if (contact?.isBlocked == true) {
                Text(
                    text = "You have blocked this person. Unblock them from their profile to " +
                        "start messaging again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }

            attachError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(conversation?.messages.orEmpty(), key = { it.messageId }) { message ->
                    MessageBubble(
                        content = message.content,
                        attachment = message.attachment,
                        onOpenAttachment = { path, name ->
                            runCatching { context.startActivity(viewModel.openAttachment(path, name)) }
                                .onFailure { attachError = "No app can open $name" }
                        },
                        isOutgoing = message.isOutgoing,
                        timestamp = message.timestampEpochMillis,
                        deliveryState = message.deliveryState,
                        showDetail = detailForMessageId == message.messageId,
                        onToggleDetail = {
                            detailForMessageId =
                                if (detailForMessageId == message.messageId) null
                                else message.messageId
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // An attachment is always addressed to the person in this conversation; there is
                // no path here that broadcasts one.
                AttachmentPickerButton(
                    onPicked = { picked ->
                        viewModel.sendAttachment(
                            recipient = counterparty,
                            filePath = picked.path,
                            fileName = picked.fileName,
                            kind = picked.kind
                        )
                    },
                    onError = { attachError = it }
                )
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
                            viewModel.sendDirect(counterparty, draft)
                            draft = ""
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    content: String,
    attachment: Attachment? = null,
    onOpenAttachment: (String, String) -> Unit = { _, _ -> },
    isOutgoing: Boolean,
    timestamp: Long,
    deliveryState: DeliveryState,
    showDetail: Boolean,
    onToggleDetail: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isOutgoing) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .clickable(enabled = isOutgoing, onClick = onToggleDetail)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (attachment != null) {
                AttachmentContent(attachment, onOpenAttachment)
            } else {
                Text(text = content, style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isOutgoing) DeliveryLabel(deliveryState)
            }

            // Only on request. The default view says "Waiting to deliver"; this says why.
            if (showDetail && isOutgoing) {
                Box(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = deliveryExplanation(deliveryState),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The longer explanation shown when a message is tapped.
 *
 * Written for someone who has no idea what a mesh is: it explains what is happening to their
 * message and what will resolve it, without naming a transport.
 */
private fun deliveryExplanation(state: DeliveryState): String = when (state) {
    DeliveryState.SENDING -> "Sending now."
    DeliveryState.SENT -> "Sent. Waiting for their device to confirm."
    DeliveryState.DELIVERED -> "Delivered to their device."
    DeliveryState.READ -> "They have read this."
    DeliveryState.PENDING_OFFLINE ->
        "Saved on your phone. It will send by itself as soon as they are close enough, " +
            "or reachable through someone nearby. No internet needed."
    DeliveryState.FAILED -> "This one could not be sent. Tap and hold to try again."
}

/**
 * How an attachment appears inside a bubble.
 *
 * A photo is shown, because that is the whole point of sending one. Anything else is a named row
 * that opens on tap — a filename someone can recognise is more use than a generic paperclip.
 */
@Composable
private fun AttachmentContent(
    attachment: Attachment,
    onOpen: (String, String) -> Unit
) {
    Column(
        modifier = Modifier.clickable { onOpen(attachment.path, attachment.fileName) },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (attachment.kind == AttachmentKind.IMAGE) {
            val bitmap = remember(attachment.path) {
                runCatching {
                    android.graphics.BitmapFactory.decodeFile(attachment.path)?.asImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap,
                    contentDescription = attachment.fileName,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                // The file is on its way, or is no longer readable. Naming it is better than an
                // empty space that looks like nothing was sent.
                Text("🖼  ${attachment.fileName}", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val icon = if (attachment.kind == AttachmentKind.VOICE) "🎤" else "📄"
            Text("$icon  ${attachment.fileName}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Tap to open",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
