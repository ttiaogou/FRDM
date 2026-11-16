package com.freedom.application.ports

/** Platform-neutral result produced from the in-app gesture calibration samples. */
data class GestureCalibrationResult(
    val valid: Boolean,
    val message: String,
    val recommendedFrequencyHz: Float,
    val medianIntervalMs: Int,
    val p85IntervalMs: Int,
    val jitterPercent: Int,
    val intervalCount: Int,
    val completedTrialCount: Int
)

/** UI-facing calibration capability. The Android-specific environment is supplied by an adapter. */
interface GestureCalibrationPresentationPort {
    fun evaluateSamples(trials: List<List<Long>>): GestureCalibrationResult
    fun saveCalibration(result: GestureCalibrationResult)
    fun isCalibrated(): Boolean
    fun configurationSubtitle(): String
    fun calibrationSummary(): String
    fun formatFrequency(value: Float): String
}

/** Stable local broadcast contract shared by the UI and Android execution adapter. */
object GestureCalibrationContract {
    const val ACTION_START_CALIBRATION = "com.freedom.action.START_GESTURE_CALIBRATION"
    const val ACTION_CANCEL_CALIBRATION = "com.freedom.action.CANCEL_GESTURE_CALIBRATION"
    const val ACTION_CALIBRATION_TRIAL_COMPLETED = "com.freedom.action.GESTURE_CALIBRATION_TRIAL_COMPLETED"
    const val ACTION_CALIBRATION_FINISHED = "com.freedom.action.GESTURE_CALIBRATION_FINISHED"
    const val ACTION_CALIBRATION_FAILED = "com.freedom.action.GESTURE_CALIBRATION_FAILED"

    const val EXTRA_LEFT = "calibration_left"
    const val EXTRA_TOP = "calibration_top"
    const val EXTRA_WIDTH = "calibration_width"
    const val EXTRA_HEIGHT = "calibration_height"
    const val EXTRA_TRIAL_INDEX = "calibration_trial_index"
    const val EXTRA_SUCCESS = "calibration_success"
    const val EXTRA_MESSAGE = "calibration_message"

    const val CALIBRATION_TRIAL_COUNT = 4
    const val CALIBRATION_TIMEOUT_MS = 20_000L
    const val MIN_POINTS_PER_TRIAL = 5
}
