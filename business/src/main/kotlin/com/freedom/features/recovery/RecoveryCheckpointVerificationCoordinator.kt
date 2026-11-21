package com.freedom.features.recovery

/**
 * Shared checkpoint restore and two-fresh-snapshot verification gate.
 *
 * It never receives nodes or action ports. A caller may restore the original execution phase
 * only after [ResumeReady] and must explicitly acknowledge that resumption completed.
 */
class RecoveryCheckpointVerificationCoordinator(
    private val transaction: RecoveryEventDispatcher,
    private val scheduler: RecoveryScheduler,
    private val onLog: (String) -> Unit = {}
) {
    private var checkpoint: RecoveryCheckpoint? = null
    private var lastObservationGeneration = -1L

    fun restorePersisted(
        repository: RecoveryCheckpointRepository,
        source: RecoveryCheckpointRestoreSource
    ): RecoveryCheckpointRestoreResult = repository.load()?.let { restore(it, source) }
        ?: RecoveryCheckpointRestoreResult.NoCheckpoint

    fun restore(
        restoredCheckpoint: RecoveryCheckpoint,
        source: RecoveryCheckpointRestoreSource
    ): RecoveryCheckpointRestoreResult {
        checkpoint = restoredCheckpoint
        lastObservationGeneration = -1L
        val identity = identityFor(restoredCheckpoint, 0L)
        transaction.activate(identity)
        transaction.dispatch(
            RecoveryTransactionEvent.CheckpointLoaded(
                when (source) {
                    RecoveryCheckpointRestoreSource.SERVICE_RECONNECT -> RecoveryTransactionEvent.Source.SERVICE_RECONNECT
                    RecoveryCheckpointRestoreSource.PROCESS_RESTART -> RecoveryTransactionEvent.Source.PROCESS_RESTART
                    RecoveryCheckpointRestoreSource.PAGE_OBSERVATION -> RecoveryTransactionEvent.Source.PAGE_OBSERVATION
                }
            ),
            identity
        )
        val plan = scheduler.begin(identity)
        onLog(plan.logFields("checkpoint_restored"))
        return RecoveryCheckpointRestoreResult.Recovering(plan)
    }

    fun observe(evidence: RecoveryVerificationEvidence): RecoveryCheckpointVerificationResult {
        val activeCheckpoint = checkpoint ?: return RecoveryCheckpointVerificationResult.NoActiveCheckpoint
        if (evidence.observationGeneration <= lastObservationGeneration) {
            return RecoveryCheckpointVerificationResult.StaleEvidence
        }
        lastObservationGeneration = evidence.observationGeneration
        val identity = identityFor(activeCheckpoint, evidence.observationGeneration)

        if (!evidenceMatches(activeCheckpoint, evidence)) {
            transaction.dispatch(RecoveryTransactionEvent.UnknownPage("RECOVERY_EVIDENCE_MISMATCH"), identity)
            val plan = scheduler.begin(identity)
            onLog(plan.logFields("recovery_evidence_rejected"))
            return RecoveryCheckpointVerificationResult.Recovering(plan)
        }

        if (transaction.state == RecoveryTransactionState.RECOVERING) {
            transaction.dispatch(RecoveryTransactionEvent.VerificationStarted(1), identity)
        }
        transaction.dispatch(RecoveryTransactionEvent.VerificationSample(stable = true), identity)
        if (transaction.stableVerificationSamples < UnifiedRecoveryTransactionStateMachine.REQUIRED_STABLE_SAMPLES) {
            return RecoveryCheckpointVerificationResult.Verifying(transaction.stableVerificationSamples)
        }
        transaction.dispatch(RecoveryTransactionEvent.ResumeRequested, identity)
        return if (transaction.state == RecoveryTransactionState.RESUMING) {
            RecoveryCheckpointVerificationResult.ResumeReady(activeCheckpoint.executionPhase)
        } else {
            RecoveryCheckpointVerificationResult.Recovering(requireNotNull(scheduler.currentPlan()))
        }
    }

    /** Called only after the route has restored the checkpoint's phase without replaying old UI data. */
    fun completeResume(): Boolean {
        val activeCheckpoint = checkpoint ?: return false
        val identity = identityFor(activeCheckpoint, lastObservationGeneration.coerceAtLeast(0L))
        transaction.dispatch(RecoveryTransactionEvent.ResumeCompleted, identity)
        if (transaction.state != RecoveryTransactionState.ACTIVE) return false
        scheduler.completeRecovery(identity)
        checkpoint = null
        lastObservationGeneration = -1L
        return true
    }

    fun state(): RecoveryTransactionState = transaction.state

    fun currentObservationPlan(): RecoveryScheduleSnapshot? = scheduler.currentPlan()

    private fun evidenceMatches(
        checkpoint: RecoveryCheckpoint,
        evidence: RecoveryVerificationEvidence
    ): Boolean =
        evidence.packageName == checkpoint.targetPackage &&
            evidence.pageType == checkpoint.pageType &&
            evidence.pageFingerprint == checkpoint.pageFingerprint &&
            evidence.runGeneration == checkpoint.runGeneration &&
            (checkpoint.targetIdentityFingerprint == null ||
                evidence.targetIdentityFingerprint == checkpoint.targetIdentityFingerprint)

    private fun identityFor(
        checkpoint: RecoveryCheckpoint,
        observationGeneration: Long
    ): RecoveryEventIdentity = RecoveryEventIdentity(
        taskId = checkpoint.taskId,
        routeId = checkpoint.routeId,
        runGeneration = checkpoint.runGeneration,
        checkpointId = checkpoint.checkpointId,
        observationGeneration = observationGeneration,
        unknownReason = checkpoint.recoveryReason
    )
}

sealed interface RecoveryCheckpointRestoreResult {
    data class Recovering(val plan: RecoveryScheduleSnapshot) : RecoveryCheckpointRestoreResult
    data object NoCheckpoint : RecoveryCheckpointRestoreResult
}

sealed interface RecoveryCheckpointVerificationResult {
    data class Recovering(val plan: RecoveryScheduleSnapshot) : RecoveryCheckpointVerificationResult
    data class Verifying(val stableSamples: Int) : RecoveryCheckpointVerificationResult
    data class ResumeReady(val executionPhase: String) : RecoveryCheckpointVerificationResult
    data object StaleEvidence : RecoveryCheckpointVerificationResult
    data object NoActiveCheckpoint : RecoveryCheckpointVerificationResult
}
