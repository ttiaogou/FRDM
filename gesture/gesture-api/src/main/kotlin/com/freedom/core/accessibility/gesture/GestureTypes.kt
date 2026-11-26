package com.freedom.core.accessibility.gesture

/** Semantic label for the screen gesture; it never changes the supplied physical path. */
enum class GestureKind {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE,
    DRAG,
    CUSTOM
}

enum class GestureExecutionStatus {
    COMPLETED,
    CANCELLED,
    REJECTED,
    FAILED,
    /** A previously accepted command was not dispatched because the target was not ready; refresh context and retry. */
    DEFERRED
}

data class GestureExecutionResult(
    val commandId: String,
    val status: GestureExecutionStatus,
    val reason: String = ""
)

fun interface GestureExecutionListener {
    /** Called immediately before the coordinator submits this command to the platform dispatcher. */
    fun onResult(result: GestureExecutionResult)

    /** Read-only business target proof, evaluated at the first actual platform dispatch.
     * Default preserves existing commands without a mutable business target. */
    fun isDispatchTargetCurrent(commandId: String): Boolean = true

    /**
     * Actual dispatch time in the Android uptime clock domain. Queueing, visualization and
     * pre-dispatch delays are deliberately excluded from event-feedback deadlines.
     */
    fun onDispatched(commandId: String, dispatchedAtUptimeMs: Long) = Unit
}
