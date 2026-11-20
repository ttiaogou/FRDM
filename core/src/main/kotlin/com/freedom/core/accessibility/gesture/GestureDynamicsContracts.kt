package com.freedom.core.accessibility.gesture

/** Ported path families from the supplied examples 1, 2, 3 and 7, 8, 9. */
enum class GestureTrajectoryKind {
    FIXED_CUBIC_BEZIER,
    LEFT_BIASED_CUBIC_BEZIER,
    RANDOMIZED_CUBIC_BEZIER,
    NORMAL_DIRECTION_TREMOR,
    QUADRATIC_BEZIER_2D_TREMOR,
    RANDOM_PHASE_2D_TREMOR
}

/** Ported easing families from supplied examples 4, 5 and 6. */
enum class GestureVelocityCurve {
    COSINE_EASE_IN_OUT,
    CUBIC_EASE_OUT,
    QUADRATIC_EASE_IN_OUT
}

enum class GestureMotionMode {
    /** Fixed duration; easing curve determines normalized distance over time. */
    DURATION_DRIVEN,
    /** Fixed duration and distance; solve the peak velocity from speed/acceleration constraints. */
    KINEMATIC_LIMITED
}

enum class GestureAxis { HORIZONTAL, VERTICAL, FREE_ANGLE }
enum class GestureCurveBend { POSITIVE_NORMAL, NEGATIVE_NORMAL, RANDOM }
enum class GestureToolType { FINGER, STYLUS, MOUSE, UNKNOWN }

data class GesturePlatformCapabilityReport(
    val pressureInjectionSupported: Boolean = false,
    val contactAreaInjectionSupported: Boolean = false,
    val toolTypeInjectionSupported: Boolean = false,
    val pointerLifecycleSupported: Boolean = true,
    val continuedStrokeSupported: Boolean = true,
    val postReleaseInertiaControllable: Boolean = false
) {
    companion object {
        val ACCESSIBILITY_SERVICE = GesturePlatformCapabilityReport()
    }
}

data class GestureDirectionProfile(
    val axis: GestureAxis = GestureAxis.VERTICAL,
    val baseAngleDegrees: Float? = null,
    val directionOffsetDegrees: Float = 0f,
    val pathRotationDegrees: Float = 0f,
    val horizontalBias: Float = 0f,
    val verticalBias: Float = 0f,
    val curveBend: GestureCurveBend = GestureCurveBend.RANDOM,
    val curveAmplitudePx: Float = 48f
)

data class GestureMotionProfile(
    val mode: GestureMotionMode = GestureMotionMode.DURATION_DRIVEN,
    val velocityCurve: GestureVelocityCurve = GestureVelocityCurve.COSINE_EASE_IN_OUT,
    val initialVelocityPxPerSecond: Float = 0f,
    val maximumVelocityPxPerSecond: Float = 8_000f,
    val releaseVelocityPxPerSecond: Float = 0f,
    val accelerationPxPerSecondSquared: Float = 5_000f,
    val decelerationPxPerSecondSquared: Float = 5_000f
)

data class GesturePauseProfile(
    val enabled: Boolean = false,
    val probability: Float = 0f,
    val progress: Float = 0.4f,
    /** Legacy/nominal duration used only when no distribution is configured. */
    val durationMs: Long = 80L,
    val durationDistribution: GestureTimeDistributionProfile? = null
)

/** Per-request independent variation ranges for ordinary screen swipes. */
data class GestureSwipeRandomizationProfile(
    val enabled: Boolean = true,
    val startJitterSigmaPx: Float = 16f,
    val startJitterMaxAbsPx: Float = 25f,
    val endJitterSigmaPx: Float = 6f,
    val endJitterMaxAbsPx: Float = 12f,
    val distanceVariationRatio: Float = 0.075f,
    val directionOffsetVariationDegrees: Float = 3.5f,
    val pathRotationVariationDegrees: Float = 2f,
    val curveAmplitudeVariationRatio: Float = 0.20f,
    val durationVariationRatio: Float = 0.08f,
    val initialVelocityVariationRatio: Float = 0.10f,
    val maximumVelocityVariationRatio: Float = 0.10f,
    val releaseVelocityVariationRatio: Float = 0.10f,
    val accelerationVariationRatio: Float = 0.10f,
    val decelerationVariationRatio: Float = 0.10f
) {
    fun isValid(): Boolean = listOf(
        startJitterSigmaPx, startJitterMaxAbsPx, endJitterSigmaPx, endJitterMaxAbsPx
    ).all { it.isFinite() && it in 0f..100f } &&
        distanceVariationRatio.isFinite() && distanceVariationRatio in 0f..0.10f &&
        directionOffsetVariationDegrees.isFinite() && directionOffsetVariationDegrees in 0f..8f &&
        pathRotationVariationDegrees.isFinite() && pathRotationVariationDegrees in 0f..5f &&
        curveAmplitudeVariationRatio.isFinite() && curveAmplitudeVariationRatio in 0f..0.50f &&
        durationVariationRatio.isFinite() && durationVariationRatio in 0f..0.20f &&
        initialVelocityVariationRatio.isFinite() && initialVelocityVariationRatio in 0f..0.30f &&
        maximumVelocityVariationRatio.isFinite() && maximumVelocityVariationRatio in 0f..0.30f &&
        releaseVelocityVariationRatio.isFinite() && releaseVelocityVariationRatio in 0f..0.30f &&
        accelerationVariationRatio.isFinite() && accelerationVariationRatio in 0f..0.30f &&
        decelerationVariationRatio.isFinite() && decelerationVariationRatio in 0f..0.30f

    companion object {
        val DETERMINISTIC = GestureSwipeRandomizationProfile(
            enabled = false,
            startJitterSigmaPx = 0f,
            startJitterMaxAbsPx = 0f,
            endJitterSigmaPx = 0f,
            endJitterMaxAbsPx = 0f,
            distanceVariationRatio = 0f,
            directionOffsetVariationDegrees = 0f,
            pathRotationVariationDegrees = 0f,
            curveAmplitudeVariationRatio = 0f,
            durationVariationRatio = 0f,
            initialVelocityVariationRatio = 0f,
            maximumVelocityVariationRatio = 0f,
            releaseVelocityVariationRatio = 0f,
            accelerationVariationRatio = 0f,
            decelerationVariationRatio = 0f
        )
    }
}

