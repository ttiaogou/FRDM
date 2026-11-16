package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskExecutionController

/**
 * AI Reply 对通用任务执行控制器的领域适配门面。
 * 私聊与收到招呼共享同一个控制器实例，以支持原子租约转交。
 */
internal class AiReplyTaskOwnership(
    coordinator: TaskCoordinator?,
    currentRunGeneration: () -> Long,
    onLog: (String) -> Unit
) {
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
