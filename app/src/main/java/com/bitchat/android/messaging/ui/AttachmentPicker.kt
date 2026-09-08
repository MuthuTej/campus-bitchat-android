package com.bitchat.android.messaging.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bitchat.android.messaging.conversation.AttachmentKind
import java.io.File

/** A file the user chose, already copied somewhere the transport can read it. */
data class PickedAttachment(
    val path: String,
    val fileName: String,
    val kind: AttachmentKind
)

/**
 * Choosing a photo or a document to send.
 *
 * The picked content is copied into the app's own storage before anything else happens. A content
 * URI is a temporary grant belonging to another app, and a transfer that survives the user leaving
 * the screen — or being carried across the room before a route appears — cannot depend on that
 * grant still being valid when the bytes are finally read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerButton(
    onPicked: (PickedAttachment) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var showChoices by remember { mutableStateOf(false) }

    fun handle(uri: Uri?, kind: AttachmentKind) {
        showChoices = false
        if (uri == null) return
        val picked = copyIntoAppStorage(context, uri, kind)
        if (picked == null) onError("That file could not be read") else onPicked(picked)
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { handle(it, AttachmentKind.IMAGE) }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { handle(it, AttachmentKind.FILE) }

    IconButton(onClick = { showChoices = true }) {
        Icon(Icons.Filled.AttachFile, contentDescription = "Attach")
    }

    if (showChoices) {
        ModalBottomSheet(onDismissRequest = { showChoices = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Send",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                )
                TextButton(
                    onClick = { imageLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Text("  Photo", modifier = Modifier.fillMaxWidth())
                }
                TextButton(
                    onClick = { documentLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Text("  Document", modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Copy the chosen content into app storage and return where it landed.
 *
 * Returns null rather than throwing when the source cannot be read, so a revoked permission or a
 * file that vanished between choosing and reading becomes a message to the user instead of a crash.
 */
private fun copyIntoAppStorage(
    context: Context,
    uri: Uri,
    kind: AttachmentKind
): PickedAttachment? = runCatching {
    val name = displayNameOf(context, uri) ?: "attachment"
    val outDir = File(context.filesDir, "outgoing").apply { mkdirs() }
    // Prefixed with a unique value so two files of the same name cannot overwrite one another
    // while either is still in flight.
    val target = File(outDir, "${System.currentTimeMillis()}_$name")

    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return null

    PickedAttachment(path = target.absolutePath, fileName = name, kind = kind)
}.getOrNull()

private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
