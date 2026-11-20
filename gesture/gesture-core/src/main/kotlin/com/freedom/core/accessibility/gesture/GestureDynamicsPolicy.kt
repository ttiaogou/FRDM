package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureStrokeSpec
import com.freedom.core.accessibility.api.ScreenRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

data class ResolvedGestureMotion(
    val durationMs: Long,
    val pathLengthPx: Float,
    val initialVelocityPxPerSecond: Float,
    val maximumVelocityPxPerSecond: Float,
    val releaseVelocityPxPerSecond: Float,
    val accelerationPxPerSecondSquared: Float,
    val decelerationPxPerSecondSquared: Float
)

/**
 * Advisory estimate of a host application's inertial continuation after pointer release.
 * It is not a dispatched second gesture: the target app and Android own the actual fling.
 */
data class GestureInertiaEstimate(
    val releaseVelocityPxPerSecond: Float,
    val estimatedDecelerationPxPerSecondSquared: Float,
    val estimatedDurationMs: Long,
    val estimatedDistancePx: Float
)

data class GestureDynamicsPlan(
    val strokes: List<GestureStrokeSpec>,
    val start: GesturePoint,
    val control1: GesturePoint,
    val control2: GesturePoint,
    val end: GesturePoint,
    val safeBounds: ScreenRect,
    val motion: ResolvedGestureMotion,
    val trajectory: GestureTrajectoryKind,
    val velocityCurve: GestureVelocityCurve,
    val directionAngleDegrees: Float,
    val pauseApplied: Boolean,
    val pauseDurationMs: Long,
    val inertiaEstimate: GestureInertiaEstimate?
) {
    val totalDurationMs: Long get() = strokes.sumOf { it.durationMs }
}

/** Platform-neutral trajectory and kinematic planner. Only the Android adapter creates Path objects. */
object GestureDynamicsPolicy {
    private const val MIN_DURATION_MS = 20L
    private const val MIN_PATH_LENGTH_PX = 1.0
    private const val DEFAULT_EDGE_INSET_RATIO = 0.04f
    private const val MAX_SAMPLES = 256
    private const val MIN_SAMPLES = 24
    private val FIVE_PHASE_TIME_BOUNDARIES = listOf(0.0, 0.15, 0.35, 0.65, 0.85, 1.0)

