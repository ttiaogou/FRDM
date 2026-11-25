package com.freedom.application.runtime

import com.freedom.core.runtime.ModeRuntimePort
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.RunStateRepository
import com.freedom.core.runtime.RuntimeLogSink
import com.freedom.core.runtime.TaskCoordinator
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

    // Page selection/readiness is evidence, not an independently granted UI lease.
    @Volatile private var pageRuntime: PageRuntimeSessionState? = null
    private var nextRouteRevision = 0L
    private var runtimePrepared = false
    private var pageTransitionInProgress = false
    private var pendingCleanup: (() -> Unit)? = null
    @Volatile private var pageServiceConnected = initiallyServiceConnected
    @Volatile private var pageServiceDestroyed = false
    // The final write gate derives its route from the single runtime snapshot and
    // the exact task capability. No independently published READY copy exists.
    fun currentWriteRoute(): PageRuntimeSessionState? {
        val route = pageRuntime ?: return null
        if (route.lifecycle != PageRuntimeLifecycle.READY || !isPageObservationAllowed() ||
            route.runGeneration != generationClock.current()
        ) return null
        val tasks = taskCoordinator
        if (tasks != null) {
            val lease = tasks.currentLease() ?: return null
            if (lease.taskId != route.taskId || lease.runGeneration != route.runGeneration) return null
        }
        return route.takeIf { pageRuntime == it }
    }

    private fun <T> withTaskBarrier(action: () -> T): T {
        val tasks = taskCoordinator
        return if (tasks == null) action() else tasks.withOwnershipBarrier(action)
    }

    private fun publishPageRuntime(route: PageRuntimeSessionState?) =
        withTaskBarrier { pageRuntime = route }

    fun isPageObservationAllowed(): Boolean =
        pageServiceConnected && !pageServiceDestroyed && state.isRunRequested()

    @Synchronized
    fun isRuntimeObserving(mode: RunMode): Boolean = activeMode() == mode

    fun isRuntimeReady(mode: RunMode): Boolean = currentWriteRoute()?.mode == mode

    private fun finishPageUserRun(stopped: Boolean, reason: String) {
        cancelCurrentPageObservation()
        val cleanup = runCatching { onUserRunEnded() }
        cleanup.onFailure { log.recordException("RunSessionCoordinator", "run_end_cleanup_failed", reason, it) }
        log.finishRun(if (stopped && cleanup.isSuccess) "STOPPED" else "FAILED", reason)
    }

    private fun logPageLifecycle(state: PageRuntimeSessionState, reason: String) {
        // Diagnostic failures must not interrupt lease revocation or page teardown.
        runCatching {
            log.append("页面运行时[${state.lifecycle}]：taskId=${state.taskId}；mode=${state.mode}；" +
                "runGeneration=${state.runGeneration}；routeRevision=${state.routeRevision}；reason=$reason")
        }
    }

    private fun beginPageRuntime(generation: Long, taskId: String, mode: RunMode): PageRuntimeSessionState {
        require(generation >= 0L && taskId.isNotBlank())
        val previous = pageRuntime
        check(previous == null || previous.lifecycle == PageRuntimeLifecycle.STOPPING) {
            "Previous page route must stop before replacement"
        }
        check(nextRouteRevision < Long.MAX_VALUE) { "page route revision exhausted" }
        return PageRuntimeSessionState(generation, taskId, mode, PageRuntimeLifecycle.STARTING,
            ++nextRouteRevision).also(::publishPageRuntime)
    }

    private fun markPageStopping(expected: PageRuntimeSessionState): PageRuntimeSessionState? =
        pageRuntime?.takeIf { it == expected }?.copy(lifecycle = PageRuntimeLifecycle.STOPPING)
            ?.also(::publishPageRuntime)

    /** STOPPING is visible throughout cleanup; revocation precedes any teardown callback. */
    private fun stopPageRuntime(reason: String, preserveProgress: Boolean,
                                cleanup: ((ModeRuntimePort) -> Unit)? = null): Boolean {
        val previous = pageRuntime
        if (previous?.lifecycle == PageRuntimeLifecycle.STOPPING && pendingCleanup != null) {
            // The old lease and generation have already been revoked.
            if (!preserveProgress) {
                val runtime = runtimes[previous.mode]
                pendingCleanup = { runtime?.onRunStopped(reason) }
            }
            return executePendingCleanup(reason)
        }
        if (previous == null) {
            advanceRunGeneration()
            runtimePrepared = false
            return true
        }
        val stopping = markPageStopping(previous) ?: return false
        logPageLifecycle(stopping, reason)
        runtimePrepared = false
        val runtime = runtimes[previous.mode]
        if (preserveProgress && runtime != null) {
            // Save only the source's durable progress. The actual service boundary is
            // explicit (its dedicated cleanup callback); an ordinary page switch is not one.
            // STOPPING already closes UI authority while the writer reads the old task identity.
            runCatching { runtime.saveProgressBeforeExit(reason, serviceBoundary = cleanup != null) }.onFailure {
                runCatching { log.recordException("RunSessionCoordinator", "page_runtime_checkpoint_failed", reason, it) }
            }
        }
        advanceRunGeneration()
        pendingCleanup = {
            if (runtime != null) {
                if (cleanup != null) cleanup(runtime)
                else if (preserveProgress) runtime.onRunPaused(reason)
                else runtime.onRunStopped(reason)
            }
        }
        val stopped = executePendingCleanup(reason)
        if (!stopped && isPageObservationAllowed()) onObserveCurrentPage()
        return stopped
    }

    private fun executePendingCleanup(reason: String): Boolean {
        val operation = pendingCleanup ?: return false
        return try {
            operation()
            publishPageRuntime(null)
            pendingCleanup = null
            true
        } catch (error: Throwable) {
            runCatching { log.recordException("RunSessionCoordinator", "page_runtime_stop_failed", reason, error) }
            false
        }
    }

    /** Retry only a failed runtime teardown, never a source task's business recovery. */
    @Synchronized
    fun retryPendingPageCleanup(): Boolean {
        if (!isPageObservationAllowed() || pageTransitionInProgress ||
            pageRuntime?.lifecycle != PageRuntimeLifecycle.STOPPING ||
            pendingCleanup == null) return false
        pageTransitionInProgress = true
        try {
            val cleaned = executePendingCleanup("STOPPING_READ_ONLY_RETRY")
            if (cleaned) {
                runCatching { log.append("页面运行时[CLEANUP_RECOVERED]：旧运行时已清理；等待新鲜页面") }
                onObserveCurrentPage()
            }
            return cleaned
        } finally {
            pageTransitionInProgress = false
        }
    }

    /** An unverified page retains observation, not write authority. */
    @Synchronized
    fun reconcileUnclassifiedPage(observedGeneration: Long, ownedPageVerified: Boolean) {
        if (!isPageObservationAllowed() || observedGeneration != runGeneration ||
            pageTransitionInProgress) return
        val current = pageRuntime ?: return
        if (current.lifecycle == PageRuntimeLifecycle.STOPPING) return
        if (ownedPageVerified) {
            if (current.lifecycle == PageRuntimeLifecycle.UNCLASSIFIED) {
                val pending = current.copy(lifecycle = PageRuntimeLifecycle.STARTING)
                publishPageRuntime(pending)
                tryCompletePageStart(pending)
            }
        } else if (current.lifecycle != PageRuntimeLifecycle.UNCLASSIFIED) {
            logPageLifecycle(unclassifiedPage(current), "UNKNOWN_WRITE_HOLD：页面归属未确认；仅保留观察")
        }
    }

    private fun unclassifiedPage(current: PageRuntimeSessionState): PageRuntimeSessionState {
        check(nextRouteRevision < Long.MAX_VALUE) { "page route revision exhausted" }
        return current.copy(lifecycle = PageRuntimeLifecycle.UNCLASSIFIED, routeRevision = ++nextRouteRevision)
            .also(::publishPageRuntime)
    }

    private fun tryCompletePageStart(expected: PageRuntimeSessionState): PageRuntimeSessionState? {
        val current = pageRuntime ?: return null
        if (!isPageObservationAllowed() || current.lifecycle == PageRuntimeLifecycle.UNCLASSIFIED ||
            current.runGeneration != runGeneration ||
            current.routeRevision != expected.routeRevision || current.taskId != expected.taskId ||
            current.mode != expected.mode || !runtimePrepared ||
            current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return null
        val ready = if (current.lifecycle == PageRuntimeLifecycle.READY) current
            else current.copy(lifecycle = PageRuntimeLifecycle.READY)
        val tasks = taskCoordinator
        if (tasks != null) {
            val lease = tasks.currentLease() ?: return null
            if (lease.taskId != current.taskId || lease.runGeneration != current.runGeneration) return null
            // Revalidate even an unchanged READY route, but publish only a transition.
            if (tasks.withCurrentLease(lease) {
                    if (current !== ready) pageRuntime = ready
                    true
                } != true
            ) return null
        } else if (current !== ready) publishPageRuntime(ready)
        if (current !== ready) logPageLifecycle(ready, "运行时准备完成且任务租约已确认")
        return ready
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
        val current = pageRuntime ?: return false
        if (current.runGeneration != runGeneration || lease.runGeneration != current.runGeneration ||
            current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return false
        if (current.taskId == lease.taskId) {
            tryCompletePageStart(current)
            return true
        }
        if (current.lifecycle == PageRuntimeLifecycle.UNCLASSIFIED || !verifiedHandoff ||
            current.mode != RunMode.AI_REPLY || !runtimePrepared ||
            lease.taskId !in setOf("AI_REPLY_PRIVATE_MESSAGES", "AI_REPLY_RECEIVED_GREETINGS")
        ) return false
        markPageStopping(current)?.let { logPageLifecycle(it, "已验证业务任务交接") }
        val next = beginPageRuntime(runGeneration, lease.taskId, current.mode)
        logPageLifecycle(next, "业务准入及目标租约已确认")
        return tryCompletePageStart(next) != null
    }

    /** Recheck a failed admission only if the coordinator confirms that exact release
     * is still the latest capability. A newer lease or an already-held route wins. */
    @Synchronized
    fun rollbackBusinessPageTaskOwnership(failedLease: TaskLease): Boolean {
        val current = pageRuntime ?: return false
        val tasks = taskCoordinator ?: return false
        if (!isPageObservationAllowed() || current.mode != RunMode.AI_REPLY ||
            current.runGeneration != runGeneration ||
            current.runGeneration != failedLease.runGeneration ||
            current.taskId != failedLease.taskId ||
            current.lifecycle == PageRuntimeLifecycle.STOPPING ||
            current.lifecycle == PageRuntimeLifecycle.UNCLASSIFIED
        ) return false
        val held = tasks.withOwnershipBarrier {
            if (!tasks.isLatestReleasedLease(failedLease)) null
            else unclassifiedPage(current)
        } ?: return false
        logPageLifecycle(held, "业务准入失败；等待新鲜页面重新选择")
        onObserveCurrentPage()
        return true
    }

    /**
     * Independent top-level page observations are different from an automatic
     * business handoff. On a sibling-page change, suspend the source task and
     * invalidate its generation before asking for a fresh destination snapshot.
     * Never start the destination using the source generation's snapshot.
     */
    @Synchronized
    fun routeIndependentPageTask(
        mode: RunMode,
        taskId: String,
        observedGeneration: Long = runGeneration
    ): PageRuntimeSessionState? {
        val current = pageRuntime
        if (observedGeneration != runGeneration) return current
        if (!isPageObservationAllowed() || !observedRunRequested || pageTransitionInProgress)
            return current
        if (current?.lifecycle == PageRuntimeLifecycle.STOPPING) return current
        if (current != null && (current.mode != mode || current.taskId != taskId)) {
            pageTransitionInProgress = true
            try {
                val suspended = stopPageRuntime("独立页面业务交接：${current.taskId} -> $taskId", preserveProgress = true)
                if (suspended && isPageObservationAllowed()) onObserveCurrentPage()
                return pageRuntime
            } finally {
                pageTransitionInProgress = false
            }
        }
        if (current?.lifecycle == PageRuntimeLifecycle.UNCLASSIFIED) {
            publishPageRuntime(current.copy(lifecycle = PageRuntimeLifecycle.STARTING))
        }
        return routePageTask(mode, taskId)
    }

    @Synchronized
    fun markPageRuntimeReady(expected: PageRuntimeSessionState): PageRuntimeSessionState? {
        val current = pageRuntime ?: return null
        if (!isPageObservationAllowed() || current.runGeneration != runGeneration ||
            current.routeRevision != expected.routeRevision || current.taskId != expected.taskId ||
            current.mode != expected.mode || current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return null
        runtimePrepared = true
        return tryCompletePageStart(expected)
    }

    private fun dispatchMode(): RunMode? =
        pageRuntime?.takeIf {
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
        pageRuntime?.let(::markPageStopping)
        state.setRunRequested(false)
        observedRunRequested = false
        pageTransitionInProgress = true
        try {
            cancelCurrentPageObservation()
            val stopped = stopPageRuntime(reason, preserveProgress = false)
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
            (requestedRun || pageRuntime?.lifecycle != PageRuntimeLifecycle.STOPPING)
        ) return false
        observedRunRequested = requestedRun
        pageTransitionInProgress = true
        try {
            cancelCurrentPageObservation()
            val stopped = stopPageRuntime("运行请求已变化", preserveProgress = false)
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
        pageRuntime?.takeIf {
            isPageObservationAllowed() && it.lifecycle != PageRuntimeLifecycle.STOPPING &&
                it.runGeneration == runGeneration
        }?.mode

    @Synchronized
    fun onAccessibilityServiceConnected() {
        if (pageServiceDestroyed) return
        if (pageRuntime != null) onAccessibilityServiceDisconnected()
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
        withTaskBarrier { pageServiceConnected = false }
        cancelCurrentPageObservation()
        pageTransitionInProgress = true
        try {
            stopPageRuntime("SERVICE_DISCONNECTED", preserveProgress = true) {
                it.onAccessibilityServiceDisconnected()
            }
        } finally {
            pageTransitionInProgress = false
        }
    }

    @Synchronized
    fun routePageTask(mode: RunMode?, taskId: String? = null): PageRuntimeSessionState? {
        val currentPage = pageRuntime
        if (!isPageObservationAllowed() || !observedRunRequested) return null
        if (pageTransitionInProgress) return currentPage
        if (currentPage?.lifecycle == PageRuntimeLifecycle.STOPPING) {
            retryPendingPageCleanup()
            return pageRuntime
        }
        if (currentPage != null && currentPage.mode == mode && currentPage.taskId == taskId) {
            if (tryCompletePageStart(currentPage) == null) {
                val runtime = runtimes[currentPage.mode]
                if (!runtimePrepared) {
                    // Only a freshly selected matching page may retry preparation. Do not
                    // reset a prepared runtime that is merely waiting for its business lease.
                    pageTransitionInProgress = true
                    try {
                        if (runtime?.onRunStarted(currentPage.taskId, retry = true) == true) {
                            markPageRuntimeReady(currentPage)
                        }
                    } catch (error: Throwable) {
                        log.recordException("RunSessionCoordinator", "page_runtime_prepare_retry_failed",
                            "页面运行时重新准备失败；继续只读等待", error)
                        stopPageRuntime("页面运行时重新准备失败", preserveProgress = true)
                    } finally {
                        pageTransitionInProgress = false
                    }
                } else {
                    runtime?.onPageTaskChanged(currentPage)
                }
            }
            return pageRuntime
        }
        pageTransitionInProgress = true
        try {
            if (mode == null || taskId == null) {
                if (currentPage != null) stopPageRuntime("页面路由未确认", preserveProgress = true)
                return null
            }
            // Private messages and greetings share one runtime and a verified atomic
            // business handoff. Do not reset it, invent a lease or bypass its page checks.
            if (currentPage != null && currentPage.mode == mode && runtimePrepared) {
                markPageStopping(currentPage)?.let { logPageLifecycle(it, "同运行时任务切换") }
                val next = beginPageRuntime(runGeneration, taskId, mode)
                if (currentPage.lifecycle == PageRuntimeLifecycle.UNCLASSIFIED) {
                    publishPageRuntime(next.copy(lifecycle = PageRuntimeLifecycle.UNCLASSIFIED))
                }
                logPageLifecycle(requireNotNull(pageRuntime), "等待目标任务验证及租约交接")
                if (tryCompletePageStart(next) == null) runtimes[mode]?.onPageTaskChanged(next)
                return pageRuntime
            }
            if (currentPage != null &&
                !stopPageRuntime("页面任务路由切换", preserveProgress = true)
            ) return pageRuntime
            val runtime = runtimes[mode] ?: return null
            val next = beginPageRuntime(runGeneration, taskId, mode)
            logPageLifecycle(next, "页面识别选中任务")
            try {
                if (runtime.onRunStarted(next.taskId, retry = false)) {
                    markPageRuntimeReady(next)
                }
            } catch (error: Throwable) {
                log.recordException("RunSessionCoordinator", "page_runtime_start_failed",
                    "页面运行时启动失败；保持只读等待", error)
                stopPageRuntime("页面运行时启动失败", preserveProgress = true)
            }
            return pageRuntime
        } finally {
            pageTransitionInProgress = false
        }
    }

    @Synchronized
    fun currentPageRuntimeSession(): PageRuntimeSessionState? = pageRuntime

    @Synchronized
    fun dispatchTargetApplicationEvent(event: AccessibilityEventEnvelope) {
        synchronizeRunRequest()
        val pageSession = pageRuntime
        val mode = when {
            isPageObservationAllowed() &&
                pageSession != null &&
                (pageSession.lifecycle == PageRuntimeLifecycle.STARTING ||
                    pageSession.lifecycle == PageRuntimeLifecycle.UNCLASSIFIED) &&
                runtimePrepared &&
                pageSession.runGeneration == runGeneration -> {
                // The AI observer must receive page signals to finish its existing verification.
                // The task controller still owns all business admission and lease checks.
                pageSession.mode
            }
            pageSession != null && pageSession.lifecycle != PageRuntimeLifecycle.READY -> null
            else -> dispatchMode()
        }
        mode?.let { runtimes[it]?.onTargetApplicationEvent(event) }
    }

    @Synchronized
    fun dispatchGlobalTouchEvent(event: AccessibilityEventEnvelope) {
        synchronizeRunRequest()
        val mode = activeMode()
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
            stopPageRuntime(reason, preserveProgress = true)
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
            stopPageRuntime(reason, preserveProgress = true) { it.onAccessibilityServiceInterrupted(reason) }
        } finally {
            pageTransitionInProgress = false
        }
        if (isPageObservationAllowed()) onObserveCurrentPage.invoke()
    }

    @Synchronized
    fun onServiceDestroyed() {
        if (pageServiceDestroyed) return
        withTaskBarrier {
            pageServiceDestroyed = true
            pageServiceConnected = false
        }
        cancelCurrentPageObservation()
        pageTransitionInProgress = true
        try {
            val requested = state.isRunRequested()
            val stopped = stopPageRuntime("SERVICE_DESTROYED", preserveProgress = requested) {
                if (requested) it.onServiceDestroyed() else it.onRunStopped("用户运行授权已关闭")
            }
            if (!requested) finishPageUserRun(stopped, "SERVICE_DESTROYED")
        } finally {
            pageTransitionInProgress = false
        }
    }

}
