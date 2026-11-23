package com.freedom.core.runtime

/**
 * Exclusive, run-generation-scoped ownership of automated UI operations.
 *
 * The owner identifier is intentionally opaque to core so feature names and business rules do not
 * leak into the runtime layer. A lease is a capability: only the exact current lease can transfer
 * or release ownership.
 */
data class UiOperationLease internal constructor(
    val ownerId: String,
    val runGeneration: Long,
    internal val revision: Long
)

class UiOperationOwnershipCoordinator {
    private var activeLease: UiOperationLease? = null
    private var latestRunGeneration = -1L
    private var nextRevision = 0L

    /** Acquire the single UI-operation slot, or return the existing lease for the same owner. */
    @Synchronized
    fun tryAcquire(ownerId: String, runGeneration: Long): UiOperationLease? {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(runGeneration >= 0L) { "runGeneration must be non-negative" }

        if (runGeneration < latestRunGeneration) return null
        if (runGeneration > latestRunGeneration) {
            // A newer run invalidates all capabilities issued by the previous run.
            latestRunGeneration = runGeneration
            activeLease = null
        }

        val current = activeLease
        if (current != null) return current.takeIf { it.ownerId == ownerId }
        return createLease(ownerId, runGeneration)
    }

    /** Atomically hand the slot to another task in the same run. */
    @Synchronized
    fun transfer(current: UiOperationLease, nextOwnerId: String): UiOperationLease? {
        require(nextOwnerId.isNotBlank()) { "nextOwnerId must not be blank" }
        if (activeLease != current || current.runGeneration != latestRunGeneration) return null
        if (current.ownerId == nextOwnerId) return current
        return createLease(nextOwnerId, current.runGeneration)
    }

    /** A stale task cannot release a lease that has since been transferred or renewed. */
    @Synchronized
    fun release(lease: UiOperationLease): Boolean {
        if (activeLease != lease) return false
        activeLease = null
        return true
    }

    @Synchronized
    fun isCurrent(lease: UiOperationLease): Boolean =
        activeLease == lease && lease.runGeneration == latestRunGeneration

    @Synchronized
    fun currentOwnerId(): String? = activeLease?.ownerId

    private fun createLease(ownerId: String, runGeneration: Long): UiOperationLease {
        check(nextRevision < Long.MAX_VALUE) { "UI operation lease revision exhausted" }
        return UiOperationLease(ownerId, runGeneration, ++nextRevision).also {
            activeLease = it
        }
    }
}
