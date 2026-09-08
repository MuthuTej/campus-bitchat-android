package com.bitchat.android.messaging.identity

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.bitchat.android.messaging.data.MessagingDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * An identity the device has encountered, together with what the local user decided about it.
 *
 * [identity] is what the other party asserts about themselves. Everything else on this type is a
 * local decision or a local observation, and none of it is trusted input from the network.
 */
data class StoredIdentity(
    val identity: MessagingIdentity,
    /** The user explicitly saved this person; distinguishes a contact from a peer merely seen. */
    val isContact: Boolean,
    val isBlocked: Boolean,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long
) {
    val publicKey: MessagingPublicKey get() = identity.publicKey
}

/**
 * Durable knowledge of who the user has met.
 *
 * Exists because identities previously lived only in a process-lifetime map: every restart erased
 * every name the device had learned, so people the user had been talking to for weeks reverted to
 * anonymous keys. Persisting the association is what makes a contact list possible at all.
 *
 * Deliberately stores no presence and no reachability. Those describe the network right now, and a
 * value read back from disk is by definition not current — showing a stale "available" would be a
 * worse answer than showing nothing. They are derived live in the presence layer instead.
 *
 * Writes are serialized on a single thread, matching the conversation store, so a rename racing an
 * insert cannot interleave into a half-applied row.
 */
