package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.BrowsePageEvidence
import com.freedom.features.accountbrowse.ports.BrowsePageKind
import com.freedom.features.recovery.RecoveryEventDispatcher
import com.freedom.features.recovery.RecoveryEventIdentity
import com.freedom.features.recovery.RecoveryTransactionEvent
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.RecoveryScheduler
import com.freedom.features.recovery.RecoveryScheduleRepository
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
        dispatcher.reset()
        val eventIdentity = identity("CHECKPOINT_LOADED")
        dispatcher.activate(eventIdentity)
        dispatcher.dispatch(RecoveryTransactionEvent.CheckpointLoaded(source), eventIdentity)
        val plan = recoveryScheduler.restorePersisted(eventIdentity)
        onLog(plan.logFields("recovery_plan_rebound_" + source.name))
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
