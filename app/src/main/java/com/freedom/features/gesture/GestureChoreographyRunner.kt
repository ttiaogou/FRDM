package com.freedom.features.gesture

import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureTimeDistributionKind
import com.freedom.core.accessibility.gesture.GestureTimeDistributionPolicy
import com.freedom.core.accessibility.gesture.GestureTimeDistributionProfile
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.gesture.api.GestureApi
import kotlin.random.Random

/** One semantic request in a sequence; each request is independently validated by GestureApi. */
data class GestureChoreographyStep(
    /** Static request. Use null when requestFactory creates a fresh request for each stage. */
    val request: GestureIntentRequest? = null,
    /** Nominal inter-stage pause. Without an explicit distribution, a truncated normal centred on
     * this value is generated (±30%). */
    val pauseAfterMs: Long = 0L,
    val pauseAfterDistribution: GestureTimeDistributionProfile? = null,
    /** Upper bound includes request preparation, context checks, FIFO wait and platform completion. */
    val maximumStageDurationMs: Long = 30_000L,
    /** Domain-specific stage guard, evaluated immediately before submission. */
    val precondition: (() -> Boolean)? = null,
    /** Domain-specific state confirmation, evaluated after the gesture completion callback. */
    val postcondition: (() -> Boolean)? = null,
    /**
     * Optional just-in-time request builder. It is called when the stage starts, allowing the
     * business owner to re-read the current page and create a request with fresh bounds/context.
     * Exactly one of request or requestFactory must be supplied.
     */
    val requestFactory: (() -> GestureIntentRequest?)? = null
)

data class GestureChoreographyResult(
    val choreographyId: String,
    val status: GestureExecutionStatus,
    val completedSteps: Int,
    val reason: String = ""
)

fun interface GestureChoreographyListener {
    fun onResult(result: GestureChoreographyResult)
}

fun interface GestureChoreographyHandle {
    fun cancel(reason: String): Boolean
}

/**
 * Runs ordered requests through the single public GestureApi. Each stage can build its request
 * just-in-time from fresh page evidence, revalidates window/display context, has a time budget,
 * and can expose domain pre/postconditions. Inter-stage waits use the shared bounded distribution.
 * This class never dispatches Android input.
 *
 * Other GestureApi clients may submit work between steps. Each step joins the global FIFO queue;
 * the entire choreography is deliberately not an atomic queue reservation.
 */
