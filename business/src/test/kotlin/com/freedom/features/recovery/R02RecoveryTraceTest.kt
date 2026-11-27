package com.freedom.features.recovery

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class R02RecoveryTraceTest {
    @Test
    fun boundedBackoffContinuesUntilMatchingUserStop() {
        val scheduler = FakeScheduler()
        val observations = mutableListOf<RecoveryScheduleSnapshot>()
        val recovery = RecoveryScheduler(scheduler, { 1_000L }, observations::add)
        val id = identity()
        val trace = mutableListOf<String>()
        recovery.begin(id)
        val expected = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L)
        for (delay in expected) {
            trace += "next:${scheduler.pendingDelays().single()}"
            scheduler.runNext(delay)
            trace += "observation:${observations.last().observationCount}:${observations.last().backoffStage}"
        }
        trace += "late-stop:${recovery.stopByUser(identity(0L))}"
        trace += "stop:${recovery.stopByUser(id)}:${recovery.currentPlan() == null}:${scheduler.pendingDelays().isEmpty()}"
        assertEquals(listOf("next:500", "observation:1:1", "next:1000", "observation:2:2",
            "next:2000", "observation:3:3", "next:5000", "observation:4:4",
            "next:10000", "observation:5:5", "next:30000", "observation:6:5",
            "next:30000", "observation:7:5", "late-stop:false", "stop:true:true:true"), trace)
    }

    private fun identity(observationGeneration: Long = 1L) = RecoveryEventIdentity(
        taskId = "TASK",
        routeId = "ROUTE",
        runGeneration = 7L,
        checkpointId = "CHECKPOINT",
        observationGeneration = observationGeneration,
        unknownReason = "UNKNOWN_PAGE"
    )

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
