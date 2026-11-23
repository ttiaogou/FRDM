package com.freedom.core.runtime

/**
 * A business-neutral request to hand the next page observation to another task.
 *
 * The runtime only transports the opaque task identifiers and reason. Page fingerprints,
 * checkpoints and recovery policy remain owned by the corresponding feature adapters.
 */
data class BusinessTaskSwitchEvent(
    val fromTaskId: String,
    val toTaskId: String,
    val reason: String,
    val sequence: Long
) {
    init {
        require(fromTaskId.isNotBlank()) { "fromTaskId must not be blank" }
        require(toTaskId.isNotBlank()) { "toTaskId must not be blank" }
        require(reason.isNotBlank()) { "reason must not be blank" }
        require(sequence >= 0L) { "sequence must be non-negative" }
    }
}