class IdentityRepository internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "messaging-identity-store").apply { isDaemon = true }
        }
        .asCoroutineDispatcher(),
    databaseName: String = MessagingDatabase.DEFAULT_DATABASE_NAME,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    storageCipher: com.bitchat.android.services.ConversationStorageCipher =
        com.bitchat.android.services.AndroidConversationStorageCipher(
            MessagingDatabase.MESSAGING_KEY_ALIAS
        )
) {

    private val database = MessagingDatabase(
        context.applicationContext,
        databaseName,
        storageCipher
    )

    private val _identities = MutableStateFlow<List<StoredIdentity>>(emptyList())

    /** Every identity the device knows, most recently seen first. */
    val identities: StateFlow<List<StoredIdentity>> = _identities.asStateFlow()

    companion object {
        @Volatile
        private var instance: IdentityRepository? = null

        fun getInstance(context: Context): IdentityRepository =
            instance ?: synchronized(this) {
                instance ?: IdentityRepository(context.applicationContext).also { instance = it }
            }

        internal fun resetForTesting() {
            instance = null
        }
    }

    /**
     * Record that an identity exists, or refresh when it was last seen.
     *
     * Announced display names are treated as a cache of what the peer claims, not as an authority:
     * a blank or missing name never erases a name already stored, so a peer cannot blank out how
     * they appear in someone else's contact list by announcing an empty string.
     */
    suspend fun remember(identity: MessagingIdentity): StoredIdentity = withContext(dispatcher) {
        val now = clockMillis()
        val key = identity.publicKey.hex
        val existing = readRow(key)

        val values = ContentValues().apply {
            put("public_key", key)
            val name = identity.displayName.ifEmpty { existing?.identity?.displayName.orEmpty() }
            put("display_name_ciphertext", database.seal(key, name))
            // The phone number is local metadata: only overwrite when a value is actually supplied.
            val phone = identity.phoneNumber ?: existing?.identity?.phoneNumber
            put("phone_number_ciphertext", database.seal(key, phone))
            put("is_contact", if (existing?.isContact == true) 1 else 0)
            put("is_blocked", if (existing?.isBlocked == true) 1 else 0)
            put("first_seen_at", existing?.firstSeenAtMillis ?: now)
            put("last_seen_at", now)
            put("updated_at", now)
        }
        database.writableDatabase.insertWithOnConflict(
            MessagingDatabase.TABLE_IDENTITIES,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        refreshUnsafe()
        requireNotNull(readRow(key))
    }

    suspend fun get(publicKey: MessagingPublicKey): StoredIdentity? = withContext(dispatcher) {
        readRow(publicKey.hex)
    }

    /** Save or unsave someone as a contact. Returns false when the identity is unknown. */
    suspend fun setContact(publicKey: MessagingPublicKey, isContact: Boolean): Boolean =
        updateFlag(publicKey, "is_contact", isContact)

    /**
     * Block or unblock someone.
     *
     * Blocking is a local decision and is enforced locally: it is not announced, because telling
     * someone they have been blocked is itself information the user did not choose to share.
     */
    suspend fun setBlocked(publicKey: MessagingPublicKey, isBlocked: Boolean): Boolean =
        updateFlag(publicKey, "is_blocked", isBlocked)

    /** Replace the locally stored name for someone. */
    suspend fun setDisplayName(publicKey: MessagingPublicKey, displayName: String): Boolean =
        withContext(dispatcher) {
            val key = publicKey.hex
            if (readRow(key) == null) return@withContext false
            val values = ContentValues().apply {
                put(
                    "display_name_ciphertext",
                    database.seal(key, MessagingIdentity.sanitizeDisplayName(displayName))
                )
                put("updated_at", clockMillis())
            }
            val rows = database.writableDatabase.update(
                MessagingDatabase.TABLE_IDENTITIES, values, "public_key = ?", arrayOf(key)
            )
            refreshUnsafe()
            rows > 0
        }

    /** Forget an identity entirely, including the personal fields stored for it. */
    suspend fun forget(publicKey: MessagingPublicKey): Boolean = withContext(dispatcher) {
        val rows = database.writableDatabase.delete(
            MessagingDatabase.TABLE_IDENTITIES, "public_key = ?", arrayOf(publicKey.hex)
        )
        refreshUnsafe()
        rows > 0
    }

    /** Re-read everything from disk into [identities]. Call once at startup. */
    suspend fun load() = withContext(dispatcher) { refreshUnsafe() }

    private suspend fun updateFlag(
        publicKey: MessagingPublicKey,
        column: String,
        value: Boolean
    ): Boolean = withContext(dispatcher) {
        val key = publicKey.hex
        if (readRow(key) == null) return@withContext false
        val values = ContentValues().apply {
            put(column, if (value) 1 else 0)
            put("updated_at", clockMillis())
        }
        val rows = database.writableDatabase.update(
            MessagingDatabase.TABLE_IDENTITIES, values, "public_key = ?", arrayOf(key)
        )
        refreshUnsafe()
        rows > 0
    }

    /** Must be called on [dispatcher]; publishes the current table contents. */
    private fun refreshUnsafe() {
        _identities.value = readAll()
    }

    private fun readAll(): List<StoredIdentity> =
        database.readableDatabase.query(
            MessagingDatabase.TABLE_IDENTITIES,
            null, null, null, null, null,
            "last_seen_at DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    hydrate(cursor)?.let(::add)
                }
            }
        }

    private fun readRow(publicKeyHex: String): StoredIdentity? =
        database.readableDatabase.query(
            MessagingDatabase.TABLE_IDENTITIES,
            null, "public_key = ?", arrayOf(publicKeyHex), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) hydrate(cursor) else null
        }

    /**
     * Build an identity from a row, or null when the row's key is unreadable.
     *
     * A malformed key means the row cannot be addressed or verified, so it is skipped rather than
     * surfaced as a contact nobody can message.
     */
    private fun hydrate(cursor: Cursor): StoredIdentity? {
        val keyHex = cursor.getString(cursor.getColumnIndexOrThrow("public_key"))
        val publicKey = MessagingPublicKey.parse(keyHex) ?: return null
        val name = database.unseal(
            keyHex, cursor.getBlobOrNull("display_name_ciphertext")
        )
        val phone = database.unseal(
            keyHex, cursor.getBlobOrNull("phone_number_ciphertext")
        )
        return StoredIdentity(
            identity = MessagingIdentity.of(publicKey, name, phone),
            isContact = cursor.getInt(cursor.getColumnIndexOrThrow("is_contact")) == 1,
            isBlocked = cursor.getInt(cursor.getColumnIndexOrThrow("is_blocked")) == 1,
            firstSeenAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen_at")),
            lastSeenAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen_at"))
        )
    }

    private fun Cursor.getBlobOrNull(column: String): ByteArray? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getBlob(index)
    }

    internal fun close() = database.close()
}
