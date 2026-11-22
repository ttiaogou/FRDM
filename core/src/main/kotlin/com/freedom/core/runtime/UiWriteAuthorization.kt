package com.freedom.core.runtime

/**
 * Scope of a business-originated Android UI mutation. A scope is never itself an
 * authorization: the exact task lease, run generation and latched run mode must
 * also match at the final platform dispatch.
 */
enum class UiWriteScope {
    BROWSE_NODE_ACTION,
    AI_COMPOSER,
    VERIFIED_AI_BACK,
    GLOBAL_NAVIGATION,
    AUTONOMOUS_GESTURE
}

/**
 * One fail-closed authority shared by node actions, global navigation, composer
 * changes and gesture dispatch. The task coordinator serializes permission checks
 * with lease release/transfer, so a stale callback cannot submit after revocation.
 *
 * Read-only observations never require this capability. Explicit user-requested
 * Freedom-internal calibration and lock-screen safety actions are not business
 * mutations and retain their separately verified platform paths.
 */
class UiWriteAuthorization(
    private val taskCoordinator: TaskCoordinator,
    private val currentRunGeneration: () -> Long,
    private val activeRunMode: () -> RunMode?
) {
    /** Advisory preflight only. The final mutation MUST call [withAuthorizedWrite]. */
    fun permits(scope: UiWriteScope): Boolean {
        val runGeneration = currentRunGeneration()
        val mode = activeRunMode()
        val lease = taskCoordinator.currentLease() ?: return false
        return lease.runGeneration == runGeneration && accepts(scope, mode, lease.taskId)
    }

    /** Holds the exact current task lease across the actual platform submission. */
    fun <T> withAuthorizedWrite(scope: UiWriteScope, action: () -> T): T? {
        val runGeneration = currentRunGeneration()
        val mode = activeRunMode()
        val lease = taskCoordinator.currentLease() ?: return null
        if (lease.runGeneration != runGeneration || !accepts(scope, mode, lease.taskId)) return null
        return taskCoordinator.withCurrentLease(lease) {
            // TaskCoordinator owns the authoritative lease revocation barrier. In particular,
            // no callback may reuse a capability after its mode or generation changes.
            action()
        }
    }

    private fun accepts(scope: UiWriteScope, mode: RunMode?, taskId: String): Boolean {
        val browse = mode == RunMode.ACCOUNT_BROWSE && taskId == ACCOUNT_BROWSE
        val privateMessages = mode == RunMode.AI_REPLY && taskId == AI_PRIVATE
        val greetings = mode == RunMode.AI_REPLY && taskId == AI_GREETINGS
        return when (scope) {
            UiWriteScope.BROWSE_NODE_ACTION -> browse
            UiWriteScope.AI_COMPOSER -> privateMessages
            UiWriteScope.VERIFIED_AI_BACK -> privateMessages || greetings
            UiWriteScope.GLOBAL_NAVIGATION, UiWriteScope.AUTONOMOUS_GESTURE ->
                browse || privateMessages || greetings
        }
    }

    private companion object {
        const val ACCOUNT_BROWSE = "ACCOUNT_BROWSE"
        const val AI_PRIVATE = "AI_REPLY_PRIVATE_MESSAGES"
        const val AI_GREETINGS = "AI_REPLY_RECEIVED_GREETINGS"
    }
}
