package com.freedom.core.accessibility.overlay

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot

/** WindowManager and View implementation remains behind the Android overlay adapter. */
interface OverlayPort {
    fun isMonitorAttached(): Boolean
    fun touchStateDescription(): String
    fun setMonitorTouchPassthrough(enabled: Boolean, watchdogTimeoutMillis: Long? = null): Boolean
    fun showFirstSwipeCountdown(secondsRemaining: Int, message: String): Boolean
    fun hideFirstSwipeCountdown()
    fun refreshMonitor(appLabel: String, pageLabel: String)
    fun clearGestureTrail()

    /** Draws a short-lived, non-interactive highlight for a freshly validated node target. */
    fun showNodeBoundsHighlight(
        selectedBounds: ScreenRect,
        ancestorBounds: List<ScreenRect>,
        label: String,
        durationMs: Long = 5_000L
    ): Boolean

    /** Draws a bounded set of fresh target-app node bounds over the target app without receiving touches. */
    fun showNodeLayoutBounds(
        bounds: List<ScreenRect>,
        label: String,
        durationMs: Long = 12_000L
    ): Boolean = false

    /** Draws fresh same-target-app bounds until explicitly cleared; it remains pass-through. */
    fun showPersistentNodeLayoutBounds(
        bounds: List<ScreenRect>,
        label: String
    ): Boolean = false

    /** Draws a persistent, touch-interactive inspector over the target app from detached snapshots. */
    fun showInteractiveNodeLayoutBounds(
        snapshots: List<AccessibilityTreeSnapshot>,
        label: String
    ): Boolean = false

    fun clearNodeBoundsHighlight()
}
