package com.freedom.application.ports

/** Persisted local calibration values for the device-specific gesture sampler. */
data class GestureCalibrationRecord(
    val frequencyHz: Float,
    val medianIntervalMs: Int,
    val p85IntervalMs: Int,
    val jitterPercent: Int,
    val sampleCount: Int,
    val trialCount: Int,
    val completedAtMillis: Long
)

/** Existing user-tunable browse gesture values, with defaults supplied by the adapter config. */
data class BrowseGesturePreferences(
    val durationMs: Long,
    val curveOffsetRatio: Float
)

/** Application-owned boundary for persisted gesture tuning and device calibration. */
interface GestureSettingsRepository {
    fun readCalibration(scopeKey: String, defaultFrequencyHz: Float): GestureCalibrationRecord?
    fun saveCalibration(scopeKey: String, record: GestureCalibrationRecord)
    fun readBrowseGesturePreferences(
        defaultDurationMs: Long,
        defaultCurveOffsetRatio: Float
    ): BrowseGesturePreferences
}