data class GesturePreDispatchDelayProfile(
    val enabled: Boolean = false,
    val medianMs: Double = 400.0,
    val sigma: Double = 0.35,
    val minimumMs: Long = 150L,
    val maximumMs: Long = 1_200L
)

/** These are descriptive metadata only; AccessibilityService cannot inject them into MotionEvent. */
data class GestureTouchProfile(
    val pressure: Float = 1f,
    val contactWidthPx: Float = 8f,
    val contactHeightPx: Float = 8f,
    val toolType: GestureToolType = GestureToolType.FINGER
) {
    fun isValid(): Boolean = pressure.isFinite() && pressure in 0f..1f &&
        contactWidthPx.isFinite() && contactWidthPx in 0f..256f &&
        contactHeightPx.isFinite() && contactHeightPx in 0f..256f
}

data class GestureDynamicsSettings(
    val trajectoryCandidates: List<GestureTrajectoryKind> = GestureTrajectoryKind.entries,
    val velocityCurveCandidates: List<GestureVelocityCurve> = GestureVelocityCurve.entries,
    val direction: GestureDirectionProfile = GestureDirectionProfile(),
    val motionMode: GestureMotionMode = GestureMotionMode.DURATION_DRIVEN,
    /** Optional per-request mode candidates; empty keeps the explicit motionMode. */
    val motionModeCandidates: List<GestureMotionMode> = emptyList(),
    val maximumVelocityPxPerSecond: Float = 8_000f,
    val initialVelocityPxPerSecond: Float = 0f,
    val releaseVelocityPxPerSecond: Float = 0f,
    val accelerationPxPerSecondSquared: Float = 5_000f,
    val decelerationPxPerSecondSquared: Float = 5_000f,
    val pause: GesturePauseProfile = GesturePauseProfile(),
    val swipeRandomization: GestureSwipeRandomizationProfile = GestureSwipeRandomizationProfile(),
    // Keep the platform-neutral default behavior unchanged; an app/task provider opts in explicitly.
    val preDispatchDelay: GesturePreDispatchDelayProfile = GesturePreDispatchDelayProfile(),
    val touch: GestureTouchProfile = GestureTouchProfile(),
    val sampleCount: Int = 120,
    val tremorFrequencyHz: Float = 10f,
    val tremorAmplitudePx: Float = 1.2f,
    val quadraticTremorFrequencyHz: Float = 12f,
    val quadraticTremorAmplitudePx: Float = 1f,
    val randomPhaseTremorFrequencyHz: Float = 8f,
    val randomPhaseTremorAmplitudePx: Float = 1.5f
)


/** Distribution families for bounded gesture waits. */
enum class GestureTimeDistributionKind {
    UNIFORM,
    TRUNCATED_NORMAL,
    TRUNCATED_LOG_NORMAL
}

data class GestureTimeDistributionProfile(
    val enabled: Boolean = true,
    val kind: GestureTimeDistributionKind = GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL,
    val centerMs: Double = 400.0,
    val standardDeviationMs: Double = 80.0,
    val logNormalSigma: Double = 0.25,
    val minimumMs: Long = 100L,
    val maximumMs: Long = 1_000L
) {
    fun isValid(): Boolean {
        if (!centerMs.isFinite() || centerMs < 0.0 ||
            !standardDeviationMs.isFinite() || standardDeviationMs < 0.0 ||
            !logNormalSigma.isFinite() || logNormalSigma < 0.0 ||
            minimumMs < 0L || maximumMs < minimumMs || maximumMs > MAX_WAIT_MS
        ) return false
        return when (kind) {
            GestureTimeDistributionKind.UNIFORM -> true
            GestureTimeDistributionKind.TRUNCATED_NORMAL ->
                centerMs in minimumMs.toDouble()..maximumMs.toDouble()
            GestureTimeDistributionKind.TRUNCATED_LOG_NORMAL ->
                centerMs > 0.0 && centerMs in minimumMs.toDouble()..maximumMs.toDouble()
        }
    }

    companion object {
        const val MAX_WAIT_MS = 120_000L
    }
}
