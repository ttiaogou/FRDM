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

/**
 * Shared, business-neutral recovery transaction.
 *
 * The page adapters only provide observations. The feature coordinator owns this reducer and
 * decides when the task is paused or may resume. It deliberately has no retry limit: a retry
 * schedule is an event, not a terminal condition.
 */
class UnifiedRecoveryTransactionStateMachine(
    initialState: RecoveryTransactionState = RecoveryTransactionState.ACTIVE
) {
    var state: RecoveryTransactionState = initialState
        private set
    var stableVerificationSamples: Int = 0
        private set
    private var lastObservedFingerprint: String = ""

    fun dispatch(event: RecoveryTransactionEvent): RecoveryTransactionState {
        when (event) {
            is RecoveryTransactionEvent.UnknownPage -> {
                stableVerificationSamples = 0
                state = RecoveryTransactionState.PAUSED
            }
            is RecoveryTransactionEvent.CheckpointLoaded -> {
                stableVerificationSamples = 0
                state = RecoveryTransactionState.VERIFYING
            }
            is RecoveryTransactionEvent.VerificationStarted -> {
                stableVerificationSamples = 0
                state = RecoveryTransactionState.VERIFYING
            }
            is RecoveryTransactionEvent.VerificationSample -> {
                if (state != RecoveryTransactionState.VERIFYING &&
                    state != RecoveryTransactionState.PAUSED
                ) return state
                stableVerificationSamples = if (event.stable) {
                    stableVerificationSamples + 1
                } else {
                    0
                }
                state = if (stableVerificationSamples >= REQUIRED_STABLE_SAMPLES) {
                    RecoveryTransactionState.VERIFIED
                } else {
                    RecoveryTransactionState.VERIFYING
                }
            }
            RecoveryTransactionEvent.UserInteractionStarted -> {
                if (state != RecoveryTransactionState.CANCELLED) {
                    state = RecoveryTransactionState.PAUSED
                    stableVerificationSamples = 0
                }
            }
            RecoveryTransactionEvent.UserInteractionEnded -> {
                if (state == RecoveryTransactionState.PAUSED) {
                    state = RecoveryTransactionState.VERIFYING
                    stableVerificationSamples = 0
                }
            }
            is RecoveryTransactionEvent.RetryScheduled -> {
                if (state != RecoveryTransactionState.CANCELLED) {
                    state = RecoveryTransactionState.PAUSED
                }
            }
            RecoveryTransactionEvent.ResumeRequested -> {
                if (state == RecoveryTransactionState.VERIFIED) {
                    state = RecoveryTransactionState.RESUMING
                }
            }
            RecoveryTransactionEvent.ResumeCompleted -> {
                if (state == RecoveryTransactionState.RESUMING) {
                    state = RecoveryTransactionState.ACTIVE
                    stableVerificationSamples = 0
                }
            }
            is RecoveryTransactionEvent.Cancelled -> {
                state = RecoveryTransactionState.CANCELLED
                stableVerificationSamples = 0
            }
        }
        return state
    }

    /** Feeds one adapter fingerprint into the shared stable-sample gate. */
    fun observeFingerprint(fingerprint: String): RecoveryTransactionState {
        if (fingerprint.isBlank()) {
            lastObservedFingerprint = ""
            return dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
        }
        if (fingerprint != lastObservedFingerprint) {
            lastObservedFingerprint = fingerprint
            if (state != RecoveryTransactionState.VERIFYING &&
                state != RecoveryTransactionState.PAUSED
            ) {
                dispatch(RecoveryTransactionEvent.VerificationStarted(1))
            } else if (stableVerificationSamples > 0) {
                dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
            }
        }
        return dispatch(RecoveryTransactionEvent.VerificationSample(stable = true))
    }

    fun reset() {
        state = RecoveryTransactionState.ACTIVE
        stableVerificationSamples = 0
        lastObservedFingerprint = ""
    }

    companion object {
        const val REQUIRED_STABLE_SAMPLES = 2
    }
}
