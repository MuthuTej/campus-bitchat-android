package com.bitchat.android.campus

import java.io.File

/**
 * Local retention for verified bulletin envelopes.
 *
 * A phone keeps a bulletin until its signed expiry so a student who arrives later can collect it
 * from this device. Unverified envelopes are never written. Expired rows are dropped on read.
 */
class CampusBulletinStore(
    private val file: File,
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {

    private data class Row(
        val alertId: String,
        val expiresAtEpochSeconds: Long,
        val timestampEpochSeconds: Long,
        val envelope: String
    )

    private val rows = LinkedHashMap<String, Row>()

    init {
        load()
    }

    @Synchronized
    fun retain(alert: CampusAlert, envelope: String) {
        if (!CampusAlert.looksLikeAlert(envelope)) return
        if (alert.isExpiredAt(clockSeconds())) return
        rows[alert.alertId] = Row(
            alertId = alert.alertId,
            expiresAtEpochSeconds = alert.expiresAtEpochSeconds,
            timestampEpochSeconds = alert.timestampEpochSeconds,
            envelope = envelope
        )
        trim()
        persist()
    }

    @Synchronized
    fun activeEnvelopes(): List<String> {
        purgeExpired()
        return rows.values
            .sortedByDescending { it.timestampEpochSeconds }
            .map { it.envelope }
    }

    @Synchronized
    fun activeCount(): Int {
        purgeExpired()
        return rows.size
    }

    @Synchronized
    private fun load() {
        rows.clear()
        if (!file.exists()) return
        file.readLines().forEach { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size != 4) return@forEach
            val expiresAt = parts[1].toLongOrNull() ?: return@forEach
            val timestamp = parts[2].toLongOrNull() ?: return@forEach
            val envelope = parts[3]
            if (!CampusAlert.looksLikeAlert(envelope)) return@forEach
            if (expiresAt != 0L && clockSeconds() > expiresAt) return@forEach
            rows[parts[0]] = Row(parts[0], expiresAt, timestamp, envelope)
        }
    }

    private fun purgeExpired() {
        val now = clockSeconds()
        val before = rows.size
        rows.entries.removeIf { (_, row) ->
            row.expiresAtEpochSeconds != 0L && now > row.expiresAtEpochSeconds
        }
        if (rows.size != before) persist()
    }

    private fun trim() {
        purgeExpired()
        if (rows.size <= MAX_RETAINED) return
        val overflow = rows.values.sortedBy { it.timestampEpochSeconds }
            .take(rows.size - MAX_RETAINED)
        overflow.forEach { rows.remove(it.alertId) }
    }

    private fun persist() {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val body = rows.values.joinToString("\n") { row ->
            "${row.alertId}\t${row.expiresAtEpochSeconds}\t${row.timestampEpochSeconds}\t${row.envelope}"
        }
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(body)
        if (file.exists() && !file.delete()) {
            file.writeText(body)
            temporary.delete()
            return
        }
        if (!temporary.renameTo(file)) {
            file.writeText(body)
            temporary.delete()
        }
    }

    companion object {
        const val MAX_RETAINED: Int = 40

        fun inDirectory(directory: File, clockSeconds: () -> Long = { System.currentTimeMillis() / 1000 }): CampusBulletinStore {
            if (!directory.exists()) directory.mkdirs()
            return CampusBulletinStore(File(directory, "bulletins.tsv"), clockSeconds)
        }
    }
}
