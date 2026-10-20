package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType

/**
 * Utilities for building human-friendly notification text/previews.
 */
object NotificationTextUtils {
    /**
     * Build a user-friendly notification preview for private messages, especially attachments.
     * Examples:
     * - Image: "📷 sent an image"
     * - Audio: "🎤 sent a voice message"
     * - File (pdf): "📄 file.pdf"
     * - Text: original message content
     */
    private const val ENVELOPE_PREFIX = "BCM1"
    private const val ENVELOPE_HEADER_BYTES = 4 + 1 + 16 // prefix (4) + kind (1) + recipient tag (16)

    fun buildPrivateMessagePreview(message: BitchatMessage): String {
        // An application envelope wraps the text in framing that means nothing to a reader. Show
        // what was actually written; control traffic has nothing to show and yields an empty
        // preview, which the notifier treats as "do not announce this".
        if (message.content.length > ENVELOPE_HEADER_BYTES && message.content.startsWith(ENVELOPE_PREFIX)) {
            val kind = message.content[ENVELOPE_PREFIX.length]
            return if (kind == 'T') {
                message.content.drop(ENVELOPE_HEADER_BYTES)
            } else {
                ""
            }
        }
        return try {
            when (message.type) {
                BitchatMessageType.Image -> "📷 sent an image"
                BitchatMessageType.Audio -> "🎤 sent a voice message"
                BitchatMessageType.File -> {
                    // Show just the filename (not the full path)
                    val name = try { java.io.File(message.content).name } catch (_: Exception) { null }
                    if (!name.isNullOrBlank()) {
                        val lower = name.lowercase()
                        val icon = when {
                            lower.endsWith(".pdf") -> "📄"
                            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") -> "🗜️"
                            lower.endsWith(".doc") || lower.endsWith(".docx") -> "📄"
                            lower.endsWith(".xls") || lower.endsWith(".xlsx") -> "📊"
                            lower.endsWith(".ppt") || lower.endsWith(".pptx") -> "📈"
                            else -> "📎"
                        }
                        "$icon $name"
                    } else {
                        "📎 sent a file"
                    }
                }
                else -> message.content
            }
        } catch (_: Exception) {
            // Fallback to original content on any error
            message.content
        }
    }
}
