package com.freedom.application.runtime

import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository
import com.freedom.core.runtime.RuntimeLogSink
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.PageRuntimeSession
import com.freedom.core.runtime.PageRuntimeSessionState
import com.freedom.core.runtime.PageRuntimeLifecycle
import com.freedom.core.runtime.TaskLease
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope

/**
 * Reconciles user run authorization with page-selected runtime lifecycle. Observations are
 * independent of task ownership; normalized business events reach at most one runtime.
 */
/** Shared by service-scoped coordinators and their process-owned task lease coordinators. */
class RunGenerationClock {
    private var generation = 0L

    @Synchronized
    fun current(): Long = generation

    @Synchronized
    fun advance(): Long {
        check(generation < Long.MAX_VALUE) { "run generation exhausted" }
        return ++generation
    }
}

class RunSessionCoordinator(
    private val state: RunStateRepository,
    private val runtimes: Map<RunMode, ModeRuntimePort>,
    private val log: RuntimeLogSink,
    private val generationClock: RunGenerationClock = RunGenerationClock(),
    /** Shared process task slot; optional only for lifecycle-only test fixtures. */
    private val taskCoordinator: TaskCoordinator? = null,
    /** Request a fresh independent observation; it never selects a runtime itself. */
    private val onObserveCurrentPage: () -> Unit = {},
    private val cancelCurrentPageObservation: () -> Unit = {},
    private val onUserRunEnded: () -> Unit = {},
    initiallyServiceConnected: Boolean = true
) {

    private var observedRunRequested: Boolean = false

    private val pageRuntimeSession = PageRuntimeSession()
    private var preparedRuntimeMode: RunMode? = null
    private var pageTransitionInProgress = false
    @Volatile private var pageServiceConnected = initiallyServiceConnected
    @Volatile private var pageServiceDestroyed = false
    @Volatile private var writeRoute: PageRuntimeSessionState? = null

    /** The final write gate reads immutable state without taking the coordinator monitor. */
    fun currentWriteRoute(): PageRuntimeSessionState? = writeRoute?.takeIf {
        pageServiceConnected && !pageServiceDestroyed && state.isRunRequested() &&
            it.runGeneration == generationClock.current()
    }

    private fun publishWriteRoute(route: PageRuntimeSessionState?) {
        val tasks = taskCoordinator
        if (tasks == null) writeRoute = route
        else tasks.withOwnershipBarrier { writeRoute = route }
    }

    fun isPageObservationAllowed(): Boolean =
        pageServiceConnected && !pageServiceDestroyed && state.isRunRequested()

    @Synchronized
    fun isRuntimeObserving(mode: RunMode): Boolean = activeMode() == mode

    fun isRuntimeReady(mode: RunMode): Boolean {
        val route = currentWriteRoute() ?: return false
        if (route.mode != mode) return false
        val lease = taskCoordinator?.currentLease()
        return taskCoordinator == null || (lease != null && lease.taskId == route.taskId &&
            lease.runGeneration == route.runGeneration && currentWriteRoute() == route)
    }

    private fun finishPageUserRun(stopped: Boolean, reason: String) {
        cancelCurrentPageObservation()
        val cleanup = runCatching { onUserRunEnded() }
        cleanup.onFailure { log.recordException("RunSessionCoordinator", "run_end_cleanup_failed", reason, it) }
        log.finishRun(if (stopped && cleanup.isSuccess) "STOPPED" else "FAILED", reason)
    }

    private fun logPageLifecycle(state: PageRuntimeSessionState, reason: String) {
        log.append("页面运行时[${state.lifecycle}]：taskId=${state.taskId}；mode=${state.mode}；" +
            "runGeneration=${state.runGeneration}；routeRevision=${state.routeRevision}；reason=$reason")
    }

    /** STOPPING is visible throughout cleanup; revocation precedes any teardown callback. */
    private fun stopPageRuntime(reason: String, preserveRecovery: Boolean,
                                cleanup: ((ModeRuntimePort) -> Unit)? = null): Boolean {
        publishWriteRoute(null)
        val previous = pageRuntimeSession.current()
        if (previous == null) {
            advanceRunGeneration()
            preparedRuntimeMode = null
            return true
        }
        val stopping = pageRuntimeSession.markStopping(previous) ?: return false
        logPageLifecycle(stopping, reason)
        preparedRuntimeMode = null
        val runtime = runtimes[previous.mode]
        if (preserveRecovery && runtime != null) {
            // The checkpoint writer needs the still-current task identity, but STOPPING
            // has already closed runtime dispatch. No UI work is authorized here.
            runCatching { runtime.onPageRuntimeWillSuspend(reason) }.onFailure {
                log.recordException("RunSessionCoordinator", "page_runtime_checkpoint_failed", reason, it)
            }
        }
        advanceRunGeneration()
        return try {
            if (runtime != null) {
                if (cleanup != null) cleanup(runtime)
                else if (preserveRecovery) runtime.onPageRuntimeSuspended(reason)
                else runtime.onRunStopped(reason)
            }
            pageRuntimeSession.clear()
            true
        } catch (error: Throwable) {
            // Failed cleanup must not start a second runtime. Keep STOPPING for safe retry.
            log.recordException("RunSessionCoordinator", "page_runtime_stop_failed", reason, error)
            false
        }
    }

    private fun tryCompletePageStart(expected: PageRuntimeSessionState): PageRuntimeSessionState? {
        val current = pageRuntimeSession.current() ?: return null
        if (!isPageObservationAllowed() || current.runGeneration != runGeneration ||
            current.routeRevision != expected.routeRevision || current.taskId != expected.taskId ||
            current.mode != expected.mode || preparedRuntimeMode != current.mode ||
            current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return null
        if (taskCoordinator != null) {
            val lease = taskCoordinator.currentLease() ?: return null
            if (lease.taskId != current.taskId || lease.runGeneration != current.runGeneration) return null
        }
        val ready = pageRuntimeSession.markReady(expected) ?: return null
        publishWriteRoute(ready)
        if (current.lifecycle != PageRuntimeLifecycle.READY) logPageLifecycle(ready, "运行时准备完成且任务租约已确认")
        return ready
    }

    /** Called only by the task controller after obtaining its existing verified capability. */
    @Synchronized
    fun confirmPageTaskOwnership(lease: TaskLease): PageRuntimeSessionState? {
        if (taskCoordinator?.isCurrent(lease) != true) return null
        val current = pageRuntimeSession.current() ?: return null
        if (current.taskId != lease.taskId || current.runGeneration != lease.runGeneration) return null
        return tryCompletePageStart(current)
    }

    /**
     * Existing AI business admission gates may explicitly transfer to their sibling task,
     * including verified source-page navigation. A normal claim cannot overwrite an
     * independently selected different route. No new capability is granted here.
     */
    @Synchronized
    fun admitBusinessPageTaskOwnership(lease: TaskLease, verifiedHandoff: Boolean): Boolean {
        if (!isPageObservationAllowed() ||
            taskCoordinator?.isCurrent(lease) != true
        ) return false
        val current = pageRuntimeSession.current() ?: return false
        if (current.runGeneration != runGeneration || lease.runGeneration != current.runGeneration ||
            current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return false
        if (current.taskId == lease.taskId) {
            tryCompletePageStart(current)
            return true
        }
        if (!verifiedHandoff || current.mode != RunMode.AI_REPLY || preparedRuntimeMode != current.mode ||
            lease.taskId !in setOf("AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS")
        ) return false
        publishWriteRoute(null)
        pageRuntimeSession.markStopping(current)?.let { logPageLifecycle(it, "已验证业务任务交接") }
        val next = pageRuntimeSession.begin(runGeneration, lease.taskId, current.mode)
        logPageLifecycle(next, "业务准入及目标租约已确认")
        return tryCompletePageStart(next) != null
    }

    @Synchronized
    fun markPageRuntimeReady(expected: PageRuntimeSessionState): PageRuntimeSessionState? {
        val current = pageRuntimeSession.current() ?: return null
        if (!isPageObservationAllowed() || current.runGeneration != runGeneration ||
            current.routeRevision != expected.routeRevision || current.taskId != expected.taskId ||
            current.mode != expected.mode || current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return null
        preparedRuntimeMode = current.mode
        return tryCompletePageStart(expected)
    }

    private fun dispatchMode(): RunMode? =
        pageRuntimeSession.current()?.takeIf {
            isPageObservationAllowed() && it.runGeneration == runGeneration &&
                it.lifecycle == PageRuntimeLifecycle.READY
        }?.mode
    /** Read from the process-owned clock so a recreated Service cannot issue lower generations. */
    private val runGeneration: Long get() = generationClock.current()

    @Synchronized
    fun currentRunGeneration(): Long = runGeneration

    private fun advanceRunGeneration() {
        // Invalidate old task/UI leases before stopping or starting business runtimes.
        // The process clock is authoritative across recreated service instances.
        val newGeneration = generationClock.advance()
        taskCoordinator?.advanceRunGeneration(newGeneration)
    }

    /** Clear persisted intent and notify only the runtime that owned the active session. */
    @Synchronized
    fun stop(reason: String) {
        log.append("运行请求准备停止：来源=RunSessionCoordinator.stop；原因=$reason")
        publishWriteRoute(null)
        state.setRunRequested(false)
        observedRunRequested = false
        pageTransitionInProgress = true
        try {
            cancelCurrentPageObservation()
            val stopped = stopPageRuntime(reason, preserveRecovery = false)
            finishPageUserRun(stopped, reason)
        } finally {
            pageTransitionInProgress = false
        }
    }

    /**
     * Applies persisted run-intent changes written by the command boundary or restored by the
     * system. Returns true only when this call performed a runtime lifecycle transition.
     */
    @Synchronized
    fun synchronizeRunRequest(): Boolean {
        val requestedRun = state.isRunRequested()
        if (pageServiceDestroyed) return false
        if (requestedRun == observedRunRequested &&
            (requestedRun || pageRuntimeSession.current()?.lifecycle != PageRuntimeLifecycle.STOPPING)
        ) return false
        observedRunRequested = requestedRun
        pageTransitionInProgress = true
        try {
            cancelCurrentPageObservation()
            val stopped = stopPageRuntime("运行请求已变化", preserveRecovery = false)
            if (requestedRun) {
                if (isPageObservationAllowed()) onObserveCurrentPage.invoke()
            } else finishPageUserRun(stopped, "运行请求状态变化")
        } finally {
            pageTransitionInProgress = false
        }
        return true
    }

    @Synchronized
    fun activeMode(): RunMode? =
        pageRuntimeSession.current()?.takeIf {
            isPageObservationAllowed() && it.lifecycle != PageRuntimeLifecycle.STOPPING &&
                it.runGeneration == runGeneration
        }?.mode

    @Synchronized
    fun onAccessibilityServiceConnected() {
        if (pageServiceDestroyed) return
        if (pageRuntimeSession.current() != null) onAccessibilityServiceDisconnected()
        pageServiceConnected = true
        val changed = synchronizeRunRequest()
        if (!changed) {
            if (isPageObservationAllowed()) onObserveCurrentPage.invoke()
            else runCatching { onUserRunEnded() }.onFailure {
                log.recordException("RunSessionCoordinator", "run_end_cleanup_failed", "服务连接时运行授权已关闭", it)
            }
        }
    }

    @Synchronized
    fun onAccessibilityServiceDisconnected() {
        // A reconnect must never reuse capabilities or callbacks issued before the service
        // boundary.  Advance before notifying the runtime so its reset and any late callback
        // observe the new generation as stale.
        if (!pageServiceConnected || pageServiceDestroyed) return
        publishWriteRoute(null)
        pageServiceConnected = false
        cancelCurrentPageObservation()
        pageTransitionInProgress = true
        try {
            stopPageRuntime("SERVICE_DISCONNECTED", preserveRecovery = true) {
                it.onAccessibilityServiceDisconnected()
            }
        } finally {
            pageTransitionInProgress = false
        }
    }

    @Synchronized
    fun routePageTask(mode: RunMode?, taskId: String? = null): PageRuntimeSessionState? {
        val currentPage = pageRuntimeSession.current()
        if (!isPageObservationAllowed() || !observedRunRequested) return null
        if (pageTransitionInProgress) return currentPage
        if (currentPage != null && currentPage.mode == mode && currentPage.taskId == taskId &&
            currentPage.lifecycle != PageRuntimeLifecycle.STOPPING
        ) {
            if (tryCompletePageStart(currentPage) == null) {
                val runtime = runtimes[currentPage.mode]
                if (preparedRuntimeMode != currentPage.mode) {
                    // Only a freshly selected matching page may retry preparation. Do not
                    // reset a prepared runtime that is merely waiting for its business lease.
                    pageTransitionInProgress = true
                    try {
                        runtime?.onPageRuntimePreparationRetry(currentPage) { prepared ->
                            markPageRuntimeReady(prepared)
                        }
                    } catch (error: Throwable) {
                        log.recordException("RunSessionCoordinator", "page_runtime_prepare_retry_failed",
                            "页面运行时重新准备失败；继续只读等待", error)
                        stopPageRuntime("页面运行时重新准备失败", preserveRecovery = true)
                    } finally {
                        pageTransitionInProgress = false
                    }
                } else {
                    runtime?.onPageTaskChanged(currentPage)
                }
            }
            return pageRuntimeSession.current()
        }
        pageTransitionInProgress = true
        try {
            if (mode == null || taskId == null) {
                if (currentPage != null) stopPageRuntime("页面路由未确认", preserveRecovery = true)
                return null
            }
            // Private messages and greetings share one runtime and a verified atomic
            // business handoff. Do not reset it, invent a lease or bypass its page checks.
            if (currentPage != null && currentPage.mode == mode &&
                currentPage.lifecycle != PageRuntimeLifecycle.STOPPING &&
                preparedRuntimeMode == mode
            ) {
                publishWriteRoute(null)
                pageRuntimeSession.markStopping(currentPage)?.let { logPageLifecycle(it, "同运行时任务切换") }
                val next = pageRuntimeSession.begin(runGeneration, taskId, mode)
                logPageLifecycle(next, "等待目标任务验证及租约交接")
                if (tryCompletePageStart(next) == null) runtimes[mode]?.onPageTaskChanged(next)
                return pageRuntimeSession.current()
            }
            if (currentPage != null &&
                !stopPageRuntime("页面任务路由切换", preserveRecovery = true)
            ) return pageRuntimeSession.current()
            val runtime = runtimes[mode] ?: return null
            val next = pageRuntimeSession.begin(runGeneration, taskId, mode)
            logPageLifecycle(next, "页面识别选中任务")
            try {
                runtime.onPageRuntimeStarted(next) { prepared -> markPageRuntimeReady(prepared) }
            } catch (error: Throwable) {
                log.recordException("RunSessionCoordinator", "page_runtime_start_failed",
                    "页面运行时启动失败；保持只读等待", error)
                stopPageRuntime("页面运行时启动失败", preserveRecovery = true)
            }
            return pageRuntimeSession.current()
        } finally {
            pageTransitionInProgress = false
        }
    }

    @Synchronized
    fun currentPageRuntimeSession(): PageRuntimeSessionState? = pageRuntimeSession.current()

    @Synchronized
    fun dispatchTargetApplicationEvent(event: AccessibilityEventEnvelope) {
        synchronizeRunRequest()
        val pageSession = pageRuntimeSession.current()
        if (isPageObservationAllowed() &&
            pageSession?.lifecycle == PageRuntimeLifecycle.STARTING &&
            pageSession.mode == RunMode.AI_REPLY && preparedRuntimeMode == RunMode.AI_REPLY &&
            pageSession.runGeneration == runGeneration
        ) {
            // The AI observer must receive page signals to finish its existing verification.
            // The task controller still owns all business admission and lease checks.
            runtimes[RunMode.AI_REPLY]?.onTargetApplicationEvent(event)
            return
        }
        if (pageSession != null && pageSession.lifecycle != PageRuntimeLifecycle.READY) return
        dispatchMode()?.let { runtimes[it]?.onTargetApplicationEvent(event) }
    }

    @Synchronized
    fun dispatchGlobalTouchEvent(event: AccessibilityEventEnvelope) {
        synchronizeRunRequest()
        val mode = dispatchMode()
        log.append("全局触摸链路[RUN_SESSION_RECEIVED]：事件=${event.type}；eventTime=${event.eventTimeMillis}；模式=${mode ?: "NONE"}")
        val runtime = mode?.let(runtimes::get)
        if (runtime == null) {
            log.append("全局触摸链路[RUN_SESSION_DROPPED]：没有活动运行时；事件=${event.type}")
            return
        }
        runCatching { runtime.onGlobalTouchEvent(event) }
            .onSuccess { log.append("全局触摸链路[RUN_SESSION_FORWARDED]：事件=${event.type}；模式=$mode") }
            .onFailure {
                log.recordException(
                    component = "RunSessionCoordinator",
                    eventType = "global_touch_dispatch_failed",
                    message = "全局触摸事件分发失败；event=${event.type}",
                    error = it
                )
            }
    }

    @Synchronized
    fun dispatchActivityTransition(activityClass: String) {
        dispatchMode()?.let { runtimes[it]?.onActivityTransition(activityClass) }
    }

    @Synchronized
    fun onExternalRefresh() {
        // A new session has already received onRunStarted() while synchronizing. Do not run the
        // idle-refresh hook again for the same broadcast; later refreshes still reach the runtime.
        if (synchronizeRunRequest()) return
        if (isPageObservationAllowed() && dispatchMode() == null) {
            onObserveCurrentPage.invoke()
            return
        }
        dispatchMode()?.let { runtimes[it]?.onExternalRefresh() }
    }

    @Synchronized
    fun isTrackedInteractionEvent(event: AccessibilityEventEnvelope): Boolean =
        dispatchMode()?.let { runtimes[it]?.isTrackedInteractionEvent(event) } ?: false

    @Synchronized
    fun interactionDiagnostics(event: AccessibilityEventEnvelope, nowMillis: Long): List<String> =
        dispatchMode()?.let { runtimes[it]?.interactionDiagnostics(event, nowMillis) }.orEmpty()

    @Synchronized
    fun handleManualInteractionFallback(event: AccessibilityEventEnvelope) {
        dispatchMode()?.let { runtimes[it]?.handleManualInteractionFallback(event) }
    }

    /** Target-package change is one boundary, not a reset of each historical mode. */
    @Synchronized
    fun onTargetPackageChanged(reason: String) {
        if (pageServiceDestroyed) return
        cancelCurrentPageObservation()
        pageTransitionInProgress = true
        try {
            stopPageRuntime(reason, preserveRecovery = true)
        } finally {
            pageTransitionInProgress = false
        }
        if (isPageObservationAllowed()) onObserveCurrentPage.invoke()
    }

    @Synchronized
    fun onAccessibilityServiceInterrupted(reason: String) {
        if (pageServiceDestroyed || !pageServiceConnected) return
        cancelCurrentPageObservation()
        pageTransitionInProgress = true
        try {
            stopPageRuntime(reason, preserveRecovery = true) { it.onAccessibilityServiceInterrupted(reason) }
        } finally {
            pageTransitionInProgress = false
        }
        if (isPageObservationAllowed()) onObserveCurrentPage.invoke()
    }

    @Synchronized
    fun onServiceDestroyed() {
        if (pageServiceDestroyed) return
        publishWriteRoute(null)
        pageServiceDestroyed = true
        pageServiceConnected = false
        cancelCurrentPageObservation()
        pageTransitionInProgress = true
        try {
            val requested = state.isRunRequested()
            val stopped = stopPageRuntime("SERVICE_DESTROYED", preserveRecovery = requested) {
                if (requested) it.onServiceDestroyed() else it.onRunStopped("用户运行授权已关闭")
            }
            if (!requested) finishPageUserRun(stopped, "SERVICE_DESTROYED")
        } finally {
            pageTransitionInProgress = false
        }
    }

}
