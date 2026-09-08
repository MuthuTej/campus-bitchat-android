package com.bitchat.android.messaging.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.messaging.conversation.DeliveryState
import com.bitchat.android.messaging.identity.IdentityAttestation
import com.bitchat.android.messaging.identity.VerificationState
import com.bitchat.android.messaging.presence.PresenceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared pieces of the messaging interface.
 *
 * The vocabulary here is deliberately ordinary — available, waiting, delivered. Nothing in this file
 * names a transport, a hop or a packet, because none of that is something the person holding the
 * phone has to understand in order to send a message to a classmate.
 */

/** A coloured dot conveying reachability at a glance. */
@Composable
fun PresenceDot(presence: PresenceState, modifier: Modifier = Modifier) {
    val colour = when (presence) {
        PresenceState.DIRECTLY_REACHABLE -> Color(0xFF2E7D32)
        PresenceState.MESH_REACHABLE -> Color(0xFFF9A825)
        PresenceState.RECENTLY_SEEN -> Color(0xFF9E9E9E)
        PresenceState.OFFLINE, PresenceState.UNKNOWN -> Color(0xFFBDBDBD)
    }
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(colour)
    )
}

/** Round monogram stand-in for a photo. */
@Composable
fun Avatar(name: String, sizeDp: Int = 44) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
            fontSize = (sizeDp / 2.4).sp
        )
    }
}

/**
 * The one-line status a message row shows.
 *
 * A waiting message says it is waiting, not that something failed and not that the network is down:
 * on this app being out of range is ordinary, and the message really will arrive.
 */
@Composable
fun DeliveryLabel(state: DeliveryState) {
    Text(
        text = state.label,
        style = MaterialTheme.typography.labelSmall,
        color = if (state == DeliveryState.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/** A row heading used by the list screens. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** Centred message for an empty list, explaining what would fill it. */
@Composable
fun EmptyState(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A person's name with their reachability beside it. */
@Composable
fun NameWithPresence(name: String, presence: PresenceState, subtitle: String? = null) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = name, style = MaterialTheme.typography.titleSmall)
            if (presence != PresenceState.UNKNOWN) {
                PresenceDot(presence)
            }
        }
        val detail = subtitle ?: presence.label.takeIf { it.isNotEmpty() }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Institutional standing, when there is any to show.
 *
 * Renders nothing at all when no claim has been made. Most people have no affiliation, and marking
 * them "unverified" would turn the ordinary case into a warning and drain the word of meaning for
 * the case that matters — someone who presented a credential that failed.
 */
@Composable
fun AttestationBadge(attestation: IdentityAttestation?) {
    if (attestation == null) return

    val (text, colour) = when (attestation.state) {
        VerificationState.VERIFIED ->
            "✓ ${attestation.subtitle.ifEmpty { "Verified" }} · ${attestation.issuerLabel}" to
                Color(0xFF2E7D32)
        VerificationState.REVOKED ->
            "⚠ ${attestation.subtitle}" to MaterialTheme.colorScheme.error
        VerificationState.INVALID ->
            "⚠ ${attestation.subtitle}" to MaterialTheme.colorScheme.error
        VerificationState.UNVERIFIED -> return
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = colour
    )
}

/** Clock time for today, date otherwise — the convention every messenger uses. */
fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return ""
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let {
        it.format(Date(millis)) == it.format(Date(now))
    }
    val pattern = if (sameDay) "HH:mm" else "d MMM"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}
