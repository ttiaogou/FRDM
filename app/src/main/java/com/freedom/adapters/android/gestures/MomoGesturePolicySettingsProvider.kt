package com.freedom.adapters.android.gestures

import android.content.Context
import com.freedom.application.gesture.FeedSwipePolicySettings
import com.freedom.application.gesture.GesturePolicySettingsProvider
import com.freedom.core.accessibility.gesture.GestureDynamicsSettings
import com.freedom.core.accessibility.gesture.GesturePauseProfile
import com.freedom.core.accessibility.gesture.GestureTimeDistributionKind
import com.freedom.core.accessibility.gesture.GestureTimeDistributionProfile
import com.freedom.core.accessibility.gesture.GesturePreDispatchDelayProfile
import com.freedom.core.accessibility.gesture.GestureMotionMode
import com.freedom.application.ports.GestureSettingsRepository
import com.freedom.adapters.social.momo.MomoFeedGesturePolicyDefaults

/**
 * Android adapter that supplies the latest persisted feed curve preference and device calibration
 * to GesturePolicyManager. It supplies settings only; it never creates coordinates or paths.
 */
internal class MomoGesturePolicySettingsProvider(
    private val context: Context,
    private val repository: GestureSettingsRepository
) : GesturePolicySettingsProvider {
    override fun loadFeedSwipeSettings(): FeedSwipePolicySettings {
        val preferences = repository.readBrowseGesturePreferences(
            defaultDurationMs = MomoFeedGesturePolicyDefaults.DEFAULT_GESTURE_DURATION_MS,
            defaultCurveOffsetRatio = MomoFeedGesturePolicyDefaults.DEFAULT_CURVE_OFFSET_RATIO
        )
        return FeedSwipePolicySettings(
            curveOffsetRatio = preferences.curveOffsetRatio.coerceIn(
                0f,
                MomoFeedGesturePolicyDefaults.MAX_CURVE_OFFSET_RATIO
            ),
            microPerturbationFrequencyHz = GestureTuning.currentFrequencyHz(context, repository),
            maxCurveOffsetRatio = MomoFeedGesturePolicyDefaults.MAX_CURVE_OFFSET_RATIO,
            microPerturbationEnabled = MomoFeedGesturePolicyDefaults.ENABLE_MICRO_PERTURBATION,
            microPerturbationAmplitudePx = MomoFeedGesturePolicyDefaults.MICRO_PERTURBATION_AMPLITUDE_PX,
            gesturePathSteps = MomoFeedGesturePolicyDefaults.GESTURE_PATH_STEPS,
            microPerturbationPathSteps = MomoFeedGesturePolicyDefaults.MICRO_PERTURBATION_PATH_STEPS
        )
    }

    override fun loadDynamicsSettings(): GestureDynamicsSettings = GestureDynamicsSettings(
        // Alternate easing-based movement and a constrained acceleration/deceleration profile per request.
        motionModeCandidates = listOf(
            GestureMotionMode.DURATION_DRIVEN,
            GestureMotionMode.KINEMATIC_LIMITED
        ),
        initialVelocityPxPerSecond = 220f,
        releaseVelocityPxPerSecond = 140f,
        accelerationPxPerSecondSquared = 20_000f,
        decelerationPxPerSecondSquared = 20_000f,
        // The imported example 12 is enabled conservatively: a short hold is possible on 5% of swipes.
        pause = GesturePauseProfile(
            enabled = true,
            probability = 0.05f,
            progress = 0.40f,
            durationMs = 85L,
            durationDistribution = GestureTimeDistributionProfile(
                enabled = true,
                kind = GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL,
                centerMs = 85.0,
                logNormalSigma = 0.20,
                minimumMs = 55L,
                maximumMs = 145L
            )
        ),
        // Example 10's Box–Muller delay is sampled per command and scheduled by the coordinator.
        preDispatchDelay = GesturePreDispatchDelayProfile(
            enabled = true,
            medianMs = 400.0,
            sigma = 0.35,
            minimumMs = 150L,
            maximumMs = 1_200L
        )
    )
}
