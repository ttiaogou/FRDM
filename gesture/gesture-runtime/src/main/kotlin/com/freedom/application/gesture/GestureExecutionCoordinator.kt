package com.freedom.application.gesture

import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.GestureCompletion
import com.freedom.core.accessibility.api.GestureExecutionPort
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureResultListener
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.toGestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureVisualizationPort
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskCoordinator
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admits screen gestures to a bounded FIFO queue, binds them to a page generation,
 * and enforces visualization-before-execution with a single active platform stroke.
 * It is platform-neutral and therefore independently testable.
 */
internal class GestureExecutionCoordinator(
    private val displayMetrics: () -> DisplayMetricsSnapshot,
    private val dispatcher: GestureExecutionPort,
    private val visualizer: GestureVisualizationPort,
    private val onDiagnostic: (String) -> Unit = {},
    private val scheduler: TaskScheduler? = null,
    private val currentPageGeneration: () -> Long = { 0L },
    private val currentRunGeneration: () -> Long = { 0L },
    private val isTargetWindowCurrent: (WindowRef) -> Boolean = { window ->
        window.generation == runCatching(currentPageGeneration).getOrDefault(0L)
    },
    private val currentCoordinateContext: () -> GestureCoordinateContext? = {
        runCatching(displayMetrics).getOrNull()?.toGestureCoordinateContext()
    },
    private val taskCoordinator: TaskCoordinator? = null,
    private val maxPendingCommands: Int = DEFAULT_MAX_PENDING_COMMANDS,
    private val uptimeClockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) : InterruptibleGestureExecutor {
    private class ActiveExecution(
        val command: GestureCommand,
        val listener: GestureExecutionListener,
        val pageGeneration: Long,
        var wasQueued: Boolean = false,
        /** True once execute() has returned ACCEPTED before the platform reaches a terminal state. */
        var asyncResultRequired: Boolean = false
    ) {
        val resultDelivered = AtomicBoolean(false)
        var visualizationInProgress: Boolean = true
        var visualizationShown: Boolean = false
        var drainWatchdog: ScheduledTask? = null
        var preDispatchTask: ScheduledTask? = null
        var waitingForPreDispatch: Boolean = false
        var dispatchStarted: Boolean = false
        var platformSettled: Boolean = false
        var cancellationRequestedReason: String? = null
        var drainTimedOut: Boolean = false
        var timeoutTerminalDeliveryComplete: Boolean = false
        var expectedPageTransitionObserved: Boolean = false
    }

    private val policyValidator = GesturePolicyValidator(displayMetrics)
    private val lock = Any()
    private var activeExecution: ActiveExecution? = null
    private val pendingExecutions = ArrayDeque<ActiveExecution>()

    init {
        require(maxPendingCommands in 1..MAX_PENDING_COMMANDS) {
            "maxPendingCommands must be between 1 and $MAX_PENDING_COMMANDS"
        }
    }

    override fun execute(
        approvedCommand: ApprovedGestureCommand,
        listener: GestureExecutionListener
    ): GestureSubmission {
        val command = approvedCommand.command
        val validationFailure = validate(command)
        if (validationFailure != null) {
            diagnostic("手势提交被拒绝：参数校验失败；id=${command.id}；原因=$validationFailure")
            return GestureSubmission.REJECTED
        }

        val execution = ActiveExecution(
            command = command,
            listener = listener,
            pageGeneration = command.executionContext.targetWindow.generation
        )
        val admission = synchronized(lock) {
            val duplicate = activeExecution?.command?.id == command.id ||
                pendingExecutions.any { it.command.id == command.id }
            when {
                duplicate -> QueueAdmission.DUPLICATE
                activeExecution == null -> {
                    activeExecution = execution
                    QueueAdmission.START_NOW
                }
                pendingExecutions.size >= maxPendingCommands -> QueueAdmission.FULL
                else -> {
                    pendingExecutions.addLast(execution)
                    QueueAdmission.QUEUED
                }
            }
        }

        return when (admission) {
            QueueAdmission.DUPLICATE -> {
                diagnostic("手势提交被拒绝：重复 commandId；id=${command.id}")
                GestureSubmission.REJECTED
            }
            QueueAdmission.FULL -> {
                diagnostic(
                    "手势提交被拒绝：FIFO 队列已满；id=${command.id}；" +
                        "pending=${pendingCount()}；capacity=$maxPendingCommands"
                )
                GestureSubmission.REJECTED
            }
            QueueAdmission.QUEUED -> {
                diagnostic(
                    "手势已进入 FIFO 队列：id=${command.id}；position=${queuePosition(command.id)}；" +
                        "pending=${pendingCount()}；active=${activeCommandSnapshot()}"
                )
                // ACCEPTED means the Runtime has admitted the command. It will only be sent to
                // Android after all earlier commands have reached a platform-safe terminal.
                GestureSubmission.ACCEPTED
            }
            QueueAdmission.START_NOW -> {
                diagnostic("手势已预留：id=${command.id}；类型=${command.kind}；queue=immediate")
                startExecution(execution)
            }
        }
    }

    private enum class QueueAdmission { START_NOW, QUEUED, FULL, DUPLICATE }

    /** Starts a command that already owns the active slot. */
    private fun startExecution(execution: ActiveExecution): GestureSubmission {
        val command = execution.command
        val validationFailure = validate(command)
        if (validationFailure != null) {
            val cancelledReason = synchronized(lock) {
                execution.visualizationInProgress = false
                execution.cancellationRequestedReason.takeIf { execution.resultDelivered.get() }
            }
            if (cancelledReason != null) {
                finishPreDispatchCancellation(execution, cancelledReason)
                return GestureSubmission.REJECTED
            }
            val reason = "queued_or_delayed_execution_context_invalid:$validationFailure"
            val ownsTerminal = execution.resultDelivered.compareAndSet(false, true)
            if (ownsTerminal) {
                diagnostic("排队手势在实际执行前被拒绝：id=${command.id}；原因=$reason")
                notify(execution.listener, GestureExecutionResult(
                    command.id, GestureExecutionStatus.REJECTED, reason
                ))
                release(execution)
            } else {
                // A concurrent cancellation/page transition already owns terminal delivery and
                // will release the slot after its visual/business feedback is complete.
                diagnostic("排队手势上下文失效与取消同时发生；由先取得终态权的路径负责释放；id=${command.id}")
            }
            return GestureSubmission.REJECTED
        }

        val mayVisualize = synchronized(lock) {
            if (activeExecution === execution && !execution.resultDelivered.get()) true
            else {
                execution.visualizationInProgress = false
                false
            }
        }
        if (!mayVisualize) {
            finishPreDispatchCancellation(
                execution, execution.cancellationRequestedReason ?: "cancelled_before_visualization"
            )
            return GestureSubmission.REJECTED
        }

        val visualReady = runCatching { visualizer.show(command) }.getOrDefault(false)
        val cancelledDuringVisualization = synchronized(lock) {
            execution.visualizationInProgress = false
            execution.visualizationShown = visualReady
            execution.cancellationRequestedReason.takeIf { execution.resultDelivered.get() }
        }
        if (cancelledDuringVisualization != null) {
            finishPreDispatchCancellation(execution, cancelledDuringVisualization)
            return GestureSubmission.REJECTED
        }

        if (!visualReady) {
            val ownsTerminal = execution.resultDelivered.compareAndSet(false, true)
            if (ownsTerminal) {
                diagnostic("手势未派发：可视化准备失败；id=${command.id}")
                runCatching { visualizer.onRejected(command, "gesture_visualization_unavailable") }
                if (execution.wasQueued) {
                    notify(execution.listener, GestureExecutionResult(
                        command.id, GestureExecutionStatus.REJECTED, "gesture_visualization_unavailable"
                    ))
                }
                release(execution)
            } else {
                // A concurrent preemption owns the terminal callback and slot release.
                diagnostic("手势可视化失败与取消同时发生；不覆盖取消终态或提前推进 FIFO；id=${command.id}")
            }
            return GestureSubmission.REJECTED
        }

        val preDispatchDelay = command.preDispatchDelayMs
        if (preDispatchDelay > 0L) {
            return schedulePreDispatch(execution, preDispatchDelay)
        }
        return dispatchPreparedExecution(execution)
    }

    private fun schedulePreDispatch(execution: ActiveExecution, delayMs: Long): GestureSubmission {
        val taskScheduler = scheduler ?: run {
            rejectBeforePlatformDispatch(execution, "pre_dispatch_scheduler_unavailable")
            return GestureSubmission.REJECTED
        }
        val mayWait = synchronized(lock) {
            if (activeExecution === execution && !execution.resultDelivered.get() && !execution.dispatchStarted) {
                execution.waitingForPreDispatch = true
                // The caller receives ACCEPTED as soon as this timer is installed. Any later
                // dispatch result (including readiness deferral) must therefore arrive by listener.
                execution.asyncResultRequired = true
                true
            } else false
        }
        if (!mayWait) return GestureSubmission.REJECTED
        val scheduled = runCatching {
            taskScheduler.schedule(delayMs) {
                val ownsStart = synchronized(lock) {
                    execution.preDispatchTask = null
                    execution.waitingForPreDispatch = false
                    activeExecution === execution && !execution.resultDelivered.get() && !execution.dispatchStarted
                }
                if (ownsStart) {
                    val failure = validate(execution.command)
                    if (failure != null) {
                        rejectBeforePlatformDispatch(execution, "pre_dispatch_revalidation_failed:$failure")
                    } else {
                        diagnostic("手势操作前延迟结束：id=${execution.command.id}；delayMs=$delayMs")
                        dispatchPreparedExecution(execution)
                    }
                }
            }
        }.getOrElse {
            synchronized(lock) { execution.asyncResultRequired = false }
            rejectBeforePlatformDispatch(execution, "pre_dispatch_schedule_failed:${it.javaClass.simpleName}")
            return GestureSubmission.REJECTED
        }
        val cancelImmediately = synchronized(lock) {
            if (execution.waitingForPreDispatch && activeExecution === execution && !execution.resultDelivered.get()) {
                execution.preDispatchTask = scheduled
                false
            } else true
        }
        if (cancelImmediately) scheduled.cancel()
        diagnostic("手势已进入操作前延迟：id=${execution.command.id}；delayMs=$delayMs")
        return GestureSubmission.ACCEPTED
    }

    private fun dispatchPreparedExecution(execution: ActiveExecution): GestureSubmission {
        val command = execution.command
        // Serialize the final cancellation check with the actual platform submission. If a
        // human interaction wins the lock first, this command is never sent to Android. If the
        // dispatch wins first, cancelActive treats it as an in-flight platform stroke and drains
        // its callback/watchdog before another command may be submitted.
        var contextFailureBeforeDispatch: String? = null
        var dispatchTargetRejected = false
        val dispatchAttempt = synchronized(lock) {
            if (activeExecution !== execution || execution.resultDelivered.get()) {
                null
            } else {
                val contextFailure = executionContextFailure(execution.command.executionContext)
                if (contextFailure != null) {
                    contextFailureBeforeDispatch = "execution_context_changed_before_dispatch:$contextFailure"
                    execution.cancellationRequestedReason = contextFailureBeforeDispatch
                    execution.platformSettled = true
                    execution.resultDelivered.compareAndSet(false, true)
                    null
                } else {
                    // The platform port invokes this at its real dispatch boundary, after
                    // readiness checks, gesture construction and the coordinator pre-delay.
                    // A task-bound command submits while TaskCoordinator still holds the exact
                    // lease monitor, making lease validation and dispatch one atomic boundary.
                    val submitToPlatform = {
                        execution.dispatchStarted = true
                        runCatching {
                            dispatcher.dispatchGesture(command.gesture,
                                GestureResultListener { result ->
                                when (result) {
                                    GestureCompletion.COMPLETED -> settleFromPlatform(
                                        execution, GestureExecutionStatus.COMPLETED
                                    )
                                    GestureCompletion.CANCELLED -> settleFromPlatform(
                                        execution, GestureExecutionStatus.CANCELLED, "android_gesture_cancelled"
                                    )
                                }
                            }, commandId = command.id, canDispatch = {
                                val targetCurrent = runCatching {
                                    execution.listener.isDispatchTargetCurrent(command.id)
                                }.getOrDefault(false)
                                val stillCurrent = targetCurrent &&
                                    activeExecution === execution && !execution.resultDelivered.get() &&
                                    execution.cancellationRequestedReason == null &&
                                    executionContextFailure(command.executionContext) == null
                                if (!stillCurrent) dispatchTargetRejected = true
                                stillCurrent
                            }) {
                                runCatching {
                                    execution.listener.onDispatched(command.id, uptimeClockMillis())
                                }
                            }
                        }
                    }
                    val taskLease = command.executionContext.taskLease
                    if (taskLease == null) {
                        submitToPlatform()
                    } else {
                        val guardedSubmission = runCatching {
                            taskCoordinator?.withCurrentLease(taskLease, submitToPlatform)
                        }.getOrNull()
                        if (guardedSubmission == null) {
                            contextFailureBeforeDispatch =
                                "execution_context_changed_before_dispatch:task_lease_changed"
                            execution.cancellationRequestedReason = contextFailureBeforeDispatch
                            execution.platformSettled = true
                            execution.resultDelivered.compareAndSet(false, true)
                            null
                        } else {
                            guardedSubmission
                        }
                    }
                }
            }
        }
        if (contextFailureBeforeDispatch != null) {
            val reason = contextFailureBeforeDispatch!!
            diagnostic(
                "手势未派发：可视化期间执行上下文已失效；id=${command.id}；" +
                    "reason=$reason；submittedRunGeneration=${command.executionContext.runGeneration}；" +
                    "currentRunGeneration=${runCatching(currentRunGeneration).getOrDefault(-1L)}；" +
                    "submittedWindowGeneration=${command.executionContext.targetWindow.generation}；" +
                    "currentWindowGeneration=${readCurrentPageGeneration()}"
            )
            deliverTerminalVisual(execution, GestureExecutionStatus.CANCELLED, reason)
            release(execution)
            notify(execution.listener, GestureExecutionResult(command.id, GestureExecutionStatus.CANCELLED, reason))
            return GestureSubmission.REJECTED
        }
        if (dispatchAttempt == null) {
            diagnostic("手势未派发：命令已由并发取消/页面切换路径接管；等待该路径完成反馈后推进 FIFO；id=${command.id}")
            return GestureSubmission.REJECTED
        }

        val submission = dispatchAttempt.getOrElse {
            val failure = it.javaClass.simpleName.ifBlank { "dispatch_failed" }
            settleFromPlatform(
                execution, GestureExecutionStatus.FAILED, failure,
                notifyListener = execution.wasQueued || execution.asyncResultRequired
            )
            diagnostic("手势派发异常：id=${command.id}；异常=$failure")
            return GestureSubmission.REJECTED
        }

        when (submission) {
            GestureSubmission.ACCEPTED -> Unit
            GestureSubmission.DEFERRED_NOT_READY -> {
                val deferredSettlement = synchronized(lock) {
                    execution.platformSettled = true
                    val watchdog = execution.drainWatchdog.also { execution.drainWatchdog = null }
                    val cancellationReason = execution.cancellationRequestedReason
                    val ownsTerminal = execution.resultDelivered.compareAndSet(false, true)
                    DeferredSettlement(watchdog, cancellationReason, ownsTerminal)
                }
                deferredSettlement.watchdog?.cancel()
                if (deferredSettlement.ownsTerminal) {
                    if (deferredSettlement.cancellationReason != null) {
                        val reason = "cancellation_requested=${deferredSettlement.cancellationReason};" +
                            "dispatch_result=DEFERRED_NOT_READY"
                        deliverTerminalVisual(execution, GestureExecutionStatus.CANCELLED, reason)
                        notify(execution.listener, GestureExecutionResult(
                            command.id, GestureExecutionStatus.CANCELLED, reason
                        ))
                    } else {
                        // The preview is not evidence of a platform gesture. End it on every
                        // readiness deferral, even when the caller will handle the synchronous
                        // DEFERRED_NOT_READY return instead of an asynchronous listener result.
                        val reason = "deferred_not_ready;refresh_context_and_retry"
                        deliverTerminalVisual(execution, GestureExecutionStatus.DEFERRED, reason)
                        if (execution.wasQueued || execution.asyncResultRequired) {
                            notify(execution.listener, GestureExecutionResult(
                                command.id, GestureExecutionStatus.DEFERRED, reason
                            ))
                        }
                    }
                }
                release(execution)
                diagnostic(
                    "手势暂缓提交：目标应用仍处于启动保护或页面确认阶段；" +
                        "id=${command.id}；queued=${execution.wasQueued}；" +
                        "asyncResultRequired=${execution.asyncResultRequired}；" +
                        "cancelledDuringDispatch=${deferredSettlement.cancellationReason != null}；不记为平台已执行"
                )
            }
            GestureSubmission.REJECTED -> settleFromPlatform(
                execution, GestureExecutionStatus.REJECTED,
                if (dispatchTargetRejected) "dispatch_target_unverified" else "android_rejected_gesture",
                notifyListener = execution.wasQueued || execution.asyncResultRequired
            )
        }
        return submission
    }

    private fun rejectBeforePlatformDispatch(execution: ActiveExecution, reason: String) {
        val ownsTerminal = synchronized(lock) {
            execution.preDispatchTask?.cancel()
            execution.preDispatchTask = null
            execution.waitingForPreDispatch = false
            execution.platformSettled = true
            execution.resultDelivered.compareAndSet(false, true)
        }
        if (ownsTerminal) {
            diagnostic("延迟派发前再次验证失败：id=${execution.command.id}；reason=$reason")
            if (execution.visualizationShown) deliverTerminalVisual(execution, GestureExecutionStatus.REJECTED, reason)
            if (execution.wasQueued || execution.asyncResultRequired) {
                notify(execution.listener, GestureExecutionResult(
                    execution.command.id, GestureExecutionStatus.REJECTED, reason
                ))
            }
            release(execution)
        }
    }

    private fun cancelScheduledPreDispatch(execution: ActiveExecution) {
        val task = synchronized(lock) {
            execution.waitingForPreDispatch = false
            execution.preDispatchTask.also { execution.preDispatchTask = null }
        }
        runCatching { task?.cancel() }
    }

    private fun finishPreDispatchCancellation(execution: ActiveExecution, reason: String) {
        if (execution.visualizationShown) {
            deliverTerminalVisual(execution, GestureExecutionStatus.CANCELLED, reason)
        }
        release(execution)
        notify(execution.listener, GestureExecutionResult(
            execution.command.id, GestureExecutionStatus.CANCELLED, reason
        ))
    }

    /**
     * Cancels a matching active or queued command. A preemption immediately revokes future
     * business steps. Once Android has accepted a stroke,
     * however, the business terminal is delayed until the platform callback. A bounded watchdog
     * may end business waiting when the callback is missing, but it never proves that the platform
     * stroke drained and therefore never releases the serial slot.
     */
    override fun cancelActive(commandId: String?, reason: String): Boolean {
        var queuedCancellation: ActiveExecution? = null
        val cancellation = synchronized(lock) {
            val active = activeExecution?.takeIf {
                commandId.isNullOrBlank() || it.command.id == commandId
            }
            if (active == null && !commandId.isNullOrBlank()) {
                val iterator = pendingExecutions.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (candidate.command.id == commandId) {
                        iterator.remove()
                        queuedCancellation = candidate
                        break
                    }
                }
                return@synchronized null
            }
            if (active == null || active.resultDelivered.get() || active.cancellationRequestedReason != null) {
                return@synchronized null
            }
            if (active.dispatchStarted && !active.platformSettled) {
                active.cancellationRequestedReason = reason.ifBlank { "preempted_by_user" }
                ActiveCancellation(active, deferUntilPlatformDrain = true, deferUntilVisualizationReturns = false)
            } else {
                active.cancellationRequestedReason = reason.ifBlank { "preempted_before_platform_dispatch" }
                if (!active.resultDelivered.compareAndSet(false, true)) return@synchronized null
                active.platformSettled = true
                ActiveCancellation(
                    active, deferUntilPlatformDrain = false,
                    deferUntilVisualizationReturns = active.visualizationInProgress
                )
            }
        }

        queuedCancellation?.let { queued ->
            val terminalReason = "cancelled_while_queued:${reason.ifBlank { "preempted_by_user" }}"
            diagnostic("排队手势已取消：id=${queued.command.id}；reason=$terminalReason")
            notify(queued.listener, GestureExecutionResult(
                queued.command.id, GestureExecutionStatus.CANCELLED, terminalReason
            ))
            return true
        }

        val decision = cancellation ?: run {
            diagnostic(
                "手势取消未执行：没有匹配的活动或排队命令、命令已进入终态或取消已登记；" +
                    "目标=${commandId ?: "ANY"}；原因=$reason"
            )
            return false
        }
        val execution = decision.execution
        if (decision.deferUntilPlatformDrain) {
            diagnostic(
                "活动手势已登记取消请求：id=${execution.command.id}；原因=$reason；" +
                    "平台手势仍在执行；暂不向业务交付终态；保留串行槽直至平台终态回调"
            )
            scheduleDrainWatchdog(execution)
        } else if (decision.deferUntilVisualizationReturns) {
            cancelScheduledPreDispatch(execution)
            diagnostic(
                "活动手势在可视化进行期间收到取消：id=${execution.command.id}；" +
                    "等待当前可视化调用返回后再释放 FIFO 槽，避免两个轨迹同时更新"
            )
        } else {
            cancelScheduledPreDispatch(execution)
            val terminalReason = execution.cancellationRequestedReason.orEmpty()
            if (execution.visualizationShown) {
                deliverTerminalVisual(execution, GestureExecutionStatus.CANCELLED, terminalReason)
            }
            diagnostic(
                "活动手势已在Android提交前逻辑取消：id=${execution.command.id}；原因=$reason；" +
                    "未派发手势；可安全释放串行锁"
            )
            release(execution)
            notify(execution.listener, GestureExecutionResult(
                execution.command.id, GestureExecutionStatus.CANCELLED, terminalReason
            ))
        }
        return true
    }

    /**
     * Reconciles an active command when the service observes a new page/window generation.
     * Ordinary gestures are invalidated; explicit navigation gestures retain their completion
     * signal and must be followed by destination-page verification. Any dispatched stroke keeps
     * owning the serial slot until Android reports its terminal callback.
     */
    fun onPageTransition(newPageGeneration: Long, reason: String = "page_or_window_transition") {
        val staleQueued = synchronized(lock) {
            val stale = mutableListOf<ActiveExecution>()
            val iterator = pendingExecutions.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.pageGeneration != newPageGeneration) {
                    iterator.remove()
                    stale += queued
                }
            }
            stale
        }
        staleQueued.forEach { queued ->
            val terminalReason = "$reason;queued_command_invalidated;" +
                "submittedPageGeneration=${queued.pageGeneration};currentPageGeneration=$newPageGeneration"
            diagnostic("页面代次变化：已清理过期排队手势；id=${queued.command.id}；$terminalReason")
            notify(queued.listener, GestureExecutionResult(
                queued.command.id, GestureExecutionStatus.CANCELLED, terminalReason
            ))
        }

        val invalidation = synchronized(lock) {
            val active = activeExecution?.takeIf {
                it.command.executionContext.targetWindow.generation != newPageGeneration &&
                    !it.platformSettled &&
                    !it.resultDelivered.get()
            } ?: return@synchronized null
            if (active.cancellationRequestedReason != null) return@synchronized null

            val feedbackIntent = active.command.gesture.feedbackIntent
            val expectsNavigation = feedbackIntent == GestureFeedbackIntent.NAVIGATION_ENTRY ||
                feedbackIntent == GestureFeedbackIntent.NAVIGATION_TAB
            if (active.dispatchStarted && expectsNavigation) {
                active.expectedPageTransitionObserved = true
                active to PageTransitionDisposition.EXPECTED_NAVIGATION
            } else {
                val cancellationReason = "$reason;submittedPageGeneration=${active.pageGeneration};" +
                    "currentPageGeneration=$newPageGeneration"
                active.cancellationRequestedReason = cancellationReason
                if (active.dispatchStarted) {
                    active to PageTransitionDisposition.CANCEL_AFTER_PLATFORM_DRAIN
                } else {
                    active.cancellationRequestedReason = cancellationReason
                    active.platformSettled = true
                    active.resultDelivered.compareAndSet(false, true)
                    active to if (active.visualizationInProgress) {
                        PageTransitionDisposition.DEFER_CANCEL_UNTIL_VISUALIZATION_RETURNS
                    } else {
                        PageTransitionDisposition.CANCEL_BEFORE_PLATFORM_DISPATCH
                    }
                }
            }
        }

        if (invalidation == null) return
        val (execution, disposition) = invalidation
        diagnostic(
            "页面代次变化处理：id=${execution.command.id}；" +
                "submittedPageGeneration=${execution.pageGeneration}；currentPageGeneration=$newPageGeneration；" +
                "disposition=$disposition；feedbackIntent=${execution.command.gesture.feedbackIntent}"
        )
        when (disposition) {
            PageTransitionDisposition.EXPECTED_NAVIGATION -> scheduleDrainWatchdog(execution)
            PageTransitionDisposition.CANCEL_AFTER_PLATFORM_DRAIN -> scheduleDrainWatchdog(execution)
            PageTransitionDisposition.DEFER_CANCEL_UNTIL_VISUALIZATION_RETURNS -> {
                diagnostic("页面切换发生在可视化过程中：等待当前 show() 返回后再推进 FIFO；id=${execution.command.id}")
            }
            PageTransitionDisposition.CANCEL_BEFORE_PLATFORM_DISPATCH -> {
                cancelScheduledPreDispatch(execution)
                finishPreDispatchCancellation(
                    execution, execution.cancellationRequestedReason.orEmpty()
                )
            }
        }
    }

    private fun settleFromPlatform(
        execution: ActiveExecution,
        status: GestureExecutionStatus,
        reason: String = "",
        notifyListener: Boolean = true
    ) {
        val settlement = synchronized(lock) {
            if (execution.platformSettled) {
                null
            } else {
                execution.platformSettled = true
                val watchdog = execution.drainWatchdog.also { execution.drainWatchdog = null }
                val shouldDeliver = execution.resultDelivered.compareAndSet(false, true)
                val allowsPageTransition = execution.expectedPageTransitionObserved ||
                    execution.command.gesture.feedbackIntent == GestureFeedbackIntent.NAVIGATION_ENTRY ||
                    execution.command.gesture.feedbackIntent == GestureFeedbackIntent.NAVIGATION_TAB
                val cancellationReason = execution.cancellationRequestedReason
                    ?: executionContextFailure(
                        execution.command.executionContext,
                        allowExpectedWindowTransition = allowsPageTransition
                    )?.let { "execution_context_changed_before_platform_callback:$it" }
                val terminalStatus = if (cancellationReason != null) {
                    GestureExecutionStatus.CANCELLED
                } else status
                val terminalReason = if (cancellationReason != null) {
                    "cancellation_requested=$cancellationReason;platform_result=$status;" +
                        "platform_reason=${reason.ifBlank { "none" }}"
                } else reason
                PlatformSettlement(
                    watchdog = watchdog,
                    shouldDeliver = shouldDeliver,
                    terminalStatus = terminalStatus,
                    terminalReason = terminalReason,
                    callbackAfterDrainTimeout = execution.drainTimedOut,
                    releaseSerialSlot = !execution.drainTimedOut || execution.timeoutTerminalDeliveryComplete
                )
            }
        }
        if (settlement == null) {
            diagnostic(
                "已忽略重复或已结算后的手势平台回调：id=${execution.command.id}；" +
                    "平台状态=$status；平台串行槽状态已结算"
            )
            return
        }
        settlement.watchdog?.cancel()
        if (settlement.shouldDeliver) {
            diagnostic(
                "手势平台终态已确认：id=${execution.command.id}；platformResult=$status；" +
                    "businessResult=${settlement.terminalStatus}；" +
                    "businessReason=${settlement.terminalReason.ifBlank { "none" }}；" +
                    "callbackAfterDrainTimeout=${settlement.callbackAfterDrainTimeout}"
            )
            deliverTerminalVisual(execution, settlement.terminalStatus, settlement.terminalReason)
        } else if (settlement.callbackAfterDrainTimeout) {
            diagnostic(
                "手势平台迟到终态已确认：id=${execution.command.id}；platformResult=$status；" +
                    "业务终态此前已按超时交付，不重复通知"
            )
        }
        // The platform callback is the only proof that the old stroke has drained. Keep the
        // visualizer's terminal update inside the serial slot, then release before invoking
        // feature code so a listener can safely submit the next gesture synchronously.
        if (settlement.releaseSerialSlot) {
            release(execution)
        } else {
            diagnostic(
                "平台回调在看门狗业务终态交付期间到达：id=${execution.command.id}；" +
                    "等待业务终态交付完成后释放串行槽"
            )
        }
        if (settlement.shouldDeliver && notifyListener) {
            notify(execution.listener, GestureExecutionResult(
                execution.command.id,
                settlement.terminalStatus,
                settlement.terminalReason
            ))
        }
    }

    private enum class PageTransitionDisposition {
        EXPECTED_NAVIGATION,
        CANCEL_AFTER_PLATFORM_DRAIN,
        DEFER_CANCEL_UNTIL_VISUALIZATION_RETURNS,
        CANCEL_BEFORE_PLATFORM_DISPATCH
    }

    private data class ActiveCancellation(
        val execution: ActiveExecution,
        val deferUntilPlatformDrain: Boolean,
        val deferUntilVisualizationReturns: Boolean
    )

    private data class DeferredSettlement(
        val watchdog: ScheduledTask?,
        val cancellationReason: String?,
        val ownsTerminal: Boolean
    )

    private data class PlatformSettlement(
        val watchdog: ScheduledTask?,
        val shouldDeliver: Boolean,
        val terminalStatus: GestureExecutionStatus,
        val terminalReason: String,
        val callbackAfterDrainTimeout: Boolean,
        val releaseSerialSlot: Boolean
    )

    private fun deliverTerminalVisual(
        execution: ActiveExecution,
        status: GestureExecutionStatus,
        reason: String = ""
    ) {
        val command = execution.command
        diagnostic(
            "手势结束：id=${command.id}；类型=${command.kind}；状态=$status；" +
                "原因=${reason.ifBlank { "none" }}"
        )
        when (status) {
            GestureExecutionStatus.COMPLETED -> runCatching { visualizer.onCompleted(command) }
            GestureExecutionStatus.CANCELLED -> runCatching { visualizer.onCancelled(command) }
            GestureExecutionStatus.REJECTED, GestureExecutionStatus.DEFERRED ->
                runCatching { visualizer.onRejected(command, reason) }
            GestureExecutionStatus.FAILED -> runCatching { visualizer.onFailed(command, reason) }
        }
    }

    private fun scheduleDrainWatchdog(execution: ActiveExecution) {
        val taskScheduler = scheduler ?: return
        val shouldSchedule = synchronized(lock) {
            activeExecution === execution &&
                !execution.platformSettled &&
                !execution.resultDelivered.get() &&
                execution.drainWatchdog == null &&
                !execution.drainTimedOut
        }
        if (!shouldSchedule) return

        val watchdog = runCatching {
            taskScheduler.schedule(CANCELLED_DISPATCH_DRAIN_TIMEOUT_MS) {
                val timeoutSettlement = synchronized(lock) {
                    if (execution.platformSettled || execution.resultDelivered.get()) null else {
                        // A timeout tells us only that Android did not call back in time. It is
                        // not evidence that the gesture stopped, so keep activeExecution reserved.
                        execution.drainTimedOut = true
                        execution.drainWatchdog = null
                        val shouldDeliver = execution.resultDelivered.compareAndSet(false, true)
                        execution.cancellationRequestedReason to shouldDeliver
                    }
                }
                if (timeoutSettlement != null) {
                    val (cancellationReason, shouldDeliver) = timeoutSettlement
                    diagnostic(
                        "活动手势平台回调超时：id=${execution.command.id}；platformResult=UNKNOWN；" +
                            "cancellationRequested=${cancellationReason != null}；串行槽保持隔离，" +
                            "等待迟到的平台终态回调；禁止提交新手势"
                    )
                    if (shouldDeliver) {
                        val terminalReason = "platform_callback_timeout;platform_result=UNKNOWN;" +
                            "cancellation_requested=${cancellationReason ?: "none"}"
                        deliverTerminalVisual(execution, GestureExecutionStatus.CANCELLED, terminalReason)
                        notify(execution.listener, GestureExecutionResult(
                            execution.command.id,
                            GestureExecutionStatus.CANCELLED,
                            terminalReason
                        ))
                    }
                    val releaseAfterDelivery = synchronized(lock) {
                        execution.timeoutTerminalDeliveryComplete = true
                        execution.platformSettled
                    }
                    if (releaseAfterDelivery) release(execution)
                }
            }
        }.getOrNull() ?: run {
            diagnostic(
                "活动手势看门狗无法建立：id=${execution.command.id}；串行槽保持隔离直至平台回调"
            )
            return
        }

        val cancelImmediately = synchronized(lock) {
            if (execution.platformSettled || execution.resultDelivered.get() || execution.drainTimedOut) {
                true
            } else {
                execution.drainWatchdog = watchdog
                false
            }
        }
        if (cancelImmediately) watchdog.cancel()
    }

    private fun readCurrentPageGeneration(): Long =
        runCatching(currentPageGeneration).getOrDefault(0L)

    private fun activeCommandSnapshot(): String? = synchronized(lock) { activeExecution?.command?.id }

    private fun pendingCount(): Int = synchronized(lock) { pendingExecutions.size }

    private fun queuePosition(commandId: String): Int = synchronized(lock) {
        pendingExecutions.indexOfFirst { it.command.id == commandId }.let { if (it < 0) 0 else it + 1 }
    }

    private fun diagnostic(message: String) {
        runCatching { onDiagnostic(message) }
    }

    private fun release(execution: ActiveExecution) {
        val next = synchronized(lock) {
            if (activeExecution !== execution) return
            if (pendingExecutions.isEmpty()) {
                activeExecution = null
                null
            } else {
                pendingExecutions.removeFirst().also { nextExecution ->
                    nextExecution.wasQueued = true
                    activeExecution = nextExecution
                }
            }
        }
        if (next != null) {
            diagnostic(
                "FIFO 队列推进：previous=${execution.command.id}；next=${next.command.id}；" +
                    "remaining=${pendingCount()}"
            )
            startExecution(next)
        }
    }

    private fun validate(command: GestureCommand): String? =
        executionContextFailure(command.executionContext) ?: policyValidator.validate(command)

    private fun executionContextFailure(
        context: GestureExecutionContext,
        allowExpectedWindowTransition: Boolean = false
    ): String? = when {
        context.runGeneration != runCatching(currentRunGeneration).getOrDefault(-1L) ->
            "run_generation_changed"
        context.taskLease?.let { lease ->
            lease.runGeneration != context.runGeneration ||
                runCatching { taskCoordinator?.isCurrent(lease) }.getOrNull() != true
        } == true ->
            "task_lease_changed"
        context.taskLease == null &&
            runCatching { taskCoordinator?.currentLease() != null }.getOrDefault(true) ->
            "task_lease_missing"
        runCatching(currentCoordinateContext).getOrNull() != context.coordinateContext ->
            "coordinate_context_changed"
        !allowExpectedWindowTransition &&
            !runCatching { isTargetWindowCurrent(context.targetWindow) }.getOrDefault(false) ->
            "target_window_changed"
        else -> null
    }

    private fun notify(listener: GestureExecutionListener, result: GestureExecutionResult) {
        runCatching { listener.onResult(result) }
    }

    private companion object {
        const val CANCELLED_DISPATCH_DRAIN_TIMEOUT_MS = 1_500L
        const val DEFAULT_MAX_PENDING_COMMANDS = 16
        const val MAX_PENDING_COMMANDS = 128
    }
}
