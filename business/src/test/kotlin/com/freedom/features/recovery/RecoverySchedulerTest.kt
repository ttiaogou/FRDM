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

        fun runNext(delayMs: Long) {
            val entry = entries.firstOrNull { !it.cancelled && it.delayMs == delayMs }
                ?: error("No pending task with delay=$delayMs")
            entry.cancelled = true
            entry.task()
        }
    }
}
