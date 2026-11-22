package com.freedom.features.aireply

import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.recovery.RecoveryCheckpoint
import com.freedom.features.recovery.RecoveryCheckpointRestoreSource
import com.freedom.features.recovery.RecoveryCheckpointVerificationCoordinator
import com.freedom.features.recovery.RecoveryEventDispatcher
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryScheduler
import com.freedom.features.recovery.RecoveryVerificationEvidence
import com.freedom.features.recovery.UnifiedRecoveryTransactionStateMachine

/**
 * Common read-only recovery plumbing for inbox and received-greetings checkpoints.
 * This factory owns no shared state: every caller keeps its own scheduler and verifier,
 * while its business adapter, page/route checks, fingerprint, phase and lease stay local.
 */
internal object CheckpointVerificationWiring {
    fun createScheduler(
        scheduler: TaskScheduler,
        onReadOnlyObservation: () -> Unit,
        onLog: (String) -> Unit,
        scheduleRepository: RecoveryScheduleRepository?
    ): RecoveryScheduler = RecoveryScheduler(
        scheduler = scheduler,
        nowEpochMs = System::currentTimeMillis,
        onReadOnlyObservation = { onReadOnlyObservation() },
        onLog = onLog,
        scheduleRepository = scheduleRepository
    )

    fun restoreVerifier(
        checkpoint: RecoveryCheckpoint,
        restoreSource: RecoveryCheckpointRestoreSource,
        schedule: RecoveryScheduler,
        onLog: (String) -> Unit
    ): RecoveryCheckpointVerificationCoordinator = RecoveryCheckpointVerificationCoordinator(
        transaction = RecoveryEventDispatcher(
            UnifiedRecoveryTransactionStateMachine(),
            onLog
        ),
        scheduler = schedule,
        onLog = onLog
    ).also { it.restore(checkpoint, restoreSource) }

    fun verifiedEvidence(
        snapshot: ChatPageSnapshot,
        pageFingerprint: String,
        runGeneration: Long,
        observationGeneration: Long
    ): RecoveryVerificationEvidence = RecoveryVerificationEvidence(
        packageName = snapshot.packageName,
        pageType = snapshot.pageKind.name,
        pageFingerprint = pageFingerprint,
        targetIdentityFingerprint = null,
        runGeneration = runGeneration,
        observationGeneration = observationGeneration
    )

    fun rejectedEvidence(
        runGeneration: Long,
        observationGeneration: Long
    ): RecoveryVerificationEvidence = RecoveryVerificationEvidence(
        packageName = "",
        pageType = ChatPageKind.UNKNOWN.name,
        pageFingerprint = "",
        targetIdentityFingerprint = null,
        runGeneration = runGeneration,
        observationGeneration = observationGeneration
    )
}
