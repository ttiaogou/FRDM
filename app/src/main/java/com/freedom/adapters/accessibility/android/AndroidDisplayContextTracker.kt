package com.freedom.adapters.accessibility.android

import android.accessibilityservice.AccessibilityService
import android.view.Display
import android.view.WindowManager
import com.freedom.core.accessibility.api.DisplayMetricsSnapshot

/** Detects display-coordinate changes and asks the Gateway to invalidate old window generations. */
internal class AndroidDisplayContextTracker(
    private val service: AccessibilityService,
    private val onDisplayContextChanged: () -> Unit
) {
    private val lock = Any()
    private var lastSnapshot: DisplayMetricsSnapshot? = null

    fun snapshot(): DisplayMetricsSnapshot {
        val metrics = service.resources.displayMetrics
        val display = runCatching {
            service.getSystemService(WindowManager::class.java)?.defaultDisplay
        }.getOrNull()
        val snapshot = DisplayMetricsSnapshot(
            widthPixels = metrics.widthPixels,
            heightPixels = metrics.heightPixels,
            density = metrics.density,
            displayId = runCatching { display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY,
            rotationDegrees = ((runCatching { display?.rotation }.getOrNull() ?: 0) * 90)
        )
        synchronized(lock) {
            val previous = lastSnapshot
            if (previous != null && previous != snapshot) onDisplayContextChanged()
            lastSnapshot = snapshot
        }
        return snapshot
    }
}
