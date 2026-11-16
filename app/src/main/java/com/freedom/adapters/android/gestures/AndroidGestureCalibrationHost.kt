package com.freedom.adapters.android.gestures

import com.freedom.gesture.api.GestureApi
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import com.freedom.accessibility.gesture.GestureCalibrationHost
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionContext

/** Adapts Android display/window information and the shared Gesture Policy Pipeline. */
internal class AndroidGestureCalibrationHost(
    private val service: AccessibilityService,
    private val gesturePolicy: GestureApi,
    private val handler: Handler
) : GestureCalibrationHost {

    override val packageName: String
        get() = service.packageName

    override val screenWidthPixels: Int
        get() = service.resources.displayMetrics.widthPixels

    override val screenHeightPixels: Int
        get() = service.resources.displayMetrics.heightPixels

    override fun activeWindowPackageName(): String? {
        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        return try {
            activeRoot.packageName?.toString().orEmpty()
        } finally {
            runCatching { activeRoot.recycle() }
        }
    }

    override fun captureGestureContext(): GestureExecutionContext? =
        gesturePolicy.captureContext()?.takeIf { it.targetWindow.packageName == packageName }

    override fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density + 0.5f).toInt()

    override fun executeGesture(
        request: GestureIntentRequest,
        listener: GestureExecutionListener
    ): GestureSubmission = gesturePolicy.execute(request, listener)

    override fun sendBroadcast(intent: Intent) {
        service.sendBroadcast(intent)
    }

    override fun postDelayed(runnable: Runnable, delayMillis: Long) {
        handler.postDelayed(runnable, delayMillis)
    }

    override fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}
