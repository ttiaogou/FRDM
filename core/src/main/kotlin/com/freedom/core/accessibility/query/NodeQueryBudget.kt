package com.freedom.core.accessibility.query

/** Bounded work allowance for a query over an immutable accessibility snapshot. */
data class NodeQueryBudget(
    val maxVisitedNodes: Int = 5_000,
    val timeoutMs: Long = 250L
) {
    init {
        require(maxVisitedNodes > 0) { "maxVisitedNodes must be positive" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    companion object {
        val DEFAULT = NodeQueryBudget()
        /** Explicit escape hatch for offline, already bounded immutable data only. */
        val UNBOUNDED = NodeQueryBudget(maxVisitedNodes = Int.MAX_VALUE, timeoutMs = Long.MAX_VALUE)
    }
}
