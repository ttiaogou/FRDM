package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureStrokeSpec
import com.freedom.core.accessibility.api.ScreenRect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Shared, platform-neutral geometry rules for safe screen taps and curved movement gestures.
 * The caller supplies fresh accessibility bounds and keeps the returned geometry immutable for
 * both visualization and dispatch.
 */
object GestureGeometryPolicy {
    enum class VerticalSwipeDirection { UP, DOWN }

    data class BezierSwipePlan(
        /** Five time-parameterized phases joined through one continuous Android pointer. */
        val strokes: List<GestureStrokeSpec>,
        val start: GesturePoint,
        val control1: GesturePoint,
        val control2: GesturePoint,
        val end: GesturePoint,
        /** Integer, half-open region containing every point and control point in the path. */
        val safeBounds: ScreenRect
    ) {
        val commands: List<GesturePathCommand> get() = strokes.first().commands
    }

    /**
     * AccessibilityService does not expose the foreground application's WindowInsets. Until a
     * target-window inset adapter is available, reserve a conservative edge band on every side.
     * This is intentionally a safety exclusion, not a claim that it exactly mirrors system insets.
     */
    fun safeDisplayBounds(
        displayBounds: ScreenRect,
        density: Float,
        edgeGuardDp: Float = DEFAULT_SYSTEM_EDGE_GUARD_DP
    ): ScreenRect? {
        if (displayBounds.isEmpty || !density.isFinite() || density <= 0f ||
            !edgeGuardDp.isFinite() || edgeGuardDp < 0f
        ) return null
        val guardPx = ceil(density * edgeGuardDp).toInt()
        if (displayBounds.width <= guardPx * 2 + 2 ||
            displayBounds.height <= guardPx * 2 + 2
        ) return null
        return ScreenRect(
            left = displayBounds.left + guardPx,
            top = displayBounds.top + guardPx,
            right = displayBounds.right - guardPx,
            bottom = displayBounds.bottom - guardPx
        )
    }

    /**
     * Samples uniformly from the safe inward region of the intersection of all verified bounds.
     * A center-point fallback is deliberately not provided: invalid or tiny targets are rejected.
     */
    fun sampleTapPoint(
        preferredBounds: ScreenRect,
        targetBounds: ScreenRect,
        displayBounds: ScreenRect,
        random: Random = Random.Default,
        insetRatio: Float = DEFAULT_TAP_INSET_RATIO
    ): GesturePoint? {
        val region = tapValidationRegion(
            intersect(preferredBounds, targetBounds, displayBounds) ?: return null,
            insetRatio
        ) ?: return null
        val point = GesturePoint(
            x = region.left + random.nextFloat() * (region.right - region.left),
            y = region.top + random.nextFloat() * (region.bottom - region.top)
        )
        return point.takeIf(region::contains)
    }

    /**
     * Exact inclusive coordinate region used by both tap sampling and tap-stroke creation.
     * The right/bottom edge reserves the final display pixel before applying the inward margin.
     */
    fun tapValidationRegion(
        bounds: ScreenRect,
        insetRatio: Float = DEFAULT_TAP_INSET_RATIO
    ): GestureCoordinateRegion? {
        if (bounds.isEmpty || !insetRatio.isFinite() || insetRatio < 0f || insetRatio >= 0.5f) {
            return null
        }
        val width = bounds.width.toFloat()
        val height = bounds.height.toFloat()
        if (width <= 1f || height <= 1f) return null
        val insetX = width * insetRatio
        val insetY = height * insetRatio
        val left = bounds.left + insetX
        val top = bounds.top + insetY
        val right = bounds.right - 1f - insetX
        val bottom = bounds.bottom - 1f - insetY
        if (right - left < 2f || bottom - top < 2f) return null
        return GestureCoordinateRegion(left, top, right, bottom)
    }

