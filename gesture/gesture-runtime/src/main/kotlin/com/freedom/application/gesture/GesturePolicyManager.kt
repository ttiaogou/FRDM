package com.freedom.application.gesture

import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentAxis
import com.freedom.core.accessibility.gesture.GestureDynamicsPolicy
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureMotionMode
import com.freedom.core.accessibility.gesture.GestureMotionProfile
import com.freedom.core.accessibility.gesture.GestureDirectionProfile
import com.freedom.core.accessibility.gesture.GestureDynamicsSettings
import com.freedom.core.accessibility.gesture.GesturePauseProfile
import com.freedom.core.accessibility.gesture.GesturePreDispatchDelayProfile
import com.freedom.core.accessibility.gesture.GestureTouchProfile
import com.freedom.core.accessibility.gesture.GestureSwipeRandomizationProfile
import com.freedom.core.accessibility.gesture.GestureTrajectoryKind
import com.freedom.core.accessibility.gesture.GestureVelocityCurve
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.core.accessibility.gesture.GestureAxis
import kotlin.random.Random

/** Runtime-owned feed trajectory settings, loaded from the existing preference/calibration adapter. */
data class FeedSwipePolicySettings(
    val curveOffsetRatio: Float,
    val microPerturbationFrequencyHz: Float,
    val maxCurveOffsetRatio: Float = 0.04f,
    val microPerturbationEnabled: Boolean = true,
    val microPerturbationAmplitudePx: Float = 1.2f,
    val gesturePathSteps: Int = 32,
    val microPerturbationPathSteps: Int = 100
)

/** Application composition boundary. Implementations can reload all settings for each request. */
fun interface GesturePolicySettingsProvider {
    fun loadFeedSwipeSettings(): FeedSwipePolicySettings
    fun loadDynamicsSettings(): GestureDynamicsSettings = GestureDynamicsSettings()
}

object DefaultGesturePolicySettingsProvider : GesturePolicySettingsProvider {
    override fun loadFeedSwipeSettings() = FeedSwipePolicySettings(
        curveOffsetRatio = 0.012f,
        microPerturbationFrequencyHz = 10f
    )
}

internal enum class GestureAlgorithmId {
    SAFE_UNIFORM_TAP,
    VERTICAL_CUBIC_BEZIER,
    FEED_CUBIC_BEZIER,
    FEED_CUBIC_BEZIER_WITH_MICRO_PERTURBATION,
    CALIBRATION_CUBIC_BEZIER,
    DIRECTIONAL_MOTION_PROFILE,
    IMAGE_PAGING_HORIZONTAL
}

internal data class GesturePolicyDecision(
    val algorithmId: GestureAlgorithmId,
    val feedSwipeSettings: FeedSwipePolicySettings? = null,
    val trajectory: GestureTrajectoryKind? = null,
    val velocityCurve: GestureVelocityCurve? = null,
    val direction: GestureDirectionProfile? = null,
    val motion: GestureMotionProfile? = null,
    val pause: GesturePauseProfile = GesturePauseProfile(),
    val swipeRandomization: GestureSwipeRandomizationProfile = GestureSwipeRandomizationProfile(),
    val preDispatchDelay: GesturePreDispatchDelayProfile = GesturePreDispatchDelayProfile(),
    val touch: GestureTouchProfile = GestureTouchProfile(),
    val sampleCount: Int = 120,
    val tremorFrequencyHz: Float = 10f,
    val tremorAmplitudePx: Float = 1.2f,
    val quadraticTremorFrequencyHz: Float = 12f,
    val quadraticTremorAmplitudePx: Float = 1f,
    val randomPhaseTremorFrequencyHz: Float = 8f,
    val randomPhaseTremorAmplitudePx: Float = 1.5f,
    val preDispatchDelayMs: Long = 0L
)

/**
 * Chooses the compatible path family and motion profile independently for each request. Low-level
 * parameters never enter Business GestureIntent. All invalid settings fail closed.
 */
