package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.recovery.RecoveryEventDispatcher
import com.freedom.features.recovery.RecoveryEventIdentity
import com.freedom.features.recovery.RecoveryTransactionEvent
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.RecoveryScheduler
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryCheckpoint
import com.freedom.features.recovery.RecoveryCheckpointRestoreSource
import com.freedom.features.recovery.RecoveryCheckpointVerificationCoordinator
import com.freedom.features.recovery.RecoveryCheckpointVerificationResult
import com.freedom.features.recovery.RecoveryVerificationEvidence
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.recovery.UnifiedRecoveryTransactionStateMachine

/**
 * Owns dynamic-page recovery verification without owning page inspection or browse actions.
 * The caller supplies fresh page evidence and remains responsible for scheduling and actions.
 */
class DynamicPageRecovery(
    scheduler: TaskScheduler,
    private val targetPackage: () -> String,
    private val currentRunGeneration: () -> Long,
    private val onLog: (String) -> Unit = {},
    onReadOnlyObservation: () -> Unit = {},
    scheduleRepository: RecoveryScheduleRepository? = null
) {
    private val dispatcher = RecoveryEventDispatcher(
        UnifiedRecoveryTransactionStateMachine(),
        onLog
    )

    private val taskId = "ACCOUNT_BROWSE"
    private val routeId = "account-browse"
    private val checkpointId = "account-browse-checkpoint"
    private val recoveryScheduler = RecoveryScheduler(
        scheduler = scheduler,
        nowEpochMs = System::currentTimeMillis,
        onReadOnlyObservation = { onReadOnlyObservation() },
        onLog = onLog,
        scheduleRepository = scheduleRepository
    )

    // The restored-checkpoint branch uses the same dispatcher and read-only scheduler as UNKNOWN.
    // The ordinary dynamic-page recovery branch is unchanged.
    private var checkpointVerifier: RecoveryCheckpointVerificationCoordinator? = null
    private var verifiedCheckpointFingerprint: String? = null
    private var checkpointObservationGeneration: Long = 0L
    private var checkpointRestoreSource: RecoveryCheckpointRestoreSource =
        RecoveryCheckpointRestoreSource.PROCESS_RESTART

    fun restoreFromCheckpoint(savedAtEpochMs: Long) {
        restoreObservationPlan(
            if (savedAtEpochMs > 0L) {
                RecoveryTransactionEvent.Source.PROCESS_RESTART
            } else {
                RecoveryTransactionEvent.Source.SERVICE_RECONNECT
            }
        )
    }

    fun suspendForServiceDisconnect() = recoveryScheduler.suspendForServiceDisconnect()

    fun restoreAfterServiceReconnect() =
        restoreObservationPlan(RecoveryTransactionEvent.Source.SERVICE_RECONNECT)

    private fun restoreObservationPlan(source: RecoveryTransactionEvent.Source) {
        // Verification samples and event generation from the old service are no longer valid.
        checkpointVerifier = null
        verifiedCheckpointFingerprint = null
        checkpointObservationGeneration = 0L
        checkpointRestoreSource = if (source == RecoveryTransactionEvent.Source.SERVICE_RECONNECT) {
            RecoveryCheckpointRestoreSource.SERVICE_RECONNECT
        } else {
            RecoveryCheckpointRestoreSource.PROCESS_RESTART
        }
        dispatcher.reset()
        val eventIdentity = identity("CHECKPOINT_LOADED")
        dispatcher.activate(eventIdentity)
        dispatcher.dispatch(RecoveryTransactionEvent.CheckpointLoaded(source), eventIdentity)
        val plan = recoveryScheduler.restorePersisted(eventIdentity)
        onLog(plan.logFields("recovery_plan_rebound_" + source.name))
    }

    /**
     * A persisted AccountBrowse checkpoint is verified only from newly read, confirmed feed
     * evidence. Mapping and route checks remain with the feature runtime; this is a second
     * defense before giving fresh evidence to the shared checkpoint verification coordinator.
     *
     * A changing feed fingerprint resets only its verification samples; the business checkpoint
     * and existing read-only backoff schedule are retained.
     */
    fun observeRestoredCheckpoint(
        checkpoint: RecoveryCheckpoint,
        page: BrowsePageEvidence
    ): Boolean {
        if (checkpoint.taskId != taskId ||
            checkpoint.routeId != routeId ||
            checkpoint.checkpointId != checkpointId ||
            checkpoint.runGeneration != currentRunGeneration() ||
            page.packageName != targetPackage() ||
            page.pageKind != BrowsePageKind.DYNAMIC_FEED ||
            page.confidence < 80 ||
            !page.windowAvailable ||
            page.fingerprint.isBlank() ||
            checkpoint.pageFingerprint.isBlank()
        ) {
            invalidateRestoredCheckpointEvidence()
            return false
        }
        if (checkpointVerifier == null ||
            verifiedCheckpointFingerprint != checkpoint.pageFingerprint
        ) {
            // The expected fingerprint is established from a fresh, route-verified feed read.
            // Rebase on a changed feed without losing the existing business checkpoint.
            dispatcher.reset()
            val verifier = RecoveryCheckpointVerificationCoordinator(
                transaction = dispatcher,
                scheduler = recoveryScheduler,
                onLog = onLog
            )
            checkpointVerifier = verifier
            verifiedCheckpointFingerprint = checkpoint.pageFingerprint
            verifier.restore(checkpoint, checkpointRestoreSource)
        }
        check(checkpointObservationGeneration < Long.MAX_VALUE) {
            "Recovery observation generation exhausted"
        }
        val generation = ++checkpointObservationGeneration
        val result = requireNotNull(checkpointVerifier).observe(
            RecoveryVerificationEvidence(
                packageName = page.packageName,
                pageType = page.pageKind.name,
                pageFingerprint = checkpoint.pageFingerprint,
                targetIdentityFingerprint = checkpoint.targetIdentityFingerprint,
                runGeneration = currentRunGeneration(),
                observationGeneration = generation
            )
        )
        if (result !is RecoveryCheckpointVerificationResult.ResumeReady ||
            result.executionPhase != checkpoint.executionPhase
        ) return false
        if (!requireNotNull(checkpointVerifier).completeResume()) return false
        checkpointVerifier = null
        verifiedCheckpointFingerprint = null
        onLog("recoveryAction=account_browse_shared_checkpoint_verified; runGeneration=${currentRunGeneration()}")
        return true
    }

    /** A rejected fresh observation must reset the shared verifier's stable-sample counter. */
    fun invalidateRestoredCheckpointEvidence() {
        val verifier = checkpointVerifier ?: return
        check(checkpointObservationGeneration < Long.MAX_VALUE) {
            "Recovery observation generation exhausted"
        }
        verifier.observe(
            RecoveryVerificationEvidence(
                packageName = "",
                pageType = BrowsePageKind.UNKNOWN.name,
                pageFingerprint = "",
                targetIdentityFingerprint = null,
                runGeneration = currentRunGeneration(),
                observationGeneration = ++checkpointObservationGeneration
            )
        )
    }

    fun observePage(page: BrowsePageEvidence): RecoveryTransactionState {
        val unknown = page.pageKind == BrowsePageKind.UNKNOWN ||
            page.packageName.isBlank() ||
            page.packageName != targetPackage() ||
            page.fingerprint.isBlank() ||
            page.confidence < 80
        if (!unknown) return dispatcher.state

        dispatcher.activate(identity(page.reason.ifBlank { "ACCOUNT_BROWSE_PAGE_UNKNOWN" }))
        dispatcher.dispatch(
            RecoveryTransactionEvent.UnknownPage(
                page.reason.ifBlank { "ACCOUNT_BROWSE_PAGE_UNKNOWN" }
            )
        )
        recoveryScheduler.begin(identity(page.reason.ifBlank { "ACCOUNT_BROWSE_PAGE_UNKNOWN" }))
        return dispatcher.state
    }

    fun observeFingerprint(fingerprint: String): Boolean {
        if (fingerprint.isNotBlank() && dispatcher.state == RecoveryTransactionState.RECOVERING) {
            dispatcher.dispatch(RecoveryTransactionEvent.VerificationStarted(1))
        }
        dispatcher.observeFingerprint(fingerprint)
        if (dispatcher.state != RecoveryTransactionState.VERIFYING ||
            dispatcher.stableVerificationSamples < UnifiedRecoveryTransactionStateMachine.REQUIRED_STABLE_SAMPLES
        ) {
            return false
        }
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeRequested)
        dispatcher.dispatch(RecoveryTransactionEvent.ResumeCompleted)
        recoveryScheduler.completeRecovery(identity("RECOVERY_VERIFIED"))
        return true
    }

    fun state(): RecoveryTransactionState = dispatcher.state

    fun stableSamples(): Int = dispatcher.stableVerificationSamples

    fun hasPendingVerification(): Boolean = dispatcher.state != RecoveryTransactionState.ACTIVE

    fun onUserInteractionStarted() {
        dispatcher.currentIdentity()?.let(recoveryScheduler::onUserInteractionStarted)
    }

    fun onUserInteractionEnded() {
        dispatcher.currentIdentity()?.let(recoveryScheduler::onUserInteractionEnded)
    }

    fun reset() {
        dispatcher.currentIdentity()?.let(recoveryScheduler::stopByUser)
        checkpointVerifier = null
        verifiedCheckpointFingerprint = null
        checkpointObservationGeneration = 0L
        dispatcher.reset()
    }

    private fun identity(reason: String): RecoveryEventIdentity = RecoveryEventIdentity(
        taskId = taskId,
        routeId = routeId,
        runGeneration = currentRunGeneration().coerceAtLeast(0L),
        checkpointId = checkpointId,
        observationGeneration = dispatcher.stableVerificationSamples.toLong(),
        unknownReason = reason
    )
}
