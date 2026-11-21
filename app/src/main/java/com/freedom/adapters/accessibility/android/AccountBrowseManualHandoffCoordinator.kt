package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler

/**
 * Owns the adapter-side overlay watchdog only. User touch is handled by the shared recovery
 * scheduler; this collaborator deliberately has no manual-resume timer or retry ladder.
 */
internal class AccountBrowseManualHandoffCoordinator(
    private val scheduler: TaskScheduler,
    private val isOverlayPassthroughActive: () -> Boolean,
    private val isWaitingForScrollSettle: () -> Boolean,
    private val restoreOverlayTouchPassthrough: () -> Unit,
    private val pauseAfterWatchdog: (String) -> Unit
) {
    private var overlayPassthroughWatchdogTask: ScheduledTask? = null

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
        cancelOverlayPassthroughWatchdog()
    }
}
