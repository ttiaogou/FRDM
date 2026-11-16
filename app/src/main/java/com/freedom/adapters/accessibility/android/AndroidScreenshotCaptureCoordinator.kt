package com.freedom.adapters.accessibility.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import java.util.concurrent.Executor

/** Owns screenshot throttling, overlay visibility leases and callback completion for OCR capture. */
internal class AndroidScreenshotCaptureCoordinator(
    private val service: AccessibilityService,
    private val targetPackage: () -> String,
    private val activityClass: () -> String,
    private val mainHandler: Handler
) : AndroidScreenshotCapture {
    private val screenshotThrottle = ScreenshotCaptureThrottle(
        minimumIntervalMillis = ANDROID_SCREENSHOT_MIN_INTERVAL_MS,
        nowMillis = SystemClock::elapsedRealtime
    )

    override fun captureActiveDisplay(
        expectedPackage: String,
        expectedActivity: String,
        callback: (AndroidScreenshotFrame?) -> Unit
    ) = captureActiveDisplayAttempt(expectedPackage, expectedActivity, callback, retryAttempt = 0)

    private fun captureActiveDisplayAttempt(
        expectedPackage: String,
        expectedActivity: String,
        callback: (AndroidScreenshotFrame?) -> Unit,
        retryAttempt: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null)
            return
        }
        if (targetPackage() != expectedPackage || activityClass() != expectedActivity) {
            callback(null)
            return
        }
        if (!screenshotThrottle.tryAcquire()) {
            if (retryAttempt >= MAX_SCREENSHOT_THROTTLE_RETRIES) {
                callback(null)
                return
            }
            mainHandler.postDelayed(
                {
                    captureActiveDisplayAttempt(
                        expectedPackage,
                        expectedActivity,
                        callback,
                        retryAttempt + 1
                    )
                },
                screenshotThrottle.retryDelayMillis()
            )
            return
        }

        // The overlay is hidden before the screenshot request is issued and remains hidden
        // until the OCR consumer closes the frame after recognition/sample handling.
        MonitorOverlay.acquireObservationVisibility(service) { visibilityLease ->
            if (targetPackage() != expectedPackage || activityClass() != expectedActivity) {
                screenshotThrottle.complete()
                visibilityLease.close()
                callback(null)
                return@acquireObservationVisibility
            }
            captureScreenshotWithLease(
                expectedPackage = expectedPackage,
                expectedActivity = expectedActivity,
                visibilityLease = visibilityLease,
                callback = callback
            )
        }
    }

    private fun captureScreenshotWithLease(
        expectedPackage: String,
        expectedActivity: String,
        visibilityLease: ObservationVisibilityLease,
        callback: (AndroidScreenshotFrame?) -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                captureScreenshotWithLease(
                    expectedPackage = expectedPackage,
                    expectedActivity = expectedActivity,
                    visibilityLease = visibilityLease,
                    callback = callback
                )
            }
            return
        }
        // Diagnostic node rectangles must never be included in OCR/screenshot evidence.
        NodeBoundsHighlightOverlay.clear()
        val completionLock = Any()
        var completed = false
        var screenshotScope = AndroidScreenshotScope.DISPLAY
        lateinit var timeoutRunnable: Runnable
        val complete: (Bitmap?) -> Unit = { result ->
            val firstCompletion = synchronized(completionLock) {
                if (completed) false else {
                    completed = true
                    true
                }
            }
            if (firstCompletion) {
                mainHandler.removeCallbacks(timeoutRunnable)
                screenshotThrottle.complete()
                val frame = result?.let {
                    AndroidScreenshotFrame(
                        bitmap = it,
                        scope = screenshotScope,
                        monitorOverlayHidden = MonitorOverlay.isObservationHidden(),
                        visibilityLease = visibilityLease
                    )
                }
                if (frame == null) visibilityLease.close()
                try {
                    callback(frame)
                } catch (_: Exception) {
                    runCatching { result?.recycle() }
                    frame?.close()
                }
            } else {
                runCatching { result?.recycle() }
            }
        }
        timeoutRunnable = Runnable { complete(null) }
        mainHandler.postDelayed(timeoutRunnable, SCREENSHOT_CALLBACK_TIMEOUT_MS)
        val callbackExecutor = Executor { command -> mainHandler.post(command) }
        val screenshotCallback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                val hardwareBuffer = screenshot.hardwareBuffer
                if (targetPackage() != expectedPackage || activityClass() != expectedActivity) {
                    runCatching { hardwareBuffer.close() }
                    complete(null)
                    return
                }
                val bitmap = runCatching {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    try {
                        hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        runCatching { hardwareBitmap?.recycle() }
                    }
                }.getOrNull()
                runCatching { hardwareBuffer.close() }
                complete(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                complete(null)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val root = service.rootInActiveWindow
                val windowId = try {
                    if (safeString { root?.packageName?.toString().orEmpty() } == expectedPackage) {
                        root?.windowId ?: -1
                    } else {
                        -1
                    }
                } finally {
                    recycle(root)
                }
                if (windowId >= 0) {
                    screenshotScope = AndroidScreenshotScope.TARGET_WINDOW
                    service.takeScreenshotOfWindow(windowId, callbackExecutor, screenshotCallback)
                } else {
                    screenshotScope = AndroidScreenshotScope.DISPLAY
                    service.takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, screenshotCallback)
                }
            } else {
                screenshotScope = AndroidScreenshotScope.DISPLAY
                service.takeScreenshot(Display.DEFAULT_DISPLAY, callbackExecutor, screenshotCallback)
            }
        }.onFailure {
            complete(null)
        }
    }

    private inline fun safeString(block: () -> String): String = runCatching(block).getOrDefault("")

    private fun recycle(node: android.view.accessibility.AccessibilityNodeInfo?) {
        if (node != null) runCatching { node.recycle() }
    }

    private companion object {
        const val ANDROID_SCREENSHOT_MIN_INTERVAL_MS = 1_100L
        const val SCREENSHOT_CALLBACK_TIMEOUT_MS = 8_000L
        const val MAX_SCREENSHOT_THROTTLE_RETRIES = 12
    }
}

internal enum class AndroidScreenshotScope {
    TARGET_WINDOW,
    DISPLAY
}

/** Screenshot data is caller-owned. The visibility lease remains active until the consumer closes it. */
internal class AndroidScreenshotFrame(
    val bitmap: Bitmap,
    val scope: AndroidScreenshotScope,
    val monitorOverlayHidden: Boolean,
    private val visibilityLease: ObservationVisibilityLease
) : java.io.Closeable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) visibilityLease.close()
    }
}

/** Screenshot access remains in the Android adapter boundary. */
internal interface AndroidScreenshotCapture {
    fun captureActiveDisplay(
        expectedPackage: String,
        expectedActivity: String,
        callback: (AndroidScreenshotFrame?) -> Unit
    )
}
