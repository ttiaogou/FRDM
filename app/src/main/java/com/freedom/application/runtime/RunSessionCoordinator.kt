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
 * Reconciles persisted run intent with runtime lifecycle and dispatches normalized events to at
 * most one mode runtime. RunSessionCommandUseCase is the sole user-command write boundary. A null
 * mode is a valid manual-observation session with no business runtime.
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
    /** Production uses a mode-independent observation request; null retains legacy test seams. */
    private val onObserveCurrentPage: (() -> Unit)? = null
) {
    private var observedMode: RunMode? = null
    private var observedRunRequested: Boolean = false
    private var pageRoutedMode: RunMode? = null
    private val pageRuntimeSession = PageRuntimeSession()
    private var preparedRuntimeMode: RunMode? = null
    private var pageTransitionInProgress = false

    private fun logPageLifecycle(state: PageRuntimeSessionState, reason: String) {
        log.append("页面运行时[${state.lifecycle}]：taskId=${state.taskId}；mode=${state.mode}；" +
            "runGeneration=${state.runGeneration}；routeRevision=${state.routeRevision}；reason=$reason")
    }

    /** STOPPING is visible throughout cleanup; revocation precedes any teardown callback. */
    private fun stopPageRuntime(reason: String, preserveRecovery: Boolean,
                                cleanup: ((ModeRuntimePort) -> Unit)? = null): Boolean {
        val previous = pageRuntimeSession.current()
        if (previous == null) {
            advanceRunGeneration()
            preparedRuntimeMode = null
            pageRoutedMode = null
            return true
        }
        val stopping = pageRuntimeSession.markStopping(previous) ?: return false
        logPageLifecycle(stopping, reason)
        preparedRuntimeMode = null
        pageRoutedMode = null
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
        if (!state.isRunRequested() || current.runGeneration != runGeneration ||
            current.routeRevision != expected.routeRevision || current.taskId != expected.taskId ||
            current.mode != expected.mode || preparedRuntimeMode != current.mode ||
            current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return null
        if (taskCoordinator != null) {
            val lease = taskCoordinator.currentLease() ?: return null
            if (lease.taskId != current.taskId || lease.runGeneration != current.runGeneration) return null
        }
        val ready = pageRuntimeSession.markReady(expected) ?: return null
        if (current.lifecycle != PageRuntimeLifecycle.READY) logPageLifecycle(ready, "运行时准备完成且任务租约已确认")
        return ready
    }

    /** Called only by the task controller after obtaining its existing verified capability. */
    @Synchronized
    fun confirmPageTaskOwnership(lease: TaskLease): PageRuntimeSessionState? {
        if (onObserveCurrentPage == null || taskCoordinator?.isCurrent(lease) != true) return null
        val current = pageRuntimeSession.current() ?: return null
        if (current.taskId != lease.taskId || current.runGeneration != lease.runGeneration) return null
        return tryCompletePageStart(current)
    }

    @Synchronized
    fun markPageRuntimeReady(expected: PageRuntimeSessionState): PageRuntimeSessionState? {
        val current = pageRuntimeSession.current() ?: return null
        if (!state.isRunRequested() || current.runGeneration != runGeneration ||
            current.routeRevision != expected.routeRevision || current.taskId != expected.taskId ||
            current.mode != expected.mode || current.lifecycle == PageRuntimeLifecycle.STOPPING
        ) return null
        preparedRuntimeMode = current.mode
        return tryCompletePageStart(expected)
    }

    private fun dispatchMode(): RunMode? =
        if (onObserveCurrentPage == null) pageRoutedMode ?: activeMode()
        else pageRuntimeSession.current()?.takeIf {
            state.isRunRequested() && it.runGeneration == runGeneration &&
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
        if (onObserveCurrentPage != null) {
            log.append("运行请求准备停止：来源=RunSessionCoordinator.stop；原因=$reason")
            state.setRunRequested(false)
            observedRunRequested = false
            pageTransitionInProgress = true
            try {
                val stopped = stopPageRuntime(reason, preserveRecovery = false)
                log.finishRun(if (stopped) "STOPPED" else "FAILED", reason)
            } finally {
                pageTransitionInProgress = false
            }
            return
        }
        val active = activeMode() ?: observedMode
        if (active != null || state.isRunRequested()) advanceRunGeneration()
        // Write the stop cause before clearing the run flag. The data adapter starts a fallback
        // finish timer; this coordinator seals immediately after runtime/archive cleanup below.
        log.append("运行请求准备停止：来源=RunSessionCoordinator.stop；原因=$reason；原模式=$active")
        state.setRunRequested(false)
        try {
            if (active != null) runtimes[active]?.onRunStopped(reason)
        } catch (error: Throwable) {
            log.recordException(
                component = "RunSessionCoordinator",
                eventType = "runtime_stop_failed",
                message = "停止运行时清理失败；mode=$active；reason=$reason",
                error = error
            )
            throw error
        } finally {
            log.finishRun("STOPPED", reason)
            observedMode = null
            observedRunRequested = false
            pageRoutedMode = null
        }
    }

    /**
     * Applies persisted run-intent changes written by the command boundary or restored by the
     * system. Returns true only when this call performed a runtime lifecycle transition.
     */
    @Synchronized
    fun synchronizeRunRequest(): Boolean {
        val requestedRun = state.isRunRequested()
        if (onObserveCurrentPage != null) {
            if (requestedRun == observedRunRequested) return false
            observedRunRequested = requestedRun
            pageTransitionInProgress = true
            try {
                val stopped = stopPageRuntime("运行请求已变化", preserveRecovery = false)
                if (requestedRun) onObserveCurrentPage.invoke()
                else log.finishRun(if (stopped) "STOPPED" else "FAILED", "运行请求状态变化")
            } finally {
                pageTransitionInProgress = false
            }
            return true
        }
        val requestedMode = activeMode()
        if (requestedMode == observedMode && requestedRun == observedRunRequested) return false
        // Invalidate old requests before asking the previous runtime to perform cleanup. Any
        // late callback or scheduled step from that runtime must not submit into the new run.
        advanceRunGeneration()
        log.append(
            "运行请求发生变化：来源=synchronizeRunRequest；旧观察模式=$observedMode；" +
                "当前请求模式=$requestedMode；runRequested=${state.isRunRequested()}；" +
                "持久化模式=${state.activeMode()}"
        )
        val previous = observedMode
        if (previous != null) {
            pageRoutedMode = null
            pageRuntimeSession.clear()
            if (requestedMode == null) {
                log.append(
                    "运行请求停止[OBSERVED]：source=RunSessionCoordinator.synchronizeRunRequest；" +
                        "previousMode=$previous；runRequested=${state.isRunRequested()}；" +
                        "persistedMode=${state.activeMode() ?: "NONE"}；reason=外部运行请求被清除"
                )
            }
            try {
                runtimes[previous]?.onRunStopped("运行模式或运行请求已变化")
            } catch (error: Throwable) {
                log.recordException(
                    component = "RunSessionCoordinator",
                    eventType = "runtime_sync_stop_failed",
                    message = "同步运行状态时停止旧运行时失败；mode=$previous",
                    error = error
                )
                throw error
            } finally {
                if (requestedMode == null) {
                    log.finishRun("STOPPED", "运行请求状态变化；原运行时已完成停止清理")
                }
            }
        }
        observedMode = requestedMode
        observedRunRequested = requestedRun
        if (requestedMode != null) {
            val runtime = runtimes[requestedMode]
            try {
                runtime?.onRunStarted()
            } catch (error: Throwable) {
                log.recordException(
                    component = "RunSessionCoordinator",
                    eventType = "runtime_sync_start_failed",
                    message = "同步运行状态时启动运行时失败；mode=$requestedMode",
                    error = error
                )
                runCatching { runtime?.onRunStopped("恢复启动失败，执行安全清理") }
                    .onFailure { cleanupError ->
                        log.recordException(
                            component = "RunSessionCoordinator",
                            eventType = "runtime_restore_cleanup_failed",
                            message = "恢复启动失败后的安全清理也失败；mode=$requestedMode",
                            error = cleanupError
                        )
                    }
                state.setRunRequested(false)
                log.finishRun("FAILED", "runtime_sync_start_failed:$requestedMode")
                observedMode = null
                observedRunRequested = false
                throw error
            }
        }
        return true
    }

    /**
     * Restore only a mode explicitly latched for this run. A null mode is a valid manual-
     * observation session; mutable UI preferences must never silently start an AI/Browse runtime.
     */
    @Synchronized
    fun resolveActiveModeForRestore(): RunMode? =
        if (onObserveCurrentPage == null && state.isRunRequested()) state.activeMode() else null

    @Synchronized
    fun activeMode(): RunMode? =
        if (!state.isRunRequested()) null
        else if (onObserveCurrentPage != null) pageRuntimeSession.current()?.takeIf {
            it.lifecycle != PageRuntimeLifecycle.STOPPING && it.runGeneration == runGeneration
        }?.mode
        else resolveActiveModeForRestore()

    @Synchronized
    fun onAccessibilityServiceConnected() {
        if (onObserveCurrentPage != null) {
            if (pageRuntimeSession.current() != null) onAccessibilityServiceDisconnected()
            val changed = synchronizeRunRequest()
            if (!changed && state.isRunRequested()) onObserveCurrentPage.invoke()
            return
        }
        synchronizeRunRequest()
        observedMode?.let { runtimes[it]?.onAccessibilityServiceConnected() }
    }

    @Synchronized
    fun onAccessibilityServiceDisconnected() {
        // A reconnect must never reuse capabilities or callbacks issued before the service
        // boundary.  Advance before notifying the runtime so its reset and any late callback
        // observe the new generation as stale.
        if (onObserveCurrentPage != null) {
            pageTransitionInProgress = true
            try {
                stopPageRuntime("SERVICE_DISCONNECTED", preserveRecovery = true) {
                    it.onAccessibilityServiceDisconnected()
                }
            } finally {
                pageTransitionInProgress = false
            }
            return
        }
        val previous = observedMode
        advanceRunGeneration()
        pageRoutedMode = null
        pageRuntimeSession.clear()
        log.append("无障碍服务租约隔离：事件=DISCONNECTED；runGeneration=$runGeneration")
        previous?.let { runtimes[it]?.onAccessibilityServiceDisconnected() }
    }

    @Synchronized
    fun routePageTask(mode: RunMode?, taskId: String? = null): PageRuntimeSessionState? {
        val currentPage = pageRuntimeSession.current()
        if (onObserveCurrentPage != null) {
            if (!state.isRunRequested() || !observedRunRequested) return null
            if (pageTransitionInProgress) return currentPage
            if (currentPage != null && currentPage.mode == mode && currentPage.taskId == taskId &&
                currentPage.lifecycle != PageRuntimeLifecycle.STOPPING
            ) {
                tryCompletePageStart(currentPage)
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
                pageRoutedMode = mode
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
        if (currentPage != null && (mode == null || currentPage.mode != mode || currentPage.taskId != taskId)) {
            pageRuntimeSession.markStopping(currentPage.runGeneration)
            runtimes[currentPage.mode]?.onRunStopped("页面任务路由切换")
            log.append(
                "页面任务路由[STOPPING]：taskId=" + currentPage.taskId +
                    "；nextTaskId=" + (taskId ?: "NONE") +
                    "；runGeneration=" + currentPage.runGeneration +
                    "；旧运行时调度已取消"
            )
        }
        pageRoutedMode = mode
        val session = if (mode != null && taskId != null) {
            pageRuntimeSession.begin(runGeneration, taskId, mode)
        } else {
            pageRuntimeSession.current()?.let { pageRuntimeSession.markStopping(it.runGeneration) }
            taskCoordinator?.currentLease()?.let { taskCoordinator.release(it) }
            pageRuntimeSession.clear()
            pageRoutedMode = null
            null
        }
        log.append(
            "页面任务路由[SET]：mode=" + (mode?.name ?: "NONE") +
                "；taskId=" + (taskId ?: "NONE") +
                "；runGeneration=" + runGeneration
        )
        return session
    }

    @Synchronized
    fun markPageRuntimeReady(runGeneration: Long): PageRuntimeSessionState? {
        // Production callbacks must provide the exact route token, never generation alone.
        if (onObserveCurrentPage != null) return null
        val ready = pageRuntimeSession.markReady(runGeneration) ?: return null
        val current = taskCoordinator?.currentLease()
        val transferred = if (current == null) {
            taskCoordinator?.tryAcquire(ready.taskId, runGeneration) != null
        } else if (current.taskId == ready.taskId) {
            true
        } else {
            taskCoordinator?.transfer(current, ready.taskId) != null
        }
        log.append(
            "页面运行时[READY]：taskId=" + ready.taskId +
                "；ownershipTransferred=" + transferred +
                "；runGeneration=" + runGeneration
        )
        return ready
    }

    @Synchronized
    fun currentPageRuntimeSession(): PageRuntimeSessionState? = pageRuntimeSession.current()

    @Synchronized
    fun dispatchTargetApplicationEvent(event: AccessibilityEventEnvelope) {
        synchronizeRunRequest()
        val pageSession = pageRuntimeSession.current()
        if (onObserveCurrentPage != null && state.isRunRequested() &&
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
        if (onObserveCurrentPage != null && state.isRunRequested() && dispatchMode() == null) {
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

    @Synchronized
    fun resetRuntime(mode: RunMode, reason: String) {
        if (onObserveCurrentPage != null) {
            if (pageRuntimeSession.current()?.mode == mode) {
                pageTransitionInProgress = true
                try {
                    stopPageRuntime(reason, preserveRecovery = true)
                } finally {
                    pageTransitionInProgress = false
                }
            }
            if (state.isRunRequested()) onObserveCurrentPage.invoke()
            return
        }
        advanceRunGeneration()
        log.append("运行时租约隔离：事件=RESET；mode=$mode；reason=$reason；runGeneration=$runGeneration")
        // This entry point is the generation barrier used by the service composition layer.
        // The caller that owns the concrete runtime performs its reset/cleanup separately;
        // invoking resetRuntime on the same runtime here would re-enter onRunStopped and can
        // duplicate archive finalization or other lifecycle cleanup.
    }

    @Synchronized
    fun onAccessibilityServiceInterrupted(reason: String) {
        if (onObserveCurrentPage != null) {
            pageTransitionInProgress = true
            try {
                stopPageRuntime(reason, preserveRecovery = true) { it.onAccessibilityServiceInterrupted(reason) }
            } finally {
                pageTransitionInProgress = false
            }
            return
        }
        val previous = activeMode()
        advanceRunGeneration()
        pageRoutedMode = null
        pageRuntimeSession.clear()
        log.append("无障碍服务租约隔离：事件=INTERRUPTED；reason=$reason；runGeneration=$runGeneration")
        previous?.let { runtimes[it]?.onAccessibilityServiceInterrupted(reason) }
    }

    @Synchronized
    fun onServiceDestroyed() {
        if (onObserveCurrentPage != null && pageRuntimeSession.current() != null) {
            pageTransitionInProgress = true
            try {
                stopPageRuntime("SERVICE_DESTROYED", preserveRecovery = true)
            } finally {
                pageTransitionInProgress = false
            }
        } else advanceRunGeneration()
        preparedRuntimeMode = null
        pageRoutedMode = null
        pageRuntimeSession.clear()
        log.append("无障碍服务租约隔离：事件=DESTROYED；runGeneration=$runGeneration")
        runtimes.values.forEach(ModeRuntimePort::onServiceDestroyed)
    }

    private fun RunMode.displayName(): String = when (this) {
        RunMode.AI_REPLY -> "AI 回复基础模式"
        RunMode.ACCOUNT_BROWSE -> "自动浏览模式"
    }
}
