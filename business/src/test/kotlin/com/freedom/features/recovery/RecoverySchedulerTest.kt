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
