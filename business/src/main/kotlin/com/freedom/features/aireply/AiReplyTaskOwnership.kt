package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskExecutionController

/** Shared business yield reason; neither a lease release nor a traversal state. */
internal enum class AiReplyTaskYieldReason {
    TRAVERSAL_EXHAUSTED,
    SAFE_SESSION_BOUNDARY,
    DEFERRED_WORK;

    val hasNoImmediatelyRunnableWork: Boolean
        get() = this == TRAVERSAL_EXHAUSTED || this == DEFERRED_WORK
}

/** Run-local scheduling credit, independent of the durable contact/media ledger.
 * Only a unique confirmed send AND verified return receipt is admitted by the runtime.
 * Retrying a handoff must not consume credit; reset a slice only after actual transfer.
 */
internal class AiReplyTaskSliceQuota {
    companion object { const val LIMIT = 2 }
    private val countedReceipts = HashSet<String>()
    var completed: Int = 0
        private set
    val reached: Boolean get() = completed >= LIMIT

    fun record(receipt: String): Boolean {
        if (receipt.isBlank() || !countedReceipts.add(receipt)) return false
        completed = (completed + 1).coerceAtMost(LIMIT)
        return true
    }

    fun finishSlice() { completed = 0 }
    fun resetRun() {
        completed = 0
        countedReceipts.clear()
    }
}

/**
 * AI Reply 对通用任务执行控制器的领域适配门面。
 * 私聊与收到招呼共享同一个控制器实例，以支持原子租约转交。
 */
internal class AiReplyTaskOwnership(
    coordinator: TaskCoordinator?,
    currentRunGeneration: () -> Long,
    onLog: (String) -> Unit
) {
    // R07-G: run-local paired-task scheduling facts; the delegate alone owns leases.
    // Task-local yield and pair wake are independent facts.
    var privateToGreetingsHandoff: AiReplyTaskYieldReason? = null
    val privateSliceQuota = AiReplyTaskSliceQuota()
    val greetingSliceQuota = AiReplyTaskSliceQuota()
    var pendingPrivateQuotaReceipt: String? = null
        private set
    var privateQuotaReturnReady = false
        private set
    enum class PairWakeMode { REFRESH_PRIVATE, RESUME_PRIVATE }
    data class PairWake(val mode: PairWakeMode, var due: Boolean, var retryAttempt: Int = 0)
    var pairWake: PairWake? = null
        private set
    // A deferred yield owes the original relocation a retry before yielding again.
    var privateRelocationRetryOwed = false
    /** Limits repeated handoff lease churn while the original private checkpoint stays pending. */
    val crossTaskGreetingsHandoffRetryGate = CrossTaskGreetingsHandoffRetryGate()
    var crossTaskGreetingsHandoffRetryTask: ScheduledTask? = null

    fun retainPrivateQuotaReceipt(sendConfirmationId: String) {
        pendingPrivateQuotaReceipt = sendConfirmationId
        privateQuotaReturnReady = false
    }

    fun markPrivateQuotaReturnReady(sendConfirmationId: String?) {
        if (sendConfirmationId != null && pendingPrivateQuotaReceipt == sendConfirmationId) {
            privateQuotaReturnReady = true
        }
    }

    fun discardUnreadyPrivateQuotaReceipt() {
        if (!privateQuotaReturnReady) pendingPrivateQuotaReceipt = null
    }

    fun clearPrivateQuotaReceipt() {
        pendingPrivateQuotaReceipt = null
        privateQuotaReturnReady = false
    }

    fun requestPairWake(mode: PairWakeMode, due: Boolean): PairWake {
        val wake = PairWake(mode, due)
        pairWake = wake
        return wake
    }

    fun clearPairWake() {
        pairWake = null
    }

    fun resetPairedRun() {
        privateToGreetingsHandoff = null
        privateRelocationRetryOwed = false
        privateSliceQuota.resetRun()
        greetingSliceQuota.resetRun()
        clearPrivateQuotaReceipt()
    }

    fun resetCrossTaskGreetingsHandoffRetry() {
        crossTaskGreetingsHandoffRetryTask?.cancel()
        crossTaskGreetingsHandoffRetryTask = null
        crossTaskGreetingsHandoffRetryGate.reset()
    }

    private val delegate = TaskExecutionController(
        coordinator = coordinator,
        currentRunGeneration = currentRunGeneration,
        onLog = onLog
    )

    fun beginRun(generation: Long) = delegate.beginRun(generation)

    fun claim(taskId: String, reason: String): Boolean = delegate.claim(taskId, reason)

    fun owns(taskId: String): Boolean = delegate.owns(taskId)

    fun activeTaskId(): String? = delegate.activeTaskId()

    fun release(taskId: String, reason: String): Boolean = delegate.release(taskId, reason)

    fun releaseCurrent(reason: String) = delegate.releaseCurrent(reason)

    fun reset(reason: String) = delegate.reset(reason)
}
