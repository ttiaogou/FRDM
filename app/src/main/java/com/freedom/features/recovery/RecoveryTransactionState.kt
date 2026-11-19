package com.freedom.features.recovery

/**
 * Business-neutral lifecycle for a page-recovery transaction.
 *
 * This file intentionally contains no Android, adapter, page-field or traversal dependency.
 * Branch-specific probes and checkpoint policies will be injected in a later integration step.
 */
enum class RecoveryTransactionState {
    ACTIVE,
    RECOVERY_REQUESTED,
    PAUSED,
    VERIFYING,
    VERIFIED,
    RESUMING,
    CANCELLED
}

/** Events understood by the shared recovery transaction. Payloads contain no page-specific data. */
sealed interface RecoveryTransactionEvent {
    data class UnknownPage(val reason: String = "UNKNOWN_PAGE") : RecoveryTransactionEvent
    data class CheckpointLoaded(val source: Source) : RecoveryTransactionEvent
    data class VerificationStarted(val attempt: Int) : RecoveryTransactionEvent
    data class VerificationSample(val stable: Boolean) : RecoveryTransactionEvent
    data object UserInteractionStarted : RecoveryTransactionEvent
    data object UserInteractionEnded : RecoveryTransactionEvent
    data object ResumeRequested : RecoveryTransactionEvent
    data object ResumeCompleted : RecoveryTransactionEvent
    data class RetryScheduled(val delayMs: Long) : RecoveryTransactionEvent
    data class Cancelled(val reason: String) : RecoveryTransactionEvent

    enum class Source {
        SERVICE_RECONNECT,
        PROCESS_RESTART,
        PAGE_OBSERVATION
    }
}
