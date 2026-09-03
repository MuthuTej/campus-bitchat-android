package com.bitchat.android.campus.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bitchat.android.campus.CampusConfig
import com.bitchat.android.campus.CampusFileTransferService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A document the user picked, already read into memory and validated. */
data class SelectedDocument(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
) {
    val sizeLabel: String get() = formatBytes(bytes.size.toLong())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SelectedDocument) return false
        return fileName == other.fileName && mimeType == other.mimeType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Attachment button using the Storage Access Framework.
 *
 * The picker is filtered to [CampusConfig.supportedFileMimeTypes], so no broad storage permission is
 * ever requested — the user grants access to one document at a time. The read itself runs on
 * [Dispatchers.IO]: a multi-megabyte PDF read on the main thread freezes the UI for the duration,
 * which is exactly the failure the stock picker exhibits.
 */
@Composable
fun CampusDocumentPickerButton(
    modifier: Modifier = Modifier,
    onDocumentSelected: (SelectedDocument) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { readDocument(context, uri) }
            outcome.fold(onSuccess = onDocumentSelected, onFailure = { onError(it.message ?: "Could not read document") })
        }
    }

    IconButton(
        onClick = { picker.launch(CampusConfig.supportedFileMimeTypes.toTypedArray()) },
        modifier = modifier.size(40.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.AttachFile,
            contentDescription = "Attach a document",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Read and validate a picked document. Must be called off the main thread.
 *
 * Size is checked against the transfer ceiling before the bytes are read, so a user who picks a
 * 900 MB file gets a clear error instead of an OutOfMemoryError.
 */
private fun readDocument(
    context: android.content.Context,
    uri: Uri
): Result<SelectedDocument> = try {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"

    if (!CampusConfig.isSupportedFileType(mimeType)) {
        Result.failure(IllegalArgumentException("That file type is not supported"))
    } else {
        var displayName = "document"
        var declaredSize = -1L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { displayName = cursor.getString(it) ?: displayName }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { declaredSize = cursor.getLong(it) }
            }
        }

        if (declaredSize > CampusFileTransferService.DEFAULT_MAX_FILE_SIZE) {
            Result.failure(
                IllegalArgumentException(
                    "That document is too large (max ${formatBytes(CampusFileTransferService.DEFAULT_MAX_FILE_SIZE)})"
                )
            )
        } else {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(java.io.IOException("Could not open document"))
            if (bytes.size > CampusFileTransferService.DEFAULT_MAX_FILE_SIZE) {
                Result.failure(IllegalArgumentException("That document is too large"))
            } else {
                Result.success(SelectedDocument(displayName, mimeType, bytes))
            }
        }
    }
} catch (e: SecurityException) {
    Result.failure(IllegalStateException("Permission denied for that document"))
} catch (e: java.io.IOException) {
    Result.failure(IllegalStateException("Could not read that document"))
} catch (e: OutOfMemoryError) {
    Result.failure(IllegalStateException("That document is too large to open"))
}