internal class GesturePolicyManager(
    private val settingsProvider: GesturePolicySettingsProvider,
    private val random: Random
) {
    fun select(request: GestureIntentRequest): GesturePolicyDecision? = when (val intent = request.intent) {
        is GestureIntent.Tap,
        is GestureIntent.DoubleTap -> selectTap()
        is GestureIntent.VerticalSwipe -> selectMotion(
            GestureAlgorithmId.VERTICAL_CUBIC_BEZIER,
            baseAngle = if (intent.direction == VerticalSwipeDirection.UP) -90f else 90f,
            axis = GestureAxis.VERTICAL
        )
        is GestureIntent.FeedSwipe -> selectFeedSwipe()
        is GestureIntent.DirectionalSwipe -> selectMotion(
            GestureAlgorithmId.DIRECTIONAL_MOTION_PROFILE,
            baseAngle = intent.angleDegrees, axis = when (intent.axis) {
                GestureIntentAxis.HORIZONTAL -> GestureAxis.HORIZONTAL
                GestureIntentAxis.VERTICAL -> GestureAxis.VERTICAL
                GestureIntentAxis.FREE_ANGLE -> GestureAxis.FREE_ANGLE
            }
        )
        is GestureIntent.ImagePagingSwipe -> selectImagePaging()
        is GestureIntent.CalibrationSwipe -> selectMotion(
            GestureAlgorithmId.CALIBRATION_CUBIC_BEZIER,
            baseAngle = -90f, axis = GestureAxis.VERTICAL
        )
    }

    private fun selectImagePaging(): GesturePolicyDecision? {
        val settings = runCatching(settingsProvider::loadDynamicsSettings).getOrNull() ?: return null
        if (!settings.isValid()) return null
        val delay = GestureDynamicsPolicy.sampleLogNormalDelay(settings.preDispatchDelay, random) ?: return null
        return GesturePolicyDecision(
            algorithmId = GestureAlgorithmId.IMAGE_PAGING_HORIZONTAL,
            preDispatchDelay = settings.preDispatchDelay,
            touch = settings.touch,
            preDispatchDelayMs = delay
        )
    }

    private fun selectTap(): GesturePolicyDecision? {
        val settings = runCatching(settingsProvider::loadDynamicsSettings).getOrNull() ?: return null
        if (!settings.isValid()) return null
        val delay = GestureDynamicsPolicy.sampleLogNormalDelay(settings.preDispatchDelay, random) ?: return null
        return GesturePolicyDecision(
            algorithmId = GestureAlgorithmId.SAFE_UNIFORM_TAP,
            preDispatchDelay = settings.preDispatchDelay,
            touch = settings.touch,
            preDispatchDelayMs = delay
        )
    }

    private fun selectFeedSwipe(): GesturePolicyDecision? {
        val settings = runCatching(settingsProvider::loadFeedSwipeSettings).getOrNull() ?: return null
        if (!settings.isValid()) return null
        val algorithm = if (settings.microPerturbationEnabled && random.nextBoolean()) {
            GestureAlgorithmId.FEED_CUBIC_BEZIER_WITH_MICRO_PERTURBATION
        } else GestureAlgorithmId.FEED_CUBIC_BEZIER
        return selectMotion(
            algorithm = algorithm,
            baseAngle = -90f,
            axis = GestureAxis.VERTICAL,
            feedSettings = settings
        )
    }

    private fun selectMotion(
        algorithm: GestureAlgorithmId,
        baseAngle: Float,
        axis: GestureAxis,
        feedSettings: FeedSwipePolicySettings? = null
    ): GesturePolicyDecision? {
        val settings = runCatching(settingsProvider::loadDynamicsSettings).getOrNull() ?: return null
        if (!settings.isValid()) return null

        val calibration = algorithm == GestureAlgorithmId.CALIBRATION_CUBIC_BEZIER
        val allCandidates = settings.trajectoryCandidates.distinct()
        val eligible = when (algorithm) {
            GestureAlgorithmId.FEED_CUBIC_BEZIER -> allCandidates.filterNot { it.isTremor() }
            GestureAlgorithmId.FEED_CUBIC_BEZIER_WITH_MICRO_PERTURBATION -> allCandidates.filter { it.isTremor() }
            else -> allCandidates
        }
        if (eligible.isEmpty()) return null
        // Device calibration must remain repeatable: do not add random tremor, pauses, mode changes
        // or operation delay to the calibration surface gesture.
        val trajectory = if (calibration) {
            GestureTrajectoryKind.FIXED_CUBIC_BEZIER.takeIf { it in eligible } ?: return null
        } else eligible[random.nextInt(eligible.size)]
        val velocityCurves = settings.velocityCurveCandidates.distinct()
        if (velocityCurves.isEmpty()) return null
        val motionModes = settings.motionModeCandidates.distinct()
        val motionMode = when {
            calibration -> GestureMotionMode.DURATION_DRIVEN
            motionModes.isEmpty() -> settings.motionMode
            else -> motionModes[random.nextInt(motionModes.size)]
        }
        // Easing is active in duration-driven mode. Kinematic mode uses the explicitly configured
        // start/peak/release velocity and acceleration/deceleration constraints instead.
        val velocity = when {
            calibration -> GestureVelocityCurve.COSINE_EASE_IN_OUT.takeIf { it in velocityCurves } ?: return null
            motionMode == GestureMotionMode.DURATION_DRIVEN -> velocityCurves[random.nextInt(velocityCurves.size)]
            else -> GestureVelocityCurve.QUADRATIC_EASE_IN_OUT
        }

        val direction = settings.direction.copy(
            axis = axis,
            // Preserve the semantic direction (UP/DOWN or the requested angle); policy offsets may rotate it.
            baseAngleDegrees = baseAngle,
            curveAmplitudePx = feedSettings?.let {
                // Ratio is converted to physical pixels only after the viewport is available in Compiler.
                settings.direction.curveAmplitudePx
            } ?: settings.direction.curveAmplitudePx
        )
        val motion = GestureMotionProfile(
            mode = motionMode,
            velocityCurve = velocity,
            initialVelocityPxPerSecond = settings.initialVelocityPxPerSecond,
            maximumVelocityPxPerSecond = settings.maximumVelocityPxPerSecond,
            releaseVelocityPxPerSecond = settings.releaseVelocityPxPerSecond,
            accelerationPxPerSecondSquared = settings.accelerationPxPerSecondSquared,
            decelerationPxPerSecondSquared = settings.decelerationPxPerSecondSquared
        )
        val selectedDelayProfile = if (calibration) GesturePreDispatchDelayProfile() else settings.preDispatchDelay
        val delay = GestureDynamicsPolicy.sampleLogNormalDelay(selectedDelayProfile, random)
            ?: return null
        return GesturePolicyDecision(
            algorithmId = algorithm,
            feedSwipeSettings = feedSettings,
            trajectory = trajectory,
            velocityCurve = velocity,
            direction = direction,
            motion = motion,
            pause = if (calibration) GesturePauseProfile() else settings.pause,
            swipeRandomization = if (calibration) GestureSwipeRandomizationProfile.DETERMINISTIC
                else settings.swipeRandomization,
            preDispatchDelay = if (calibration) GesturePreDispatchDelayProfile() else settings.preDispatchDelay,
            touch = settings.touch,
            sampleCount = if (trajectory.isTremor() && feedSettings != null) {
                feedSettings.microPerturbationPathSteps.coerceIn(24, 256)
            } else if (feedSettings != null) {
                maxOf(24, feedSettings.gesturePathSteps).coerceIn(24, 256)
            } else settings.sampleCount,
            tremorFrequencyHz = feedSettings?.microPerturbationFrequencyHz ?: settings.tremorFrequencyHz,
            tremorAmplitudePx = feedSettings?.microPerturbationAmplitudePx ?: settings.tremorAmplitudePx,
            quadraticTremorFrequencyHz = settings.quadraticTremorFrequencyHz,
            quadraticTremorAmplitudePx = settings.quadraticTremorAmplitudePx,
            randomPhaseTremorFrequencyHz = settings.randomPhaseTremorFrequencyHz,
            randomPhaseTremorAmplitudePx = settings.randomPhaseTremorAmplitudePx,
            preDispatchDelayMs = if (calibration) 0L else delay
        )
    }

    private fun GestureTrajectoryKind.isTremor(): Boolean = when (this) {
        GestureTrajectoryKind.NORMAL_DIRECTION_TREMOR,
        GestureTrajectoryKind.QUADRATIC_BEZIER_2D_TREMOR,
        GestureTrajectoryKind.RANDOM_PHASE_2D_TREMOR -> true
        else -> false
    }

    private fun GestureDynamicsSettings.isValid(): Boolean =
        trajectoryCandidates.isNotEmpty() && velocityCurveCandidates.isNotEmpty() &&
            motionModeCandidates.distinct().size == motionModeCandidates.size &&
            sampleCount in 24..256 && tremorFrequencyHz.isFinite() && tremorFrequencyHz in 0f..20f &&
            tremorAmplitudePx.isFinite() && tremorAmplitudePx in 0f..12f &&
            quadraticTremorFrequencyHz.isFinite() && quadraticTremorFrequencyHz in 0f..20f &&
            quadraticTremorAmplitudePx.isFinite() && quadraticTremorAmplitudePx in 0f..12f &&
            randomPhaseTremorFrequencyHz.isFinite() && randomPhaseTremorFrequencyHz in 0f..20f &&
            randomPhaseTremorAmplitudePx.isFinite() && randomPhaseTremorAmplitudePx in 0f..12f &&
            maximumVelocityPxPerSecond.isFinite() && maximumVelocityPxPerSecond in 1f..20_000f &&
            initialVelocityPxPerSecond.isFinite() && initialVelocityPxPerSecond >= 0f &&
            releaseVelocityPxPerSecond.isFinite() && releaseVelocityPxPerSecond >= 0f &&
            accelerationPxPerSecondSquared.isFinite() && accelerationPxPerSecondSquared in 1f..100_000f &&
            decelerationPxPerSecondSquared.isFinite() && decelerationPxPerSecondSquared in 1f..100_000f &&
            direction.isValid() && pause.isValid() && swipeRandomization.isValid() &&
            preDispatchDelay.isValid() && touch.isValid()

    private fun GestureDirectionProfile.isValid(): Boolean =
        (baseAngleDegrees?.isFinite() ?: true) &&
            directionOffsetDegrees.isFinite() && kotlin.math.abs(directionOffsetDegrees) <= 90f &&
            pathRotationDegrees.isFinite() && kotlin.math.abs(pathRotationDegrees) <= 90f &&
            horizontalBias.isFinite() && kotlin.math.abs(horizontalBias) <= 0.25f &&
            verticalBias.isFinite() && kotlin.math.abs(verticalBias) <= 0.25f &&
            curveAmplitudePx.isFinite() && curveAmplitudePx in 0f..180f

    private fun GesturePauseProfile.isValid(): Boolean =
        probability.isFinite() && probability in 0f..1f && progress.isFinite() && progress in 0.05f..0.95f &&
            durationMs in 1L..5_000L && (durationDistribution?.isValid() != false)

    private fun GesturePreDispatchDelayProfile.isValid(): Boolean =
        medianMs.isFinite() && medianMs > 0.0 && sigma.isFinite() && sigma >= 0.0 &&
            minimumMs >= 0L && maximumMs >= minimumMs && maximumMs <= 10_000L

    private fun FeedSwipePolicySettings.isValid(): Boolean =
        maxCurveOffsetRatio.isFinite() && maxCurveOffsetRatio > 0f && maxCurveOffsetRatio <= MAX_CURVE_OFFSET_RATIO &&
            curveOffsetRatio.isFinite() && curveOffsetRatio >= 0f && curveOffsetRatio <= maxCurveOffsetRatio &&
            microPerturbationFrequencyHz.isFinite() && microPerturbationFrequencyHz in 0f..MAX_FREQUENCY_HZ &&
            microPerturbationAmplitudePx.isFinite() && microPerturbationAmplitudePx >= 0f &&
            gesturePathSteps in MIN_PATH_STEPS..MAX_PATH_STEPS &&
            microPerturbationPathSteps in MIN_PATH_STEPS..MAX_PATH_STEPS

    private companion object {
        const val MIN_PATH_STEPS = 2
        const val MAX_PATH_STEPS = 256
        const val MAX_CURVE_OFFSET_RATIO = 0.04f
        const val MAX_FREQUENCY_HZ = 10f
    }
}
