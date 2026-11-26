package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler

/**
 * Coordinates persisted-session restoration after Android reconnects the AccessibilityService.
 * Platform operations are supplied by the Service composition root so this coordinator can be
 * regression-tested without constructing an Android Service or changing either mode runtime.
 */
internal class AndroidAccessibilityServiceRecoveryCoordinator(
    private val host: Host,
    private val scheduler: TaskScheduler
) {
    interface Host {
        fun isRunRequested(): Boolean
        fun isBootRecoveryPending(): Boolean
        fun clearBootRecoveryPending()
        fun appendLog(message: String)
        fun clearActivityContext()
        fun connectRuntime()
        fun canDrawOverlays(): Boolean
        fun isOverlayEnabled(): Boolean
        fun isFloatingLogEnabled(): Boolean
        fun showOverlay()
        fun isKeepAliveEnabled(): Boolean
        fun startKeepAlive()
        fun refresh()
    }

    private var pendingKeepAliveStart: ScheduledTask? = null

    /** Mirrors the established onServiceConnected restore order and failure behaviour. */
    fun onServiceConnected() {
        cancelPendingKeepAliveStart()

        val bootRecoveryPending = host.isBootRecoveryPending()

        host.clearActivityContext()
        host.connectRuntime()
        if (host.isRunRequested()) host.appendLog("服务重连：重新观察当前页面")
        if (bootRecoveryPending) completeBootRecovery()

        // The floating launcher is an independent user feature. It must recover whenever the
        // AccessibilityService reconnects, even when no AccountBrowse/AI monitoring run is active.
        // Its owner decides whether the host Activity is foreground and whether the window is
        // currently visible; this coordinator only requests an idempotent ensure operation.
        if (host.isOverlayEnabled() && host.isFloatingLogEnabled() && host.canDrawOverlays()) {
            host.showOverlay()
        }

        if (host.isRunRequested()) {
            // Keep-alive belongs to the monitoring session, not to the optional floating launcher.
            if (host.isKeepAliveEnabled()) scheduleKeepAliveStart()
        }

        host.refresh()
    }

    /** Handles the boot signal when the system had already connected this service. */
    fun onBootRecoverySignal() {
        if (!host.isBootRecoveryPending()) return
        completeBootRecovery()
    }

    /** Cancels only this coordinator's delayed recovery action when the Service is destroyed. */
    fun onServiceDestroyed() {
        cancelPendingKeepAliveStart()
    }

    private fun scheduleKeepAliveStart() {
        cancelPendingKeepAliveStart()
        pendingKeepAliveStart = scheduler.schedule(KEEP_ALIVE_START_DELAY_MS) {
            pendingKeepAliveStart = null
            if (host.isRunRequested() && host.isKeepAliveEnabled()) {
                host.startKeepAlive()
            }
        }
    }

    private fun cancelPendingKeepAliveStart() {
        pendingKeepAliveStart?.cancel()
        pendingKeepAliveStart = null
    }

    private fun completeBootRecovery() {
        if (!host.isRunRequested()) {
            host.appendLog("开机恢复[SKIPPED]：系统连接无障碍服务前，用户运行请求已清除；不恢复旧业务")
        } else {
            host.appendLog(
                "开机恢复[RECONCILED]：系统无障碍服务已连接并完成持久化运行意图协调；" +
                    "mode=PAGE_OBSERVATION；未通过开机广播强制启动服务"
            )
        }
        host.clearBootRecoveryPending()
    }

    companion object {
        const val KEEP_ALIVE_START_DELAY_MS = 500L
    }
}
