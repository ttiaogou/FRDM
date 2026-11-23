package com.freedom.features.recovery

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoverySchedulerTest {
    private fun identity(observationGeneration: Long = 1L) = RecoveryEventIdentity(
        taskId = "TASK",
        routeId = "ROUTE",
        runGeneration = 7L,
        checkpointId = "CHECKPOINT",
        observationGeneration = observationGeneration,
        unknownReason = "UNKNOWN_PAGE"
    )


    @Test
    fun sharedReadOnlyRetryPolicyKeepsExactCadenceWithoutHardStop() {
        val expected = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L)
        assertEquals(expected, (1..7).map(RecoveryScheduler::readOnlyRetryDelayMs))
        assertEquals(30_000L, RecoveryScheduler.readOnlyRetryDelayMs(Int.MAX_VALUE))
        assertEquals(expected.take(5), RecoveryScheduler.fastRetryDelaysMs().toList())
        assertEquals(5, RecoveryScheduler.fastRetryCount)
        assertEquals(30_000L, RecoveryScheduler.longWaitDelayMs)
    }

    @Test
    fun sharedRecoveryDelayArrayCannotBeMutatedByAnAiCaller() {
        val first = RecoveryScheduler.fastRetryDelaysMs()
        first[0] = 99L
        assertEquals(500L, RecoveryScheduler.fastRetryDelaysMs()[0])
        assertEquals(500L, RecoveryScheduler.readOnlyRetryDelayMs(1))
    }

    @Test
    fun sharedReadOnlyRetryPolicyRejectsInvalidAttemptCounts() {
        var rejectedZero = false
        try {
            RecoveryScheduler.readOnlyRetryDelayMs(0)
        } catch (_: IllegalArgumentException) {
            rejectedZero = true
        }
        assertTrue(rejectedZero)
    }

    @Test
    fun beginsWithAQueryableFastObservationPlanAndContinuesWithoutRetryLimit() {
        val scheduler = FakeScheduler()
        val observations = mutableListOf<RecoveryScheduleSnapshot>()
        val recovery = RecoveryScheduler(scheduler, { 1_000L }, observations::add)

        val initial = recovery.begin(identity())
        assertEquals(0L, initial.observationCount)
        assertEquals(1_500L, initial.nextObservationAtEpochMs)
        assertEquals(0, initial.backoffStage)
        assertEquals(listOf(500L), scheduler.pendingDelays())

        scheduler.runNext(500L)
        assertEquals(1L, observations.single().observationCount)
        assertEquals(1, observations.single().backoffStage)
        assertEquals(listOf(1_000L), scheduler.pendingDelays())

        scheduler.runNext(1_000L)
        scheduler.runNext(2_000L)
        scheduler.runNext(5_000L)
        scheduler.runNext(10_000L)
        scheduler.runNext(30_000L)
        scheduler.runNext(30_000L)

        assertEquals(7L, observations.last().observationCount)
        assertEquals(5, observations.last().backoffStage)
        assertEquals(listOf(30_000L), scheduler.pendingDelays())
    }

    @Test
    fun touchUsesLowFrequencyObservationThenAutomaticallyRestoresFastCadence() {
        val scheduler = FakeScheduler()
        val recovery = RecoveryScheduler(scheduler, { 100L }, {})
        val id = identity()
        recovery.begin(id)

        val duringTouch = requireNotNull(recovery.onUserInteractionStarted(id))
        assertTrue(duringTouch.touchActive)
        assertEquals(5, duringTouch.backoffStage)
        assertEquals(listOf(30_000L), scheduler.pendingDelays())

        val afterTouch = requireNotNull(recovery.onUserInteractionEnded(id))
        assertFalse(afterTouch.touchActive)
        assertEquals(0, afterTouch.backoffStage)
        assertEquals(listOf(500L), scheduler.pendingDelays())
    }

    @Test
    fun onlyCurrentTransactionCanBeStoppedByUser() {
        val scheduler = FakeScheduler()
        val recovery = RecoveryScheduler(scheduler, { 0L }, {})
        val id = identity()
        recovery.begin(id)

        assertFalse(recovery.stopByUser(identity(observationGeneration = 0L)))
        assertTrue(recovery.stopByUser(id))
        assertNull(recovery.currentPlan())
        assertTrue(scheduler.pendingDelays().isEmpty())
    }

    @Test
    fun persistsInitialAndBackoffPlansWhenRecoveryObservationsAdvance() {
        val scheduler = FakeScheduler()
        val repository = FakeRecoveryScheduleRepository()
        val recovery = RecoveryScheduler(scheduler, { 1_000L }, {}, scheduleRepository = repository)

        val initial = recovery.begin(identity())
        assertEquals(initial, repository.load())
        assertEquals(1, repository.saveCount)

        scheduler.runNext(500L)
        assertEquals(recovery.currentPlan(), repository.load())
        assertEquals(1L, repository.load()?.observationCount)
        assertEquals(1, repository.load()?.backoffStage)
        assertEquals(listOf(1_000L), scheduler.pendingDelays())
        assertEquals(2, repository.saveCount)
    }

    @Test
    fun persistsTouchCadenceChangesWithoutChangingRecoveryAlgorithm() {
        val scheduler = FakeScheduler()
        val repository = FakeRecoveryScheduleRepository()
        val recovery = RecoveryScheduler(scheduler, { 100L }, {}, scheduleRepository = repository)
        val id = identity()
        recovery.begin(id)

        val touching = recovery.onUserInteractionStarted(id)
        assertEquals(touching, repository.load())
        assertTrue(requireNotNull(repository.load()).touchActive)
        assertEquals(5, repository.load()?.backoffStage)
        assertEquals(listOf(30_000L), scheduler.pendingDelays())

        val ended = recovery.onUserInteractionEnded(id)
        assertEquals(ended, repository.load())
        assertFalse(requireNotNull(repository.load()).touchActive)
        assertEquals(0, repository.load()?.backoffStage)
        assertEquals(listOf(500L), scheduler.pendingDelays())
    }

    @Test
    fun clearsOnlyAfterAcceptedStopOrVerifiedRecoveryAndReplacesOlderPlan() {
        val scheduler = FakeScheduler()
        val repository = FakeRecoveryScheduleRepository()
        val recovery = RecoveryScheduler(scheduler, { 0L }, {}, scheduleRepository = repository)
        val original = identity()
        recovery.begin(original)

        assertFalse(recovery.stopByUser(identity(observationGeneration = 0L)))
        assertEquals(0, repository.clearCount)
        assertEquals(original.taskId, repository.load()?.taskId)
        assertTrue(recovery.completeRecovery(original))
        assertNull(repository.load())
        assertEquals(1, repository.clearCount)

        val newer = original.copy(taskId = "TASK_NEW", checkpointId = "CHECKPOINT_NEW", runGeneration = 8L)
        recovery.begin(newer)
        assertEquals("TASK_NEW", repository.load()?.taskId)
        assertTrue(recovery.stopByUser(newer))
        assertNull(repository.load())
        assertEquals(2, repository.clearCount)
    }

    @Test
    fun missingDurablePlanDoesNotResetTheActiveSameTransactionBackoff() {
        val scheduler = FakeScheduler()
        val recovery = RecoveryScheduler(scheduler, { 1_000L }, {})
        val id = identity()
        recovery.begin(id)
        scheduler.runNext(500L)
        val before = requireNotNull(recovery.currentPlan())
        assertEquals(listOf(1_000L), scheduler.pendingDelays())

        val rebound = recovery.restorePersisted(id)

        assertEquals(before, rebound)
        assertEquals(1L, rebound.observationCount)
        assertEquals(1, rebound.backoffStage)
        assertEquals(listOf(1_000L), scheduler.pendingDelays())
    }

    @Test
    fun restoresSavedBackoffWithNewRunGenerationOnlyForMatchingRouteAndCheckpoint() {
        val scheduler = FakeScheduler()
        val repository = FakeRecoveryScheduleRepository()
        val initial = identity()
        repository.save(
            RecoveryScheduleSnapshot(
                taskId = initial.taskId,
                routeId = initial.routeId,
                runGeneration = 3L,
                recoveryReason = "UNKNOWN_PAGE",
                checkpointId = initial.checkpointId,
                observationCount = 12L,
                nextObservationAtEpochMs = 2_500L,
                backoffStage = 4,
                touchActive = false
            )
        )
        val restored = RecoveryScheduler(scheduler, { 1_000L }, {}, scheduleRepository = repository)
            .restorePersisted(initial)
        assertEquals(7L, restored.runGeneration)
        assertEquals(12L, restored.observationCount)
        assertEquals(4, restored.backoffStage)
        assertEquals(2_500L, restored.nextObservationAtEpochMs)
        assertEquals(listOf(1_500L), scheduler.pendingDelays())
        assertEquals(restored, repository.load())
    }

    @Test
    fun mismatchedCheckpointCannotRestoreOldBackoffOrObservationCount() {
        val scheduler = FakeScheduler()
        val repository = FakeRecoveryScheduleRepository()
        val original = identity()
        repository.save(
            RecoveryScheduleSnapshot("TASK", "ROUTE", 7L, "OLD", "WRONG_CHECKPOINT",
                25L, 9_999L, 5, false)
        )
        val restored = RecoveryScheduler(scheduler, { 1_000L }, {}, scheduleRepository = repository)
            .restorePersisted(original)
        assertEquals(0L, restored.observationCount)
        assertEquals(0, restored.backoffStage)
        assertEquals(original.checkpointId, restored.checkpointId)
        assertEquals(listOf(500L), scheduler.pendingDelays())
    }

    @Test
    fun disconnectCancelsStaleTimerAndReconnectRebindsReadOnlyObservation() {
        val scheduler = FakeScheduler()
        val repository = FakeRecoveryScheduleRepository()
        val observations = mutableListOf<RecoveryScheduleSnapshot>()
        val recovery = RecoveryScheduler(scheduler, { 1_000L }, observations::add, scheduleRepository = repository)
        val oldIdentity = identity()
        recovery.begin(oldIdentity)
        recovery.suspendForServiceDisconnect()
        assertTrue(scheduler.pendingDelays().isEmpty())
        assertEquals(1_500L, repository.load()?.nextObservationAtEpochMs)
        scheduler.runCancelled(500L)
        assertTrue(observations.isEmpty())

        val newIdentity = oldIdentity.copy(runGeneration = 8L)
        val restored = recovery.restorePersisted(newIdentity)
        assertEquals(8L, restored.runGeneration)
        assertEquals(listOf(500L), scheduler.pendingDelays())
        scheduler.runNext(500L)
        assertEquals(1, observations.size)
        assertEquals(8L, observations.single().runGeneration)
    }

    /**
     * 4-B：替代旧的 5s→15s→60s→终止测试。
     * UNKNOWN 未被验证时，统一恢复必须始终保留下一次只读观察。
     */
    @Test
    fun legacyManualHandoffExhaustionIsReplacedByUnboundedReadOnlyBackoff() {
        val scheduler = FakeScheduler()
        val observed = mutableListOf<RecoveryScheduleSnapshot>()
        val recovery = RecoveryScheduler(scheduler, { 0L }, observed::add)
        val id = identity()

        recovery.begin(id)
        val initialDelays = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L)
        initialDelays.forEach { delay ->
            assertEquals(listOf(delay), scheduler.pendingDelays())
            scheduler.runNext(delay)
        }
        repeat(12) {
            assertEquals(listOf(30_000L), scheduler.pendingDelays())
            scheduler.runNext(30_000L)
        }

        assertEquals(17L, recovery.currentPlan()?.observationCount)
        assertEquals(17, observed.size)
        assertEquals(5, recovery.currentPlan()?.backoffStage)
        assertEquals(listOf(30_000L), scheduler.pendingDelays())
        assertEquals(id.checkpointId, recovery.currentPlan()?.checkpointId)
    }

    /**
     * 4-B：替代旧的“人工操作重开第一阶段”测试。
     * 触摸仅降低只读观察频率；松开后无需人工确认即可复位到快速重检。
     */
    @Test
    fun legacyManualInteractionRestartBecomesAutomaticFastRetryWithoutStaleCallbacks() {
        val scheduler = FakeScheduler()
        val observed = mutableListOf<RecoveryScheduleSnapshot>()
        val recovery = RecoveryScheduler(scheduler, { 10L }, observed::add)
        val id = identity()

        recovery.begin(id)
        scheduler.runNext(500L)
        scheduler.runNext(1_000L)
        scheduler.runNext(2_000L)
        scheduler.runNext(5_000L)
        scheduler.runNext(10_000L)
        assertEquals(listOf(30_000L), scheduler.pendingDelays())

        val touching = requireNotNull(recovery.onUserInteractionStarted(id))
        assertTrue(touching.touchActive)
        assertEquals(listOf(30_000L), scheduler.pendingDelays())

        val ended = requireNotNull(recovery.onUserInteractionEnded(id))
        assertFalse(ended.touchActive)
        assertEquals(0, ended.backoffStage)
        assertEquals(listOf(500L), scheduler.pendingDelays())

        val beforeStaleCallback = observed.size
        scheduler.runCancelled(30_000L)
        assertEquals(beforeStaleCallback, observed.size)
        assertEquals(listOf(500L), scheduler.pendingDelays())

        scheduler.runNext(500L)
        assertEquals(beforeStaleCallback + 1, observed.size)
        assertEquals(1, recovery.currentPlan()?.backoffStage)
        assertEquals(listOf(1_000L), scheduler.pendingDelays())
    }

    /**
     * 4-B：替代旧的“第三阶段耗尽后停止”测试。
     * 仅明确用户停止才取消后续观察；重新开始的运行不继承旧回调或旧计数。
     */
    @Test
    fun legacyManualRetryExhaustionBecomesExplicitUserStopAndFreshTransaction() {
        val scheduler = FakeScheduler()
        val observed = mutableListOf<RecoveryScheduleSnapshot>()
        val recovery = RecoveryScheduler(scheduler, { 50L }, observed::add)
        val id = identity()

        recovery.begin(id)
        scheduler.runNext(500L)
        scheduler.runNext(1_000L)
        scheduler.runNext(2_000L)
        scheduler.runNext(5_000L)
        scheduler.runNext(10_000L)
        repeat(4) { scheduler.runNext(30_000L) }

        assertEquals(9L, recovery.currentPlan()?.observationCount)
        assertEquals(listOf(30_000L), scheduler.pendingDelays())
        assertFalse(recovery.stopByUser(id.copy(observationGeneration = 0L)))
        assertEquals(listOf(30_000L), scheduler.pendingDelays())

        assertTrue(recovery.stopByUser(id))
        assertNull(recovery.currentPlan())
        assertTrue(scheduler.pendingDelays().isEmpty())
        val beforeStaleCallback = observed.size
        scheduler.runCancelled(30_000L)
        assertEquals(beforeStaleCallback, observed.size)

        val nextRun = id.copy(runGeneration = id.runGeneration + 1L)
        val fresh = recovery.begin(nextRun)
        assertEquals(0L, fresh.observationCount)
        assertEquals(0, fresh.backoffStage)
        assertEquals(nextRun.runGeneration, fresh.runGeneration)
        assertEquals(listOf(500L), scheduler.pendingDelays())
        scheduler.runNext(500L)
        assertEquals(nextRun.runGeneration, observed.last().runGeneration)
    }

    private class FakeRecoveryScheduleRepository : RecoveryScheduleRepository {
        private var current: RecoveryScheduleSnapshot? = null
        var saveCount: Int = 0
            private set
        var clearCount: Int = 0
            private set

        override fun load(): RecoveryScheduleSnapshot? = current

        override fun save(snapshot: RecoveryScheduleSnapshot) {
            current = snapshot
            saveCount++
        }

        override fun clear() {
            current = null
            clearCount++
        }
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(
            val delayMs: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false
        )

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingDelays(): List<Long> = entries.filterNot { it.cancelled }.map { it.delayMs }

        fun runCancelled(delayMs: Long) {
            val entry = entries.firstOrNull { it.cancelled && it.delayMs == delayMs }
                ?: error("No cancelled task with delay=$delayMs")
            entry.task()
        }

        fun runNext(delayMs: Long) {
            val entry = entries.firstOrNull { !it.cancelled && it.delayMs == delayMs }
                ?: error("No pending task with delay=$delayMs")
            entry.cancelled = true
            entry.task()
        }
    }
}
