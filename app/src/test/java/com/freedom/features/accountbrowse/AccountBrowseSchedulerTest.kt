package com.freedom.features.accountbrowse

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseSchedulerTest {
    @Test
    fun refusesToScheduleWhenTheEligibilityGateIsClosed() {
        val scheduler = ManualTaskScheduler()
        val browseScheduler = AccountBrowseScheduler(scheduler, canSchedule = { false }) {}

        browseScheduler.schedule(100L)

        assertEquals(0, scheduler.tasks.size)
    }

    @Test
    fun keepsTheFirstPendingStepInsteadOfReplacingIt() {
        val scheduler = ManualTaskScheduler()
        var steps = 0
        val browseScheduler = AccountBrowseScheduler(scheduler, canSchedule = { true }) { steps++ }

        browseScheduler.schedule(100L)
        browseScheduler.schedule(200L)

        assertEquals(1, scheduler.tasks.size)
        assertEquals(100L, scheduler.tasks.single().delayMillis)
        assertEquals(0, steps)
    }

    @Test
    fun clearsPendingBeforeRunningTheStepAndAllowsTheNextSchedule() {
        val scheduler = ManualTaskScheduler()
        var steps = 0
        val browseScheduler = AccountBrowseScheduler(scheduler, canSchedule = { true }) { steps++ }

        browseScheduler.schedule(100L)
        scheduler.run(0)
        browseScheduler.schedule(200L)

        assertEquals(1, steps)
        assertEquals(2, scheduler.tasks.size)
    }

    @Test
    fun cancellationCancelsOnlyTheActiveTaskAndIgnoresLateCallbacks() {
        val scheduler = ManualTaskScheduler()
        var steps = 0
        val browseScheduler = AccountBrowseScheduler(scheduler, canSchedule = { true }) { steps++ }

        browseScheduler.schedule(100L)
        browseScheduler.cancel()
        browseScheduler.schedule(200L)
        scheduler.run(0, evenIfCancelled = true)

        assertTrue(scheduler.tasks[0].cancelled)
        assertFalse(scheduler.tasks[1].cancelled)
        assertEquals(0, steps)
    }

    @Test
    fun supportsSchedulersThatExecuteTheCallbackImmediately() {
        var steps = 0
        val immediateScheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
                task()
                return ScheduledTask { }
            }
        }
        val browseScheduler = AccountBrowseScheduler(immediateScheduler, canSchedule = { true }) { steps++ }

        browseScheduler.schedule(-10L)
        browseScheduler.schedule(20L)

        assertEquals(2, steps)
    }

    private class ManualTaskScheduler : TaskScheduler {
        data class Entry(
            val delayMillis: Long,
            val callback: () -> Unit,
            var cancelled: Boolean = false
        )

        val tasks = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            tasks += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun run(index: Int, evenIfCancelled: Boolean = false) {
            val entry = tasks[index]
            if (!entry.cancelled || evenIfCancelled) entry.callback()
        }
    }
}
