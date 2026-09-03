package com.bitchat.android.campus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mesh density presented the way a phone presents cellular signal.
 *
 * Peer counts, hop distances and relay tables are meaningless to a student deciding whether to
 * trust that their message will arrive; "connected / weak / offline" is not.
 */
@Composable
fun CampusHeader(title: String, signalBars: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (signalBars) {
                    0 -> "Offline"
                    1 -> "Weak"
                    2, 3 -> "Connected"
                    else -> "Strong"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
            Icon(
                imageVector = if (signalBars == 0) {
                    Icons.Filled.SignalCellularConnectedNoInternet0Bar
                } else {
                    Icons.Filled.SignalCellularAlt
                },
                contentDescription = "Mesh signal strength: $signalBars of 4",
                tint = if (signalBars == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

fun formatCampusTime(millis: Long): String = timeFormatter.format(Date(millis))
