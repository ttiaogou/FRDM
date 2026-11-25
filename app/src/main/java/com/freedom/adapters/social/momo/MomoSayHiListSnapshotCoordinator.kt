package com.freedom.adapters.social.momo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Owns delayed fresh-snapshot recovery for the received-greetings list.
 *
 * 本协调器只管理延迟调度与恢复门禁，不缓存无障碍根节点或行定位器；每次执行都要求 Adapter 重新读取快照。
 * Every scheduled pass reacquires a fresh snapshot after navigation, gesture, and settle guards pass.
 */
internal interface MomoSayHiSnapshotTaskScheduler {
    fun postDelayed(task: Runnable, delayMs: Long)
    fun remove(task: Runnable)
}

/** Android main-thread scheduler adapter; the coordinator itself accepts a deterministic scheduler. */
internal class HandlerMomoSayHiSnapshotTaskScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : MomoSayHiSnapshotTaskScheduler {
    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }

    override fun remove(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

internal class MomoSayHiListSnapshotCoordinator(
    private val recoveryPolicy: MomoSayHiListSnapshotRecoveryPolicy,
    private val isNavigationRelevant: () -> Boolean,
    private val isGestureBusy: () -> Boolean,
    private val beforeFreshInspection: () -> Unit,
    private val inspectFreshSnapshot: () -> Unit,
    private val onLog: (String) -> Unit,
    private val taskScheduler: MomoSayHiSnapshotTaskScheduler = HandlerMomoSayHiSnapshotTaskScheduler(),
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) {
    @Volatile private var inspectionTask: Runnable? = null
    @Volatile private var retryPending = false

    /** The fallback bit selects a fresh full Momo window scan instead of the retained list subtree. */
    @Volatile
    var forceFullListSnapshot: Boolean = false
        private set

    val isPending: Boolean get() = retryPending || inspectionTask != null

    fun requestFullListFallback() {
        forceFullListSnapshot = true
    }

    fun markListSnapshotComplete() {
        forceFullListSnapshot = false
    }

    fun reset() {
        inspectionTask?.let(taskScheduler::remove)
        inspectionTask = null
        retryPending = false
        forceFullListSnapshot = false
        recoveryPolicy.reset()
    }

    fun scheduleInspection(delayMs: Long, reason: String, retry: Boolean) {
        if (!isNavigationRelevant()) return
        if (inspectionTask != null) return
        if (retry) retryPending = true
        lateinit var task: Runnable
        task = Runnable {
            if (inspectionTask !== task) return@Runnable
            inspectionTask = null
            if (retry) retryPending = false
            if (!isNavigationRelevant()) return@Runnable
            if (isGestureBusy() || recoveryPolicy.isGestureInFlight()) {
                scheduleInspection(
                    delayMs = 250L,
                    reason = "GESTURE_STILL_IN_FLIGHT",
                    retry = retry
                )
                return@Runnable
            }
            val settleRemainingMs = recoveryPolicy.remainingSettleMs(elapsedRealtimeMs())
            if (settleRemainingMs > 0L) {
                scheduleInspection(
                    delayMs = settleRemainingMs,
                    reason = "POST_GESTURE_SETTLE_WINDOW",
                    retry = retry
                )
                return@Runnable
            }
            // Every scheduled pass reacquires a fresh root. Event-owned node data is never reused.
            beforeFreshInspection()
            onLog("收到招呼列表快照恢复：执行新鲜只读快照复核；来源=$reason；延迟=${delayMs}ms")
            inspectFreshSnapshot()
        }
        inspectionTask = task
        taskScheduler.postDelayed(task, delayMs)
    }

    fun handleIncompleteSnapshot(
        windowKey: String,
        stopReason: String,
        visitedNodes: Int,
        targetedSubtree: Boolean
    ) {
        val failedScopeLabel = when {
            targetedSubtree -> "LISTVIEW_SUBTREE"
            forceFullListSnapshot -> "ACTIVE_WINDOW_FALLBACK"
            else -> "ACTIVE_WINDOW"
        }
        val decision = recoveryPolicy.onSnapshotIncomplete(windowKey)
        if (!targetedSubtree && stopReason == "ACTIVE_WINDOW_ROOT_UNAVAILABLE") {
            // Switch the next attempt to all visible Momo windows rather than repeating
            // the same active/focused root lookup.
            forceFullListSnapshot = true
        }
        if (targetedSubtree && decision.attempt >= 2) {
            // After two targeted failures, reacquire a full Activity root for fresh
            // diagnostics and locator refresh instead of retaining a stale list path.
            forceFullListSnapshot = true
        }
        onLog(
            "收到招呼列表快照[RETRY]：stopReason=$stopReason；visited=$visitedNodes；" +
                "scope=$failedScopeLabel；" +
                "attempt=${decision.attempt}/${decision.maximumAttempts}；" +
                "nextScope=${if (forceFullListSnapshot) "ACTIVE_WINDOW_FALLBACK" else "SAME_SCOPE"}；" +
                "delay=${decision.delayMs}ms；等待新快照，不派发滚动手势"
        )
        scheduleInspection(
            delayMs = decision.delayMs,
            reason = stopReason,
            retry = true
        )
    }
}
