package com.freedom.adapters.accessibility.android

import android.content.Context
import android.view.WindowManager
import com.freedom.core.accessibility.overlay.OverlayPort
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.adapters.accessibility.android.MonitorOverlay

/** Android WindowManager implementation for the floating-button and gesture overlay boundary. */
internal class AndroidOverlayPort(
    private val context: Context
) : OverlayPort {
    private val countdownOverlay by lazy(LazyThreadSafetyMode.NONE) {
        AndroidFirstSwipeCountdownOverlay(context)
    }

    override fun isMonitorAttached(): Boolean = MonitorOverlay.isAttached()

    override fun touchStateDescription(): String = MonitorOverlay.touchStateDescription()

    @Suppress("UNUSED_PARAMETER")
    override fun setMonitorTouchPassthrough(
        enabled: Boolean,
        watchdogTimeoutMillis: Long?
    ): Boolean = MonitorOverlay.setTouchPassthrough(enabled)

    override fun showFirstSwipeCountdown(secondsRemaining: Int, message: String): Boolean =
        countdownOverlay.show(
            message.ifBlank { "首次滑动倒计时\n$secondsRemaining\n请观察动态列表是否移动" }
        )

    override fun hideFirstSwipeCountdown() = countdownOverlay.hide()

    @Suppress("UNUSED_PARAMETER")
    override fun refreshMonitor(appLabel: String, pageLabel: String) {
        MonitorOverlay.update(context)
    }

    override fun clearGestureTrail() = GestureTrailOverlay.clear()

    override fun showNodeBoundsHighlight(
        selectedBounds: ScreenRect,
        ancestorBounds: List<ScreenRect>,
        label: String,
        durationMs: Long
    ): Boolean = NodeBoundsHighlightOverlay.show(
        context = context,
        selectedBounds = selectedBounds,
        ancestorBounds = ancestorBounds,
        label = label,
        durationMs = durationMs
    )

    override fun showNodeLayoutBounds(
        bounds: List<ScreenRect>,
        label: String,
        durationMs: Long
    ): Boolean = NodeBoundsHighlightOverlay.showAll(
        context = context,
        bounds = bounds,
        label = label,
        durationMs = durationMs
    )

    override fun showPersistentNodeLayoutBounds(
        bounds: List<ScreenRect>,
        label: String
    ): Boolean = NodeBoundsHighlightOverlay.showAllPersistent(
        context = context,
        bounds = bounds,
        label = label
    )

    override fun showInteractiveNodeLayoutBounds(
        snapshots: List<AccessibilityTreeSnapshot>,
        label: String
    ): Boolean = NodeBoundsHighlightOverlay.showInteractiveAll(
        context = context,
        snapshots = snapshots,
        label = label
    )

    override fun clearNodeBoundsHighlight() = NodeBoundsHighlightOverlay.clear()
}

/** Owns the non-touchable TYPE_ACCESSIBILITY_OVERLAY countdown view. */
private class AndroidFirstSwipeCountdownOverlay(
    private val context: Context
) {
    private var windowManager: WindowManager? = null
    private var view: android.widget.TextView? = null

    fun show(message: String): Boolean {
        val existing = view
        if (existing != null) {
            existing.text = message
            return true
        }
        val wm = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull() ?: return false
        val density = context.resources.displayMetrics.density
        val textView = android.widget.TextView(context).apply {
            text = message
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(22), dp(16), dp(22), dp(16))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6293548.toInt())
                cornerRadius = 16f * density
                setStroke(dp(1), 0xFF9AA8BC.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = dp(110)
        }
        return runCatching {
            wm.addView(textView, params)
            windowManager = wm
            view = textView
            true
        }.getOrDefault(false)
    }

    fun hide() {
        val current = view
        if (current != null) runCatching { windowManager?.removeView(current) }
        view = null
        windowManager = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
