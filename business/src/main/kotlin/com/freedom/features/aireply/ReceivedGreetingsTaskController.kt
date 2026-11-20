package com.freedom.features.aireply

/**
 * 收到的招呼任务状态所有者。
 *
 * 本类只管理任务生命周期、页面离开等待状态及受控恢复次数；页面指纹判断、OCR 和列表
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
    var recoveryRetryCount: Int = 0
        private set

    fun claimOwnership(reason: String): Boolean = ownership.claim(TASK_ID, reason)

    fun ownsTask(): Boolean = ownership.owns(TASK_ID)

    fun releaseOwnership(reason: String): Boolean = ownership.release(TASK_ID, reason)

    fun markNavigationStarted() {
        isActive = true
        recoveryRetryCount = 0
    }

    fun markStarted() {
        isActive = true
        completedAwaitingUserExit = false
        awaitingNormalInboxAfterReturn = false
        recoveryRetryCount = 0
    }

    fun markCompletedAwaitingUserExit() {
        isActive = false
        completedAwaitingUserExit = true
        awaitingNormalInboxAfterReturn = false
        recoveryRetryCount = 0
    }

    fun markAwaitingNormalInboxAfterReturn() {
        awaitingNormalInboxAfterReturn = true
    }

    /** Mirrors the traversal-return transition without changing the completed-page latch. */
    fun markTraversalReturnedToInbox() {
        isActive = false
        awaitingNormalInboxAfterReturn = false
        recoveryRetryCount = 0
    }

    /** Pauses this branch for an externally verified business-page handoff without clearing context. */
    fun pauseForBusinessSwitch() {
        isActive = false
        completedAwaitingUserExit = false
        awaitingNormalInboxAfterReturn = false
        recoveryRetryCount = 0
    }

    /** Clears only the A-plan page-exit latch after the message-list page is independently confirmed. */
    fun acknowledgeCompletedTaskExit() {
        completedAwaitingUserExit = false
    }

    fun resetRecoveryRetryCount() {
        recoveryRetryCount = 0
    }

    fun nextRecoveryRetryCount(): Int {
        recoveryRetryCount += 1
        return recoveryRetryCount
    }

    fun reset() {
        isActive = false
        completedAwaitingUserExit = false
        awaitingNormalInboxAfterReturn = false
        recoveryRetryCount = 0
    }
}
