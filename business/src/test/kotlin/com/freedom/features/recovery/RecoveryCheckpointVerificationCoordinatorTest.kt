package com.freedom.features.recovery

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCheckpointVerificationCoordinatorTest {
    private fun checkpoint() = RecoveryCheckpoint(
        checkpointId = "checkpoint-1",
        taskId = "TASK",
        routeId = "ROUTE",
        runGeneration = 7L,
        executionPhase = "WAIT_FOR_LIST_RETURN",
        targetPackage = "com.immomo.momo",
        pageType = "CHAT_LIST",
        pageFingerprint = "chat-list|rows=3",
        targetIdentityFingerprint = "session-hash",
        businessCursor = "cursor-hash",
        completedRecordKeys = setOf("done-hash"),
        recoveryReason = "PROCESS_RESTART",
        savedAtEpochMs = 1_000L
    )

    private fun evidence(generation: Long, fingerprint: String = "chat-list|rows=3") =
        RecoveryVerificationEvidence(
            packageName = "com.immomo.momo",
            pageType = "CHAT_LIST",
            pageFingerprint = fingerprint,
            targetIdentityFingerprint = "session-hash",
            runGeneration = 7L,
            observationGeneration = generation
        )

    @Test
    fun processRestoreStartsRecoveringWithAQueryableObservationPlan() {
        val scheduler = FakeScheduler()
        val coordinator = coordinator(scheduler)

        val restored = coordinator.restore(checkpoint(), RecoveryCheckpointRestoreSource.PROCESS_RESTART)

        assertTrue(restored is RecoveryCheckpointRestoreResult.Recovering)
        assertEquals(RecoveryTransactionState.RECOVERING, coordinator.state())
        assertEquals(listOf(500L), scheduler.pendingDelays())
    }

    @Test
    fun mismatchedFreshEvidenceReturnsToRecoveringWithoutAuthorizingResume() {
        val scheduler = FakeScheduler()
        val coordinator = coordinator(scheduler)
        coordinator.restore(checkpoint(), RecoveryCheckpointRestoreSource.SERVICE_RECONNECT)

        val outcome = coordinator.observe(evidence(1L, fingerprint = "chat-list|rows=4"))

        assertTrue(outcome is RecoveryCheckpointVerificationResult.Recovering)
        assertEquals(RecoveryTransactionState.RECOVERING, coordinator.state())
        assertFalse(outcome is RecoveryCheckpointVerificationResult.ResumeReady)
    }

    @Test
    fun twoIncreasingFreshSnapshotsAuthorizeOriginalPhaseOnlyThenComplete() {
        val scheduler = FakeScheduler()
        val coordinator = coordinator(scheduler)
        coordinator.restore(checkpoint(), RecoveryCheckpointRestoreSource.PROCESS_RESTART)

        assertEquals(
            RecoveryCheckpointVerificationResult.Verifying(1),
            coordinator.observe(evidence(1L))
        )
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("WAIT_FOR_LIST_RETURN"),
            coordinator.observe(evidence(2L))
        )
        assertEquals(RecoveryTransactionState.RESUMING, coordinator.state())

        assertTrue(coordinator.completeResume())
        assertEquals(RecoveryTransactionState.ACTIVE, coordinator.state())
        assertNull(coordinator.currentObservationPlan())
    }

    @Test
    fun staleEvidenceCannotAdvanceVerification() {
        val scheduler = FakeScheduler()
        val coordinator = coordinator(scheduler)
        coordinator.restore(checkpoint(), RecoveryCheckpointRestoreSource.PROCESS_RESTART)
        coordinator.observe(evidence(2L))

        assertEquals(RecoveryCheckpointVerificationResult.StaleEvidence, coordinator.observe(evidence(1L)))
        assertEquals(RecoveryTransactionState.VERIFYING, coordinator.state())
    }

    private fun coordinator(scheduler: FakeScheduler): RecoveryCheckpointVerificationCoordinator {
        lateinit var recoveryScheduler: RecoveryScheduler
        recoveryScheduler = RecoveryScheduler(scheduler, { 0L }, {})
        return RecoveryCheckpointVerificationCoordinator(
            RecoveryEventDispatcher(UnifiedRecoveryTransactionStateMachine()),
            recoveryScheduler
        )
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingDelays(): List<Long> = entries.filterNot { it.cancelled }.map { it.delay }
    }
}