    fun createSwipe(
        bounds: ScreenRect,
        baseAngleDegrees: Float,
        durationMs: Long,
        trajectory: GestureTrajectoryKind,
        direction: GestureDirectionProfile,
        motion: GestureMotionProfile,
        pause: GesturePauseProfile = GesturePauseProfile(),
        randomization: GestureSwipeRandomizationProfile = GestureSwipeRandomizationProfile(),
        random: Random = Random.Default,
        sampleCount: Int = 120,
        tremorFrequencyHz: Float = 10f,
        tremorAmplitudePx: Float = 1.2f,
        quadraticTremorFrequencyHz: Float = 12f,
        quadraticTremorAmplitudePx: Float = 1f,
        randomPhaseTremorFrequencyHz: Float = 8f,
        randomPhaseTremorAmplitudePx: Float = 1.5f,
        onPlanCompilationRejected: ((String) -> Unit)? = null
    ): GestureDynamicsPlan? {
        fun reject(reason: String): GestureDynamicsPlan? {
            onPlanCompilationRejected?.invoke(reason)
            return null
        }

        if (bounds.isEmpty || durationMs < MIN_DURATION_MS || sampleCount !in MIN_SAMPLES..MAX_SAMPLES ||
            !baseAngleDegrees.isFinite() || !direction.isValid() || !motion.isValid() || !pause.isValid() ||
            !randomization.isValid() ||
            !tremorFrequencyHz.isFinite() || tremorFrequencyHz !in 0f..20f ||
            !tremorAmplitudePx.isFinite() || tremorAmplitudePx !in 0f..12f ||
            !quadraticTremorFrequencyHz.isFinite() || quadraticTremorFrequencyHz !in 0f..20f ||
            !quadraticTremorAmplitudePx.isFinite() || quadraticTremorAmplitudePx !in 0f..12f ||
            !randomPhaseTremorFrequencyHz.isFinite() || randomPhaseTremorFrequencyHz !in 0f..20f ||
            !randomPhaseTremorAmplitudePx.isFinite() || randomPhaseTremorAmplitudePx !in 0f..12f
        ) return reject("swipe_input_or_profile_invalid")

        val insetX = max(2, ceil(bounds.width * DEFAULT_EDGE_INSET_RATIO).toInt())
        val insetY = max(2, ceil(bounds.height * DEFAULT_EDGE_INSET_RATIO).toInt())
        val safe = ScreenRect(bounds.left + insetX, bounds.top + insetY, bounds.right - insetX, bounds.bottom - insetY)
        if (safe.width < 40 || safe.height < 40) return reject("safe_bounds_too_small:width=${safe.width};height=${safe.height}")

        val effectiveDirection = if (!randomization.enabled) direction else direction.copy(
            directionOffsetDegrees = direction.directionOffsetDegrees +
                sampleSymmetric(randomization.directionOffsetVariationDegrees, random),
            pathRotationDegrees = direction.pathRotationDegrees +
                sampleSymmetric(randomization.pathRotationVariationDegrees, random),
            curveAmplitudePx = direction.curveAmplitudePx *
                sampleScale(randomization.curveAmplitudeVariationRatio, random)
        )
        val effectiveMotion = if (!randomization.enabled) motion else motion.copy(
            initialVelocityPxPerSecond = motion.initialVelocityPxPerSecond *
                sampleScale(randomization.initialVelocityVariationRatio, random),
            maximumVelocityPxPerSecond = motion.maximumVelocityPxPerSecond *
                sampleScale(randomization.maximumVelocityVariationRatio, random),
            releaseVelocityPxPerSecond = motion.releaseVelocityPxPerSecond *
                sampleScale(randomization.releaseVelocityVariationRatio, random),
            accelerationPxPerSecondSquared = motion.accelerationPxPerSecondSquared *
                sampleScale(randomization.accelerationVariationRatio, random),
            decelerationPxPerSecondSquared = motion.decelerationPxPerSecondSquared *
                sampleScale(randomization.decelerationVariationRatio, random)
        )
        val effectiveDurationMs = if (randomization.enabled) {
            (durationMs * sampleScale(randomization.durationVariationRatio, random)).toLong()
                .coerceAtLeast(MIN_DURATION_MS)
        } else durationMs
        val selectedAngle = effectiveDirection.baseAngleDegrees ?: baseAngleDegrees
        val angleDegrees = normalizeDegrees(
            selectedAngle + effectiveDirection.directionOffsetDegrees + effectiveDirection.pathRotationDegrees
        )
        if (!effectiveDirection.isAngleCompatible(angleDegrees)) return reject("direction_axis_incompatible:angleDegrees=$angleDegrees")
        val angle = Math.toRadians(angleDegrees.toDouble())
        val ux = cos(angle).toFloat()
        val uy = sin(angle).toFloat()
        val normalAngle = angle + Math.toRadians(90.0)
        val nx = cos(normalAngle).toFloat()
        val ny = sin(normalAngle).toFloat()
        val width = (safe.width - 1).toFloat()
        val height = (safe.height - 1).toFloat()
        val horizontalLimit = if (abs(ux) < 0.0001f) Float.POSITIVE_INFINITY else width / abs(ux)
        val verticalLimit = if (abs(uy) < 0.0001f) Float.POSITIVE_INFINITY else height / abs(uy)
        val baseDistance = min(horizontalLimit, verticalLimit) * 0.72f
        val distance = baseDistance * if (randomization.enabled) {
            sampleScale(randomization.distanceVariationRatio, random)
        } else 1f
        if (!distance.isFinite() || distance < 20f) return reject("swipe_distance_invalid:distance=$distance")

        val centreX = (safe.left + safe.right - 1) / 2f + effectiveDirection.horizontalBias * width
        val centreY = (safe.top + safe.bottom - 1) / 2f + effectiveDirection.verticalBias * height
        val baseStart = GesturePoint(centreX - ux * distance / 2f, centreY - uy * distance / 2f)
        val start = if (randomization.enabled) sampleGaussianPoint(
            baseStart, safe, randomization.startJitterSigmaPx,
            randomization.startJitterMaxAbsPx, random
        ) else baseStart.takeIf { it.isInside(safe) }
        start ?: return reject("start_point_sampling_failed")
        val baseEnd = GesturePoint(start.x + ux * distance, start.y + uy * distance)
        val end = if (randomization.enabled) sampleGaussianPoint(
            baseEnd, safe, randomization.endJitterSigmaPx,
            randomization.endJitterMaxAbsPx, random
        ) else baseEnd.takeIf { it.isInside(safe) }
        end ?: return reject("end_point_sampling_failed")
        val actualDx = end.x - start.x
        val actualDy = end.y - start.y
        val projectedDistance = actualDx * ux + actualDy * uy
        if (projectedDistance < 20f) return reject("projected_swipe_distance_too_short:distance=$projectedDistance")
        val actualAngle = Math.toDegrees(kotlin.math.atan2(actualDy.toDouble(), actualDx.toDouble()))
        if (abs(normalizeDegrees(actualAngle.toFloat() - angleDegrees)) > 12f) {
            return reject("sampled_direction_deviation_too_large:actual=$actualAngle;expected=$angleDegrees")
        }

        val bendSign = when (direction.curveBend) {
            GestureCurveBend.POSITIVE_NORMAL -> 1f
            GestureCurveBend.NEGATIVE_NORMAL -> -1f
            GestureCurveBend.RANDOM -> if (random.nextBoolean()) 1f else -1f
        }
        val bend = effectiveDirection.curveAmplitudePx * bendSign
        val dx = end.x - start.x
        val dy = end.y - start.y
        val defaultC1 = GesturePoint(start.x + dx * 0.30f + nx * bend, start.y + dy * 0.30f + ny * bend)
        val defaultC2 = GesturePoint(start.x + dx * 0.70f - nx * bend * 0.65f, start.y + dy * 0.70f - ny * bend * 0.65f)
        val controls = resolveControlPoints(
            start, end, defaultC1, defaultC2, trajectory, safe, random, nx, ny,
            effectiveDirection.curveAmplitudePx * bendSign
        ) ?: return reject("control_point_resolution_failed")

        val pathPoints = sampleTrajectory(
            start, end, controls.first, controls.second, trajectory, random, effectiveDurationMs, sampleCount,
            tremorFrequencyHz, tremorAmplitudePx, quadraticTremorFrequencyHz,
            quadraticTremorAmplitudePx, randomPhaseTremorFrequencyHz,
            randomPhaseTremorAmplitudePx, nx, ny
        ) ?: return reject("trajectory_sampling_failed")
        if (pathPoints.any { !it.isInside(safe) }) return reject("trajectory_point_outside_safe_bounds")
        val cumulative = cumulativeLengths(pathPoints)
        val totalLength = cumulative.lastOrNull()?.toFloat()
            ?: return reject("path_cumulative_length_unavailable")
        if (!totalLength.isFinite() || totalLength <= MIN_PATH_LENGTH_PX) {
            return reject("path_length_invalid:total=$totalLength")
        }

        val resolved = resolveMotion(totalLength, effectiveDurationMs, effectiveMotion)
            ?: return reject("motion_resolution_failed:mode=${effectiveMotion.mode};velocityCurve=${effectiveMotion.velocityCurve}")
        val shouldPause = pause.enabled && pause.probability > 0f && random.nextFloat() < pause.probability
        val resolvedPause = if (shouldPause) {
            val sampledDuration = if (pause.durationDistribution != null) {
                GestureTimeDistributionPolicy.sampleMillis(pause.durationDistribution, random)
                    ?: return reject("pause_duration_distribution_unavailable")
            } else pause.durationMs
            if (sampledDuration !in 1L..5_000L) return reject("pause_duration_out_of_range:durationMs=$sampledDuration")
            pause.copy(durationMs = sampledDuration)
        } else null
        var strokeCompilationFailure: String? = null
        val strokes = createStrokes(
            points = pathPoints,
            cumulative = cumulative,
            bounds = safe,
            motion = resolved,
            pause = resolvedPause,
            onRejected = { reason -> strokeCompilationFailure = reason }
        ) ?: return reject("stroke_compilation_failed:${strokeCompilationFailure ?: "unknown_reason"}")
        return GestureDynamicsPlan(
            strokes = strokes,
            start = start,
            control1 = controls.first,
            control2 = controls.second,
            end = end,
            safeBounds = safe,
            motion = resolved.summary,
            trajectory = trajectory,
            velocityCurve = effectiveMotion.velocityCurve,
            directionAngleDegrees = angleDegrees,
            pauseApplied = shouldPause,
            pauseDurationMs = resolvedPause?.durationMs ?: 0L,
            inertiaEstimate = estimatePostReleaseInertia(
                resolved.summary.releaseVelocityPxPerSecond,
                resolved.summary.decelerationPxPerSecondSquared
            )
        )
    }

