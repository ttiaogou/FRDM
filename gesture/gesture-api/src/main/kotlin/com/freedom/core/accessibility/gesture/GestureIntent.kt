package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.runtime.TaskLease

/**
 * Business-facing gesture intent. It describes what should be touched, never a physical path.
 * Only GesturePolicyPipeline may compile these intents into executable screen gestures.
 */
sealed interface GestureIntent {
    data class Tap(
        val preferredBounds: ScreenRect,
        val targetBounds: ScreenRect,
        val durationMs: Long,
        val displayBounds: ScreenRect? = null,
        val feedbackIntent: GestureFeedbackIntent = GestureFeedbackIntent.STANDARD
    ) : GestureIntent

    data class DoubleTap(
        val preferredBounds: ScreenRect,
        val targetBounds: ScreenRect,
        val durationMs: Long,
        val intervalMs: Long,
        val displayBounds: ScreenRect? = null,
        val feedbackIntent: GestureFeedbackIntent = GestureFeedbackIntent.STANDARD
    ) : GestureIntent

    data class VerticalSwipe(
        val viewport: ScreenRect,
        val direction: VerticalSwipeDirection,
        val durationMs: Long
    ) : GestureIntent

    /**
     * Semantic feed scroll request. Curve, perturbation, sampling and density are resolved by
     * GesturePolicyManager from the live runtime policy, never supplied by the business caller.
     */
    data class FeedSwipe(
        val viewport: ScreenRect,
        val durationMs: Long
    ) : GestureIntent

    /** Semantic arbitrary-angle swipe; path geometry and speed remain Runtime-owned. */
    data class DirectionalSwipe(
        val viewport: ScreenRect,
        val angleDegrees: Float,
        val durationMs: Long,
        val axis: GestureIntentAxis = GestureIntentAxis.FREE_ANGLE
    ) : GestureIntent

    /** One image-page swipe; geometry and duration belong to the independent Runtime policy. */
    data class ImagePagingSwipe(
        val viewport: ScreenRect,
        val direction: ImagePagingDirection
    ) : GestureIntent

    /** Explicit, user-requested gesture on Freedom's own calibration screen. */
    data class CalibrationSwipe(
        val region: ScreenRect,
        val durationMs: Long
    ) : GestureIntent
}

/** Axis intent is semantic metadata; it does not expose a physical path or platform implementation. */
enum class GestureIntentAxis { HORIZONTAL, VERTICAL, FREE_ANGLE }

enum class ImagePagingDirection { LEFT, RIGHT }

/** Vertical movement is expressed semantically; business code does not calculate endpoints. */
enum class VerticalSwipeDirection {
    UP,
    DOWN
}

/**
 * Immutable physical coordinate environment captured alongside a target window.
 * Gesture coordinates are physical pixels on one display; a rotation or display-size change
 * invalidates the snapshot before the command can be dispatched.
 */
data class GestureCoordinateContext(
    val displayId: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val density: Float,
    val rotationDegrees: Int
) {
    init {
        require(displayId >= 0) { "displayId must be non-negative" }
        require(widthPixels > 0 && heightPixels > 0) { "display dimensions must be positive" }
        require(density.isFinite() && density > 0f) { "display density must be positive and finite" }
        require(rotationDegrees in setOf(0, 90, 180, 270)) { "rotationDegrees must be a right angle" }
    }
}

fun DisplayMetricsSnapshot.toGestureCoordinateContext(): GestureCoordinateContext =
    GestureCoordinateContext(
        displayId = displayId,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        density = density,
        rotationDegrees = rotationDegrees
    )

/**
 * Binds gesture geometry to the runtime session and the exact Android window/display snapshot
 * from which its screen coordinates were obtained.
 */
data class GestureExecutionContext(
    val runGeneration: Long,
    val targetWindow: WindowRef,
    val coordinateContext: GestureCoordinateContext,
    /** Exact business-task capability captured with this gesture, when a task owns UI control. */
    val taskLease: TaskLease? = null
) {
    init {
        require(runGeneration >= 0L) { "runGeneration must be non-negative" }
        require(targetWindow.packageName.isNotBlank()) { "target window package must not be blank" }
        require(targetWindow.generation >= 0L) { "target window generation must be non-negative" }
    }
}

/** Stable request identity is created by the caller; path geometry is not part of this contract. */
data class GestureIntentRequest(
    val id: String,
    val intent: GestureIntent,
    val description: String,
    /** Runtime/window/display snapshot captured when the coordinate intent is prepared. */
    val executionContext: GestureExecutionContext
) {
    val kind: GestureKind
        get() = when (intent) {
            is GestureIntent.Tap -> GestureKind.TAP
            is GestureIntent.DoubleTap -> GestureKind.DOUBLE_TAP
            is GestureIntent.VerticalSwipe,
            is GestureIntent.FeedSwipe,
            is GestureIntent.DirectionalSwipe,
            is GestureIntent.ImagePagingSwipe,
            is GestureIntent.CalibrationSwipe -> GestureKind.SWIPE
        }
    val feedbackIntent: GestureFeedbackIntent
        get() = when (val value = intent) {
            is GestureIntent.Tap -> value.feedbackIntent
            is GestureIntent.DoubleTap -> value.feedbackIntent
            else -> GestureFeedbackIntent.STANDARD
        }
}
