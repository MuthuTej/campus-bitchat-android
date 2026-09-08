package com.bitchat.android.messaging.groups

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.messaging.identity.MessagingPublicKey
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Covers durable groups and keys.
 *
 * A lost group key is worse than a lost contact: it makes the group's history permanently
 * unreadable even to legitimate members, so several tests below reopen the database to prove the
 * key really survived rather than only that it was accepted.
 */
@RunWith(RobolectricTestRunner::class)
class GroupRepositoryTest {

    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var cipher: InMemoryConversationStorageCipher
    private lateinit var repository: GroupRepository

    private val owner = MessagingPublicKey.parse("0".repeat(63) + "1")!!
    private val admin = MessagingPublicKey.parse("0".repeat(63) + "2")!!
    private val member = MessagingPublicKey.parse("0".repeat(63) + "3")!!

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "groups-test-${UUID.randomUUID()}.db"
        cipher = InMemoryConversationStorageCipher()
        repository = newRepository()
    }

    @After
    fun tearDown() {
        repository.close()
        context.deleteDatabase(databaseName)
    }

    private fun newRepository() = GroupRepository(
        context = context,
        databaseName = databaseName,
        storageCipher = cipher
    )

    private fun sampleGroup(id: String = "g1") = Group(
        id = id,
        name = "Project Team",
        description = "Coursework",
        createdBy = owner,
        createdAtMillis = 1_000L,
        members = listOf(
            GroupMember(owner, GroupRole.OWNER, joinedAtMillis = 1_000L),
            GroupMember(admin, GroupRole.ADMIN, joinedAtMillis = 1_100L),
            GroupMember(member, GroupRole.MEMBER, joinedAtMillis = 1_200L)
        ),
        defaultMemberPermissions = GroupPermission.ORDINARY_MEMBER
    )

    @Test
    fun `a group survives a restart`() = runTest {
        repository.save(sampleGroup())
        repository.close()

        val reopened = newRepository()
        try {
            val groups = reopened.loadGroups()
            assertEquals(1, groups.size)
            assertEquals("Project Team", groups.first().name)
            assertEquals("Coursework", groups.first().description)
            assertEquals(owner, groups.first().createdBy)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `membership and roles survive a restart`() = runTest {
        repository.save(sampleGroup())

        val restored = newRepository().let { r ->
            try { r.loadGroups().single() } finally { r.close() }
        }

        assertEquals(3, restored.members.size)
        assertEquals(GroupRole.OWNER, restored.memberFor(owner)?.role)
        assertEquals(GroupRole.ADMIN, restored.memberFor(admin)?.role)
        assertEquals(GroupRole.MEMBER, restored.memberFor(member)?.role)
    }

    @Test
    fun `permissions survive a restart`() = runTest {
        val announcement = sampleGroup().copy(
            defaultMemberPermissions = GroupPermission.READ_ONLY,
            members = sampleGroup().members.map {
                if (it.publicKey == member) {
                    it.copy(permissionOverride = setOf(GroupPermission.CAN_SEND_MESSAGES))
                } else it
            }
        )
        repository.save(announcement)

        val restored = newRepository().let { r ->
            try { r.loadGroups().single() } finally { r.close() }
        }

        assertTrue(restored.defaultMemberPermissions.isEmpty())
        assertEquals(
            setOf(GroupPermission.CAN_SEND_MESSAGES),
            restored.memberFor(member)?.permissionOverride
        )
        // The restored policy must still behave the same way, not merely look the same.
        assertTrue(restored.can(member, GroupPermission.CAN_SEND_MESSAGES))
        assertFalse(restored.can(owner, GroupPermission.CAN_SEND_MESSAGES).not())
    }

    @Test
    fun `a removed member does not survive on disk`() = runTest {
        repository.save(sampleGroup())
        val withoutMember = sampleGroup().copy(
            members = sampleGroup().members.filterNot { it.publicKey == member }
        )
        repository.save(withoutMember)

        val restored = newRepository().let { r ->
            try { r.loadGroups().single() } finally { r.close() }
        }

        // A partial update could leave the removed row behind, silently restoring their access on
        // the next launch.
        assertEquals(2, restored.members.size)
        assertNull(restored.memberFor(member))
    }

    @Test
    fun `deleting a group removes it`() = runTest {
        repository.save(sampleGroup())
        repository.delete("g1")

        assertTrue(repository.loadGroups().isEmpty())
    }

    // MARK: - Keys

    @Test
    fun `a group key survives a restart`() = runTest {
        repository.save(sampleGroup())
        val key = GroupKey.random("g1", version = 1)
        repository.saveKey(key, isCurrent = true)
        repository.close()

        val reopened = newRepository()
        try {
            val keys = reopened.loadKeys()
            assertEquals(1, keys.size)
            assertEquals(key, keys.single().first)
            assertTrue("the current generation must be marked", keys.single().second)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `superseded keys are kept so history stays readable`() = runTest {
        repository.save(sampleGroup())
        val v1 = GroupKey.random("g1", 1)
        val v2 = GroupKey.random("g1", 2)
        repository.saveKey(v1, isCurrent = true)
        repository.saveKey(v2, isCurrent = true)

        val keys = newRepository().let { r -> try { r.loadKeys() } finally { r.close() } }

        assertEquals(2, keys.size)
        assertEquals(
            "only the newest generation may be current",
            listOf(2),
            keys.filter { it.second }.map { it.first.version }
        )
        assertTrue("the old key must still be retrievable", keys.any { it.first == v1 })
    }

    @Test
    fun `key material is not stored in the clear`() = runTest {
        repository.save(sampleGroup())
        val key = GroupKey(groupId = "g1", version = 1, material = ByteArray(32) { 0x5A })
        repository.saveKey(key, isCurrent = true)

        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            db.query(
                "group_keys", arrayOf("material_ciphertext"), null, null, null, null, null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val stored = cursor.getBlob(0)
                assertFalse(
                    "raw key material must never touch the disk",
                    stored.asList().windowed(32).any { it.all { b -> b == 0x5A.toByte() } }
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `a key cannot be moved to another group or generation`() = runTest {
        repository.save(sampleGroup("g1"))
        repository.save(sampleGroup("g2"))
        repository.saveKey(GroupKey.random("g1", 1), isCurrent = true)

        // Relabel g1's stored key as g2's.
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            val stolen = db.query(
                "group_keys", arrayOf("material_ciphertext"),
                "group_id = ?", arrayOf("g1"), null, null, null
            ).use { c -> assertTrue(c.moveToFirst()); c.getBlob(0) }
            db.insert(
                "group_keys", null,
                android.content.ContentValues().apply {
                    put("group_id", "g2")
                    put("version", 1)
                    put("material_ciphertext", stolen)
                    put("is_current", 1)
                }
            )
        } finally {
            db.close()
        }

        val keys = newRepository().let { r -> try { r.loadKeys() } finally { r.close() } }

        // Bound by associated data to its own group, so the relocated copy simply will not open.
        assertEquals(listOf("g1"), keys.map { it.first.groupId })
    }

    @Test
    fun `groups and keys load back into the managers`() = runTest {
        repository.save(sampleGroup())
        val key = GroupKey.random("g1", 1)
        repository.saveKey(key, isCurrent = true)

        val groupManager = GroupManager()
        groupManager.restore(repository.loadGroups())
        val keyManager = GroupKeyManager()
        repository.loadKeys().forEach { (k, _) -> keyManager.install(k) }

        assertNotNull(groupManager.group("g1"))
        assertTrue(groupManager.canPublish(member, "g1"))
        assertEquals(key, keyManager.currentKey("g1"))
    }
}
