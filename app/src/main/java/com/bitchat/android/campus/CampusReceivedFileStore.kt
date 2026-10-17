package com.bitchat.android.campus

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Persists completed downloads and hands them to a viewer without leaking filesystem paths.
 *
 * Files land in app-private storage and are exposed only through the existing FileProvider, so the
 * viewing app receives a temporary, scoped content:// grant rather than a path it could re-open
 * later. Nothing here is written until the transfer's SHA-256 has already been verified — a
 * corrupted document is never materialised on disk.
 */
class CampusReceivedFileStore(private val context: Context) {

    private val directory: File
        get() = File(context.filesDir, "campus/incoming").apply { if (!exists()) mkdirs() }

    /**
     * Write [bytes] under a sanitised, collision-free name.
     *
     * The sender controls [fileName], so path separators and traversal sequences are stripped rather
     * than trusted: a peer must not be able to name a file "../../databases/identity" and have us
     * write outside the incoming directory.
     */
    fun save(fileName: String, bytes: ByteArray): File {
        val safeName = sanitise(fileName)
        val target = File(directory, "${System.currentTimeMillis()}_$safeName")
        target.writeBytes(bytes)
        return target
    }

    /** Intent that opens [file] in the user's document viewer with a scoped, read-only grant. */
    fun openIntent(file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun sanitise(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return cleaned.ifBlank { "document" }.take(120)
    }
}