    private fun sampleScale(variationRatio: Float, random: Random): Float =
        1f + sampleSymmetric(variationRatio, random)

    private fun sampleSymmetric(maximumMagnitude: Float, random: Random): Float =
        if (maximumMagnitude <= 0f) 0f else (random.nextFloat() * 2f - 1f) * maximumMagnitude

    /** Gaussian offsets are bounded and rejected if they would leave the inset viewport. */
    private fun sampleGaussianPoint(
        baseline: GesturePoint,
        bounds: ScreenRect,
        sigmaPx: Float,
        maximumOffsetPx: Float,
        random: Random
    ): GesturePoint? {
        if (!baseline.isInside(bounds)) return null
        if (sigmaPx <= 0f || maximumOffsetPx <= 0f) return baseline
        repeat(12) {
            val xOffset = GestureGeometryPolicy.sampleTruncatedGaussianOffset(sigmaPx, maximumOffsetPx, random)
                ?: return null
            val yOffset = GestureGeometryPolicy.sampleTruncatedGaussianOffset(sigmaPx, maximumOffsetPx, random)
                ?: return null
            val candidate = GesturePoint(baseline.x + xOffset, baseline.y + yOffset)
            if (candidate.isInside(bounds)) return candidate
        }
        return null
    }

    /**
     * Estimates a constant-deceleration fling using v²/(2a). This does not cause any Android
     * motion after UP; it is useful only for policy diagnostics and expected-scroll calculations.
     */
    fun estimatePostReleaseInertia(
        releaseVelocityPxPerSecond: Float,
        decelerationPxPerSecondSquared: Float
    ): GestureInertiaEstimate? {
        if (!releaseVelocityPxPerSecond.isFinite() || releaseVelocityPxPerSecond < 0f ||
            !decelerationPxPerSecondSquared.isFinite() || decelerationPxPerSecondSquared <= 0f
        ) return null
        if (releaseVelocityPxPerSecond == 0f) return null
        val velocity = releaseVelocityPxPerSecond.toDouble()
        val deceleration = decelerationPxPerSecondSquared.toDouble()
        val durationMs = (velocity / deceleration * 1_000.0).toLong().coerceAtLeast(1L)
        val distancePx = (velocity * velocity / (2.0 * deceleration)).toFloat()
        if (!distancePx.isFinite()) return null
        return GestureInertiaEstimate(
            releaseVelocityPxPerSecond = releaseVelocityPxPerSecond,
            estimatedDecelerationPxPerSecondSquared = decelerationPxPerSecondSquared,
            estimatedDurationMs = durationMs,
            estimatedDistancePx = distancePx
        )
    }