class GestureChoreographyRunner(
    private val gestureApi: GestureApi,
    private val scheduler: TaskScheduler,
    private val maximumSteps: Int = 32,
    private val maximumPauseMs: Long = 5_000L,
    private val maximumStageDurationMs: Long = 120_000L,
    private val random: Random = Random.Default
) {
    init {
        require(maximumSteps in 1..128)
        require(maximumPauseMs in 0L..60_000L)
        require(maximumStageDurationMs in 1L..300_000L)
    }

    fun execute(
        choreographyId: String,
        steps: List<GestureChoreographyStep>,
        listener: GestureChoreographyListener
    ): GestureChoreographyHandle? {
        if (choreographyId.isBlank() || steps.isEmpty() || steps.size > maximumSteps ||
            steps.any { step ->
                (step.request == null) == (step.requestFactory == null) ||
                    step.request?.id?.isBlank() == true ||
                    step.pauseAfterMs !in 0L..maximumPauseMs ||
                    step.maximumStageDurationMs !in 1L..maximumStageDurationMs ||
                    step.pauseAfterDistribution?.let {
                        !it.isValid() || it.maximumMs > maximumPauseMs
                    } == true
            } || steps.mapNotNull { it.request?.id }.distinct().size != steps.count { it.request != null }
        ) return null
        val execution = Execution(choreographyId, steps.toList(), listener)
        execution.start()
        return GestureChoreographyHandle(execution::cancel)
    }

    private inner class Execution(
        private val id: String,
        private val steps: List<GestureChoreographyStep>,
        private val listener: GestureChoreographyListener
    ) {
        private val lock = Any()
        private var index = 0
        private var activeStepIndex: Int? = null
        private var currentRequestId: String? = null
        private val usedRequestIds = mutableSetOf<String>()
        private var pauseTask: ScheduledTask? = null
        private var stageTimeoutTask: ScheduledTask? = null
        private var submissionInProgressId: String? = null
        private var cancelledDuringSubmissionId: String? = null
        private var finished = false

        fun start() = dispatchNext()

        fun cancel(reason: String): Boolean {
            val state = synchronized(lock) {
                if (finished) return false
                finished = true
                val activeId = currentRequestId
                val deferCancellationUntilSubmissionReturns =
                    activeId != null && submissionInProgressId == activeId
                if (deferCancellationUntilSubmissionReturns) cancelledDuringSubmissionId = activeId
                currentRequestId = null
                val pause = pauseTask.also { pauseTask = null }
                val timeout = stageTimeoutTask.also { stageTimeoutTask = null }
                Triple(activeId to deferCancellationUntilSubmissionReturns, pause, timeout)
            }
            state.second?.cancel()
            state.third?.cancel()
            val (activeId, deferred) = state.first
            val wasCancelled = if (activeId == null || deferred) true
                else gestureApi.cancelActive(activeId, reason)
            notify(GestureExecutionStatus.CANCELLED, reason)
            return wasCancelled
        }

        private fun dispatchNext() {
            val stepIndex = synchronized(lock) {
                if (finished) return
                if (index >= steps.size) {
                    finished = true
                    -1
                } else index.also { activeStepIndex = it }
            }
            if (stepIndex < 0) {
                notify(GestureExecutionStatus.COMPLETED, "")
                return
            }
            val step = steps[stepIndex]
            val timeout = runCatching {
                scheduler.schedule(step.maximumStageDurationMs) {
                    val cancellation = synchronized(lock) {
                        if (finished || activeStepIndex != stepIndex) return@synchronized null
                        val requestId = currentRequestId
                        val deferUntilSubmissionReturns =
                            requestId != null && submissionInProgressId == requestId
                        if (deferUntilSubmissionReturns) cancelledDuringSubmissionId = requestId
                        requestId to !deferUntilSubmissionReturns
                    }
                    finish(GestureExecutionStatus.FAILED, "stage_${stepIndex}_timeout")
                    if (cancellation?.second == true) {
                        cancellation.first?.let { gestureApi.cancelActive(it, "choreography_stage_timeout") }
                    }
                }
            }.getOrElse {
                finish(GestureExecutionStatus.FAILED, "stage_timeout_schedule_failed")
                return
            }
            val cancelTimeoutImmediately = synchronized(lock) {
                if (finished || activeStepIndex != stepIndex) true else {
                    stageTimeoutTask = timeout
                    false
                }
            }
            if (cancelTimeoutImmediately) {
                timeout.cancel()
                return
            }

            val request = try {
                step.requestFactory?.invoke() ?: step.request
            } catch (error: Throwable) {
                finish(GestureExecutionStatus.FAILED, "stage_${stepIndex}_request_factory_failed:${error.javaClass.simpleName}")
                return
            }
            if (request == null || request.id.isBlank()) {
                finish(GestureExecutionStatus.REJECTED, "stage_${stepIndex}_request_unavailable")
                return
            }
            val requestAccepted = synchronized(lock) {
                if (finished || activeStepIndex != stepIndex) return@synchronized false
                if (!usedRequestIds.add(request.id)) return@synchronized false
                currentRequestId = request.id
                true
            }
            if (!requestAccepted) {
                val alreadyFinished = synchronized(lock) { finished }
                if (!alreadyFinished) finish(GestureExecutionStatus.REJECTED, "duplicate_or_invalid_request_id")
                return
            }

            val expected = request.executionContext
            val currentContext = runCatching {
                gestureApi.captureContext(expected.targetWindow, expected.coordinateContext)
            }.getOrNull()
            if (currentContext == null || currentContext.runGeneration != expected.runGeneration ||
                currentContext.targetWindow != expected.targetWindow ||
                currentContext.coordinateContext != expected.coordinateContext
            ) {
                finish(GestureExecutionStatus.DEFERRED, "stage_${stepIndex}_context_stale")
                return
            }
            val preconditionPassed = runCatching { step.precondition?.invoke() ?: true }
                .getOrDefault(false)
            if (!preconditionPassed) {
                finish(GestureExecutionStatus.DEFERRED, "stage_${stepIndex}_precondition_failed")
                return
            }

            synchronized(lock) {
                if (finished || activeStepIndex != stepIndex || currentRequestId != request.id) {
                    timeout.cancel()
                    return
                }
                submissionInProgressId = request.id
            }
            val submission = runCatching {
                gestureApi.execute(request, GestureExecutionListener(::onStepResult))
            }.getOrElse {
                synchronized(lock) {
                    if (submissionInProgressId == request.id) submissionInProgressId = null
                }
                finish(GestureExecutionStatus.FAILED, "gesture_api_exception:${it.javaClass.simpleName}")
                return
            }
            // Cancellation can race with execute(); reapply it once the request is known to Runtime.
            val cancelledDuringSubmission = synchronized(lock) {
                if (submissionInProgressId == request.id) submissionInProgressId = null
                (cancelledDuringSubmissionId == request.id).also {
                    if (it) cancelledDuringSubmissionId = null
                }
            }
            when (submission) {
                GestureSubmission.ACCEPTED -> if (cancelledDuringSubmission) {
                    gestureApi.cancelActive(request.id, "choreography_cancelled_during_submission")
                }
                GestureSubmission.DEFERRED_NOT_READY -> finish(
                    GestureExecutionStatus.DEFERRED, "gesture_api_deferred_not_ready"
                )
                GestureSubmission.REJECTED -> finish(
                    GestureExecutionStatus.REJECTED, "gesture_api_rejected_step_$stepIndex"
                )
            }
        }

        private fun onStepResult(result: GestureExecutionResult) {
            val completedStepIndex: Int
            val timeout: ScheduledTask?
            synchronized(lock) {
                if (finished || currentRequestId != result.commandId) return
                currentRequestId = null
                activeStepIndex = null
                timeout = stageTimeoutTask.also { stageTimeoutTask = null }
                completedStepIndex = index
            }
            timeout?.cancel()
            if (result.status != GestureExecutionStatus.COMPLETED) {
                finish(result.status, "step_${completedStepIndex}_failed:${result.reason}")
                return
            }
            val postconditionPassed = runCatching {
                steps[completedStepIndex].postcondition?.invoke() ?: true
            }.getOrDefault(false)
            if (!postconditionPassed) {
                synchronized(lock) { index = completedStepIndex + 1 }
                finish(GestureExecutionStatus.DEFERRED, "stage_${completedStepIndex}_postcondition_failed")
                return
            }
            val step = steps[completedStepIndex]
            synchronized(lock) { index = completedStepIndex + 1 }
            val pause = sampleInterStagePause(step)
            if (pause == null) {
                finish(GestureExecutionStatus.FAILED, "stage_${completedStepIndex}_pause_distribution_failed")
                return
            }
            if (index >= steps.size) {
                dispatchNext()
                return
            }
            // A test or adapter may report completion synchronously from execute(). Do not
            // re-enter GestureApi while the previous submission call is still on the stack.
            val waitMs = if (pause > 0L) pause else {
                if (synchronized(lock) { submissionInProgressId != null }) 0L else -1L
            }
            if (waitMs < 0L) {
                dispatchNext()
                return
            }
            val scheduled = runCatching {
                scheduler.schedule(waitMs) {
                    synchronized(lock) { pauseTask = null }
                    dispatchNext()
                }
            }.getOrElse {
                finish(GestureExecutionStatus.FAILED, "choreography_pause_schedule_failed")
                return
            }
            val cancelImmediately = synchronized(lock) {
                if (finished) true else {
                    pauseTask = scheduled
                    false
                }
            }
            if (cancelImmediately) scheduled.cancel()
        }

        private fun sampleInterStagePause(step: GestureChoreographyStep): Long? {
            val explicit = step.pauseAfterDistribution
            val profile = explicit ?: if (step.pauseAfterMs == 0L) return 0L else {
                val nominal = step.pauseAfterMs.toDouble()
                GestureTimeDistributionProfile(
                    enabled = true,
                    kind = GestureTimeDistributionKind.TRUNCATED_NORMAL,
                    centerMs = nominal,
                    standardDeviationMs = (nominal * 0.12).coerceAtLeast(1.0),
                    minimumMs = (nominal * 0.70).toLong().coerceAtLeast(1L),
                    maximumMs = (nominal * 1.30).toLong().coerceAtMost(maximumPauseMs)
                )
            }
            return GestureTimeDistributionPolicy.sampleMillis(profile, random)
        }

        private fun finish(status: GestureExecutionStatus, reason: String) {
            val tasks = synchronized(lock) {
                if (finished) return
                finished = true
                currentRequestId = null
                activeStepIndex = null
                val pause = pauseTask.also { pauseTask = null }
                val timeout = stageTimeoutTask.also { stageTimeoutTask = null }
                pause to timeout
            }
            tasks.first?.cancel()
            tasks.second?.cancel()
            notify(status, reason)
        }

        private fun notify(status: GestureExecutionStatus, reason: String) {
            val completed = synchronized(lock) { index }
            runCatching { listener.onResult(GestureChoreographyResult(id, status, completed, reason)) }
        }
    }
}
