package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.TaskLease

/**
 * AI Reply 内部任务共用的租约边界。
 *
 * Core 只理解不透明的任务 ID 和 UI 操作权；本类负责把 AI Reply 的任务代次、租约交接
 * 与诊断日志绑定起来。页面观察属于只读操作，不通过本类申请租约。
 */
internal class AiReplyTaskOwnership(
    private val coordinator: TaskCoordinator?,
    private val currentRunGeneration: () -> Long,
    private val onLog: (String) -> Unit
) {
    private var lease: TaskLease? = null
    private var runGeneration: Long? = null

    @Synchronized
    fun beginRun(generation: Long) {
        require(generation >= 0L) { "runGeneration must be non-negative" }
        runGeneration = generation
    }

    @Synchronized
    fun claim(taskId: String, reason: String): Boolean {
        val taskCoordinator = coordinator ?: return true
        val generation = runGeneration ?: currentRunGeneration().also {
            runGeneration = it
        }
        val current = lease
        val next = when {
            current == null -> taskCoordinator.tryAcquire(taskId, generation)
            taskCoordinator.isCurrent(current) && current.taskId == taskId -> current
            taskCoordinator.isCurrent(current) -> taskCoordinator.transfer(current, taskId)
            else -> {
                lease = null
                taskCoordinator.tryAcquire(taskId, generation)
            }
        }
        if (next == null) {
            onLog(
                "任务协调[REJECTED]：task=$taskId；reason=$reason；" +
                    "activeTask=${taskCoordinator.activeTaskId() ?: "NONE"}；runGeneration=$generation"
            )
            return false
        }
        lease = next
        onLog("任务协调[OWNED]：task=$taskId；reason=$reason；runGeneration=${next.runGeneration}；lease 已确认")
        return true
    }

    @Synchronized
    fun owns(taskId: String): Boolean {
        val taskCoordinator = coordinator ?: return true
        val current = lease ?: return false
        return current.taskId == taskId && taskCoordinator.isCurrent(current)
    }

    @Synchronized
    fun activeTaskId(): String? = lease?.taskId

    @Synchronized
    fun release(taskId: String, reason: String): Boolean {
        val taskCoordinator = coordinator ?: return false
        val current = lease ?: return false
        if (current.taskId != taskId) return false
        val released = taskCoordinator.release(current)
        lease = null
        logRelease(current, reason, released)
        return released
    }

    @Synchronized
    fun releaseCurrent(reason: String) {
        val taskCoordinator = coordinator ?: return
        val current = lease ?: return
        val released = taskCoordinator.release(current)
        lease = null
        logRelease(current, reason, released)
    }

    @Synchronized
    fun reset(reason: String) {
        releaseCurrent(reason)
        runGeneration = null
    }

    private fun logRelease(lease: TaskLease, reason: String, released: Boolean) {
        onLog(
            if (released) {
                "任务协调[RELEASED]：task=${lease.taskId}；reason=$reason；已释放 UI 操作权"
            } else {
                "任务协调[STALE_RELEASE_IGNORED]：task=${lease.taskId}；reason=$reason；租约已过期或已转交"
            }
        )
    }
}
