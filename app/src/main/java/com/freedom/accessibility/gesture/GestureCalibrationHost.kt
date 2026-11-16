package com.freedom.accessibility.gesture

import android.content.Intent
import android.os.Handler
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionContext

/**
 * Narrow host contract for the device-only calibration flow.
 * Screen movement is delegated to the same Gesture Policy Pipeline used by other features.
 */
internal interface GestureCalibrationHost {
    val packageName: String
    val screenWidthPixels: Int
    val screenHeightPixels: Int

    fun activeWindowPackageName(): String?
    fun captureGestureContext(): GestureExecutionContext?
    fun dp(value: Int): Int
    fun executeGesture(
        request: GestureIntentRequest,
        listener: GestureExecutionListener
    ): GestureSubmission
    fun sendBroadcast(intent: Intent)
    fun postDelayed(runnable: Runnable, delayMillis: Long)
    fun removeCallbacks(runnable: Runnable)
}
