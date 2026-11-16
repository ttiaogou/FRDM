package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TieredManualHandoffQuietPeriod

/**
 * Owns only adapter-side manual-handoff timers and quiet-period progression.
 * Feature session data remains in AccountBrowseRuntime; this collaborator receives read/action
 * callbacks and never stores or mutates AccountBrowseSessionStatePort.
 */
internal class AccountBrowseManualHandoffCoordinator(
    private val scheduler: TaskScheduler,
    private val onResumeManualTouch: (quietPeriodMs: Long, quietPeriodStage: Int) -> Unit,
    private val isOverlayPassthroughActive: () -> Boolean,
    private val isWaitingForScrollSettle: () -> Boolean,
    private val restoreOverlayTouchPassthrough: () -> Unit,
    private val pauseAfterWatchdog: (String) -> Unit
) {
    val quietPeriod = TieredManualHandoffQuietPeriod()

    private var manualTouchResumeTask: ScheduledTask? = null
    private var overlayPassthroughWatchdogTask: ScheduledTask? = null

    fun cancelManualTouchResume() {
        manualTouchResumeTask?.cancel()
        manualTouchResumeTask = null
    }

    fun scheduleManualTouchResume(delayMs: Long = quietPeriod.currentDelayMs) {
        cancelManualTouchResume()
        manualTouchResumeTask = scheduler.schedule(delayMs) {
            manualTouchResumeTask = null
            onResumeManualTouch(quietPeriod.currentDelayMs, quietPeriod.stageNumber)
        }
    }

    fun scheduleOverlayPassthroughWatchdog(delayMs: Long) {
        overlayPassthroughWatchdogTask?.cancel()
        overlayPassthroughWatchdogTask = scheduler.schedule(delayMs) {
            overlayPassthroughWatchdogTask = null
            if (isOverlayPassthroughActive()) {
                restoreOverlayTouchPassthrough()
                if (isWaitingForScrollSettle()) {
                    pauseAfterWatchdog("滑动回调超时；已恢复浮动按钮触摸状态")
                }
            }
        }
    }

    fun cancelOverlayPassthroughWatchdog() {
        overlayPassthroughWatchdogTask?.cancel()
        overlayPassthroughWatchdogTask = null
    }

    fun cleanup() {
        cancelManualTouchResume()
        cancelOverlayPassthroughWatchdog()
    }
}
