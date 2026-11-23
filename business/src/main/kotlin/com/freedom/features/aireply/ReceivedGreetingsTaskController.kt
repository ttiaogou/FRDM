package com.freedom.features.aireply

/**
 * 收到的招呼任务状态所有者。
 *
 * 本类只管理任务生命周期与页面离开等待状态；页面指纹判断、OCR 和列表
 * 遍历仍通过既有 ChatPagePort 执行。任务完成时继续采用 A 方案：留在当前页面并释放租约。
 */
internal class ReceivedGreetingsTaskController(
    private val ownership: AiReplyTaskOwnership
) {
    companion object {
        const val TASK_ID = "AI_REPLY_RECEIVED_GREETINGS"
    }

    var isActive: Boolean = false
        private set
    var completedAwaitingUserExit: Boolean = false
        private set
    var awaitingNormalInboxAfterReturn: Boolean = false
        private set

    fun claimOwnership(reason: String): Boolean = ownership.claim(TASK_ID, reason)

    fun ownsTask(): Boolean = ownership.owns(TASK_ID)

    fun releaseOwnership(reason: String): Boolean = ownership.release(TASK_ID, reason)

    fun markNavigationStarted() {
        isActive = true
    }

    fun markStarted() {
        isActive = true
        completedAwaitingUserExit = false
        awaitingNormalInboxAfterReturn = false
    }

    fun markCompletedAwaitingUserExit() {
        isActive = false
        completedAwaitingUserExit = true
        awaitingNormalInboxAfterReturn = false
    }

    fun markAwaitingNormalInboxAfterReturn() {
        awaitingNormalInboxAfterReturn = true
    }

    /** Mirrors the traversal-return transition without changing the completed-page latch. */
    fun markTraversalReturnedToInbox() {
        isActive = false
        awaitingNormalInboxAfterReturn = false
    }

    /** Pauses this branch for an externally verified business-page handoff without clearing context. */
    fun pauseForBusinessSwitch() {
        isActive = false
        completedAwaitingUserExit = false
        awaitingNormalInboxAfterReturn = false
    }

    /** Clears only the A-plan page-exit latch after the message-list page is independently confirmed. */
    fun acknowledgeCompletedTaskExit() {
        completedAwaitingUserExit = false
    }

    fun reset() {
        isActive = false
        completedAwaitingUserExit = false
        awaitingNormalInboxAfterReturn = false
    }
}
