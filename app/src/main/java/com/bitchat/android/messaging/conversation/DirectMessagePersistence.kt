package com.bitchat.android.messaging.conversation

import com.bitchat.android.messaging.protocol.ConversationId

/**
 * Where conversation history is kept.
 *
 * Declared as a seam rather than a dependency so the conversation logic stays free of Android and
 * remains exercisable without a database. Every method is fire-and-forget: writing history must
 * never make sending a message slower or able to fail, and a write that loses a race is recovered
 * on the next one rather than surfaced to the user.
 */
interface DirectMessagePersistence {

    /** Everything previously exchanged, oldest first. */
    suspend fun loadMessages(): List<DirectMessage>

    /** Conversations the user had already read when the app last closed. */
    suspend fun loadReadConversations(): Set<ConversationId>

    fun persist(message: DirectMessage)

    fun persistDeliveryState(messageId: String, state: DeliveryState)

    fun persistRead(conversationId: ConversationId)
}
