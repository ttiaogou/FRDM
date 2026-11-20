package com.freedom.features.aireply

/**
 * 私人消息任务的轻量业务控制器。
 *
 * 它独立拥有本轮只读归档计数、随机目标和私人消息任务租约入口；会话列表遍历、读取和
 * 手势状态机仍由 AiReplyObservationRuntime 执行，本阶段不改变那些已验证的业务规则。
 */
internal class PrivateMessagesTaskController(
    private val ownership: AiReplyTaskOwnership,
    private val chooseRandomInt: (Int, Int) -> Int
) {
    companion object {
        const val TASK_ID = "AI_REPLY_PRIVATE_MESSAGES"
    }

    var completedObservationCount: Int = 0
        private set
    var targetObservationCount: Int = 0
        private set

    fun beginRun(runGeneration: Long) {
        ownership.beginRun(runGeneration)
    }

    fun claimOwnership(reason: String): Boolean = ownership.claim(TASK_ID, reason)

    fun ownsTask(): Boolean = ownership.owns(TASK_ID)

    fun resetQuota() {
        completedObservationCount = 0
        targetObservationCount = chooseRandomInt(5, 11).coerceIn(5, 11)
    }

    fun recordSuccessfulObservation(): Int {
        completedObservationCount += 1
        return completedObservationCount
    }

    fun quotaReached(): Boolean =
        targetObservationCount > 0 && completedObservationCount >= targetObservationCount

    fun releaseOwnership(reason: String): Boolean = ownership.release(TASK_ID, reason)
}