    /** Creates a short, non-zero tap stroke fully inside the exact validated safe region. */
    fun createTapCommands(
        point: GesturePoint,
        safeBounds: ScreenRect,
        random: Random = Random.Default
    ): List<GesturePathCommand>? {
        val region = tapValidationRegion(safeBounds) ?: return null
        if (!region.contains(point)) return null
        val candidates = listOf(
            GesturePoint(point.x + 1f, point.y + 1f),
            GesturePoint(point.x - 1f, point.y + 1f),
            GesturePoint(point.x + 1f, point.y - 1f),
            GesturePoint(point.x - 1f, point.y - 1f)
        ).filter(region::contains)
        val end = candidates.randomOrNull(random) ?: return null
        return listOf(GesturePathCommand.MoveTo(point), GesturePathCommand.LineTo(end))
    }

    /**
     * Produces a cubic Bezier swipe. Its base start is selected from the current viewport, then
     * receives a truncated-normal X/Y perturbation. Distance is derived from a base viewport ratio
     * and receives a bounded +/-7.5% scale. No clipping is used to repair rejected samples.
     */
    fun createVerticalSwipe(
        bounds: ScreenRect,
        direction: VerticalSwipeDirection,
        random: Random = Random.Default,
        durationMs: Long = DEFAULT_MOVEMENT_DURATION_MS
    ): BezierSwipePlan? {
        if (bounds.isEmpty || bounds.width < MIN_SWIPE_WIDTH_PX ||
            bounds.height < MIN_SWIPE_HEIGHT_PX || durationMs < MIN_CONTINUOUS_STROKE_DURATION_MS
        ) return null

        val safeBounds = insetBounds(
            bounds = bounds,
            horizontalInsetPx = maxOf(1f, bounds.width * SWIPE_SAFE_INSET_RATIO),
            verticalInsetPx = maxOf(1f, bounds.height * SWIPE_SAFE_INSET_RATIO)
        ) ?: return null
        if (safeBounds.width < MIN_SWIPE_WIDTH_PX || safeBounds.height < MIN_SWIPE_HEIGHT_PX) return null

        val left = safeBounds.left.toFloat()
        val top = safeBounds.top.toFloat()
        val right = (safeBounds.right - 1).toFloat()
        val bottom = (safeBounds.bottom - 1).toFloat()
        val width = right - left
        val height = bottom - top
        if (width < MIN_SWIPE_WIDTH_PX || height < MIN_SWIPE_HEIGHT_PX) return null

        val baseX = randomBetween(left + width * 0.30f, left + width * 0.70f, random)
        val baseY = when (direction) {
            VerticalSwipeDirection.UP -> randomBetween(top + height * 0.72f, top + height * 0.80f, random)
            VerticalSwipeDirection.DOWN -> randomBetween(top + height * 0.20f, top + height * 0.28f, random)
        }
        val sigma = startJitterSigmaPx(safeBounds)
        val maxOffset = sigma * GAUSSIAN_TRUNCATION_SIGMAS
        val startX = sampleGaussianAround(baseX, left, right, sigma, maxOffset, random) ?: return null
        val startY = sampleGaussianAround(baseY, top, bottom, sigma, maxOffset, random) ?: return null

        val baseDistance = height * BASE_VERTICAL_SWIPE_DISTANCE_RATIO
        val distanceScale = 1f + randomBetween(-SWIPE_DISTANCE_VARIATION_RATIO, SWIPE_DISTANCE_VARIATION_RATIO, random)
        val signedDistance = baseDistance * distanceScale * when (direction) {
            VerticalSwipeDirection.UP -> -1f
            VerticalSwipeDirection.DOWN -> 1f
        }
        val endY = startY + signedDistance
        if (endY < top || endY > bottom) return null

        val maxEndDrift = width * MAX_END_X_DRIFT_RATIO
        val endX = randomBetween(
            maxOf(left, startX - maxEndDrift),
            minOf(right, startX + maxEndDrift),
            random
        )
        val start = GesturePoint(startX, startY)
        val end = GesturePoint(endX, endY)
        val bend = sampleBend(startX, endX, left, right, width, random) ?: return null
        val control1 = GesturePoint(startX + bend, startY + (endY - startY) / 3f)
        val control2 = GesturePoint(endX + bend, startY + (endY - startY) * 2f / 3f)
        if (!listOf(start, control1, control2, end).all { it.isInside(safeBounds) }) return null

        val sampledCurve = sampleCubicUniformly(start, control1, control2, end, SWIPE_PATH_SAMPLE_STEPS)
        val strokes = createTimeParameterizedStrokes(sampledCurve, safeBounds, durationMs) ?: return null
        return BezierSwipePlan(strokes, start, control1, control2, end, safeBounds)
    }