    /** Box–Muller log-normal delay from supplied example 10. */
    fun sampleLogNormalDelay(settings: GesturePreDispatchDelayProfile, random: Random = Random.Default): Long? {
        if (!settings.isValid()) return null
        if (!settings.enabled) return 0L
        return GestureTimeDistributionPolicy.sampleLogNormalMillis(
            medianMs = settings.medianMs,
            sigma = settings.sigma,
            minimumMs = settings.minimumMs,
            maximumMs = settings.maximumMs,
            random = random
        )
    }

    private fun resolveControlPoints(
        start: GesturePoint,
        end: GesturePoint,
        c1: GesturePoint,
        c2: GesturePoint,
        kind: GestureTrajectoryKind,
        safe: ScreenRect,
        random: Random,
        normalX: Float,
        normalY: Float,
        sampledCurveBend: Float
    ): Pair<GesturePoint, GesturePoint>? {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val raw = when (kind) {
            GestureTrajectoryKind.FIXED_CUBIC_BEZIER ->
                GesturePoint(
                    start.x + dx * 0.30f + 50f + normalX * sampledCurveBend * 0.35f,
                    start.y + dy * 0.30f - 30f + normalY * sampledCurveBend * 0.35f
                ) to GesturePoint(
                    start.x + dx * 0.70f - 40f - normalX * sampledCurveBend * 0.25f,
                    start.y + dy * 0.70f + 50f - normalY * sampledCurveBend * 0.25f
                )
            GestureTrajectoryKind.LEFT_BIASED_CUBIC_BEZIER -> {
                // The source example bends naturally on vertical paths because it offsets X.
                // For a horizontal path, add a small normal-direction bow so the curve does not
                // collapse into a straight cubic segment rejected by the geometry validator.
                val horizontalBow = if (abs(dx) > abs(dy) * 10f) 20f else 0f
                val totalBow = horizontalBow + abs(sampledCurveBend)
                GesturePoint(
                    start.x + dx * 0.25f - 60f + normalX * totalBow * 0.35f,
                    start.y + dy * 0.25f + normalY * totalBow * 0.35f
                ) to GesturePoint(
                    start.x + dx * 0.75f - 60f - normalX * totalBow * 0.25f,
                    start.y + dy * 0.75f - normalY * totalBow * 0.25f
                )
            }
            GestureTrajectoryKind.RANDOMIZED_CUBIC_BEZIER ->
                GesturePoint(start.x + dx * 0.30f + random.nextFloat() * 80f - 40f,
                    start.y + dy * 0.30f + random.nextFloat() * 80f - 40f) to
                    GesturePoint(start.x + dx * 0.70f + random.nextFloat() * 80f - 40f,
                        start.y + dy * 0.70f + random.nextFloat() * 80f - 40f)
            GestureTrajectoryKind.QUADRATIC_BEZIER_2D_TREMOR ->
                GesturePoint((start.x + end.x) / 2f + 30f, (start.y + end.y) / 2f - 20f) to c2
            else -> c1 to c2
        }
        return clampPoint(raw.first, safe) to clampPoint(raw.second, safe)
    }

