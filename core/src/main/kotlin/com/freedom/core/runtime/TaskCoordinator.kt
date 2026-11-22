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
    private var nextSwitchSequence = 0L
    private var lastBusinessTaskSwitchEvent: BusinessTaskSwitchEvent? = null

    /**
     * Receives a cross-business switch request without transferring UI ownership yet.
     * The target task must still verify its page and checkpoint before a later phase calls
     * [transfer]. Keeping this separate prevents a page-exit observation from becoming an
     * unsafe blind handoff.
     */
    @Synchronized
    fun receiveBusinessTaskSwitch(
        fromTaskId: String,
        toTaskId: String,
        reason: String
    ): BusinessTaskSwitchEvent {
        check(nextSwitchSequence < Long.MAX_VALUE) { "business switch sequence exhausted" }
        return BusinessTaskSwitchEvent(
            fromTaskId = fromTaskId,
            toTaskId = toTaskId,
            reason = reason,
            sequence = ++nextSwitchSequence
        ).also { lastBusinessTaskSwitchEvent = it }
    }

    /** Latest request is observable by coordinators that perform target-page verification. */
    @Synchronized
    fun lastBusinessTaskSwitchEvent(): BusinessTaskSwitchEvent? = lastBusinessTaskSwitchEvent

    /**
     * Seals the previous run's task capability at the run-generation boundary, even if
     * no replacement task has yet been claimed. A late callback cannot keep an old
     * owner visible to other runtime and UI gateway consumers during a stop/reconnect.
     * Older or repeated notifications never roll the shared clock backwards.
     */
    @Synchronized
    fun advanceRunGeneration(runGeneration: Long) {
        require(runGeneration >= 0L) { "runGeneration must be non-negative" }
        if (runGeneration <= latestRunGeneration) return
        activeLease?.let { uiOperationOwnership.release(it.uiOperationLease) }
        activeLease = null
        latestRunGeneration = runGeneration
    }

    /** Start a task if the single task/UI slot is free; repeated claims by the owner are idempotent. */
    @Synchronized
    fun tryAcquire(taskId: String, runGeneration: Long): TaskLease? {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(runGeneration >= 0L) { "runGeneration must be non-negative" }

        if (runGeneration < latestRunGeneration) return null
        if (runGeneration > latestRunGeneration) advanceRunGeneration(runGeneration)

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

    /** Returns the exact current capability for binding newly captured task gestures. */
    @Synchronized
    fun currentLease(): TaskLease? = activeLease?.takeIf(::isCurrent)

    /**
     * Runs [action] while holding the task-lease monitor. This closes the check/dispatch race:
     * transfer or release cannot invalidate the capability between final validation and the
     * platform dispatch submission.
     */
    @Synchronized
    fun <T> withCurrentLease(lease: TaskLease, action: () -> T): T? {
        if (!isCurrent(lease)) return null
        return action()
    }

    @Synchronized
    fun activeTaskId(): String? = currentLease()?.taskId

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