    /**
     * Reproduces the existing Momo feed path profile inside the shared policy layer. Its original
     * curve and micro-perturbation settings are retained; only start/distance sampling and the
     * final safe-region enforcement are centralized here.
     */
    fun createFeedSwipe(
        bounds: ScreenRect,
        durationMs: Long,
        curveOffsetRatio: Float,
        microPerturbationFrequencyHz: Float,
        density: Float,
        maxCurveOffsetRatio: Float = 0.04f,
        enableMicroPerturbation: Boolean = true,
        microPerturbationAmplitudePx: Float = 1.2f,
        gesturePathSteps: Int = 32,
        microPerturbationPathSteps: Int = 100,
        random: Random = Random.Default
    ): BezierSwipePlan? {
        if (bounds.isEmpty || durationMs <= 0L || !density.isFinite() || density <= 0f ||
            !curveOffsetRatio.isFinite() || curveOffsetRatio < 0f ||
            !microPerturbationFrequencyHz.isFinite() || microPerturbationFrequencyHz < 0f ||
            !maxCurveOffsetRatio.isFinite() || maxCurveOffsetRatio <= 0f ||
            !microPerturbationAmplitudePx.isFinite() || microPerturbationAmplitudePx < 0f
        ) return null

        val horizontalInset = maxOf(ceil(8f * density).toInt(), ceil(bounds.width * 0.04f).toInt())
        val verticalInset = ceil(8f * density).toInt()
        val safeBounds = insetBounds(bounds, horizontalInset.toFloat(), verticalInset.toFloat()) ?: return null
        val safeLeft = safeBounds.left.toFloat()
        val safeRight = (safeBounds.right - 1).toFloat()
        val safeTop = safeBounds.top.toFloat()
        val safeBottom = (safeBounds.bottom - 1).toFloat()
        val safeWidth = safeRight - safeLeft
        val safeHeight = safeBottom - safeTop
        if (safeWidth < 80f || safeHeight < 120f) return null

        val baseStartX = randomBetween(safeLeft + safeWidth * 0.18f, safeLeft + safeWidth * 0.82f, random)
        val baseStartY = randomBetween(safeTop + safeHeight * 0.70f, safeTop + safeHeight * 0.78f, random)
        val sigma = startJitterSigmaPx(safeBounds)
        val maxOffset = sigma * GAUSSIAN_TRUNCATION_SIGMAS
        val startXf = sampleGaussianAround(baseStartX, safeLeft, safeRight, sigma, maxOffset, random) ?: return null
        val startYf = sampleGaussianAround(baseStartY, safeTop, safeBottom, sigma, maxOffset, random) ?: return null

        val baseDistance = safeHeight * BASE_FEED_SWIPE_DISTANCE_RATIO
        val distanceScale = 1f + randomBetween(-SWIPE_DISTANCE_VARIATION_RATIO, SWIPE_DISTANCE_VARIATION_RATIO, random)
        val endYf = startYf - baseDistance * distanceScale
        if (endYf < safeTop || endYf > safeBottom) return null
        val maxEndDrift = safeWidth * 0.025f
        val endXf = randomBetween(
            maxOf(safeLeft, startXf - maxEndDrift),
            minOf(safeRight, startXf + maxEndDrift),
            random
        )

        val curveLimit = minOf(
            maxOf(
                bounds.width * curveOffsetRatio,
                maxOf(MIN_CURVE_BEND_PX, bounds.width * MIN_FEED_CURVE_OFFSET_RATIO)
            ),
            bounds.width * maxCurveOffsetRatio
        )
        val minimumCurveOffset = minOf(
            curveLimit,
            maxOf(MIN_CURVE_BEND_PX * 0.5f, bounds.width * MIN_FEED_CURVE_OFFSET_RATIO * 0.5f)
        )
        if (curveLimit < minimumCurveOffset || minimumCurveOffset <= 0f) return null
        val control1Offset = sampleSignedOffset(startXf, safeLeft, safeRight, minimumCurveOffset, curveLimit, random)
            ?: return null
        val control2Offset = sampleSignedOffset(endXf, safeLeft, safeRight, minimumCurveOffset, curveLimit, random)
            ?: return null
        val control1X = startXf + control1Offset
        val control2X = endXf + control2Offset
        val verticalDistance = endYf - startYf
        val control1Y = startYf + verticalDistance / 3f
        val control2Y = startYf + verticalDistance * 2f / 3f
        val start = GesturePoint(startXf, startYf)
        val control1 = GesturePoint(control1X, control1Y)
        val control2 = GesturePoint(control2X, control2Y)
        val end = GesturePoint(endXf, endYf)
        if (!listOf(start, control1, control2, end).all { it.isInside(safeBounds) }) return null

        val steps = if (enableMicroPerturbation) microPerturbationPathSteps else gesturePathSteps
        if (steps < 2 || steps > MAX_FEED_PATH_STEPS) return null

        val points = ArrayList<GesturePoint>(steps + 1)
        for (step in 0..steps) {
            val t = step.toFloat() / steps.toFloat()
            val progress = easeInOut(t)
            val inverse = 1f - progress
            val inverseSquared = inverse * inverse
            val progressSquared = progress * progress
            var pathX = inverseSquared * inverse * startXf +
                3f * inverseSquared * progress * control1X +
                3f * inverse * progressSquared * control2X +
                progressSquared * progress * endXf
            var pathY = inverseSquared * inverse * startYf +
                3f * inverseSquared * progress * control1Y +
                3f * inverse * progressSquared * control2Y +
                progressSquared * progress * endYf
            if (enableMicroPerturbation && step > 0 && step < steps) {
                val tangentX = 3f * inverseSquared * (control1X - startXf) +
                    6f * inverse * progress * (control2X - control1X) +
                    3f * progressSquared * (endXf - control2X)
                val tangentY = 3f * inverseSquared * (control1Y - startYf) +
                    6f * inverse * progress * (control2Y - control1Y) +
                    3f * progressSquared * (endYf - control2Y)
                val tangentLength = hypot(tangentX.toDouble(), tangentY.toDouble()).toFloat().coerceAtLeast(1f)
                val elapsedSeconds = t * durationMs / 1_000f
                val wave = sin(2.0 * PI * microPerturbationFrequencyHz * elapsedSeconds.toDouble()).toFloat() *
                    microPerturbationAmplitudePx
                pathX += (-tangentY / tangentLength) * wave
                pathY += (tangentX / tangentLength) * wave
            }
            points += GesturePoint(pathX, pathY)
        }
        val strokes = createTimeParameterizedStrokes(points, safeBounds, durationMs) ?: return null
        return BezierSwipePlan(strokes, start, control1, control2, end, safeBounds)
    }

