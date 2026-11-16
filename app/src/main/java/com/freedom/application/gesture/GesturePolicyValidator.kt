package com.freedom.application.gesture

import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GestureStrokeSpec
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureKind
import com.freedom.core.accessibility.gesture.GestureGeometryPolicy
import kotlin.math.abs
import kotlin.math.hypot

/** Final geometry, safe-region and Android continuation validation shared by compiler and executor. */
internal class GesturePolicyValidator(
    private val displayMetrics: () -> DisplayMetricsSnapshot
) {
    /**
     * Validates untrusted business input before the compiler is allowed to inspect its geometry.
     * Successful validation is represented by a Runtime-only nominal type, not a Boolean flag.
     */
    fun validateRequest(request: GestureIntentRequest): GestureRequestValidationResult {
        val failure = validateRequestFields(request)
        return if (failure == null) {
            GestureRequestValidationResult.Accepted(
                ValidatedGestureRequest.issueAfterValidation(request)
            )
        } else {
            GestureRequestValidationResult.Rejected(failure)
        }
    }

    /**
     * The only production transition that can issue ApprovedGestureCommand. Validation is repeated
     * by the coordinator immediately before visualization/dispatch to catch stale live context.
     */
    fun validateAndApprove(command: GestureCommand): GestureCommandValidationResult {
        val failure = validate(command)
        return if (failure == null) {
            GestureCommandValidationResult.Approved(
                ApprovedGestureCommand.issueAfterValidation(command)
            )
        } else {
            GestureCommandValidationResult.Rejected(failure)
        }
    }

    private fun validateRequestFields(request: GestureIntentRequest): String? {
        if (request.id.isBlank()) return "request_id_is_blank"
        if (request.description.isBlank()) return "request_description_is_blank"
        val context = request.executionContext
        if (context.runGeneration < 0L) return "request_run_generation_invalid"
        if (context.targetWindow.packageName.isBlank()) return "request_target_package_blank"
        val coordinates = context.coordinateContext
        if (coordinates.widthPixels <= 0 || coordinates.heightPixels <= 0 ||
            !coordinates.density.isFinite() || coordinates.density <= 0f ||
            coordinates.displayId < 0 || coordinates.rotationDegrees !in setOf(0, 90, 180, 270)
        ) return "request_coordinate_context_invalid"

        fun validDuration(durationMs: Long): Boolean =
            durationMs in MIN_STROKE_DURATION_MS..MAX_STROKE_DURATION_MS

        fun validBounds(bounds: com.freedom.core.accessibility.api.ScreenRect): Boolean =
            !bounds.isEmpty

        return when (val intent = request.intent) {
            is GestureIntent.Tap -> when {
                !validDuration(intent.durationMs) -> "tap_duration_out_of_range"
                !validBounds(intent.preferredBounds) -> "tap_preferred_bounds_empty"
                !validBounds(intent.targetBounds) -> "tap_target_bounds_empty"
                intent.displayBounds?.let { !validBounds(it) } == true -> "tap_display_bounds_empty"
                else -> null
            }
            is GestureIntent.DoubleTap -> when {
                !validDuration(intent.durationMs) -> "double_tap_duration_out_of_range"
                intent.intervalMs !in 1L..MAX_STROKE_DELAY_MS -> "double_tap_interval_out_of_range"
                !validBounds(intent.preferredBounds) -> "double_tap_preferred_bounds_empty"
                !validBounds(intent.targetBounds) -> "double_tap_target_bounds_empty"
                intent.displayBounds?.let { !validBounds(it) } == true -> "double_tap_display_bounds_empty"
                else -> null
            }
            is GestureIntent.VerticalSwipe -> when {
                !validDuration(intent.durationMs) -> "vertical_swipe_duration_out_of_range"
                !validBounds(intent.viewport) -> "vertical_swipe_viewport_empty"
                else -> null
            }
            is GestureIntent.FeedSwipe -> when {
                !validDuration(intent.durationMs) -> "feed_swipe_duration_out_of_range"
                !validBounds(intent.viewport) -> "feed_swipe_viewport_empty"
                else -> null
            }
            is GestureIntent.DirectionalSwipe -> when {
                !validDuration(intent.durationMs) -> "directional_swipe_duration_out_of_range"
                !validBounds(intent.viewport) -> "directional_swipe_viewport_empty"
                !intent.angleDegrees.isFinite() || intent.angleDegrees !in -180f..180f -> "directional_swipe_angle_invalid"
                !intent.angleDegrees.isCompatibleWith(intent.axis) -> "directional_swipe_axis_mismatch"
                else -> null
            }
            is GestureIntent.CalibrationSwipe -> when {
                !validDuration(intent.durationMs) -> "calibration_swipe_duration_out_of_range"
                !validBounds(intent.region) -> "calibration_swipe_region_empty"
                else -> null
            }
        }
    }

    private fun Float.isCompatibleWith(axis: com.freedom.core.accessibility.gesture.GestureIntentAxis): Boolean {
        val radians = Math.toRadians(toDouble())
        val tolerance = kotlin.math.sin(Math.toRadians(15.0))
        return when (axis) {
            com.freedom.core.accessibility.gesture.GestureIntentAxis.HORIZONTAL -> abs(kotlin.math.sin(radians)) <= tolerance
            com.freedom.core.accessibility.gesture.GestureIntentAxis.VERTICAL -> abs(kotlin.math.cos(radians)) <= tolerance
            com.freedom.core.accessibility.gesture.GestureIntentAxis.FREE_ANGLE -> true
        }
    }

    fun validate(command: GestureCommand): String? {
        if (command.id.isBlank()) return "command_id_is_blank"
        val metrics = runCatching(displayMetrics).getOrNull() ?: return "display_metrics_unavailable"
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return "invalid_display_size"
        if (!metrics.density.isFinite() || metrics.density <= 0f) return "invalid_display_density"

        val region = command.validationRegion
        if (region.left < 0f || region.top < 0f ||
            region.right >= metrics.widthPixels.toFloat() ||
            region.bottom >= metrics.heightPixels.toFloat()
        ) return "validation_region_outside_display"

        val strokes = buildList {
            add(
                GestureStrokeSpec(
                    commands = command.gesture.commands,
                    durationMs = command.gesture.durationMs,
                    startDelayMs = command.gesture.startDelayMs,
                    continuationOfPrevious = false,
                    willContinue = command.gesture.willContinue
                )
            )
            addAll(command.gesture.additionalStrokes)
        }
        if (strokes.isEmpty()) return "gesture_has_no_strokes"

        val requiresBezier = command.kind == GestureKind.SWIPE || command.kind == GestureKind.DRAG
        var movementStart: GesturePoint? = null
        var movementEnd: GesturePoint? = null
        val movementGeometry = mutableListOf<GesturePoint>()
        var previousStroke: GestureStrokeSpec? = null

        for ((index, stroke) in strokes.withIndex()) {
            val timingFailure = validateTiming(stroke, index)
            if (timingFailure != null) return timingFailure

            val firstPoint = stroke.commands.firstOrNull().asStartPoint()
                ?: return "path_must_start_with_move_to_$index"
            val lastPoint = stroke.commands.lastOrNull().asEndPoint()
                ?: return "path_missing_end_point_$index"

            if (stroke.continuationOfPrevious) {
                val previous = previousStroke ?: return "orphan_continuation_stroke_$index"
                if (!previous.willContinue) return "previous_stroke_did_not_allow_continuation_$index"
                if (stroke.startDelayMs != previous.startDelayMs + previous.durationMs) {
                    return "continuation_start_time_mismatch_$index"
                }
                val previousEnd = previous.commands.lastOrNull().asEndPoint()
                    ?: return "previous_continuation_missing_end_$index"
                if (!samePoint(previousEnd, firstPoint)) return "continuation_path_join_mismatch_$index"
            } else if (previousStroke?.willContinue == true) {
                return "continued_pointer_chain_was_interrupted_$index"
            }

            var moveCount = 0
            var hasBezier = false
            for (pathCommand in stroke.commands) {
                if (pathCommand is GesturePathCommand.MoveTo) moveCount++
                if (requiresBezier && pathCommand is GesturePathCommand.LineTo) {
                    return "linear_segment_not_allowed_for_movement_gesture_$index"
                }
                if (pathCommand is GesturePathCommand.CubicTo) hasBezier = true
                val points = when (pathCommand) {
                    is GesturePathCommand.MoveTo -> listOf(pathCommand.point)
                    is GesturePathCommand.LineTo -> listOf(pathCommand.point)
                    is GesturePathCommand.CubicTo -> listOf(
                        pathCommand.control1,
                        pathCommand.control2,
                        pathCommand.end
                    )
                }
                for (point in points) {
                    if (!point.x.isFinite() || !point.y.isFinite()) return "non_finite_coordinate_$index"
                    if (point.x < 0f || point.y < 0f ||
                        point.x >= metrics.widthPixels.toFloat() ||
                        point.y >= metrics.heightPixels.toFloat()
                    ) return "coordinate_outside_display_$index"
                    if (!region.contains(point)) return "coordinate_outside_validated_safe_region_$index"
                }
            }
            if (moveCount != 1) return "path_must_have_exactly_one_contour_$index"
            val stationaryContinuation = requiresBezier && isStationaryContinuation(stroke)
            if (requiresBezier && !hasBezier && !stationaryContinuation) {
                return "movement_gesture_requires_bezier_curve_$index"
            }

            if (requiresBezier && !stationaryContinuation) {
                if (index > 0 && !stroke.continuationOfPrevious) {
                    return "movement_gesture_must_use_one_continuous_pointer_$index"
                }
                if (movementStart == null) movementStart = firstPoint
                movementEnd = lastPoint
                movementGeometry += stroke.commands.flatMap { it.geometryPoints() }
            }

            if (index < strokes.lastIndex && !stroke.willContinue && strokes[index + 1].continuationOfPrevious) {
                return "stroke_chain_not_marked_for_continuation_$index"
            }
            previousStroke = stroke
        }

        if (previousStroke?.willContinue == true) return "gesture_ends_with_unfinished_continuation"

        if (requiresBezier) {
            if (!hasNonLinearMovementGeometry(movementStart, movementEnd, movementGeometry)) {
                return "movement_gesture_requires_non_linear_bezier_curve"
            }
            val speedProfileFailure = validateMovementSpeedProfile(strokes)
            if (speedProfileFailure != null) return speedProfileFailure
        }
        return null
    }

    /** Validates a continuous movement chain with optional stationary pause segments. */
    private fun validateMovementSpeedProfile(strokes: List<GestureStrokeSpec>): String? {
        val pauses = strokes.filter(::isStationaryContinuation)
        val movement = strokes.filterNot(::isStationaryContinuation)
        if (movement.size !in 3..8) return "movement_gesture_requires_three_to_eight_motion_segments"
        if (pauses.size > 3) return "movement_gesture_pause_segment_limit_exceeded"
        val totalDuration = strokes.sumOf { it.durationMs }
        if (totalDuration <= 0L || totalDuration > MAX_TOTAL_GESTURE_DURATION_MS) {
            return "movement_gesture_total_duration_out_of_range"
        }
        if (pauses.any { it.durationMs !in 1L..MAX_PAUSE_DURATION_MS }) {
            return "movement_gesture_pause_duration_out_of_range"
        }
        val lengths = movement.map(::approximatePathLength)
        if (lengths.any { !it.isFinite() || it <= MIN_MOVEMENT_PATH_LENGTH_PX }) {
            return "movement_gesture_path_segment_length_invalid"
        }
        val durations = movement.map { it.durationMs.toDouble() }
        if (durations.any { it <= 0.0 }) return "movement_gesture_segment_duration_invalid"
        val normalizedSpeeds = lengths.indices.map { lengths[it] / durations[it] }
        if (normalizedSpeeds.any { !it.isFinite() || it <= 0.0 }) {
            return "movement_gesture_speed_profile_invalid"
        }
        // The profile is policy-selected (ease-in/out or kinematic), so do not force one fixed
        // five-phase ratio. Instead ensure there is a finite positive speed envelope.
        val peak = normalizedSpeeds.maxOrNull() ?: return "movement_gesture_speed_profile_missing"
        if (peak > MAX_VALIDATED_SEGMENT_SPEED_PX_PER_MS) return "movement_gesture_speed_exceeds_limit"
        return null
    }

    /** A pause retains the pointer at one point using a zero-length cubic path. */
    private fun isStationaryContinuation(stroke: GestureStrokeSpec): Boolean {
        if (!stroke.continuationOfPrevious) return false
        val start = stroke.commands.firstOrNull().asStartPoint() ?: return false
        if (stroke.commands.none { it is GesturePathCommand.CubicTo }) return false
        val points = stroke.commands.flatMap { it.geometryPoints() }
        return points.isNotEmpty() && points.all { samePoint(start, it) }
    }

    /** Samples cubic pieces to estimate their arc length without Android PathMeasure. */
    private fun approximatePathLength(stroke: GestureStrokeSpec): Double {
        var cursor: GesturePoint? = null
        var total = 0.0
        for (command in stroke.commands) {
            when (command) {
                is GesturePathCommand.MoveTo -> cursor = command.point
                is GesturePathCommand.LineTo -> {
                    val start = cursor ?: return Double.NaN
                    total += distance(start, command.point)
                    cursor = command.point
                }
                is GesturePathCommand.CubicTo -> {
                    val start = cursor ?: return Double.NaN
                    var previous = start
                    for (step in 1..CUBIC_LENGTH_SAMPLE_STEPS) {
                        val t = step.toFloat() / CUBIC_LENGTH_SAMPLE_STEPS.toFloat()
                        val inverse = 1f - t
                        val inverseSquared = inverse * inverse
                        val tSquared = t * t
                        val point = GesturePoint(
                            x = inverseSquared * inverse * start.x +
                                3f * inverseSquared * t * command.control1.x +
                                3f * inverse * tSquared * command.control2.x +
                                tSquared * t * command.end.x,
                            y = inverseSquared * inverse * start.y +
                                3f * inverseSquared * t * command.control1.y +
                                3f * inverse * tSquared * command.control2.y +
                                tSquared * t * command.end.y
                        )
                        total += distance(previous, point)
                        previous = point
                    }
                    cursor = command.end
                }
            }
        }
        return total
    }

    private fun distance(first: GesturePoint, second: GesturePoint): Double =
        hypot((second.x - first.x).toDouble(), (second.y - first.y).toDouble())

    private fun validateTiming(stroke: GestureStrokeSpec, index: Int): String? {
        if (stroke.durationMs <= 0L || stroke.startDelayMs < 0L) return "invalid_stroke_timing_$index"
        if (stroke.durationMs > MAX_STROKE_DURATION_MS || stroke.startDelayMs > MAX_STROKE_DELAY_MS) {
            return "stroke_timing_out_of_range_$index"
        }
        if (stroke.continuationOfPrevious && stroke.startDelayMs == 0L) {
            // A continuation may start at zero only if the preceding stroke has zero duration,
            // which GestureStrokeSpec itself forbids. This catches malformed chain metadata.
            return "continuation_has_invalid_start_time_$index"
        }
        return null
    }

    private fun GesturePathCommand?.asStartPoint(): GesturePoint? = when (this) {
        is GesturePathCommand.MoveTo -> point
        else -> null
    }

    private fun GesturePathCommand?.asEndPoint(): GesturePoint? = when (this) {
        is GesturePathCommand.MoveTo -> point
        is GesturePathCommand.LineTo -> point
        is GesturePathCommand.CubicTo -> end
        null -> null
    }

    private fun GesturePathCommand.geometryPoints(): List<GesturePoint> = when (this) {
        is GesturePathCommand.MoveTo -> listOf(point)
        is GesturePathCommand.LineTo -> listOf(point)
        is GesturePathCommand.CubicTo -> listOf(control1, control2, end)
    }

    private fun samePoint(first: GesturePoint, second: GesturePoint): Boolean =
        abs(first.x - second.x) <= CONTINUATION_JOIN_TOLERANCE_PX &&
            abs(first.y - second.y) <= CONTINUATION_JOIN_TOLERANCE_PX

    /** Checks the whole movement path, rather than rejecting locally straight curve subdivisions. */
    private fun hasNonLinearMovementGeometry(
        firstPoint: GesturePoint?,
        lastPoint: GesturePoint?,
        points: List<GesturePoint>
    ): Boolean {
        val start = firstPoint ?: return false
        val end = lastPoint ?: return false
        val chordX = end.x - start.x
        val chordY = end.y - start.y
        if (hypot(chordX.toDouble(), chordY.toDouble()) < MIN_MOVEMENT_CHORD_PX) {
            val origin = points.firstOrNull() ?: return false
            val baselinePoint = points.firstOrNull { point ->
                hypot((point.x - origin.x).toDouble(), (point.y - origin.y).toDouble()) >= MIN_MOVEMENT_CHORD_PX
            } ?: return false
            val baselineX = baselinePoint.x - origin.x
            val baselineY = baselinePoint.y - origin.y
            return points.any { point ->
                abs(baselineX * (point.y - origin.y) - baselineY * (point.x - origin.x)) >
                    MIN_CURVE_AREA_PX_SQUARED
            }
        }
        return points.any { point ->
            abs(chordX * (point.y - start.y) - chordY * (point.x - start.x)) > MIN_CURVE_AREA_PX_SQUARED
        }
    }

    private companion object {
        const val CONTINUATION_JOIN_TOLERANCE_PX = 0.01f
        const val MIN_CURVE_AREA_PX_SQUARED = 0.5f
        const val MIN_MOVEMENT_CHORD_PX = 1f
        const val MAX_STROKE_DURATION_MS = 60_000L
        const val MAX_STROKE_DELAY_MS = 60_000L
        const val MAX_TOTAL_GESTURE_DURATION_MS = 60_000L
        const val MAX_PAUSE_DURATION_MS = 5_000L
        const val MAX_VALIDATED_SEGMENT_SPEED_PX_PER_MS = 20.0
        const val CUBIC_LENGTH_SAMPLE_STEPS = 8
        const val MIN_MOVEMENT_PATH_LENGTH_PX = 1.0
        const val MIN_STROKE_DURATION_MS = 1L
        const val MAX_DISTANCE_PROFILE_DEVIATION = 0.035
    }
}
