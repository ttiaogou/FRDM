package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.recovery.RecoveryEventDispatcher
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager
import com.freedom.features.recovery.RecoveryVerificationEvidence
import com.freedom.features.recovery.UnifiedRecoveryTransactionStateMachine

/**
 * Shared evidence construction and transaction dispatch plumbing.
 * Each route still directly constructs its own RecoveryScheduler and
 * RecoveryCheckpointVerificationCoordinator as required by architecture hard guards.
 * This object holds no active scheduler, verifier, route identity, or page proof.
 */
internal object CheckpointVerificationWiring {
    fun newDispatcher(
        onLog: (String) -> Unit,
        manager: UnifiedRecoveryTransactionManager? = null,
        laneId: String = UnifiedRecoveryTransactionManager.PRIMARY_LANE
    ): RecoveryEventDispatcher =
        RecoveryEventDispatcher(
            UnifiedRecoveryTransactionStateMachine(), onLog,
            sharedManager = manager, managerLaneId = laneId
        )

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
