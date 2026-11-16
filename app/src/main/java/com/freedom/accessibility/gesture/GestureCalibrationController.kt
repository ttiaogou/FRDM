package com.freedom.accessibility.gesture

import com.freedom.application.ports.GestureCalibrationContract

import com.freedom.adapters.android.gestures.GestureTuning

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureExecutionStatus

/**
 * Owns the device-only gesture calibration session.
 *
 * The controller submits test gestures only after verifying that Freedom's own
 * calibration screen is the active window. All movement is executed by the shared,
 * coordinate-based Gesture Policy Pipeline and therefore uses the same visible feedback.
 */
internal class GestureCalibrationController(
    private val host: GestureCalibrationHost,
    private val isBrowseGestureBusy: () -> Boolean,
    private val onLog: (String) -> Unit
) {
    private var inProgress = false
    private var trialIndex = 0
    private var bounds: Rect? = null

    private val calibrationRunnable = Runnable {
        dispatchNextTrial()
    }

    /**
     * The service registers this receiver when it connects and unregisters it
     * during destruction, preserving the original receiver lifecycle.
     */
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GestureCalibrationContract.ACTION_START_CALIBRATION -> start(intent)
                GestureCalibrationContract.ACTION_CANCEL_CALIBRATION -> {
                    cancel("用户关闭了校准页面")
                }
            }
        }
    }

    fun onServiceDestroyed() {
        inProgress = false
        bounds = null
        host.removeCallbacks(calibrationRunnable)
    }

    private fun start(intent: Intent) {
        if (inProgress) {
            sendFailure("已有一组设备采样正在进行。")
            return
        }
        if (isBrowseGestureBusy()) {
            sendFailure("当前自动浏览手势尚未结束，请停止当前操作后再采样。")
            return
        }

        val activePackage = host.activeWindowPackageName()
        if (activePackage == null) {
            sendFailure("无法读取 Freedom 校准页面；请确认校准窗口仍在前台。")
            return
        }
        if (activePackage != host.packageName) {
            sendFailure(
                "当前前台不是 Freedom 校准页面。采样不会在陌陌或其他应用页面执行。"
            )
            return
        }

        val left = intent.getIntExtra(GestureCalibrationContract.EXTRA_LEFT, Int.MIN_VALUE)
        val top = intent.getIntExtra(GestureCalibrationContract.EXTRA_TOP, Int.MIN_VALUE)
        val width = intent.getIntExtra(GestureCalibrationContract.EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(GestureCalibrationContract.EXTRA_HEIGHT, 0)
        if (left == Int.MIN_VALUE || top == Int.MIN_VALUE ||
            width < host.dp(100) || height < host.dp(100) ||
            left < 0 || top < 0 ||
            left + width > host.screenWidthPixels ||
            top + height > host.screenHeightPixels
        ) {
            sendFailure("校准区域坐标无效或超出屏幕范围，请重新打开采样页面。")
            return
        }

        bounds = Rect(left, top, left + width, top + height)
        trialIndex = 0
        inProgress = true
        onLog(
            "开始设备手势自动采样：仅在 Freedom 内部校准区域执行 " +
                "${GestureCalibrationContract.CALIBRATION_TRIAL_COUNT} 组测试手势"
        )
        host.removeCallbacks(calibrationRunnable)
        host.postDelayed(calibrationRunnable, 350L)
    }

    private fun dispatchNextTrial() {
        if (!inProgress) return
        val currentBounds = bounds
        if (currentBounds == null) {
            fail("校准区域信息已失效。")
            return
        }

        val activePackage = host.activeWindowPackageName()
        if (activePackage == null) {
            fail("校准期间无法读取 Freedom 页面；测试已停止。")
            return
        }
        if (activePackage != host.packageName) {
            fail("校准页面已离开 Freedom；为避免触碰其他应用，测试已立即停止。")
            return
        }

        val executionContext = host.captureGestureContext()?.takeIf {
            it.targetWindow.packageName == host.packageName
        } ?: run {
            fail("校准手势对应的运行代次、窗口或显示坐标上下文已变化；测试已停止。")
            return
        }

        val trialNumber = trialIndex + 1
        val request = GestureIntentRequest(
            id = "gesture-calibration-${trialNumber}-${System.nanoTime()}",
            intent = GestureIntent.CalibrationSwipe(
                region = ScreenRect(
                    currentBounds.left,
                    currentBounds.top,
                    currentBounds.right,
                    currentBounds.bottom
                ),
                durationMs = GestureTuning.CALIBRATION_GESTURE_DURATION_MS
            ),
            description = "Freedom 内部手势校准",
            executionContext = executionContext
        )
        val submission = runCatching {
            host.executeGesture(request) { result ->
                if (!inProgress) return@executeGesture
                when (result.status) {
                    GestureExecutionStatus.COMPLETED -> {
                        sendProgress(trialNumber)
                        trialIndex++
                        if (trialIndex >= GestureCalibrationContract.CALIBRATION_TRIAL_COUNT) {
                            host.postDelayed({ finish() }, 250L)
                        } else {
                            host.postDelayed(
                                calibrationRunnable,
                                GestureTuning.CALIBRATION_TRIAL_GAP_MS
                            )
                        }
                    }
                    GestureExecutionStatus.CANCELLED ->
                        fail("Android 系统中断了第 ${trialNumber} 组内部测试手势。")
                    GestureExecutionStatus.REJECTED ->
                        fail("第 ${trialNumber} 组校准手势被安全策略拒绝：${result.reason}")
                    GestureExecutionStatus.FAILED ->
                        fail("第 ${trialNumber} 组校准手势执行失败：${result.reason}")
                    GestureExecutionStatus.DEFERRED ->
                        fail("第 ${trialNumber} 组校准手势因目标应用暂不可用而未派发：${result.reason}")
                }
            }
        }.getOrDefault(GestureSubmission.REJECTED)

        if (submission != GestureSubmission.ACCEPTED && inProgress) {
            fail("统一手势执行器未接受第 ${trialNumber} 组内部测试手势。")
        }
    }

    private fun sendProgress(trialNumber: Int) {
        host.sendBroadcast(
            Intent(GestureCalibrationContract.ACTION_CALIBRATION_TRIAL_COMPLETED)
                .setPackage(host.packageName)
                .putExtra(GestureCalibrationContract.EXTRA_TRIAL_INDEX, trialNumber)
        )
    }

    private fun finish() {
        if (!inProgress) return
        inProgress = false
        bounds = null
        onLog("Freedom 内部手势采样完成；正在由主界面分析收到的 MotionEvent 时间戳")
        host.sendBroadcast(
            Intent(GestureCalibrationContract.ACTION_CALIBRATION_FINISHED)
                .setPackage(host.packageName)
                .putExtra(GestureCalibrationContract.EXTRA_SUCCESS, true)
        )
    }

    private fun fail(reason: String) {
        if (!inProgress) {
            sendFailure(reason)
            return
        }
        inProgress = false
        bounds = null
        host.removeCallbacks(calibrationRunnable)
        onLog("设备手势自动采样中止：$reason")
        sendFailure(reason)
    }

    private fun cancel(reason: String) {
        if (!inProgress) return
        inProgress = false
        bounds = null
        host.removeCallbacks(calibrationRunnable)
        onLog("设备手势自动采样已取消：$reason")
    }

    private fun sendFailure(reason: String) {
        host.sendBroadcast(
            Intent(GestureCalibrationContract.ACTION_CALIBRATION_FAILED)
                .setPackage(host.packageName)
                .putExtra(GestureCalibrationContract.EXTRA_MESSAGE, reason)
        )
    }
}
