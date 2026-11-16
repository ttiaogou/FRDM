package com.freedom.core.runtime

/**
 * Common business-task execution boundary used by AccountBrowse and AI Reply sub-tasks.
 *
 * It centralizes run-generation binding, task acquisition/transfer, stale-lease rejection and
 * release diagnostics. It does not own business state or decide when a task should run; those
 * decisions remain with the corresponding feature controller.
 */
class TaskExecutionController(
    private val coordinator: TaskCoordinator?,
    private val currentRunGeneration: () -> Long,
    private val onLog: (String) -> Unit = {}
) {
    private var lease: TaskLease? = null
    private var runGeneration: Long? = null

    @Synchronized
    fun beginRun(generation: Long) {
        require(generation >= 0L) { "runGeneration must be non-negative" }
        val current = lease
        if (current != null && current.runGeneration != generation) {
            // A controller may survive a service reconnect or a new explicit run. Do not let an
            // otherwise-current lease from the previous generation be reused for the new run.
            coordinator?.release(current)
            lease = null
        }
        runGeneration = generation
    }

    /** Claims the task slot or atomically transfers the current valid lease to [taskId]. */
    @Synchronized
    fun claim(taskId: String, reason: String): Boolean {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        val taskCoordinator = coordinator ?: return true
        val generation = runGeneration ?: currentRunGeneration().also { runGeneration = it }
        require(generation >= 0L) { "runGeneration must be non-negative" }
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
