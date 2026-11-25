package com.freedom.core.runtime

/**
 * Scope of a business-originated Android UI mutation. A scope is never itself an
 * authorization: the exact task lease, run generation and latched run mode must
 * also match at the final platform dispatch.
 */
enum class UiWriteScope {
    BROWSE_NODE_ACTION,
    AI_COMPOSER,
    VERIFIED_GREETING_COMPOSER,
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
    private val activeRunMode: () -> RunMode?,
    /** Production supplies an immutable, lock-free READY route snapshot. Null provider is
     * retained for legacy mode-only fixtures, not for a missing production route. */
    private val currentPageRuntime: (() -> PageRuntimeSessionState?)? = null
) {
    private fun routeMatches(route: PageRuntimeSessionState?, mode: RunMode?, lease: TaskLease): Boolean =
        currentPageRuntime == null || (route != null &&
            route.lifecycle == PageRuntimeLifecycle.READY &&
            route.runGeneration == lease.runGeneration && route.taskId == lease.taskId &&
            route.mode == mode)

    private fun currentRoute() = currentPageRuntime?.invoke()
    /** Advisory preflight only. The final mutation MUST call [withAuthorizedWrite]. */
    fun permits(scope: UiWriteScope): Boolean {
        val runGeneration = currentRunGeneration()
        val mode = activeRunMode()
        val lease = taskCoordinator.currentLease() ?: return false
        return lease.runGeneration == runGeneration && accepts(scope, mode, lease.taskId) &&
            routeMatches(currentRoute(), mode, lease)
    }

    /** Holds the exact current task lease across the actual platform submission. */
    fun <T> withAuthorizedWrite(scope: UiWriteScope, action: () -> T): T? {
        val runGeneration = currentRunGeneration()
        val mode = activeRunMode()
        val lease = taskCoordinator.currentLease() ?: return null
        val route = currentRoute()
        if (lease.runGeneration != runGeneration || !accepts(scope, mode, lease.taskId) ||
            !routeMatches(route, mode, lease)) return null
        return taskCoordinator.withCurrentLease(lease) {
            // Production route publication uses the same ownership monitor. Revalidate the
            // captured route (including its revision) inside the final submission barrier.
            if (currentPageRuntime != null &&
                (currentRoute() != route || !routeMatches(route, mode, lease) ||
                    currentRunGeneration() != runGeneration || activeRunMode() != mode)
            ) null else action()
        }
    }

    /** Advisory check for the EXACT lease captured when this draft request was admitted. */
    fun permitsBound(scope: UiWriteScope, expectedLease: TaskLease?): Boolean {
        val expected = expectedLease ?: return false
        return expected.runGeneration == currentRunGeneration() &&
            activeRunMode().let { mode ->
                accepts(scope, mode, expected.taskId) && routeMatches(currentRoute(), mode, expected)
            } && taskCoordinator.isCurrent(expected)
    }

    /** Final mutation barrier: ownership transfers cannot revive an old asynchronous draft. */
    fun <T> withBoundAuthorizedWrite(scope: UiWriteScope, expectedLease: TaskLease?, action: () -> T): T? {
        val expected = expectedLease ?: return null
        val runGeneration = currentRunGeneration()
        val mode = activeRunMode()
        val route = currentRoute()
        if (expected.runGeneration != runGeneration || !accepts(scope, mode, expected.taskId) ||
            !routeMatches(route, mode, expected)) return null
        return taskCoordinator.withCurrentLease(expected) {
            if (currentRunGeneration() != runGeneration || activeRunMode() != mode ||
                (currentPageRuntime != null && currentRoute() != route)
            ) null else action()
        }
    }

    private fun accepts(scope: UiWriteScope, mode: RunMode?, taskId: String): Boolean {
        val browse = mode == RunMode.ACCOUNT_BROWSE && taskId == ACCOUNT_BROWSE
        val privateMessages = mode == RunMode.AI_REPLY && taskId == AI_PRIVATE
        val greetings = mode == RunMode.AI_REPLY && taskId == AI_GREETINGS
        return when (scope) {
            UiWriteScope.BROWSE_NODE_ACTION -> browse
            UiWriteScope.AI_COMPOSER -> privateMessages
            // This is a distinct capability; the Momo adapter must verify live chat/window/target
            // before calling its Android-only composer entry point.
            UiWriteScope.VERIFIED_GREETING_COMPOSER -> greetings
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
