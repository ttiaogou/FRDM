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

    private enum class Lifecycle { IDLE, TRAVERSING, RETURN_PENDING, RETURN_REQUESTED, INBOX_CONFIRMED }

    enum class ReturnReason { TRAVERSAL_EXHAUSTED, SAFE_SESSION_BOUNDARY }

    private var lifecycle = Lifecycle.IDLE
    var returnReason: ReturnReason? = null
        private set
    val isActive: Boolean
        get() = lifecycle != Lifecycle.IDLE
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

    fun markNavigationStarted() {
        if (!isActive) lifecycle = Lifecycle.TRAVERSING
    }

    fun markStarted() {
        returnReason = null
        lifecycle = Lifecycle.TRAVERSING
    }

    /** Return intent is independent of traversal exhaustion and never releases the lease. */
    fun requestReturn(reason: ReturnReason): Boolean {
        if (lifecycle == Lifecycle.TRAVERSING) {
            returnReason = reason
            lifecycle = Lifecycle.RETURN_PENDING
        }
        return awaitingNormalInboxAfterReturn && returnReason == reason
    }

    /** Compatibility for the existing exhausted-work decision, not a handoff gate. */
    fun markTraversalCompleted() {
        requestReturn(ReturnReason.TRAVERSAL_EXHAUSTED)
    }

    /** The adapter accepted a return step; this is not proof of arrival. */
    fun markReturnRequested() {
        if (lifecycle == Lifecycle.RETURN_PENDING) lifecycle = Lifecycle.RETURN_REQUESTED
    }

    /** Adapter cleanup was acknowledged; lease handoff may still need retry. */
    fun markInboxConfirmed() {
        if (awaitingNormalInboxAfterReturn) lifecycle = Lifecycle.INBOX_CONFIRMED
    }

    fun markTraversalReturnedToInbox() {
        if (awaitingNormalInboxAfterReturn) reset()
    }

    /** Pauses this branch for an externally verified business-page handoff without clearing context. */
    fun pauseForBusinessSwitch() = reset()

    fun reset() {
        lifecycle = Lifecycle.IDLE
        returnReason = null
    }
}