    private fun sampleTrajectory(
        start: GesturePoint,
        end: GesturePoint,
        control1: GesturePoint,
        control2: GesturePoint,
        kind: GestureTrajectoryKind,
        random: Random,
        durationMs: Long,
        samples: Int,
        frequencyHz: Float,
        amplitudePx: Float,
        quadraticFrequencyHz: Float,
        quadraticAmplitudePx: Float,
        phaseFrequencyHz: Float,
        phaseAmplitudePx: Float,
        normalX: Float,
        normalY: Float
    ): List<GesturePoint>? {
        val phase = random.nextDouble() * PI
        return (0..samples).map { index ->
            val t = index.toFloat() / samples
            val inverse = 1f - t
            when (kind) {
                GestureTrajectoryKind.FIXED_CUBIC_BEZIER,
                GestureTrajectoryKind.LEFT_BIASED_CUBIC_BEZIER,
                GestureTrajectoryKind.RANDOMIZED_CUBIC_BEZIER -> cubic(start, control1, control2, end, t)
                GestureTrajectoryKind.NORMAL_DIRECTION_TREMOR -> {
                    val envelope = sin(PI * t)
                    val seconds = durationMs * t / 1000.0
                    val wave = sin(2.0 * PI * frequencyHz * seconds) * amplitudePx * envelope
                    val base = cubic(start, control1, control2, end, t)
                    GesturePoint(base.x + normalX * wave.toFloat(),
                        base.y + normalY * wave.toFloat())
                }
                GestureTrajectoryKind.QUADRATIC_BEZIER_2D_TREMOR -> {
                    val x = inverse * inverse * start.x + 2f * inverse * t * control1.x + t * t * end.x
                    val y = inverse * inverse * start.y + 2f * inverse * t * control1.y + t * t * end.y
                    val envelope = sin(PI * t)
                    val seconds = durationMs * t / 1000.0
                    val tx = sin(2.0 * PI * quadraticFrequencyHz * seconds) * quadraticAmplitudePx * envelope
                    val ty = cos(2.0 * PI * quadraticFrequencyHz * seconds) * quadraticAmplitudePx * envelope
                    GesturePoint(x + tx.toFloat(), y + ty.toFloat())
                }
                GestureTrajectoryKind.RANDOM_PHASE_2D_TREMOR -> {
                    val envelope = sin(PI * t)
                    val seconds = durationMs * t / 1000.0
                    val tx = sin(2.0 * PI * phaseFrequencyHz * seconds + phase) * phaseAmplitudePx * envelope
                    val ty = cos(2.0 * PI * phaseFrequencyHz * seconds + phase) * phaseAmplitudePx * envelope
                    val base = cubic(start, control1, control2, end, t)
                    GesturePoint(base.x + tx.toFloat(), base.y + ty.toFloat())
                }
            }
        }
    }

    private data class MotionResolution(
        val summary: ResolvedGestureMotion,
        val progressAt: (Double) -> Double
    )

    private fun resolveMotion(distance: Float, durationMs: Long, profile: GestureMotionProfile): MotionResolution? {
        val seconds = durationMs / 1000.0
        if (seconds <= 0.0) return null
        return when (profile.mode) {
            GestureMotionMode.DURATION_DRIVEN -> {
                val derivativeMax = (0..2_000).maxOf { velocityDerivative(profile.velocityCurve, it / 2_000.0) }
                val secondDerivativeMax = (0..2_000).maxOf { abs(velocitySecondDerivative(profile.velocityCurve, it / 2_000.0)) }
                val initial = distance * velocityDerivative(profile.velocityCurve, 0.0) / seconds
                val release = distance * velocityDerivative(profile.velocityCurve, 1.0) / seconds
                val peak = distance * derivativeMax / seconds
                val acceleration = distance * secondDerivativeMax / (seconds * seconds)
                if (!listOf(initial, release, peak, acceleration).all { it.isFinite() } ||
                    peak > profile.maximumVelocityPxPerSecond + 0.5f
                ) return null
                MotionResolution(
                    ResolvedGestureMotion(durationMs, distance, initial.toFloat(), peak.toFloat(),
                        release.toFloat(), acceleration.toFloat(), acceleration.toFloat())
                ) { time -> easingProgress(profile.velocityCurve, time.coerceIn(0.0, 1.0)) }
            }
            GestureMotionMode.KINEMATIC_LIMITED -> {
                val v0 = profile.initialVelocityPxPerSecond.toDouble()
                val vmax = profile.maximumVelocityPxPerSecond.toDouble()
                val vf = profile.releaseVelocityPxPerSecond.toDouble()
                val accel = profile.accelerationPxPerSecondSquared.toDouble()
                val decel = profile.decelerationPxPerSecondSquared.toDouble()
                if (v0 > vmax || vf > vmax || accel <= 0.0 || decel <= 0.0) return null
                fun distanceForPeak(peak: Double): Double =
                    peak * seconds - (peak - v0).coerceAtLeast(0.0).let { it * it / (2.0 * accel) } -
                        (peak - vf).coerceAtLeast(0.0).let { it * it / (2.0 * decel) }

                // distanceForPeak() is concave, not monotonic across the entire [low, vmax]
                // interval. Once the peak velocity is high enough that acceleration +
                // deceleration consume more than the requested duration, the implied cruise
                // time becomes negative and the computed distance falls again. Searching all
                // the way to vmax can therefore reject an otherwise feasible gesture (or make
                // bisection converge to the physically impossible descending branch).
                // Restrict the search to the increasing branch, ending at the peak where
                // d(distanceForPeak)/d(peak) == remaining cruise time == 0.
                val minimumPeak = max(v0, vf)
                val unconstrainedMaximumPeak =
                    (seconds + v0 / accel + vf / decel) / (1.0 / accel + 1.0 / decel)
                if (!unconstrainedMaximumPeak.isFinite()) return null
                val maximumFeasiblePeak = min(vmax, unconstrainedMaximumPeak)
                if (maximumFeasiblePeak < minimumPeak) return null
                var low = minimumPeak
                var high = maximumFeasiblePeak
                if (distanceForPeak(low) > distance + 0.5 || distanceForPeak(high) < distance - 0.5) return null
                repeat(90) {
                    val mid = (low + high) / 2.0
                    if (distanceForPeak(mid) < distance) low = mid else high = mid
                }
                val peak = (low + high) / 2.0
                val tAccel = (peak - v0) / accel
                val tDecel = (peak - vf) / decel
                val tCruise = seconds - tAccel - tDecel
                if (tCruise < -0.002) return null
                val cruise = tCruise.coerceAtLeast(0.0)
                val accelerationDistance = (v0 + peak) * 0.5 * tAccel
                val cruiseDistance = peak * cruise
                val decelerationDistance = (peak + vf) * 0.5 * tDecel
                val actualDistance = accelerationDistance + cruiseDistance + decelerationDistance
                if (!actualDistance.isFinite() || abs(actualDistance - distance) > max(2.0, distance * 0.005)) return null
                val summary = ResolvedGestureMotion(durationMs, distance, v0.toFloat(), peak.toFloat(), vf.toFloat(),
                    accel.toFloat(), decel.toFloat())
                MotionResolution(summary) { normalizedTime ->
                    val time = normalizedTime.coerceIn(0.0, 1.0) * seconds
                    val travelled = when {
                        time <= tAccel -> v0 * time + 0.5 * accel * time * time
                        time <= tAccel + cruise -> accelerationDistance + peak * (time - tAccel)
                        else -> {
                            val afterDecel = (time - tAccel - cruise).coerceAtMost(tDecel)
                            accelerationDistance + cruiseDistance + peak * afterDecel - 0.5 * decel * afterDecel * afterDecel
                        }
                    }
                    (travelled / distance).coerceIn(0.0, 1.0)
                }
            }
        }
    }

