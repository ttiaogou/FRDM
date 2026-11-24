package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsBackResult
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.conversation.domain.contact.ContactRowIdentity

/**
 * 收到招呼业务流程控制器。
 *
 * 本类统一拥有收到招呼任务的生命周期状态，并把页面快照/适配器单步结果转换为业务决策。
 * 它不采集 Android 页面快照、不安排 Runtime 定时器，也不实现陌陌页面指纹或遍历算法；
 * 这些能力仍由页面观察层和 ChatPagePort 适配器提供。
 */
internal class ReceivedGreetingsTaskFlowController(
    ownership: AiReplyTaskOwnership,
    pagePort: ChatPagePort
) {
    enum class StartResult { STARTED, NOT_SUPPORTED, OWNERSHIP_REJECTED, START_FAILED }
    enum class DecisionKind {
        ENTRY_NOT_FOUND,
        COMPLETE,
        WAIT_FOR_DETAIL_PAGE,
        DETAIL_READY,
        RETRY_REQUIRED,
        LIST_CONTINUES,
        PROFILE_CARD_PENDING,
        ENTRY_NOT_FOUND_RECHECK,
        PAGE_TRANSITION_PENDING
    }

    enum class DecisionOrigin {
        PAGE_PROGRESS,
        ADAPTER_STEP
    }

    data class Decision(
        val kind: DecisionKind,
        val status: ReceivedGreetingsTraversalStatus,
        val origin: DecisionOrigin
    )

    private val lifecycle = ReceivedGreetingsTaskController(ownership)
    private val executor = ReceivedGreetingsTaskExecutor(pagePort) { lifecycle.ownsTask() }

    val isActive: Boolean
        get() = lifecycle.isActive
    val awaitingNormalInboxAfterReturn: Boolean
        get() = lifecycle.awaitingNormalInboxAfterReturn
    val returnRequested: Boolean
        get() = lifecycle.returnRequested

    fun claimOwnership(reason: String): Boolean = lifecycle.claimOwnership(reason)

    fun ownsTask(): Boolean = lifecycle.ownsTask()

    fun releaseOwnership(reason: String): Boolean = lifecycle.releaseOwnership(reason)

    fun reset() = lifecycle.reset()

    fun supportsWorkflow(): Boolean = executor.supportsWorkflow()

    fun workflowCapabilities(): ReceivedGreetingsTaskExecutor.WorkflowCapabilities =
        executor.workflowCapabilities()

    fun observeManuallyOpenedPage(): ReceivedGreetingsPageArrivalStatus =
        executor.observeManuallyOpenedPage()

    fun observePageReadOnly(): Boolean = executor.observePageReadOnly()

    fun traversalStatus(): ReceivedGreetingsTraversalStatus = executor.traversalStatus()

    fun isWaitingForSafeTargetEvidence(): Boolean = executor.isWaitingForSafeTargetEvidence()

    fun currentDisplayName(): String? = executor.currentDisplayName()

    fun currentRowKeyHash(): String? = executor.currentRowKeyHash()

    fun currentContactIdentity(): ContactRowIdentity? = executor.currentContactIdentity()

    /**
     * Starts the existing adapter-owned navigation flow. The adapter reset remains bounded and
     * best-effort exactly as before; lifecycle ownership moves to this controller.
     */
    fun beginNavigationTask(): StartResult {
        if (lifecycle.isActive) {
            return if (lifecycle.ownsTask()) StartResult.STARTED else StartResult.START_FAILED
        }
        return when (executor.beginNavigationTask()) {
        ReceivedGreetingsTaskExecutor.StartResult.STARTED -> {
            lifecycle.markStarted()
            StartResult.STARTED
        }
        ReceivedGreetingsTaskExecutor.StartResult.NOT_SUPPORTED -> StartResult.NOT_SUPPORTED
        ReceivedGreetingsTaskExecutor.StartResult.START_FAILED -> StartResult.START_FAILED
        }
    }

    /** Begins only from a page already confirmed by the dedicated page recognizer. */
    fun beginFromConfirmedList(): StartResult {
        if (lifecycle.isActive) {
            return if (lifecycle.ownsTask()) StartResult.STARTED else StartResult.START_FAILED
        }
        return when (executor.beginFromConfirmedList()) {
        ReceivedGreetingsTaskExecutor.StartResult.STARTED -> {
            lifecycle.markStarted()
            StartResult.STARTED
        }
        ReceivedGreetingsTaskExecutor.StartResult.NOT_SUPPORTED -> StartResult.NOT_SUPPORTED
        ReceivedGreetingsTaskExecutor.StartResult.START_FAILED -> StartResult.START_FAILED
        }
    }

    fun synchronizeRecoveryConfirmation(): Boolean = executor.synchronizeRecoveryConfirmation()

    fun markStarted() = lifecycle.markStarted()

    fun performInitialIdleStep(): Boolean = executor.performIdleStep()

    fun returnForPriorityCheck(): ReceivedGreetingsBackResult =
        if (lifecycle.inboxConfirmed) ReceivedGreetingsBackResult.WAITING_FOR_PAGE_CONFIRMATION
        else executor.returnForPriorityCheck()

    fun isCompletionReturnIntermediate(): Boolean =
        lifecycle.awaitingNormalInboxAfterReturn && lifecycle.returnRequested &&
            executor.isCompletionReturnIntermediate()

    fun confirmCompletionReturn(): Boolean {
        if (!lifecycle.awaitingNormalInboxAfterReturn || !lifecycle.ownsTask()) return false
        if (lifecycle.inboxConfirmed) return true
        if (!executor.confirmCompletionReturn()) return false
        lifecycle.markInboxConfirmed()
        return true
    }

    fun completeDetailInspection(success: Boolean): Boolean =
        executor.completeDetailInspection(success)

    fun deferAfterModelFailure(): Boolean = executor.deferAfterModelFailure()

    fun markTraversalCompleted() = lifecycle.markTraversalCompleted()

    fun markReturnRequested() = lifecycle.markReturnRequested()

    fun markTraversalReturnedToInbox() = lifecycle.markTraversalReturnedToInbox()

    fun pauseForBusinessSwitch() = lifecycle.pauseForBusinessSwitch()

    /**
     * Converts a fresh classified page snapshot into one stable task decision. A classified
     * ordinary inbox that still reports ENTRY_NOT_FOUND is treated as the confirmed missing-entry
     * condition, matching the existing admission branch.
     */
    fun evaluatePageSnapshot(pageKind: ChatPageKind): Decision {
        if (lifecycle.awaitingNormalInboxAfterReturn) {
            return Decision(DecisionKind.COMPLETE, ReceivedGreetingsTraversalStatus.COMPLETE, DecisionOrigin.PAGE_PROGRESS)
        }
        val progress = executor.evaluatePageProgress(pageKind)
        return when (progress.decision) {
            ReceivedGreetingsTaskExecutor.PageDecision.ENTRY_NOT_FOUND ->
                Decision(DecisionKind.ENTRY_NOT_FOUND, progress.status, DecisionOrigin.PAGE_PROGRESS)
            ReceivedGreetingsTaskExecutor.PageDecision.COMPLETE ->
                Decision(DecisionKind.COMPLETE, progress.status, DecisionOrigin.PAGE_PROGRESS)
            ReceivedGreetingsTaskExecutor.PageDecision.WAIT_FOR_DETAIL_PAGE ->
                Decision(DecisionKind.WAIT_FOR_DETAIL_PAGE, progress.status, DecisionOrigin.PAGE_PROGRESS)
            ReceivedGreetingsTaskExecutor.PageDecision.DETAIL_READY ->
                Decision(DecisionKind.DETAIL_READY, progress.status, DecisionOrigin.PAGE_PROGRESS)
            ReceivedGreetingsTaskExecutor.PageDecision.ADVANCE_REQUIRED ->
                evaluateAdapterStep(classifiedPage = true)
        }
    }

    /** Unknown/non-chat snapshots never authorize a business step; the coordinator must recover. */
    fun evaluateUnclassifiedPageSnapshot(): Decision =
        Decision(DecisionKind.RETRY_REQUIRED, executor.traversalStatus(), DecisionOrigin.PAGE_PROGRESS)

    private fun evaluateAdapterStep(classifiedPage: Boolean): Decision {
        val observation = executor.performIdleStepAndInspect()
        if (!observation.accepted) {
            if (classifiedPage &&
                observation.status == ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
            ) {
                return Decision(DecisionKind.ENTRY_NOT_FOUND, observation.status, DecisionOrigin.ADAPTER_STEP)
            }
            return Decision(DecisionKind.RETRY_REQUIRED, observation.status, DecisionOrigin.ADAPTER_STEP)
        }

        val kind = when (observation.decision) {
            ReceivedGreetingsTaskExecutor.StepDecision.RETRY_REQUIRED -> DecisionKind.RETRY_REQUIRED
            ReceivedGreetingsTaskExecutor.StepDecision.ENTRY_NOT_FOUND_RECHECK ->
                if (classifiedPage) DecisionKind.ENTRY_NOT_FOUND_RECHECK else DecisionKind.RETRY_REQUIRED
            ReceivedGreetingsTaskExecutor.StepDecision.COMPLETE -> DecisionKind.COMPLETE
            ReceivedGreetingsTaskExecutor.StepDecision.LIST_CONTINUES -> DecisionKind.LIST_CONTINUES
            ReceivedGreetingsTaskExecutor.StepDecision.PROFILE_CARD_PENDING ->
                if (classifiedPage) DecisionKind.PROFILE_CARD_PENDING else DecisionKind.RETRY_REQUIRED
            ReceivedGreetingsTaskExecutor.StepDecision.PAGE_TRANSITION_PENDING ->
                if (classifiedPage) DecisionKind.PAGE_TRANSITION_PENDING else DecisionKind.RETRY_REQUIRED
        }
        return Decision(kind, observation.status, DecisionOrigin.ADAPTER_STEP)
    }
}
