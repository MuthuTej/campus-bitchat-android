package com.bitchat.android.messaging.conversation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.bitchat.android.messaging.data.MessagingDatabase
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.ConversationId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Conversation history on disk.
 *
 * Message text is encrypted at rest under the same Keystore-backed key the rest of this database
 * uses, bound by associated data to the message it belongs to so a stored message cannot be moved
 * into another conversation.
 *
 * Writes are serialized on one thread. A message and the delivery state that follows it must land
 * in the order they happened, or a restart could show a message as still sending when it was
 * delivered before the app closed.
 */
class DirectMessageRepository internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "messaging-history-store").apply { isDaemon = true }
        }
        .asCoroutineDispatcher(),
    databaseName: String = MessagingDatabase.DEFAULT_DATABASE_NAME,
    storageCipher: com.bitchat.android.services.ConversationStorageCipher =
        com.bitchat.android.services.AndroidConversationStorageCipher(
            MessagingDatabase.MESSAGING_KEY_ALIAS
        )
) : DirectMessagePersistence {

    private val database = MessagingDatabase(
        context.applicationContext,
        databaseName,
        storageCipher
    )
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    override suspend fun loadMessages(): List<DirectMessage> = withContext(dispatcher) {
        database.readableDatabase.query(
            MessagingDatabase.TABLE_DIRECT_MESSAGES,
            null, null, null, null, null, "sent_at"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    hydrate(cursor)?.let(::add)
                }
            }
        }
    }

    override suspend fun loadReadConversations(): Set<ConversationId> = withContext(dispatcher) {
        database.readableDatabase.query(
            MessagingDatabase.TABLE_READ_CONVERSATIONS,
            arrayOf("conversation_id"), null, null, null, null, null
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    ConversationId.parse(cursor.getString(0))?.let(::add)
                }
            }
        }
    }

    override fun persist(message: DirectMessage) {
        scope.launch {
            val values = ContentValues().apply {
                put("message_id", message.messageId)
                put("conversation_id", message.conversationId.value)
                put("sender_key", message.senderPublicKey.hex)
                put("recipient_key", message.recipientPublicKey.hex)
                put(
                    "content_ciphertext",
                    database.sealFor(
                        contentContext(message.messageId),
                        message.content.toByteArray(Charsets.UTF_8)
                    )
                )
                put("sent_at", message.timestampEpochMillis)
                put("is_outgoing", if (message.isOutgoing) 1 else 0)
                put("delivery_state", message.deliveryState.name)
            }
            database.writableDatabase.insertWithOnConflict(
                MessagingDatabase.TABLE_DIRECT_MESSAGES,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    override fun persistDeliveryState(messageId: String, state: DeliveryState) {
        scope.launch {
            database.writableDatabase.update(
                MessagingDatabase.TABLE_DIRECT_MESSAGES,
                ContentValues().apply { put("delivery_state", state.name) },
                "message_id = ?",
                arrayOf(messageId)
            )
        }
    }

    override fun persistRead(conversationId: ConversationId) {
        scope.launch {
            database.writableDatabase.insertWithOnConflict(
                MessagingDatabase.TABLE_READ_CONVERSATIONS,
                null,
                ContentValues().apply {
                    put("conversation_id", conversationId.value)
                    put("read_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /**
     * Rebuild one stored message.
     *
     * A row whose keys or content cannot be read is skipped rather than surfaced as a partial
     * message: showing a conversation entry with a missing sender or unreadable text would be worse
     * than showing one fewer message.
     */
    private fun hydrate(cursor: android.database.Cursor): DirectMessage? {
        fun str(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))

        val messageId = str("message_id")
        val conversationId = ConversationId.parse(str("conversation_id")) ?: return null
        val sender = MessagingPublicKey.parse(str("sender_key")) ?: return null
        val recipient = MessagingPublicKey.parse(str("recipient_key")) ?: return null
        val content = database.unsealFor(
            contentContext(messageId),
            cursor.getBlob(cursor.getColumnIndexOrThrow("content_ciphertext"))
        )?.toString(Charsets.UTF_8) ?: return null
        val state = runCatching { DeliveryState.valueOf(str("delivery_state")) }.getOrNull()
            ?: DeliveryState.SENT

        return DirectMessage(
            messageId = messageId,
            conversationId = conversationId,
            senderPublicKey = sender,
            recipientPublicKey = recipient,
            content = content,
            timestampEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("sent_at")),
            isOutgoing = cursor.getInt(cursor.getColumnIndexOrThrow("is_outgoing")) == 1,
            deliveryState = state
        )
    }

    internal fun close() = database.close()

    private companion object {
        fun contentContext(messageId: String) = "dm:$messageId"
    }
}
