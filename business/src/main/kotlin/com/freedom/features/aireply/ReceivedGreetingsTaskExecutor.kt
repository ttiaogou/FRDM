package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsBackResult
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.conversation.domain.contact.ContactRowIdentity

/**
 * 收到招呼任务的适配器执行边界。
 *
 * 所有可能推进页面或列表遍历的适配器调用都在这里统一检查当前任务租约；只读状态查询
 * 保持可用。具体重试节律、状态转换和完成后的安全返回调度仍由上层任务协调逻辑负责。
 */
internal class ReceivedGreetingsTaskExecutor(
    private val pagePort: ChatPagePort,
    private val ownsTask: () -> Boolean
) {
    data class WorkflowCapabilities(
        val entry: Boolean,
        val pageRecognition: Boolean,
        val traversal: Boolean
    ) {
        val supported: Boolean
            get() = entry && pageRecognition && traversal
    }

    enum class StartResult { STARTED, NOT_SUPPORTED, START_FAILED }
    enum class PageDecision {
        ENTRY_NOT_FOUND,
        COMPLETE,
        WAIT_FOR_DETAIL_PAGE,
        DETAIL_READY,
        ADVANCE_REQUIRED
    }

    data class PageProgress(
        val status: ReceivedGreetingsTraversalStatus,
        val decision: PageDecision
    )

    enum class StepDecision {
        RETRY_REQUIRED,
        ENTRY_NOT_FOUND_RECHECK,
        COMPLETE,
        LIST_CONTINUES,
        PROFILE_CARD_PENDING,
        PAGE_TRANSITION_PENDING
    }

    data class StepObservation(
        val accepted: Boolean,
        val status: ReceivedGreetingsTraversalStatus,
        val decision: StepDecision
    )

    /** Converts adapter evidence into one application-level traversal decision. */
    fun evaluatePageProgress(pageKind: ChatPageKind): PageProgress {
        val status = traversalStatus()
        val decision = when (status) {
            ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND -> PageDecision.ENTRY_NOT_FOUND
            ReceivedGreetingsTraversalStatus.COMPLETE -> PageDecision.COMPLETE
            ReceivedGreetingsTraversalStatus.DETAIL_READY -> if (
                pageKind == ChatPageKind.CHAT_DETAIL_CANDIDATE
            ) PageDecision.DETAIL_READY else PageDecision.WAIT_FOR_DETAIL_PAGE
            ReceivedGreetingsTraversalStatus.NAVIGATING,
            ReceivedGreetingsTraversalStatus.SCANNING,
            ReceivedGreetingsTraversalStatus.PROFILE_CARD,
            ReceivedGreetingsTraversalStatus.UNKNOWN -> PageDecision.ADVANCE_REQUIRED
        }
        return PageProgress(status, decision)
    }

    /** Performs one bounded adapter step and captures its resulting status exactly once. */
    fun performIdleStepAndInspect(): StepObservation {
        val accepted = performIdleStep()
        val status = traversalStatus()
        val decision = when {
            !accepted -> StepDecision.RETRY_REQUIRED
            status == ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND -> StepDecision.ENTRY_NOT_FOUND_RECHECK
            status == ReceivedGreetingsTraversalStatus.COMPLETE -> StepDecision.COMPLETE
            status == ReceivedGreetingsTraversalStatus.SCANNING -> StepDecision.LIST_CONTINUES
            status == ReceivedGreetingsTraversalStatus.PROFILE_CARD -> StepDecision.PROFILE_CARD_PENDING
            else -> StepDecision.PAGE_TRANSITION_PENDING
        }
        return StepObservation(accepted, status, decision)
    }
    fun workflowCapabilities(): WorkflowCapabilities = WorkflowCapabilities(
        entry = pagePort.supportsReceivedGreetingsEntry(),
        pageRecognition = pagePort.supportsReceivedGreetingsPageRecognition(),
        traversal = pagePort.supportsReceivedGreetingsTraversal()
    )

    fun supportsWorkflow(): Boolean = workflowCapabilities().supported

    fun observeManuallyOpenedPage(): ReceivedGreetingsPageArrivalStatus =
        runCatching { pagePort.observeManuallyOpenedReceivedGreetingsPage() }
            .getOrDefault(ReceivedGreetingsPageArrivalStatus.UNKNOWN)

    fun observePageReadOnly(): Boolean =
        runCatching { pagePort.observeReceivedGreetingsPage() }.getOrDefault(false)

    fun isWaitingForSafeTargetEvidence(): Boolean =
        runCatching { pagePort.isReceivedGreetingsWaitingForSafeTargetEvidence() }.getOrDefault(false)

    fun traversalStatus(): ReceivedGreetingsTraversalStatus =
        runCatching { pagePort.receivedGreetingsTraversalStatus() }
            .getOrDefault(ReceivedGreetingsTraversalStatus.UNKNOWN)

    fun currentDisplayName(): String? = runCatching {
        pagePort.receivedGreetingsCurrentDisplayName()
    }.getOrNull()

    fun currentRowKeyHash(): String? = runCatching {
        pagePort.receivedGreetingsCurrentRowKeyHash()
    }.getOrNull()

    fun currentContactIdentity(): ContactRowIdentity? = runCatching {
        pagePort.receivedGreetingsCurrentContactIdentity()
    }.getOrNull()

    /** Resets adapter-owned search state; the caller retains lifecycle and retry decisions. */
    fun beginNavigationTask(): StartResult {
        if (!workflowCapabilities().supported) return StartResult.NOT_SUPPORTED
        if (!ownsTask()) return StartResult.START_FAILED
        return runCatching {
            pagePort.beginReceivedGreetingsNavigationTask()
            StartResult.STARTED
        }.getOrDefault(StartResult.START_FAILED)
    }

    /** Executes exactly one adapter-bounded navigation/list step. */
    fun performIdleStep(): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.performReceivedGreetingsIdleStep() }.getOrDefault(false)
    }

    /** Starts a read-only traversal from the already confirmed current list. */
    fun beginFromConfirmedList(): StartResult {
        if (!workflowCapabilities().supported) return StartResult.NOT_SUPPORTED
        if (!ownsTask()) return StartResult.START_FAILED
        return runCatching {
            if (pagePort.beginReceivedGreetingsTaskFromCurrentList()) {
                StartResult.STARTED
            } else {
                StartResult.START_FAILED
            }
        }.getOrDefault(StartResult.START_FAILED)
    }

    fun synchronizeRecoveryConfirmation(): Boolean =
        runCatching { pagePort.synchronizeReceivedGreetingsRecoveryConfirmation() }
            .getOrDefault(false)

    /** Keeps the current greeting unprocessed; only the adapter may safely submit BACK. */
    fun deferAfterModelFailure(): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.deferReceivedGreetingAfterModelFailure() }.getOrDefault(false)
    }

    /** Returns from a verified greetings boundary only when this task still owns UI operations. */
    fun returnForPriorityCheck(): ReceivedGreetingsBackResult {
        if (!ownsTask()) return ReceivedGreetingsBackResult.OWNERSHIP_REJECTED
        return runCatching { pagePort.returnFromReceivedGreetingsForPriorityCheck() }
            .getOrDefault(ReceivedGreetingsBackResult.REJECTED)
    }

    fun isCompletionReturnIntermediate(): Boolean =
        runCatching { pagePort.isReceivedGreetingsCompletionReturnIntermediate() }.getOrDefault(false)

    fun confirmCompletionReturn(): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.confirmReceivedGreetingsCompletionReturn() }.getOrDefault(false)
    }

    /** Completing detail inspection can navigate back to the greetings list, so it is lease-gated. */
    fun completeDetailInspection(success: Boolean): Boolean {
        if (!ownsTask()) return false
        return runCatching { pagePort.completeReceivedGreetingDetailInspection(success) }
            .getOrDefault(false)
    }
}
