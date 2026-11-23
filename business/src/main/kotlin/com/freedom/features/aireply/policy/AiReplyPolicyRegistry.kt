package com.freedom.features.aireply.policy

/**
 * Immutable, fail-closed AI Reply policy registry.
 *
 * Only the existing two task owners are registered. This registry never selects a task,
 * acquires a lease, observes pages, retries OCR or initiates a send/recovery operation.
 * Unknown task IDs remain unregistered; they are never mapped to private messages.
 */
object AiReplyPolicyRegistry : PolicyRegistry {
    override fun resolve(taskId: String): TaskPolicy? = when (taskId) {
        ReceivedGreetingsPolicy.taskId -> ReceivedGreetingsPolicy
        PrivateMessagesPolicy.taskId -> PrivateMessagesPolicy
        else -> null
    }
}
