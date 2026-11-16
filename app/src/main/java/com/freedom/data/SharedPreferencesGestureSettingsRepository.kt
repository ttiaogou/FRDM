package com.freedom.data

import android.content.Context
import com.freedom.application.ports.BrowseGesturePreferences
import com.freedom.application.ports.GestureCalibrationRecord
import com.freedom.application.ports.GestureSettingsRepository

/** SharedPreferences-backed implementation; all persisted gesture settings stay inside Data. */
internal class SharedPreferencesGestureSettingsRepository(
    context: Context
) : GestureSettingsRepository {
    private val appContext = context.applicationContext

    override fun readCalibration(scopeKey: String, defaultFrequencyHz: Float): GestureCalibrationRecord? {
        val preferences = appContext.getSharedPreferences(CALIBRATION_PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.getBoolean("${scopeKey}_completed", false)) return null
        return GestureCalibrationRecord(
            frequencyHz = preferences.getFloat("${scopeKey}_frequency_hz", defaultFrequencyHz),
            medianIntervalMs = preferences.getInt("${scopeKey}_median_interval_ms", 0),
            p85IntervalMs = preferences.getInt("${scopeKey}_p85_interval_ms", 0),
            jitterPercent = preferences.getInt("${scopeKey}_jitter_percent", 0),
            sampleCount = preferences.getInt("${scopeKey}_sample_count", 0),
            trialCount = preferences.getInt("${scopeKey}_trial_count", 0),
            completedAtMillis = preferences.getLong("${scopeKey}_completed_at", 0L)
        )
    }

    override fun saveCalibration(scopeKey: String, record: GestureCalibrationRecord) {
        appContext.getSharedPreferences(CALIBRATION_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("${scopeKey}_completed", true)
            .putFloat("${scopeKey}_frequency_hz", record.frequencyHz)
            .putInt("${scopeKey}_median_interval_ms", record.medianIntervalMs)
            .putInt("${scopeKey}_p85_interval_ms", record.p85IntervalMs)
            .putInt("${scopeKey}_jitter_percent", record.jitterPercent)
            .putInt("${scopeKey}_sample_count", record.sampleCount)
            .putInt("${scopeKey}_trial_count", record.trialCount)
            .putLong("${scopeKey}_completed_at", record.completedAtMillis)
            .apply()
    }

    override fun readBrowseGesturePreferences(
        defaultDurationMs: Long,
        defaultCurveOffsetRatio: Float
    ): BrowseGesturePreferences {
        val preferences = appContext.getSharedPreferences(BROWSE_PREFERENCES, Context.MODE_PRIVATE)
        return BrowseGesturePreferences(
            durationMs = preferences.getLong(BROWSE_DURATION_KEY, defaultDurationMs),
            curveOffsetRatio = preferences.getFloat(BROWSE_CURVE_OFFSET_KEY, defaultCurveOffsetRatio)
        )
    }

    private companion object {
        const val CALIBRATION_PREFERENCES = "freedom_main_ui"
        const val BROWSE_PREFERENCES = "freedom_main_ui"
        const val BROWSE_DURATION_KEY = "account_browse_gesture_duration_ms"
        const val BROWSE_CURVE_OFFSET_KEY = "account_browse_gesture_curve_offset"
    }
}
