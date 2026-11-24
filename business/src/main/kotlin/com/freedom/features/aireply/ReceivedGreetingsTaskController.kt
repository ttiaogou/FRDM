package com.freedom.features.aireply

/**
 * 收到的招呼任务状态所有者。
 *
 * 本类只管理任务生命周期与完成后的返回待确认状态；页面指纹判断、OCR 和列表
 * 遍历仍通过既有 ChatPagePort 执行。列表遍历完成不等于任务结束。
 */
internal class ReceivedGreetingsTaskController(
    private val ownership: AiReplyTaskOwnership
) {
    companion object {
        const val TASK_ID = "AI_REPLY_RECEIVED_GREETINGS"
    }

    private enum class Lifecycle { IDLE, TRAVERSING, RETURN_PENDING, RETURN_REQUESTED }

    private var lifecycle = Lifecycle.IDLE
    val isActive: Boolean
        get() = lifecycle != Lifecycle.IDLE
    val awaitingNormalInboxAfterReturn: Boolean
        get() = lifecycle == Lifecycle.RETURN_PENDING || lifecycle == Lifecycle.RETURN_REQUESTED
    val returnRequested: Boolean
        get() = lifecycle == Lifecycle.RETURN_REQUESTED

    fun claimOwnership(reason: String): Boolean = ownership.claim(TASK_ID, reason)

    fun ownsTask(): Boolean = ownership.owns(TASK_ID)

    fun releaseOwnership(reason: String): Boolean = ownership.release(TASK_ID, reason)

    fun markNavigationStarted() {
        if (!isActive) lifecycle = Lifecycle.TRAVERSING
    }

    fun markStarted() {
        lifecycle = Lifecycle.TRAVERSING
    }

    /** Traversal completion does not end the task or release its lease. */
    fun markTraversalCompleted() {
        if (lifecycle == Lifecycle.TRAVERSING) lifecycle = Lifecycle.RETURN_PENDING
    }

    /** The adapter accepted a return step; this is not proof of arrival. */
    fun markReturnRequested() {
        if (lifecycle == Lifecycle.RETURN_PENDING) lifecycle = Lifecycle.RETURN_REQUESTED
    }

    fun markTraversalReturnedToInbox() {
        if (awaitingNormalInboxAfterReturn) lifecycle = Lifecycle.IDLE
    }

    /** Pauses this branch for an externally verified business-page handoff without clearing context. */
    fun pauseForBusinessSwitch() {
        lifecycle = Lifecycle.IDLE
    }

    fun reset() {
        lifecycle = Lifecycle.IDLE
    }
}