    /**
     * Freedom's calibration swipe uses the same randomized, bounded start and distance policy as
     * production swipes. The caller still marks its GestureSpec as INTERNAL_CALIBRATION.
     */
    fun createCalibrationSwipe(
        bounds: ScreenRect,
        random: Random = Random.Default,
        durationMs: Long = DEFAULT_MOVEMENT_DURATION_MS
    ): BezierSwipePlan? = createVerticalSwipe(bounds, VerticalSwipeDirection.UP, random, durationMs)

    /**
     * Converts an already sampled smooth curve into cubic segments. The supplied bounds are the
     * final safe region, not the outer viewport. Control points are rejected when they overshoot;
     * they are never silently clamped because clamping can distort the approved curve.
     */
    fun sampledCurveToBezierCommands(
        points: List<GesturePoint>,
        bounds: ScreenRect
    ): List<GesturePathCommand>? = sampledCurveToBezierCommandsRange(
        points = points,
        bounds = bounds,
        startIndex = 0,
        endIndex = points.lastIndex
    )

    /**
     * Creates five contiguous path phases whose arc-length fractions are approximately
     * 8% / 17% / 50% / 17% / 8%, while their time fractions are 15% / 20% / 30% / 20% / 15%.
     * This produces a stepwise acceleration, faster middle section, and deceleration. Segment
     * joins reuse the exact same sampled point and are linked with Android continueStroke(),
     * so the same pointer remains down across the complete logical movement.
     */
    private fun createTimeParameterizedStrokes(
        points: List<GesturePoint>,
        bounds: ScreenRect,
        durationMs: Long
    ): List<GestureStrokeSpec>? {
        if (durationMs < MIN_CONTINUOUS_STROKE_DURATION_MS || points.size < MIN_TIME_PROFILE_POINTS) return null
        val lengths = DoubleArray(points.size)
        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val current = points[index]
            lengths[index] = lengths[index - 1] + hypot(
                (current.x - previous.x).toDouble(),
                (current.y - previous.y).toDouble()
            )
        }
        val totalLength = lengths.last()
        if (!totalLength.isFinite() || totalLength <= MIN_PROFILE_PATH_LENGTH_PX) return null

