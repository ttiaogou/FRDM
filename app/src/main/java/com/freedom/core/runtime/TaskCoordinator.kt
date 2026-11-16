package com.freedom.core.runtime

/**
 * Coordinates one active business task and binds that task to the exclusive UI-operation lease.
 * Task identifiers are opaque strings so core remains independent of individual feature modules.
 */
data class TaskLease internal constructor(
    val taskId: String,
    val runGeneration: Long,
    internal val uiOperationLease: UiOperationLease,
    internal val revision: Long
)

class TaskCoordinator(
    private val uiOperationOwnership: UiOperationOwnershipCoordinator
) {
    private var activeLease: TaskLease? = null
    private var latestRunGeneration = -1L
    private var nextRevision = 0L

    /** Start a task if the single task/UI slot is free; repeated claims by the owner are idempotent. */
    @Synchronized
    fun tryAcquire(taskId: String, runGeneration: Long): TaskLease? {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(runGeneration >= 0L) { "runGeneration must be non-negative" }

        if (runGeneration < latestRunGeneration) return null
        if (runGeneration > latestRunGeneration) {
            activeLease?.let { uiOperationOwnership.release(it.uiOperationLease) }
            activeLease = null
            latestRunGeneration = runGeneration
        }

        val current = activeLease
        if (current != null) {
            return current.takeIf {
                it.taskId == taskId && it.runGeneration == runGeneration &&
                    uiOperationOwnership.isCurrent(it.uiOperationLease)
            }
        }

        val operationLease = uiOperationOwnership.tryAcquire(taskId, runGeneration) ?: return null
        return createLease(taskId, runGeneration, operationLease)
    }

    /** Transfer task and UI ownership together; no unowned gap is exposed between the tasks. */
    @Synchronized
    fun transfer(current: TaskLease, nextTaskId: String): TaskLease? {
        require(nextTaskId.isNotBlank()) { "nextTaskId must not be blank" }
        if (activeLease != current || current.runGeneration != latestRunGeneration ||
            !uiOperationOwnership.isCurrent(current.uiOperationLease)
        ) return null
        if (current.taskId == nextTaskId) return current

        val operationLease = uiOperationOwnership.transfer(current.uiOperationLease, nextTaskId)
            ?: return null
        return createLease(nextTaskId, current.runGeneration, operationLease)
    }

    /** Release only the exact task lease currently owning the UI-operation slot. */
    @Synchronized
    fun release(lease: TaskLease): Boolean {
        if (activeLease != lease) return false
        val released = uiOperationOwnership.release(lease.uiOperationLease)
        activeLease = null
        return released
    }

    @Synchronized
    fun isCurrent(lease: TaskLease): Boolean =
        activeLease == lease && lease.runGeneration == latestRunGeneration &&
            uiOperationOwnership.isCurrent(lease.uiOperationLease)

    @Synchronized
    fun activeTaskId(): String? = activeLease?.taskId

    private fun createLease(
        taskId: String,
        runGeneration: Long,
        operationLease: UiOperationLease
    ): TaskLease {
        check(nextRevision < Long.MAX_VALUE) { "task lease revision exhausted" }
        return TaskLease(taskId, runGeneration, operationLease, ++nextRevision).also {
            activeLease = it
        }
    }
}
