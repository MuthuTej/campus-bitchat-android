package com.bitchat.android.messaging.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bitchat.android.services.AndroidConversationStorageCipher
import com.bitchat.android.services.ConversationStorageCipher

/**
 * Durable storage for the application layer: who the user knows, and what they chose to remember.
 *
 * ## Why this is separate from the conversation database
 *
 * Messages and conversations already have a home in `ConversationRepository`, and they stay there —
 * this database deliberately stores neither, so there is exactly one message store in the app.
 * What it holds is the set of entities that previously had nowhere to live and were therefore lost
 * on every process death: known identities, saved contacts, and later groups and their membership.
 *
 * It is a second database rather than new tables on the conversation database because the
 * conversation database is opened by every build, including the stock upstream one that has no
 * concept of contacts or groups. Adding tables there would force a schema migration on installs
 * that would never read a single row of them. The cost of the split is one extra file handle; the
 * benefit is that the upstream storage path is untouched.
 *
 * ## At rest
 *
 * Personal fields — display names and phone numbers — are encrypted with the same audited
 * Keystore-backed AES-GCM implementation the conversation database uses, under its own key alias so
 * that erasing one store cannot decrypt the other. Every value is bound by associated data to the
 * public key of the row holding it, so a ciphertext lifted from one row cannot be replayed into
 * another to make one person's name display under another's key.
 */
internal class MessagingDatabase(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val storageCipher: ConversationStorageCipher =
        AndroidConversationStorageCipher(MESSAGING_KEY_ALIAS)
) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    companion object {
        internal const val DEFAULT_DATABASE_NAME = "messaging_app_state.db"
        internal const val DATABASE_VERSION = 3
        internal const val MESSAGING_KEY_ALIAS = "bitchat_messaging_storage_v1"

        internal const val TABLE_IDENTITIES = "identities"
        internal const val TABLE_GROUPS = "groups_"
        internal const val TABLE_GROUP_MEMBERS = "group_members"
        internal const val TABLE_GROUP_KEYS = "group_keys"
        internal const val TABLE_DIRECT_MESSAGES = "direct_messages"
        internal const val TABLE_READ_CONVERSATIONS = "read_conversations"
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // Names and numbers are personal data; overwrite freed cells rather than leaving them to be
        // recovered from reclaimed pages after a contact is deleted.
        db.rawQuery("PRAGMA secure_delete = ON", null).use { cursor ->
            if (cursor.moveToFirst()) Unit
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_IDENTITIES (
                public_key TEXT PRIMARY KEY NOT NULL,
                display_name_ciphertext BLOB,
                phone_number_ciphertext BLOB,
                is_contact INTEGER NOT NULL DEFAULT 0,
                is_blocked INTEGER NOT NULL DEFAULT 0,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_identities_contact_seen " +
                "ON $TABLE_IDENTITIES(is_contact, last_seen_at)"
        )
        createGroupTables(db)
        createMessageTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Tables are added rather than the file being recreated, so saved contacts survive an
        // update.
        if (oldVersion < 2) createGroupTables(db)
        if (oldVersion < 3) createMessageTables(db)
    }

    /**
     * Direct message history.
     *
     * Conversations previously existed only in memory, so every restart erased both sides of every
     * chat — the app forgot what had been said the moment it was closed, which is the one thing a
     * messenger cannot do. Message text is encrypted at rest like every other personal field.
     */
    private fun createMessageTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DIRECT_MESSAGES (
                message_id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                sender_key TEXT NOT NULL,
                recipient_key TEXT NOT NULL,
                content_ciphertext BLOB NOT NULL,
                sent_at INTEGER NOT NULL,
                is_outgoing INTEGER NOT NULL,
                delivery_state TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_direct_messages_conversation " +
                "ON $TABLE_DIRECT_MESSAGES(conversation_id, sent_at)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_READ_CONVERSATIONS (
                conversation_id TEXT PRIMARY KEY NOT NULL,
                read_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /**
     * Groups, their membership, and their keys.
     *
     * Key material is stored encrypted like any other secret. Losing it would be worse than losing
     * a contact name: without the key a group's history becomes permanently unreadable even to its
     * legitimate members, so it has to persist — but it must never sit on disk in the clear.
     */
    private fun createGroupTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_GROUPS (
                group_id TEXT PRIMARY KEY NOT NULL,
                name_ciphertext BLOB,
                description_ciphertext BLOB,
                created_by TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                default_permissions TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_GROUP_MEMBERS (
                group_id TEXT NOT NULL,
                public_key TEXT NOT NULL,
                role TEXT NOT NULL,
                permission_override TEXT,
                joined_at INTEGER NOT NULL,
                PRIMARY KEY (group_id, public_key),
                FOREIGN KEY(group_id) REFERENCES $TABLE_GROUPS(group_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_GROUP_KEYS (
                group_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                material_ciphertext BLOB NOT NULL,
                is_current INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (group_id, version),
                FOREIGN KEY(group_id) REFERENCES $TABLE_GROUPS(group_id)
                    ON DELETE CASCADE ON UPDATE CASCADE
            )
            """.trimIndent()
        )
    }

    /** Seal a value against an arbitrary context string, for tables other than identities. */
    fun sealFor(context: String, value: ByteArray?): ByteArray? =
        value?.takeIf { it.isNotEmpty() }?.let {
            storageCipher.encrypt(it, context.toByteArray(Charsets.UTF_8))
        }

    fun unsealFor(context: String, envelope: ByteArray?): ByteArray? {
        if (envelope == null || envelope.isEmpty()) return null
        return runCatching {
            storageCipher.decrypt(envelope, context.toByteArray(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * Encrypt a personal field for storage under [publicKey].
     *
     * The key is the associated data, which is what stops a ciphertext being moved between rows.
     */
    fun seal(publicKey: String, value: String?): ByteArray? =
        value?.takeIf { it.isNotEmpty() }?.let {
            storageCipher.encrypt(it.toByteArray(Charsets.UTF_8), associatedDataFor(publicKey))
        }

    /**
     * Decrypt a personal field.
     *
     * Returns null rather than throwing when a value cannot be opened: a row written under a key
     * that has since been destroyed is unreadable but must not prevent the rest of the contact list
     * from loading.
     */
    fun unseal(publicKey: String, envelope: ByteArray?): String? {
        if (envelope == null || envelope.isEmpty()) return null
        return runCatching {
            String(storageCipher.decrypt(envelope, associatedDataFor(publicKey)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun associatedDataFor(publicKey: String): ByteArray =
        "$TABLE_IDENTITIES:$publicKey".toByteArray(Charsets.UTF_8)

    /** Convenience for the repository: values common to every identity write. */
    fun timestamps(values: ContentValues, now: Long, isInsert: Boolean) {
        if (isInsert) values.put("first_seen_at", now)
        values.put("updated_at", now)
    }
}