        val splitFractions = listOf(
            SPEED_PHASE_DISTANCE_FRACTIONS[0],
            SPEED_PHASE_DISTANCE_FRACTIONS[0] + SPEED_PHASE_DISTANCE_FRACTIONS[1],
            SPEED_PHASE_DISTANCE_FRACTIONS[0] + SPEED_PHASE_DISTANCE_FRACTIONS[1] + SPEED_PHASE_DISTANCE_FRACTIONS[2],
            1.0 - SPEED_PHASE_DISTANCE_FRACTIONS.last()
        )
        val splitIndices = ArrayList<Int>(splitFractions.size)
        var minimumIndex = 1
        for ((splitIndex, fraction) in splitFractions.withIndex()) {
            val maximumIndex = points.lastIndex - (splitFractions.size - splitIndex)
            val selected = nearestArcLengthIndex(
                cumulativeLengths = lengths,
                targetLength = totalLength * fraction,
                minimumIndex = minimumIndex,
                maximumIndex = maximumIndex
            ) ?: return null
            splitIndices += selected
            minimumIndex = selected + 1
        }

        val ranges = buildList {
            var startIndex = 0
            for (split in splitIndices) {
                add(startIndex to split)
                startIndex = split
            }
            add(startIndex to points.lastIndex)
        }
        val durations = LongArray(SPEED_PHASE_DURATION_FRACTIONS.size)
        var allocatedDuration = 0L
        for (index in 0 until durations.lastIndex) {
            durations[index] = (durationMs * SPEED_PHASE_DURATION_FRACTIONS[index]).roundToLong()
                .coerceAtLeast(1L)
            allocatedDuration += durations[index]
        }
        durations[durations.lastIndex] = durationMs - allocatedDuration
        if (durations.any { it <= 0L } || ranges.size != durations.size) return null