    private fun createStrokes(
        points: List<GesturePoint>,
        cumulative: DoubleArray,
        bounds: ScreenRect,
        motion: MotionResolution,
        pause: GesturePauseProfile?,
        onRejected: (String) -> Unit
    ): List<GestureStrokeSpec>? {
        fun reject(reason: String): List<GestureStrokeSpec>? {
            onRejected(reason)
            return null
        }

        val total = cumulative.lastOrNull()
            ?: return reject("path_cumulative_lengths_empty")
        if (!total.isFinite() || total <= MIN_PATH_LENGTH_PX) {
            return reject("path_length_invalid:total=$total")
        }
        val duration = motion.summary.durationMs
        val splitTimes = FIVE_PHASE_TIME_BOUNDARIES.toMutableList()
        val pauseTime = pause?.let { findTimeForProgress(motion, it.progress.toDouble()) }
        if (pauseTime != null && pauseTime in 0.05..0.95) splitTimes += pauseTime
        val ordered = splitTimes.distinct().sorted()
        if (ordered.size < 2) return reject("split_boundaries_insufficient:count=${ordered.size}")

        val boundaryIndices = mutableListOf<Int>()
        var minimumIndex = 0
        for ((boundaryPosition, time) in ordered.withIndex()) {
            // Keep at least one sampled path interval for every remaining stroke. Without this
            // reservation, strongly front-loaded easing (notably CUBIC_EASE_OUT) can map the
            // penultimate time boundary to the final sample and leave the last stroke empty.
            val remainingSegments = ordered.lastIndex - boundaryPosition
            val maximumIndex = points.lastIndex - remainingSegments
            val target = total * motion.progressAt(time)
            val selected = nearestArcIndex(cumulative, target, minimumIndex, maximumIndex)
                ?: return reject(
                    "split_boundary_index_unavailable:position=$boundaryPosition;" +
                        "time=$time;targetDistance=$target;minimumIndex=$minimumIndex;" +
                        "maximumIndex=$maximumIndex;sampleCount=${points.lastIndex}"
                )
            boundaryIndices += selected
            minimumIndex = selected + 1
        }
        if (boundaryIndices.firstOrNull() != 0 || boundaryIndices.lastOrNull() != points.lastIndex) {
            return reject(
                "split_boundaries_do_not_cover_path:first=${boundaryIndices.firstOrNull()};" +
                    "last=${boundaryIndices.lastOrNull()};terminal=${points.lastIndex}"
            )
        }

        val result = mutableListOf<GestureStrokeSpec>()
        var elapsedWithPauses = 0L
        for (index in 0 until ordered.lastIndex) {
            val startIndex = boundaryIndices[index]
            val endIndex = boundaryIndices[index + 1]
            if (endIndex <= startIndex) {
                return reject(
                    "split_boundary_not_strictly_increasing:segment=$index;" +
                        "startIndex=$startIndex;endIndex=$endIndex;sampleCount=${points.lastIndex}"
                )
            }
            val startTimeMs = (ordered[index] * duration).toLong()
            val endTimeMs = if (ordered[index + 1] >= 1.0) duration
                else (ordered[index + 1] * duration).toLong()
            val movementDuration = (endTimeMs - startTimeMs).coerceAtLeast(1L)
            val commands = sampledToBezier(points, bounds, startIndex, endIndex)
                ?: return reject(
                    "sampled_bezier_generation_failed:segment=$index;" +
                        "startIndex=$startIndex;endIndex=$endIndex"
                )
            result += GestureStrokeSpec(
                commands = commands,
                durationMs = movementDuration,
                startDelayMs = elapsedWithPauses,
                continuationOfPrevious = result.isNotEmpty(),
                willContinue = true
            )
            elapsedWithPauses += movementDuration

            if (pause != null && pauseTime != null && abs(ordered[index + 1] - pauseTime) < 0.0000001) {
                val holdPoint = points[endIndex]
                // MoveTo-only Android Paths may be treated as empty. A zero-length cubic keeps
                // the path non-empty while the pointer remains at exactly the same coordinate.
                result += GestureStrokeSpec(
                    commands = listOf(
                        GesturePathCommand.MoveTo(holdPoint),
                        GesturePathCommand.CubicTo(holdPoint, holdPoint, holdPoint)
                    ),
                    durationMs = pause.durationMs,
                    startDelayMs = elapsedWithPauses,
                    continuationOfPrevious = true,
                    willContinue = true
                )
                elapsedWithPauses += pause.durationMs
            }
        }
        if (result.isEmpty()) return reject("compiled_stroke_chain_empty")
        result[result.lastIndex] = result.last().copy(willContinue = false)
        return result
    }

