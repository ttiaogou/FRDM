package com.freedom.features.aireply

/**
 * 收到的招呼任务状态所有者。
 *
 * 本类只管理任务生命周期与安全返回待确认状态；页面指纹判断、OCR 和列表
 * 遍历仍通过既有 ChatPagePort 执行。任务交接不要求列表遍历完成。
 */
internal class ReceivedGreetingsTaskController(
    private val ownership: AiReplyTaskOwnership
) {
    companion object {
        const val TASK_ID = "AI_REPLY_RECEIVED_GREETINGS"
    }

    private enum class Lifecycle { IDLE, ACTIVE, RETURN_PENDING, RETURN_REQUESTED, INBOX_CONFIRMED, SUSPENDED }

    private var lifecycle = Lifecycle.IDLE
    var returnReason: AiReplyTaskYieldReason? = null
        private set
    val isActive: Boolean
        get() = lifecycle != Lifecycle.IDLE && lifecycle != Lifecycle.SUSPENDED
    val isSuspended: Boolean
        get() = lifecycle == Lifecycle.SUSPENDED
    val awaitingNormalInboxAfterReturn: Boolean
        get() = lifecycle == Lifecycle.RETURN_PENDING || lifecycle == Lifecycle.RETURN_REQUESTED ||
            lifecycle == Lifecycle.INBOX_CONFIRMED
    val inboxConfirmed: Boolean
        get() = lifecycle == Lifecycle.INBOX_CONFIRMED
    val returnRequested: Boolean
        get() = lifecycle == Lifecycle.RETURN_REQUESTED

    fun claimOwnership(reason: String): Boolean = ownership.claim(TASK_ID, reason)

    fun ownsTask(): Boolean = ownership.owns(TASK_ID)

    fun releaseOwnership(reason: String): Boolean = ownership.release(TASK_ID, reason)

    /** A repeated admission cannot overwrite a pending return. */
    fun markStarted() {
        if (isActive) return
        returnReason = null
        lifecycle = Lifecycle.ACTIVE
    }

    /** Return intent is independent of traversal exhaustion and never releases the lease. */
    fun requestReturn(reason: AiReplyTaskYieldReason): Boolean {
        if (lifecycle == Lifecycle.ACTIVE) {
            returnReason = reason
            lifecycle = Lifecycle.RETURN_PENDING
        }
        return awaitingNormalInboxAfterReturn && returnReason == reason
    }

    /** Exhaustion is a yield reason, never a completed task. */
    fun requestExhaustedReturn() = requestReturn(AiReplyTaskYieldReason.TRAVERSAL_EXHAUSTED)

    /** The adapter accepted a return step; this is not proof of arrival. */
    fun markReturnRequested() {
        if (lifecycle == Lifecycle.RETURN_PENDING) lifecycle = Lifecycle.RETURN_REQUESTED
    }

    /** Adapter cleanup was acknowledged; lease handoff may still need retry. */
    fun markInboxConfirmed() {
        if (awaitingNormalInboxAfterReturn) lifecycle = Lifecycle.INBOX_CONFIRMED
    }

    /** Only the independently acknowledged destination may retire this return intent. */
    fun finishVerifiedHandoff(): Boolean {
        if (!inboxConfirmed) return false
        lifecycle = Lifecycle.SUSPENDED
        returnReason = null
        return true
    }

    /** Recovery suspension does not stop the run or erase the external checkpoint. */
    fun suspendForRecovery() {
        if (!isActive) return
        lifecycle = Lifecycle.SUSPENDED
        returnReason = null
    }

    /** Explicit run reset only. */
    fun reset() {
        lifecycle = Lifecycle.IDLE
        returnReason = null
    }
}