        val result = ArrayList<GestureStrokeSpec>(ranges.size)
        var startTime = 0L
        for (index in ranges.indices) {
            val commands = sampledCurveToBezierCommandsRange(
                points = points,
                bounds = bounds,
                startIndex = ranges[index].first,
                endIndex = ranges[index].second
            ) ?: return null
            result += GestureStrokeSpec(
                commands = commands,
                durationMs = durations[index],
                startDelayMs = startTime,
                continuationOfPrevious = index > 0,
                willContinue = index < ranges.lastIndex
            )
            startTime += durations[index]
        }
        return result.takeIf { startTime == durationMs }
    }

    private fun nearestArcLengthIndex(
        cumulativeLengths: DoubleArray,
        targetLength: Double,
        minimumIndex: Int,
        maximumIndex: Int
    ): Int? {
        if (minimumIndex > maximumIndex) return null
        var bestIndex = -1
        var bestDifference = Double.POSITIVE_INFINITY
        for (index in minimumIndex..maximumIndex) {
            val difference = abs(cumulativeLengths[index] - targetLength)
            if (difference < bestDifference) {
                bestDifference = difference
                bestIndex = index
            }
        }
        return bestIndex.takeIf { it >= minimumIndex }
    }

    private fun sampledCurveToBezierCommandsRange(
        points: List<GesturePoint>,
        bounds: ScreenRect,
        startIndex: Int,
        endIndex: Int
    ): List<GesturePathCommand>? {
        if (points.size < 2 || bounds.isEmpty || bounds.width < 2 || bounds.height < 2 ||
            startIndex < 0 || endIndex > points.lastIndex || endIndex - startIndex < 1
        ) return null
        if (points.any { !it.isInside(bounds) }) return null
        val minX = bounds.left.toFloat()
        val minY = bounds.top.toFloat()
        val maxX = (bounds.right - 1).toFloat()
        val maxY = (bounds.bottom - 1).toFloat()

        val commands = ArrayList<GesturePathCommand>(endIndex - startIndex + 1)
        commands += GesturePathCommand.MoveTo(points[startIndex])
        for (index in startIndex until endIndex) {
            val start = points[index]
            val end = points[index + 1]
            // Neighbor points outside this segment are retained to keep tangents continuous at joins.
            val previous = points.getOrElse(index - 1) { start }
            val next = points.getOrElse(index + 2) { end }
            val control1 = GesturePoint(
                x = start.x + (end.x - previous.x) / 6f,
                y = start.y + (end.y - previous.y) / 6f
            )
            val control2 = GesturePoint(
                x = end.x - (next.x - start.x) / 6f,
                y = end.y - (next.y - start.y) / 6f
            )
            if (!control1.isInside(minX, minY, maxX, maxY) ||
                !control2.isInside(minX, minY, maxX, maxY)
            ) return null
            commands += GesturePathCommand.CubicTo(control1, control2, end)
        }
        return commands
    }

    fun validationRegion(bounds: ScreenRect): GestureCoordinateRegion? {
        if (bounds.isEmpty || bounds.width < 2 || bounds.height < 2) return null
        return GestureCoordinateRegion(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            (bounds.right - 1).toFloat(),
            (bounds.bottom - 1).toFloat()
        )
    }

    internal fun sampleTruncatedGaussianOffset(
        sigmaPx: Float,
        maxAbsOffsetPx: Float,
        random: Random = Random.Default
    ): Float? {
        if (!sigmaPx.isFinite() || sigmaPx <= 0f ||
            !maxAbsOffsetPx.isFinite() || maxAbsOffsetPx <= 0f
        ) return null
        repeat(MAX_GAUSSIAN_REJECTION_ATTEMPTS) {
            val first = random.nextDouble().coerceAtLeast(MIN_BOX_MULLER_U1)
            val second = random.nextDouble()
            val standardNormal = sqrt(-2.0 * ln(first)) * cos(2.0 * PI * second)
            val offset = (standardNormal * sigmaPx).toFloat()
            if (offset.isFinite() && abs(offset) <= maxAbsOffsetPx) return offset
        }
        return null
    }

    private fun sampleGaussianAround(
        baseline: Float,
        minimum: Float,
        maximum: Float,
        sigmaPx: Float,
        maxAbsOffsetPx: Float,
        random: Random
    ): Float? {
        if (baseline !in minimum..maximum) return null
        repeat(MAX_GAUSSIAN_REJECTION_ATTEMPTS) {
            val offset = sampleTruncatedGaussianOffset(sigmaPx, maxAbsOffsetPx, random) ?: return null
            val candidate = baseline + offset
            if (candidate in minimum..maximum) return candidate
        }
        return null
    }

    private fun sampleBend(
        startX: Float,
        endX: Float,
        left: Float,
        right: Float,
        width: Float,
        random: Random
    ): Float? {
        val minimumBend = maxOf(MIN_CURVE_BEND_PX, width * MIN_CURVE_BEND_RATIO)
        val maximumBend = maxOf(minimumBend, width * MAX_CURVE_BEND_RATIO)
        val positiveSpace = minOf(right - startX, right - endX)
        val negativeSpace = minOf(startX - left, endX - left)
        val positiveAllowed = positiveSpace >= minimumBend
        val negativeAllowed = negativeSpace >= minimumBend
        if (!positiveAllowed && !negativeAllowed) return null
        val positive = when {
            positiveAllowed && negativeAllowed -> random.nextBoolean()
            else -> positiveAllowed
        }
        val available = if (positive) positiveSpace else negativeSpace
        val maxMagnitude = minOf(maximumBend, available)
        if (maxMagnitude < minimumBend) return null
        val magnitude = randomBetween(minimumBend, maxMagnitude, random)
        return if (positive) magnitude else -magnitude
    }

    private fun sampleSignedOffset(
        originX: Float,
        minimum: Float,
        maximum: Float,
        minimumMagnitude: Float,
        maximumMagnitude: Float,
        random: Random
    ): Float? {
        val positiveSpace = maximum - originX
        val negativeSpace = originX - minimum
        val positiveAllowed = positiveSpace >= minimumMagnitude
        val negativeAllowed = negativeSpace >= minimumMagnitude
        if (!positiveAllowed && !negativeAllowed) return null
        val positive = when {
            positiveAllowed && negativeAllowed -> random.nextBoolean()
            else -> positiveAllowed
        }
        val available = if (positive) positiveSpace else negativeSpace
        val allowedMagnitude = minOf(maximumMagnitude, available)
        if (allowedMagnitude < minimumMagnitude) return null
        val magnitude = randomBetween(minimumMagnitude, allowedMagnitude, random)
        return if (positive) magnitude else -magnitude
    }

    private fun insetBounds(
        bounds: ScreenRect,
        horizontalInsetPx: Float,
        verticalInsetPx: Float
    ): ScreenRect? {
        if (bounds.isEmpty || !horizontalInsetPx.isFinite() || !verticalInsetPx.isFinite() ||
            horizontalInsetPx < 0f || verticalInsetPx < 0f
        ) return null
        val left = ceil(bounds.left + horizontalInsetPx).toInt()
        val top = ceil(bounds.top + verticalInsetPx).toInt()
        val right = floor(bounds.right - horizontalInsetPx).toInt()
        val bottom = floor(bounds.bottom - verticalInsetPx).toInt()
        if (right - left < 2 || bottom - top < 2) return null
        return ScreenRect(left, top, right, bottom)
    }

    private fun sampleCubicUniformly(
        start: GesturePoint,
        control1: GesturePoint,
        control2: GesturePoint,
        end: GesturePoint,
        steps: Int
    ): List<GesturePoint> = (0..steps).map { step ->
        val progress = step.toFloat() / steps.toFloat()
        val inverse = 1f - progress
        val inverseSquared = inverse * inverse
        val progressSquared = progress * progress
        GesturePoint(
            x = inverseSquared * inverse * start.x +
                3f * inverseSquared * progress * control1.x +
                3f * inverse * progressSquared * control2.x +
                progressSquared * progress * end.x,
            y = inverseSquared * inverse * start.y +
                3f * inverseSquared * progress * control1.y +
                3f * inverse * progressSquared * control2.y +
                progressSquared * progress * end.y
        )
    }

    private fun easeInOut(progress: Float): Float =
        0.5f - cos(PI * progress.toDouble()).toFloat() / 2f

    private fun randomBetween(minimum: Float, maximum: Float, random: Random): Float {
        require(minimum <= maximum)
        return if (minimum == maximum) minimum else minimum + random.nextFloat() * (maximum - minimum)
    }

    private fun startJitterSigmaPx(bounds: ScreenRect): Float =
        (minOf(bounds.width, bounds.height) * START_JITTER_SCALE).coerceIn(
            MIN_START_JITTER_SIGMA_PX,
            MAX_START_JITTER_SIGMA_PX
        )

    private fun GesturePoint.isInside(bounds: ScreenRect): Boolean =
        isInside(bounds.left.toFloat(), bounds.top.toFloat(), (bounds.right - 1).toFloat(), (bounds.bottom - 1).toFloat())

    private fun GesturePoint.isInside(left: Float, top: Float, right: Float, bottom: Float): Boolean =
        x.isFinite() && y.isFinite() && x >= left && x <= right && y >= top && y <= bottom

    private fun intersect(first: ScreenRect, second: ScreenRect, third: ScreenRect): ScreenRect? {
        val bounds = ScreenRect(
            left = maxOf(first.left, second.left, third.left),
            top = maxOf(first.top, second.top, third.top),
            right = minOf(first.right, second.right, third.right),
            bottom = minOf(first.bottom, second.bottom, third.bottom)
        )
        return bounds.takeUnless { it.isEmpty }
    }

    private const val DEFAULT_TAP_INSET_RATIO = 0.15f
    private const val DEFAULT_SYSTEM_EDGE_GUARD_DP = 24f
    private const val SWIPE_SAFE_INSET_RATIO = 0.04f
    private const val MAX_END_X_DRIFT_RATIO = 0.035f
    private const val MIN_CURVE_BEND_RATIO = 0.018f
    private const val MAX_CURVE_BEND_RATIO = 0.075f
    private const val MIN_CURVE_BEND_PX = 4f
    private const val MIN_SWIPE_WIDTH_PX = 80f
    private const val MIN_SWIPE_HEIGHT_PX = 120f
    private const val SWIPE_PATH_SAMPLE_STEPS = 72
    private const val MIN_TIME_PROFILE_POINTS = 7
    private const val MIN_PROFILE_PATH_LENGTH_PX = 1.0
    private const val MIN_CONTINUOUS_STROKE_DURATION_MS = 5L
    private const val DEFAULT_MOVEMENT_DURATION_MS = 500L
    // The 8/17/50/17/8 distance and 15/20/30/20/15 time profiles create
    // increasing speed through the first three phases and matching deceleration.
    val SPEED_PHASE_DISTANCE_FRACTIONS = listOf(0.08, 0.17, 0.50, 0.17, 0.08)
    val SPEED_PHASE_DURATION_FRACTIONS = listOf(0.15, 0.20, 0.30, 0.20, 0.15)
    private const val MIN_FEED_CURVE_OFFSET_RATIO = 0.008f
    private const val MAX_FEED_PATH_STEPS = 256
    private const val START_JITTER_SCALE = 0.006f
    private const val MIN_START_JITTER_SIGMA_PX = 5f
    private const val MAX_START_JITTER_SIGMA_PX = 8f
    private const val GAUSSIAN_TRUNCATION_SIGMAS = 3f
    private const val MAX_GAUSSIAN_REJECTION_ATTEMPTS = 128
    private const val MIN_BOX_MULLER_U1 = 1e-12
    private const val BASE_VERTICAL_SWIPE_DISTANCE_RATIO = 0.48f
    private const val BASE_FEED_SWIPE_DISTANCE_RATIO = 0.46f
    private const val SWIPE_DISTANCE_VARIATION_RATIO = 0.075f
}
