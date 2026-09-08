package com.bitchat.android.messaging.groups

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.bitchat.android.messaging.data.MessagingDatabase
import com.bitchat.android.messaging.identity.MessagingPublicKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Durable storage for groups, their membership, and their keys.
 *
 * Without this a restart destroyed every group and every key, which for a group is not merely
 * inconvenient: the key is the only thing that makes the group's history readable, so losing it
 * locks out even its legitimate members permanently. Membership is reconstructible from other
 * people; a discarded key is not.
 *
 * Key material is encrypted at rest under the same Keystore-backed cipher the rest of this database
 * uses, bound by associated data to the group and generation it belongs to so a stored key cannot
 * be moved between groups.
 */
class GroupRepository internal constructor(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Executors
        .newSingleThreadExecutor { runnable ->
            Thread(runnable, "messaging-group-store").apply { isDaemon = true }
        }
        .asCoroutineDispatcher(),
    databaseName: String = MessagingDatabase.DEFAULT_DATABASE_NAME,
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

    /** Persist a group, replacing whatever was stored for it. */
    suspend fun save(group: Group) = withContext(dispatcher) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict(
                MessagingDatabase.TABLE_GROUPS,
                null,
                ContentValues().apply {
                    put("group_id", group.id)
                    put("name_ciphertext", database.sealFor(groupContext(group.id), group.name.toByteArray()))
                    put(
                        "description_ciphertext",
                        database.sealFor(groupContext(group.id), group.description.toByteArray())
                    )
                    put("created_by", group.createdBy.hex)
                    put("created_at", group.createdAtMillis)
                    put("default_permissions", encodePermissions(group.defaultMemberPermissions))
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )

            // Replaced wholesale rather than diffed: a member removed from the in-memory group must
            // not survive on disk, which a partial update could easily allow.
            db.delete(
                MessagingDatabase.TABLE_GROUP_MEMBERS, "group_id = ?", arrayOf(group.id)
            )
            group.members.forEach { member ->
                db.insertWithOnConflict(
                    MessagingDatabase.TABLE_GROUP_MEMBERS,
                    null,
                    ContentValues().apply {
                        put("group_id", group.id)
                        put("public_key", member.publicKey.hex)
                        put("role", member.role.name)
                        put(
                            "permission_override",
                            member.permissionOverride?.let(::encodePermissions)
                        )
                        put("joined_at", member.joinedAtMillis)
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(groupId: String) = withContext(dispatcher) {
        database.writableDatabase.delete(
            MessagingDatabase.TABLE_GROUPS, "group_id = ?", arrayOf(groupId)
        )
        Unit
    }

    /** Every stored group. */
    suspend fun loadGroups(): List<Group> = withContext(dispatcher) {
        val db = database.readableDatabase
        db.query(MessagingDatabase.TABLE_GROUPS, null, null, null, null, null, "created_at").use {
            buildList {
                while (it.moveToNext()) {
                    hydrateGroup(it)?.let(::add)
                }
            }
        }
    }

    /** Persist one key generation. */
    suspend fun saveKey(key: GroupKey, isCurrent: Boolean) = withContext(dispatcher) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            if (isCurrent) {
                db.update(
                    MessagingDatabase.TABLE_GROUP_KEYS,
                    ContentValues().apply { put("is_current", 0) },
                    "group_id = ?",
                    arrayOf(key.groupId)
                )
            }
            db.insertWithOnConflict(
                MessagingDatabase.TABLE_GROUP_KEYS,
                null,
                ContentValues().apply {
                    put("group_id", key.groupId)
                    put("version", key.version)
                    put(
                        "material_ciphertext",
                        database.sealFor(keyContext(key.groupId, key.version), key.material)
                    )
                    put("is_current", if (isCurrent) 1 else 0)
                },
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Every stored key, with the current generation flagged.
     *
     * Superseded generations are returned too: discarding them would make history unreadable after
     * a rotation, which is a different failure from being excluded going forward.
     */
    suspend fun loadKeys(): List<Pair<GroupKey, Boolean>> = withContext(dispatcher) {
        database.readableDatabase.query(
            MessagingDatabase.TABLE_GROUP_KEYS, null, null, null, null, null, "group_id, version"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val groupId = cursor.getString(cursor.getColumnIndexOrThrow("group_id"))
                    val version = cursor.getInt(cursor.getColumnIndexOrThrow("version"))
                    val sealed = cursor.getBlob(
                        cursor.getColumnIndexOrThrow("material_ciphertext")
                    )
                    val material = database.unsealFor(keyContext(groupId, version), sealed)
                        ?.takeIf { it.size == GroupKey.KEY_BYTES }
                        ?: continue
                    val isCurrent = cursor.getInt(cursor.getColumnIndexOrThrow("is_current")) == 1
                    add(GroupKey(groupId, version, material) to isCurrent)
                }
            }
        }
    }

    private fun hydrateGroup(cursor: Cursor): Group? {
        val id = cursor.getString(cursor.getColumnIndexOrThrow("group_id"))
        val createdBy = MessagingPublicKey.parse(
            cursor.getString(cursor.getColumnIndexOrThrow("created_by"))
        ) ?: return null
        val name = database.unsealFor(
            groupContext(id), cursor.blobOrNull("name_ciphertext")
        )?.toString(Charsets.UTF_8).orEmpty()
        val description = database.unsealFor(
            groupContext(id), cursor.blobOrNull("description_ciphertext")
        )?.toString(Charsets.UTF_8).orEmpty()

        return Group(
            id = id,
            name = name,
            description = description,
            createdBy = createdBy,
            createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            members = loadMembers(id),
            defaultMemberPermissions = decodePermissions(
                cursor.getString(cursor.getColumnIndexOrThrow("default_permissions"))
            )
        )
    }

    private fun loadMembers(groupId: String): List<GroupMember> =
        database.readableDatabase.query(
            MessagingDatabase.TABLE_GROUP_MEMBERS,
            null, "group_id = ?", arrayOf(groupId), null, null, "joined_at"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val key = MessagingPublicKey.parse(
                        cursor.getString(cursor.getColumnIndexOrThrow("public_key"))
                    ) ?: continue
                    val role = runCatching {
                        GroupRole.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("role")))
                    }.getOrNull() ?: continue
                    val overrideRaw = cursor.getColumnIndexOrThrow("permission_override").let {
                        if (cursor.isNull(it)) null else cursor.getString(it)
                    }
                    add(
                        GroupMember(
                            publicKey = key,
                            role = role,
                            permissionOverride = overrideRaw?.let(::decodePermissions),
                            joinedAtMillis = cursor.getLong(
                                cursor.getColumnIndexOrThrow("joined_at")
                            )
                        )
                    )
                }
            }
        }

    private fun Cursor.blobOrNull(column: String): ByteArray? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getBlob(index)
    }

    internal fun close() = database.close()

    private companion object {
        fun groupContext(groupId: String) = "group:$groupId"
        fun keyContext(groupId: String, version: Int) = "groupkey:$groupId:$version"

        fun encodePermissions(permissions: Set<GroupPermission>): String =
            permissions.joinToString(",") { it.name }

        /**
         * Unknown permission names are dropped rather than failing the whole set, so a database
         * written by a newer build does not make a group unopenable on an older one.
         */
        fun decodePermissions(raw: String?): Set<GroupPermission> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(",")
                .mapNotNull { name ->
                    runCatching { GroupPermission.valueOf(name.trim()) }.getOrNull()
                }
                .toSet()
        }
    }
}