    private fun findTimeForProgress(motion: MotionResolution, target: Double): Double? {
        if (!target.isFinite() || target !in 0.0..1.0) return null
        var low = 0.0
        var high = 1.0
        repeat(70) {
            val middle = (low + high) / 2.0
            if (motion.progressAt(middle) < target) low = middle else high = middle
        }
        return (low + high) / 2.0
    }

    private fun sampledToBezier(
        points: List<GesturePoint>, bounds: ScreenRect, startIndex: Int, endIndex: Int
    ): List<GesturePathCommand>? {
        if (endIndex <= startIndex) return null
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val right = (bounds.right - 1).toFloat()
        val bottom = (bounds.bottom - 1).toFloat()
        val commands = mutableListOf<GesturePathCommand>(GesturePathCommand.MoveTo(points[startIndex]))
        for (index in startIndex until endIndex) {
            val a = points[index]
            val b = points[index + 1]
            val previous = points.getOrElse(index - 1) { a }
            val next = points.getOrElse(index + 2) { b }
            val rawC1 = GesturePoint(a.x + (b.x - previous.x) / 6f, a.y + (b.y - previous.y) / 6f)
            val rawC2 = GesturePoint(b.x - (next.x - a.x) / 6f, b.y - (next.y - a.y) / 6f)
            commands += GesturePathCommand.CubicTo(
                clampPoint(rawC1, left, top, right, bottom),
                clampPoint(rawC2, left, top, right, bottom),
                b
            )
        }
        return commands
    }

    private fun cumulativeLengths(points: List<GesturePoint>): DoubleArray {
        val result = DoubleArray(points.size)
        for (index in 1 until points.size) {
            result[index] = result[index - 1] + hypot(
                (points[index].x - points[index - 1].x).toDouble(),
                (points[index].y - points[index - 1].y).toDouble()
            )
        }
        return result
    }

    private fun nearestArcIndex(cumulative: DoubleArray, target: Double, minimum: Int, maximum: Int): Int? {
        if (minimum > maximum) return null
        var selected = -1
        var best = Double.POSITIVE_INFINITY
        for (index in minimum..maximum) {
            val delta = abs(cumulative[index] - target)
            if (delta < best) { best = delta; selected = index }
        }
        return selected.takeIf { it >= minimum }
    }

    private fun cubic(a: GesturePoint, c1: GesturePoint, c2: GesturePoint, b: GesturePoint, t: Float): GesturePoint {
        val u = 1f - t
        val u2 = u * u
        val t2 = t * t
        return GesturePoint(
            u2 * u * a.x + 3f * u2 * t * c1.x + 3f * u * t2 * c2.x + t2 * t * b.x,
            u2 * u * a.y + 3f * u2 * t * c1.y + 3f * u * t2 * c2.y + t2 * t * b.y
        )
    }

