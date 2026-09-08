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
    fun buildPrivateMessagePreview(message: BitchatMessage): String {
        // An application envelope wraps the text in framing that means nothing to a reader. Show
        // what was actually written; control traffic has nothing to show and yields an empty
        // preview, which the notifier treats as "do not announce this".
        com.bitchat.android.messaging.protocol.MessagingEnvelope
            .previewPayload(message.content)
            ?.let { payload ->
                return if (
                    com.bitchat.android.messaging.protocol.MessagingEnvelope
                        .isControlTraffic(message.content)
                ) "" else payload
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
