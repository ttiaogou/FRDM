package com.freedom.features.recovery

/**
 * Business-neutral lifecycle for a page-recovery transaction.
 *
 * This file intentionally contains no Android, adapter, page-field or traversal dependency.
 * Branch-specific probes and checkpoint policies will be injected in a later integration step.
 */
enum class RecoveryTransactionState {
    ACTIVE,
    RECOVERING,
    VERIFYING,
    RESUMING,
    STOPPED_BY_USER
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
    /** The only terminal event. It may be emitted only from an explicit user stop action. */
    data class StoppedByUser(val reason: String) : RecoveryTransactionEvent

    enum class Source {
        SERVICE_RECONNECT,
        PROCESS_RESTART,
        PAGE_OBSERVATION
    }
}

/** Identity carried by every recovery event, independent of page-fingerprint structure. */
data class RecoveryEventIdentity(
    val taskId: String,
    val routeId: String,
    val runGeneration: Long,
    val checkpointId: String,
    val observationGeneration: Long,
    val unknownReason: String? = null
) {
    init {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(routeId.isNotBlank()) { "routeId must not be blank" }
        require(checkpointId.isNotBlank()) { "checkpointId must not be blank" }
        require(runGeneration >= 0L) { "runGeneration must not be negative" }
        require(observationGeneration >= 0L) { "observationGeneration must not be negative" }
    }
}

/** A recovery event plus the identity of the task/page observation that produced it. */
data class RecoveryTransactionEventEnvelope(
    val identity: RecoveryEventIdentity,
    val event: RecoveryTransactionEvent
)

/**
 * Shared, business-neutral recovery transaction.
 *
 * The page adapters only provide observations. The feature coordinator owns this reducer and
 * decides when the task may resume. It deliberately has no retry limit: a retry schedule is an
 * event, not a terminal condition. Recovery states stop dangerous actions while keeping the
 * transaction available for fresh, read-only observation.
 */
class UnifiedRecoveryTransactionStateMachine(
    initialState: RecoveryTransactionState = RecoveryTransactionState.ACTIVE
) {
    var state: RecoveryTransactionState = initialState
        private set
    var stableVerificationSamples: Int = 0
        private set
    private var lastObservedFingerprint: String = ""
    private var activeEventIdentity: RecoveryEventIdentity? = null

    fun dispatch(event: RecoveryTransactionEvent): RecoveryTransactionState {
        if (state == RecoveryTransactionState.STOPPED_BY_USER &&
            event !is RecoveryTransactionEvent.StoppedByUser
        ) return state
        when (event) {
            is RecoveryTransactionEvent.UnknownPage -> {
                stableVerificationSamples = 0
                state = RecoveryTransactionState.RECOVERING
            }
            is RecoveryTransactionEvent.CheckpointLoaded -> {
                stableVerificationSamples = 0
                state = RecoveryTransactionState.RECOVERING
            }
            is RecoveryTransactionEvent.VerificationStarted -> {
                if (state != RecoveryTransactionState.STOPPED_BY_USER) {
                    stableVerificationSamples = 0
                    state = RecoveryTransactionState.VERIFYING
                }
            }
            is RecoveryTransactionEvent.VerificationSample -> {
                if (state != RecoveryTransactionState.VERIFYING) return state
                stableVerificationSamples = if (event.stable) {
                    stableVerificationSamples + 1
                } else {
                    0
                }
            }
            RecoveryTransactionEvent.UserInteractionStarted -> {
                // Touch is an interaction-policy condition, not a recovery-task state.
            }
            RecoveryTransactionEvent.UserInteractionEnded -> {
                // Observation continues automatically; no manual-resume state is introduced.
            }
            is RecoveryTransactionEvent.RetryScheduled -> {
                if (state != RecoveryTransactionState.STOPPED_BY_USER) {
                    stableVerificationSamples = 0
                    state = RecoveryTransactionState.RECOVERING
                }
            }
            RecoveryTransactionEvent.ResumeRequested -> {
                if (state == RecoveryTransactionState.VERIFYING &&
                    stableVerificationSamples >= REQUIRED_STABLE_SAMPLES
                ) {
                    state = RecoveryTransactionState.RESUMING
                }
            }
            RecoveryTransactionEvent.ResumeCompleted -> {
                if (state == RecoveryTransactionState.RESUMING) {
                    state = RecoveryTransactionState.ACTIVE
                    stableVerificationSamples = 0
                }
            }
            is RecoveryTransactionEvent.StoppedByUser -> {
                state = RecoveryTransactionState.STOPPED_BY_USER
                stableVerificationSamples = 0
            }
        }
        return state
    }

    /** Dispatches an identified event and rejects an older identity without changing state. */
    fun dispatch(envelope: RecoveryTransactionEventEnvelope): RecoveryTransactionState {
        val active = activeEventIdentity
        if (active != null &&
            (active.taskId != envelope.identity.taskId ||
                active.routeId != envelope.identity.routeId ||
                active.runGeneration != envelope.identity.runGeneration ||
                active.checkpointId != envelope.identity.checkpointId ||
                envelope.identity.observationGeneration < active.observationGeneration)
        ) return state
        activeEventIdentity = envelope.identity
        return dispatch(envelope.event)
    }

    /** Feeds one adapter fingerprint into the shared stable-sample gate. */
    fun observeFingerprint(fingerprint: String): RecoveryTransactionState {
        if (fingerprint.isBlank()) {
            lastObservedFingerprint = ""
            return dispatch(RecoveryTransactionEvent.VerificationSample(stable = false))
        }
        if (fingerprint != lastObservedFingerprint) {
            lastObservedFingerprint = fingerprint
            if (state != RecoveryTransactionState.VERIFYING) {
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
        activeEventIdentity = null
    }

    companion object {
        const val REQUIRED_STABLE_SAMPLES = 2
    }
}
