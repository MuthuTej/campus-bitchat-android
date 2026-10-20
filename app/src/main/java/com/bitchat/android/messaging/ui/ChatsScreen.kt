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
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.messaging.groups.Group
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.presence.PresenceState

/**
 * The home screen: a list of conversations, the way every messenger opens.
 *
 * The only network information shown is one line saying how many people are reachable. That is the
 * single fact that changes what the user can do right now; everything else about the mesh is
 * deliberately absent from this screen.
 */
@Composable
fun ChatsScreen(
    viewModel: MessengerViewModel,
    onOpenDirect: (MessagingPublicKey) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenAlerts: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val groups by viewModel.myGroups.collectAsState()
    val contacts by viewModel.allKnown.collectAsState()
    val reachable by viewModel.reachableCount.collectAsState()
    val alerts by viewModel.alerts.collectAsState()

    val groupMessages by viewModel.groupMessagesByGroup.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        ReachabilityBanner(reachable)

        // Bulletins stay above chats so an official notice is never read as a personal message.
        AlertsEntry(
            count = alerts.size,
            latestTitle = alerts.firstOrNull()?.title,
            onClick = onOpenAlerts
        )
        HorizontalDivider()

        if (conversations.isEmpty() && groups.isEmpty()) {
            EmptyState(
                title = "No chats yet",
                detail = "Conversations you start will appear here. " +
                    "Find people on the Contacts tab."
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(conversations, key = { it.id.value }) { conversation ->
                val contact = contacts.firstOrNull { it.publicKey == conversation.counterparty }
                ConversationRow(
                    title = contact?.displayName ?: conversation.counterparty.shortForm(),
                    preview = conversation.lastMessage?.content.orEmpty(),
                    timestamp = conversation.lastActivityMillis,
                    unread = conversation.unreadCount,
                    presence = contact?.presence ?: PresenceState.UNKNOWN,
                    onClick = { onOpenDirect(conversation.counterparty) }
                )
                HorizontalDivider()
            }

            items(groups, key = { it.id }) { group ->
                val last = groupMessages[group.id]?.lastOrNull()
                GroupRow(
                    group = group,
                    preview = last?.content.orEmpty(),
                    timestamp = last?.timestampEpochMillis ?: group.createdAtMillis,
                    onClick = { onOpenGroup(group.id) }
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * One line of network status, in plain words.
 *
 * Says what the user can do — reach people — rather than which radio is up.
 */
@Composable
private fun ReachabilityBanner(reachableCount: Int) {
    val text = when (reachableCount) {
        0 -> "Nobody reachable nearby"
        1 -> "1 person reachable"
        else -> "$reachableCount people reachable"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PresenceDot(
            if (reachableCount > 0) PresenceState.DIRECTLY_REACHABLE else PresenceState.OFFLINE
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A distinct, tinted entry for institutional announcements. */
@Composable
private fun AlertsEntry(count: Int, latestTitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            Icons.Filled.Campaign,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Campus bulletins",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = when {
                    count == 0 -> "Official notices from staff. Not a personal chat."
                    latestTitle != null -> latestTitle
                    count == 1 -> "1 announcement"
                    else -> "$count announcements"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (count > 0) {
            Badge { Text(count.toString()) }
        }
    }
}

@Composable
private fun ConversationRow(
    title: String,
    preview: String,
    timestamp: Long,
    unread: Int,
    presence: PresenceState,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(title)
        Column(modifier = Modifier.weight(1f)) {
            NameWithPresence(name = title, presence = presence, subtitle = " ")
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatTimestamp(timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (unread > 0) Badge { Text(unread.toString()) }
        }
    }
}

@Composable
private fun GroupRow(
    group: Group,
    preview: String,
    timestamp: Long,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Avatar(group.name)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = group.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = preview.ifEmpty { "${group.members.size} members" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatTimestamp(timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
