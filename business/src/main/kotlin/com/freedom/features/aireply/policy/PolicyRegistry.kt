package com.freedom.features.aireply.policy

/**
 * Resolves only a previously verified task identity.
 *
 * Unknown or unregistered task IDs must return null; never fall back to another business.
 * Registration and runtime selection belong to a later phase, not P0.
 */
interface PolicyRegistry {
    fun resolve(taskId: String): TaskPolicy?
}
