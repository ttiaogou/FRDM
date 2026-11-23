package com.freedom.features.aireply

/**
 * 私人消息任务的轻量业务控制器。
 *
 * 只提供私人消息任务的运行代次和 UI 操作权入口。
 * 会话列表遍历、页面证据及业务检查点仍由 AiReplyObservationRuntime 和遍历执行器管理。
 * 旧只读模拟观察计数和随机配额不属于任务所有权，不再在此保存。
 */
internal class PrivateMessagesTaskController(
    private val ownership: AiReplyTaskOwnership
) {
    companion object {
        const val TASK_ID = "AI_REPLY_PRIVATE_MESSAGES"
    }

    fun beginRun(runGeneration: Long) {
        ownership.beginRun(runGeneration)
    }

    fun claimOwnership(reason: String): Boolean = ownership.claim(TASK_ID, reason)

    fun ownsTask(): Boolean = ownership.owns(TASK_ID)

    fun releaseOwnership(reason: String): Boolean = ownership.release(TASK_ID, reason)
}