    private fun easingProgress(curve: GestureVelocityCurve, t: Double): Double = when (curve) {
        GestureVelocityCurve.COSINE_EASE_IN_OUT -> (1.0 - cos(PI * t)) / 2.0
        GestureVelocityCurve.CUBIC_EASE_OUT -> 1.0 - (1.0 - t).let { it * it * it }
        GestureVelocityCurve.QUADRATIC_EASE_IN_OUT -> if (t < 0.5) 2.0 * t * t
            else 1.0 - (2.0 - 2.0 * t).let { it * it } / 2.0
    }

    private fun velocityDerivative(curve: GestureVelocityCurve, t: Double): Double = when (curve) {
        GestureVelocityCurve.COSINE_EASE_IN_OUT -> PI / 2.0 * sin(PI * t)
        GestureVelocityCurve.CUBIC_EASE_OUT -> 3.0 * (1.0 - t) * (1.0 - t)
        GestureVelocityCurve.QUADRATIC_EASE_IN_OUT -> if (t < 0.5) 4.0 * t else 4.0 * (1.0 - t)
    }

    private fun velocitySecondDerivative(curve: GestureVelocityCurve, t: Double): Double = when (curve) {
        GestureVelocityCurve.COSINE_EASE_IN_OUT -> PI * PI / 2.0 * cos(PI * t)
        GestureVelocityCurve.CUBIC_EASE_OUT -> -6.0 * (1.0 - t)
        GestureVelocityCurve.QUADRATIC_EASE_IN_OUT -> if (t < 0.5) 4.0 else -4.0
    }

    private fun normalizeDegrees(angle: Float): Float {
        val normalized = angle % 360f
        return if (normalized > 180f) normalized - 360f else if (normalized < -180f) normalized + 360f else normalized
    }

    private fun clampPoint(point: GesturePoint, bounds: ScreenRect): GesturePoint =
        clampPoint(point, bounds.left.toFloat(), bounds.top.toFloat(),
            (bounds.right - 1).toFloat(), (bounds.bottom - 1).toFloat())

    private fun clampPoint(point: GesturePoint, left: Float, top: Float, right: Float, bottom: Float) =
        GesturePoint(point.x.coerceIn(left, right), point.y.coerceIn(top, bottom))

    private fun GestureDirectionProfile.isValid(): Boolean =
        (baseAngleDegrees == null || baseAngleDegrees.isFinite()) &&
            directionOffsetDegrees.isFinite() && abs(directionOffsetDegrees) <= 90f &&
            pathRotationDegrees.isFinite() && abs(pathRotationDegrees) <= 90f &&
            horizontalBias.isFinite() && abs(horizontalBias) <= 0.25f &&
            verticalBias.isFinite() && abs(verticalBias) <= 0.25f &&
            curveAmplitudePx.isFinite() && curveAmplitudePx in 0f..180f

    /** Axis profiles constrain the main travel vector while still allowing a modest direction offset. */
    private fun GestureDirectionProfile.isAngleCompatible(angleDegrees: Float): Boolean {
        val angle = Math.toRadians(angleDegrees.toDouble())
        val tolerance = sin(Math.toRadians(15.0))
        return when (axis) {
            GestureAxis.HORIZONTAL -> abs(sin(angle)) <= tolerance
            GestureAxis.VERTICAL -> abs(cos(angle)) <= tolerance
            GestureAxis.FREE_ANGLE -> true
        }
    }

    private fun GestureMotionProfile.isValid(): Boolean =
        maximumVelocityPxPerSecond.isFinite() && maximumVelocityPxPerSecond in 1f..20_000f &&
            initialVelocityPxPerSecond.isFinite() && initialVelocityPxPerSecond >= 0f &&
            releaseVelocityPxPerSecond.isFinite() && releaseVelocityPxPerSecond >= 0f &&
            accelerationPxPerSecondSquared.isFinite() && accelerationPxPerSecondSquared in 1f..100_000f &&
            decelerationPxPerSecondSquared.isFinite() && decelerationPxPerSecondSquared in 1f..100_000f

    private fun GesturePauseProfile.isValid(): Boolean =
        probability.isFinite() && probability in 0f..1f && progress.isFinite() && progress in 0.05f..0.95f &&
            durationMs in 1L..5_000L && (durationDistribution?.isValid() != false)

    private fun GesturePreDispatchDelayProfile.isValid(): Boolean =
        medianMs.isFinite() && medianMs > 0.0 && sigma.isFinite() && sigma >= 0.0 &&
            minimumMs >= 0L && maximumMs >= minimumMs && maximumMs <= 10_000L

    private fun GesturePoint.isInside(bounds: ScreenRect): Boolean =
        isInside(bounds.left.toFloat(), bounds.top.toFloat(), (bounds.right - 1).toFloat(), (bounds.bottom - 1).toFloat())

    private fun GesturePoint.isInside(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        x.isFinite() && y.isFinite() && x in left..right && y in top..bottom
}
