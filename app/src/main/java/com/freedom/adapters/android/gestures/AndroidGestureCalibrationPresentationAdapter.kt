package com.freedom.adapters.android.gestures

import android.content.Context
import com.freedom.application.ports.GestureCalibrationPresentationPort
import com.freedom.application.ports.GestureCalibrationResult
import com.freedom.application.ports.GestureSettingsRepository

/** Adapts Android device identity/display data and Data persistence to the UI-facing port. */
internal class AndroidGestureCalibrationPresentationAdapter(
    private val context: Context,
    private val repository: GestureSettingsRepository
) : GestureCalibrationPresentationPort {
    override fun evaluateSamples(trials: List<List<Long>>): GestureCalibrationResult =
        GestureTuning.evaluateSamples(trials)

    override fun saveCalibration(result: GestureCalibrationResult) =
        GestureTuning.saveCalibration(context, result, repository)

    override fun isCalibrated(): Boolean = GestureTuning.isCalibrated(context, repository)

    override fun configurationSubtitle(): String =
        GestureTuning.configurationSubtitle(context, repository)

    override fun calibrationSummary(): String =
        GestureTuning.calibrationSummary(context, repository)

    override fun formatFrequency(value: Float): String = GestureTuning.formatFrequency(value)
}
