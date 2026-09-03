package com.bitchat.android.campus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusFileTransferService

/**
 * Confirmation step before a document is sent.
 *
 * Shown because a mesh transfer is expensive — it occupies the radio and every relaying peer's
 * battery — so an accidental attachment should be cancellable before it starts, not after.
 */
@Composable
fun CampusDocumentPreview(
    document: SelectedDocument,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "📄 ${document.fileName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = document.sizeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onSend) { Text("Send") }
        }
    }
}

/**
 * Live state of one transfer.
 *
 * PAUSED is shown rather than hidden: on a courier mesh a paused transfer is the normal case when a
 * peer walks away, and telling the user it will resume is more useful than a stalled progress bar.
 */
@Composable
fun CampusTransferRow(progress: CampusFileTransferService.Progress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = progress.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = when (progress.state) {
                    CampusFileTransferService.State.OFFERED -> "Waiting"
                    CampusFileTransferService.State.ACTIVE ->
                        "${progress.chunksDone}/${progress.totalChunks}"
                    CampusFileTransferService.State.PAUSED -> "Paused"
                    CampusFileTransferService.State.COMPLETE -> "Sent"
                    CampusFileTransferService.State.FAILED ->
                        progress.detail ?: "Failed"
                    CampusFileTransferService.State.REJECTED -> progress.detail ?: "Rejected"
                    CampusFileTransferService.State.CANCELLED -> "Cancelled"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (progress.state) {
                    CampusFileTransferService.State.FAILED,
                    CampusFileTransferService.State.REJECTED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A completed download.
 *
 * Only rendered after hash verification passed, so "Downloaded successfully" is a claim the app can
 * actually stand behind rather than a statement that bytes stopped arriving.
 */
@Composable
fun CampusDownloadRow(fileName: String, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "📄 $fileName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Downloaded successfully",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(onClick = onOpen) { Text("Open") }
        }
    }
}
