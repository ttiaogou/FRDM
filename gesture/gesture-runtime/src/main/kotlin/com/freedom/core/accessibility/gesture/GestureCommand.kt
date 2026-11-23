package com.freedom.core.accessibility.gesture

import com.freedom.core.accessibility.api.GestureSpec
import java.util.Collections

/**
 * Immutable command shared by the executor and visualizer.
 *
 * The constructor is internal to :gesture-runtime. Other modules may inspect the exact command for
 * visualization, but cannot construct or copy an executable command. The path lists are defensive
 * immutable snapshots so the validation receipt cannot be invalidated after approval.
 */
class GestureCommand internal constructor(
    val id: String,
    val kind: GestureKind,
    gesture: GestureSpec,
    /** Immutable coordinate region checked again by the serial executor before visualization/dispatch. */
    val validationRegion: GestureCoordinateRegion,
    /** Exact runtime/window/display snapshot shared by the request, preview and platform dispatch. */
    val executionContext: GestureExecutionContext,
    val description: String = "",
    /** Runtime-sampled delay before the single Android dispatch call; never a second dispatch path. */
    val preDispatchDelayMs: Long = 0L,
    /** Descriptive only: not injectable through AccessibilityService.dispatchGesture(). */
    internal val touchProfile: GestureTouchProfile = GestureTouchProfile(),
    internal val platformCapabilities: GesturePlatformCapabilityReport =
        GesturePlatformCapabilityReport.ACCESSIBILITY_SERVICE,
    /** Kinematic metadata describes release velocity; post-release fling remains target-app owned. */
    internal val releaseVelocityPxPerSecond: Float = 0f,
    /** Advisory fling prediction only; the host app controls post-UP scrolling. */
    internal val inertiaEstimate: GestureInertiaEstimate? = null
) {
    init {
        require(preDispatchDelayMs in 0L..10_000L)
        require(releaseVelocityPxPerSecond.isFinite() && releaseVelocityPxPerSecond >= 0f)
    }
    val gesture: GestureSpec = gesture.toImmutableSnapshot()

    private fun GestureSpec.toImmutableSnapshot(): GestureSpec = copy(
        commands = Collections.unmodifiableList(ArrayList(commands)),
        additionalStrokes = Collections.unmodifiableList(
            additionalStrokes.map { stroke ->
                stroke.copy(commands = Collections.unmodifiableList(ArrayList(stroke.commands)))
            }
        )
    )
}

/** Visual feedback is mandatory: show() must return false when it cannot display the command. */
interface GestureVisualizationPort {
    fun show(command: GestureCommand): Boolean
    fun onCompleted(command: GestureCommand)
    fun onCancelled(command: GestureCommand)
    fun onRejected(command: GestureCommand, reason: String)
    fun onFailed(command: GestureCommand, reason: String)
    fun clear()
}
