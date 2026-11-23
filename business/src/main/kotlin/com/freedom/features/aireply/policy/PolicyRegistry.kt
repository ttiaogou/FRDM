package com.freedom.features.aireply.policy

/**
 * Resolves only a previously verified task identity.
 *
 * Unknown or unregistered task IDs must return null; never fall back to another business.
 * Runtime composition registers exactly the two supported AI Reply strategies.
 */
interface PolicyRegistry {
    fun resolve(taskId: String): TaskPolicy?
}
