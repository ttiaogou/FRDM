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
    fun serviceReconnectRebindsPersistedReadOnlyPlanWithoutReusingPageProof() {
        val saved = RecoveryScheduleSnapshot(
            taskId = "TASK", routeId = "ROUTE", runGeneration = 3L,
            recoveryReason = "UNKNOWN_PAGE", checkpointId = "checkpoint-1",
            observationCount = 9L, nextObservationAtEpochMs = 30_000L,
            backoffStage = 3, touchActive = false
        )
        var persisted: RecoveryScheduleSnapshot? = saved
        val repository = object : RecoveryScheduleRepository {
            override fun load(): RecoveryScheduleSnapshot? = persisted
            override fun save(snapshot: RecoveryScheduleSnapshot) { persisted = snapshot }
            override fun clear() { persisted = null }
        }
        val scheduler = FakeScheduler()
        val recoveryScheduler = RecoveryScheduler(
            scheduler = scheduler, nowEpochMs = { 1_000L },
            onReadOnlyObservation = {}, scheduleRepository = repository
        )
        val coordinator = RecoveryCheckpointVerificationCoordinator(
            RecoveryEventDispatcher(UnifiedRecoveryTransactionStateMachine()),
            recoveryScheduler
        )
        val checkpoint = checkpoint().copy(runGeneration = 12L)

        assertTrue(coordinator.restore(checkpoint, RecoveryCheckpointRestoreSource.SERVICE_RECONNECT)
            is RecoveryCheckpointRestoreResult.Recovering)
        assertEquals(9L, coordinator.currentObservationPlan()?.observationCount)
        assertEquals(12L, coordinator.currentObservationPlan()?.runGeneration)
        assertEquals(listOf(5_000L), scheduler.pendingDelays())

        assertEquals(
            RecoveryCheckpointVerificationResult.Verifying(1),
            coordinator.observe(evidence(1L).copy(runGeneration = 12L))
        )
        assertEquals(
            RecoveryCheckpointVerificationResult.ResumeReady("WAIT_FOR_LIST_RETURN"),
            coordinator.observe(evidence(2L).copy(runGeneration = 12L))
        )
        assertTrue(coordinator.completeResume())
        assertNull(repository.load())
        assertTrue(scheduler.pendingDelays().isEmpty())
    }

    @Test
    fun unmatchedPersistedPlanIsReplacedWithFreshReadOnlyTimer() {
        val scheduler = FakeScheduler()
        var persisted: RecoveryScheduleSnapshot? = RecoveryScheduleSnapshot(
            taskId = "TASK", routeId = "ROUTE", runGeneration = 4L,
            recoveryReason = "OLD", checkpointId = "different-checkpoint",
            observationCount = 8L, nextObservationAtEpochMs = 12_000L,
            backoffStage = 4, touchActive = false
        )
        val repository = object : RecoveryScheduleRepository {
            override fun load(): RecoveryScheduleSnapshot? = persisted
            override fun save(snapshot: RecoveryScheduleSnapshot) { persisted = snapshot }
            override fun clear() { persisted = null }
        }
        val coordinator = RecoveryCheckpointVerificationCoordinator(
            RecoveryEventDispatcher(UnifiedRecoveryTransactionStateMachine()),
            RecoveryScheduler(scheduler, { 1_000L }, {}, scheduleRepository = repository)
        )
        coordinator.restore(checkpoint(), RecoveryCheckpointRestoreSource.PROCESS_RESTART)

        assertEquals(listOf(500L), scheduler.pendingDelays())
        assertEquals(0L, coordinator.currentObservationPlan()?.observationCount)
        assertEquals("checkpoint-1", repository.load()?.checkpointId)
        assertEquals(7L, repository.load()?.runGeneration)
    }

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
