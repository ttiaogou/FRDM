package com.freedom.data

import android.content.Context
import android.util.Log
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryScheduleSnapshot
import org.json.JSONObject

/**
 * Android data-layer implementation of the shared read-only recovery observation plan.
 * Saves values only; no callbacks, page nodes or platform objects cross this boundary.
 * The persisted plan has no retry-count or elapsed-time expiry.
 */
internal class RecoveryScheduleDataStore(context: Context) : RecoveryScheduleRepository {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun load(): RecoveryScheduleSnapshot? {
        val raw = preferences.getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            require(json.getInt("version") == VERSION) { "Unsupported recovery schedule version" }
            RecoveryScheduleSnapshot(
                taskId = json.getString("taskId"),
                routeId = json.getString("routeId"),
                runGeneration = json.getLong("runGeneration"),
                recoveryReason = json.getString("recoveryReason"),
                checkpointId = json.getString("checkpointId"),
                observationCount = json.getLong("observationCount"),
                nextObservationAtEpochMs = json.getLong("nextObservationAtEpochMs"),
                backoffStage = json.getInt("backoffStage"),
                touchActive = json.getBoolean("touchActive")
            ).also(::validate)
        }.getOrElse {
            // Invalid serialized data must never authorize resuming or replaying an action.
            clear()
            null
        }
    }

    @Synchronized
    override fun save(snapshot: RecoveryScheduleSnapshot) {
        validate(snapshot)
        val json = JSONObject()
            .put("version", VERSION)
            .put("taskId", snapshot.taskId)
            .put("routeId", snapshot.routeId)
            .put("runGeneration", snapshot.runGeneration)
            .put("recoveryReason", snapshot.recoveryReason)
            .put("checkpointId", snapshot.checkpointId)
            .put("observationCount", snapshot.observationCount)
            .put("nextObservationAtEpochMs", snapshot.nextObservationAtEpochMs)
            .put("backoffStage", snapshot.backoffStage)
            .put("touchActive", snapshot.touchActive)
        // A scheduled observation must be durably recorded before the caller moves on.
        if (!preferences.edit().putString(KEY, json.toString()).commit()) {
            Log.w(TAG, "Failed to persist recovery schedule")
        }
    }

    @Synchronized
    override fun clear() {
        if (!preferences.edit().remove(KEY).commit()) {
            Log.w(TAG, "Failed to clear recovery schedule")
        }
    }

    private fun validate(snapshot: RecoveryScheduleSnapshot) {
        require(snapshot.taskId.isNotBlank()) { "taskId must not be blank" }
        require(snapshot.routeId.isNotBlank()) { "routeId must not be blank" }
        require(snapshot.checkpointId.isNotBlank()) { "checkpointId must not be blank" }
        require(snapshot.recoveryReason.isNotBlank()) { "recoveryReason must not be blank" }
        require(snapshot.runGeneration >= 0L) { "runGeneration must not be negative" }
        require(snapshot.observationCount >= 0L) { "observationCount must not be negative" }
        require(snapshot.nextObservationAtEpochMs >= 0L) { "nextObservationAtEpochMs must not be negative" }
        require(snapshot.backoffStage in 0..5) { "Unsupported recovery backoffStage" }
    }

    private companion object {
        const val PREFERENCES = "recovery_schedule"
        const val KEY = "active_schedule_v1"
        const val VERSION = 1
        const val TAG = "RecoveryScheduleStore"
    }
}
