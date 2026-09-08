package com.bitchat.android.messaging.groups

import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.messaging.protocol.Canonical
import java.security.SecureRandom

/**
 * Holds group keys and decides who receives them.
 *
 * Distribution is deliberately one-to-one: a new key is handed to each member individually over the
 * private-message path, which is already authenticated and encrypted per recipient. That costs one
 * addressed message per member, but only when membership changes rather than per message, and it is
 * what makes the key genuinely unavailable to everyone else — a key broadcast to a channel would be
 * readable by exactly the devices it is meant to exclude.
 *
 * Rotation on removal is the point of the version number. Without it, removing someone from the
 * member list would stop them being *authorized* while leaving them perfectly *able* to read
 * everything sent afterwards, since they would still hold the key.
 */
class GroupKeyManager(
    private val random: SecureRandom = SecureRandom()
) {

    private val keysByGroup = mutableMapOf<String, MutableMap<Int, GroupKey>>()
    private val currentVersion = mutableMapOf<String, Int>()

    /** The key this device would encrypt with now, or null if it holds none for the group. */
    fun currentKey(groupId: String): GroupKey? {
        val version = currentVersion[groupId] ?: return null
        return keysByGroup[groupId]?.get(version)
    }

    /**
     * A specific generation, for decrypting older messages.
     *
     * Superseded keys are retained so history stays readable after a rotation. They are never used
     * for new messages, so a removed member's last known key cannot be used to send as the group.
     */
    fun keyForVersion(groupId: String, version: Int): GroupKey? =
        keysByGroup[groupId]?.get(version)

    /** Create the first key for a group. */
    fun createInitialKey(groupId: String): GroupKey =
        GroupKey.random(groupId, version = 1, random = random).also(::store)

    /**
     * Replace a group's key with a new generation.
     *
     * Must be called whenever someone loses access. The caller is then responsible for distributing
     * the result to the remaining members — [distributionFor] produces those messages.
     */
    fun rotate(groupId: String): GroupKey {
        val next = (currentVersion[groupId] ?: 0) + 1
        return GroupKey.random(groupId, next, random).also(::store)
    }

    /**
     * Accept a key sent by a group administrator.
     *
     * A key for a generation already held is ignored rather than overwritten, so a replayed
     * distribution message cannot roll a group back to a key a removed member still knows.
     */
    fun install(key: GroupKey): Boolean {
        val existing = keysByGroup[key.groupId]?.get(key.version)
        if (existing != null) return false
        store(key)
        return true
    }

    /** Discard everything known about a group's keys, on leaving it. */
    fun forget(groupId: String) {
        keysByGroup.remove(groupId)
        currentVersion.remove(groupId)
    }

    fun knowsKeyFor(groupId: String): Boolean = currentKey(groupId) != null

    /**
     * The payloads to send, one per recipient, to give a group's members the current key.
     *
     * Returns the encoded key rather than sending it, so this class stays free of transport
     * concerns and the whole distribution decision is testable without a radio.
     */
    fun distributionFor(
        groupId: String,
        recipients: Collection<MessagingPublicKey>
    ): List<Pair<MessagingPublicKey, String>> {
        val key = currentKey(groupId) ?: return emptyList()
        val encoded = encodeKey(key)
        return recipients.distinct().map { it to encoded }
    }

    private fun store(key: GroupKey) {
        keysByGroup.getOrPut(key.groupId) { mutableMapOf() }[key.version] = key
        // Only advance; a late-arriving older generation must not become the sending key.
        val known = currentVersion[key.groupId]
        if (known == null || key.version > known) currentVersion[key.groupId] = key.version
    }

    companion object {
        const val KEY_PREFIX = "GKEY1:"

        /** Wire form for handing a key to one member over the private-message path. */
        fun encodeKey(key: GroupKey): String = KEY_PREFIX + Canonical.b64(
            Canonical.encode(
                key.groupId,
                key.version.toString(),
                Canonical.b64(key.material)
            )
        )

        fun looksLikeKeyDistribution(payload: String): Boolean = payload.startsWith(KEY_PREFIX)

        /** Parse a distributed key, or null if it is not one or is malformed. */
        fun decodeKey(payload: String): GroupKey? {
            if (!looksLikeKeyDistribution(payload)) return null
            val raw = Canonical.unb64(payload.removePrefix(KEY_PREFIX)) ?: return null
            val fields = Canonical.decodeFields(raw, expected = 3) ?: return null
            val groupId = fields[0].takeIf { it.isNotBlank() } ?: return null
            val version = fields[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            val material = Canonical.unb64(fields[2])?.takeIf { it.size == GroupKey.KEY_BYTES }
                ?: return null
            return GroupKey(groupId, version, material)
        }
    }
}
