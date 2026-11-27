package com.freedom.data.social.momo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import com.freedom.features.aireply.ports.MomoSayHiDeferredRowCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository

/**
 * Data-layer persistence for the privacy-preserving received-greetings traversal checkpoint.
 * Only SHA-256 row/contact identity keys and retry metadata are persisted; names, greeting text
 * and raw platform IDs are deliberately excluded. The checkpoint expires to avoid stale rows
 * suppressing new work.
 */
internal class MomoSayHiTraversalCheckpointDataStore(context: Context) : MomoSayHiTraversalCheckpointRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(nowEpochMs: Long): MomoSayHiTraversalCheckpointRecord? {
        val raw = preferences.getString(KEY_CHECKPOINT, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            if (json.optInt("version", 0) != CHECKPOINT_VERSION) return null
            val savedAt = json.optLong("savedAtEpochMs", 0L)
            if (savedAt <= 0L || nowEpochMs < savedAt || nowEpochMs - savedAt > MAX_AGE_MS) {
                clear()
                return null
            }
            val processed = json.optJSONArray("processedRowKeys").toStringSet()
            // Optional for compatibility with checkpoints written before contact-ID hashes
            // were persisted. Existing row progress remains recoverable after this schema addition.
            val processedContactIds = json.optJSONArray("processedContactIdHashes").toStringSet()
            val deferred = LinkedHashMap<String, MomoSayHiDeferredRowCheckpointRecord>()
            val deferredJson = json.optJSONObject("deferredRows") ?: JSONObject()
            val keys = deferredJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = deferredJson.optJSONObject(key) ?: continue
                deferred[key] = MomoSayHiDeferredRowCheckpointRecord(
                    attempts = item.optInt("attempts", 0).coerceAtLeast(0),
                    retryAfterEpochMs = item.optLong("retryAfterEpochMs", 0L).coerceAtLeast(0L),
                    safeTargetValidationFailures = item.optInt("safeTargetValidationFailures", 0).coerceAtLeast(0),
                    lastDeferralReason = item.optString("lastDeferralReason", "").take(100),
                    failedSafeTargetEvidenceHash = item.optString("failedSafeTargetEvidenceHash", "")
                        .takeIf { it.matches(Regex("[0-9a-f]{64}")) }.orEmpty()
                )
            }
            MomoSayHiTraversalCheckpointRecord(
                processedRowKeys = processed,
                processedContactIdHashes = processedContactIds,
                deferredRows = deferred,
                deferredReturnToTopActive = json.optBoolean("deferredReturnToTopActive", false),
                deferredReplayStarted = json.optBoolean("deferredReplayStarted", false),
                savedAtEpochMs = savedAt,
                lastVisibleRowKeys = json.optJSONArray("lastVisibleRowKeys").toStringSet()
                    .filter { it.matches(Regex("[0-9a-f]{64}")) }.take(MAX_VIEWPORT_ROWS),
                reanchorToTopPending = json.optBoolean("reanchorToTopPending", false)
            )
        }.getOrElse {
            clear()
            null
        }
    }

    @Synchronized
    override fun save(checkpoint: MomoSayHiTraversalCheckpointRecord) {
        val processed = JSONArray()
        checkpoint.processedRowKeys.toList().takeLast(MAX_ROWS).forEach { processed.put(it) }
        val processedContactIds = JSONArray()
        checkpoint.processedContactIdHashes.toList().takeLast(MAX_ROWS).forEach { processedContactIds.put(it) }
        val deferred = JSONObject()
        checkpoint.deferredRows.entries.toList().takeLast(MAX_ROWS).forEach { (key, value) ->
            deferred.put(key, JSONObject()
                .put("attempts", value.attempts.coerceAtLeast(0))
                .put("retryAfterEpochMs", value.retryAfterEpochMs.coerceAtLeast(0L))
                .put("safeTargetValidationFailures", value.safeTargetValidationFailures.coerceAtLeast(0))
                .put("lastDeferralReason", value.lastDeferralReason.take(100))
                .put("failedSafeTargetEvidenceHash", value.failedSafeTargetEvidenceHash
                    .takeIf { it.matches(Regex("[0-9a-f]{64}")) }.orEmpty()))
        }
        val visible = JSONArray()
        checkpoint.lastVisibleRowKeys.filter { it.matches(Regex("[0-9a-f]{64}")) }
            .distinct().take(MAX_VIEWPORT_ROWS).forEach { visible.put(it) }
        val payload = JSONObject()
            .put("version", CHECKPOINT_VERSION)
            .put("savedAtEpochMs", System.currentTimeMillis())
            .put("processedRowKeys", processed)
            .put("processedContactIdHashes", processedContactIds)
            .put("deferredRows", deferred)
            .put("deferredReturnToTopActive", checkpoint.deferredReturnToTopActive)
            .put("deferredReplayStarted", checkpoint.deferredReplayStarted)
            .put("lastVisibleRowKeys", visible)
            .put("reanchorToTopPending", checkpoint.reanchorToTopPending)
        preferences.edit().putString(KEY_CHECKPOINT, payload.toString()).apply()
    }

    @Synchronized
    override fun clear() {
        preferences.edit().remove(KEY_CHECKPOINT).apply()
    }

    private fun JSONArray?.toStringSet(): LinkedHashSet<String> {
        val result = LinkedHashSet<String>()
        if (this == null) return result
        for (index in 0 until length().coerceAtMost(MAX_ROWS)) {
            optString(index).takeIf(String::isNotBlank)?.let(result::add)
        }
        return result
    }

    private companion object {
        const val PREFERENCES_NAME = "momo_received_greetings_traversal"
        const val KEY_CHECKPOINT = "checkpoint_v1"
        const val CHECKPOINT_VERSION = 1
        const val MAX_ROWS = 600
        const val MAX_VIEWPORT_ROWS = 64
        const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}

