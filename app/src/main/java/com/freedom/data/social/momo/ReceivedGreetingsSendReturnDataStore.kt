package com.freedom.data.social.momo

import android.content.Context
import com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnPhase
import com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnRecord
import com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnRepository
import org.json.JSONObject

/**
 * Independent from the expiring traversal cursor. A verified send remains durable even if
 * its greeting row disappears, an app process dies, or the list checkpoint expires.
 * Each record has its own synchronous commit; no silent retention eviction can permit replay.
 * The keys and values contain only irreversible fingerprints and phase names.
 */
internal class ReceivedGreetingsSendReturnDataStore(context: Context) : ReceivedGreetingsSendReturnRepository {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun loadAll(): List<ReceivedGreetingsSendReturnRecord> =
        preferences.all.entries.filter { (key, _) -> key.startsWith(KEY_PREFIX) }.map { (key, value) ->
            val raw = value as? String ?: error("INVALID_GREETING_SEND_JOURNAL")
            val item = JSONObject(raw)
            check(item.getInt("version") == VERSION) { "UNSUPPORTED_GREETING_SEND_JOURNAL" }
            val row = item.getString("row")
            check(key == KEY_PREFIX + row && row.isNotBlank()) { "CORRUPT_GREETING_SEND_ROW" }
            ReceivedGreetingsSendReturnRecord(
                rowKeyHash = row,
                contactIdHash = item.getString("contact").also { require(it.isNotBlank()) },
                sendConfirmationHash = item.getString("send").also { require(it.isNotBlank()) },
                phase = ReceivedGreetingsSendReturnPhase.valueOf(item.getString("phase")),
                savedAtEpochMs = item.getLong("savedAt")
            )
        }

    @Synchronized
    override fun save(record: ReceivedGreetingsSendReturnRecord): Boolean {
        if (record.rowKeyHash.isBlank() || record.contactIdHash.isBlank() ||
            record.sendConfirmationHash.isBlank()
        ) return false
        // Never overwrite records after partial corruption or an unknown future schema.
        loadAll()
        val payload = JSONObject()
            .put("version", VERSION)
            .put("row", record.rowKeyHash)
            .put("contact", record.contactIdHash)
            .put("send", record.sendConfirmationHash)
            .put("phase", record.phase.name)
            .put("savedAt", record.savedAtEpochMs)
        // Synchronous commit ensures a BACK cannot precede the durable send fact.
        return preferences.edit().putString(KEY_PREFIX + record.rowKeyHash, payload.toString()).commit()
    }

    private companion object {
        const val NAME = "momo_greetings_send_return_journal"
        const val KEY_PREFIX = "outcome_v1_"
        const val VERSION = 1
    }
}
