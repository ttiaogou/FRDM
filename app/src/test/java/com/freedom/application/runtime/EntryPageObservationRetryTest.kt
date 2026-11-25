package com.freedom.application.runtime

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class EntryPageObservationRetryTest {
    private class Scheduler : TaskScheduler {
        data class Entry(val delay: Long, val callback: () -> Unit, var cancelled: Boolean = false)
        val entries = mutableListOf<Entry>()
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }
    }

    @Test
    fun unavailableEntryKeepsOneTimerAndContinuesAtLowFrequencyWithoutEvents() {
        val scheduler = Scheduler()
        var reads = 0
        lateinit var retry: EntryPageObservationRetry
        retry = EntryPageObservationRetry(scheduler::schedule, { 7L }, { true }) {
            reads++
            retry.update(true)
        }
        retry.update(true)
        retry.update(true)
        assertEquals(1, scheduler.entries.size)
        val delays = listOf(500L, 1000L, 2000L, 5000L, 10000L, 30000L, 30000L)
        delays.forEachIndexed { index, delay ->
            assertEquals(delay, scheduler.entries[index].delay)
            scheduler.entries[index].callback()
        }
        assertEquals(delays.size, reads)
        retry.update(false)
        scheduler.entries.last().callback()
        assertEquals(delays.size, reads)
    }

    @Test
    fun readyRouteKeepsOneLowFrequencyObservationAndFailureRearmsFastRetry() {
        val scheduler = Scheduler()
        var reads = 0
        lateinit var retry: EntryPageObservationRetry
        retry = EntryPageObservationRetry(scheduler::schedule, { 7L }, { true }) {
            reads++
            retry.update(false, keepObserving = true)
        }
        retry.update(false, keepObserving = true)
        repeat(100) { retry.update(false, keepObserving = true) }
        assertEquals(1, scheduler.entries.size)
        assertEquals(30000L, scheduler.entries[0].delay)
        scheduler.entries[0].callback()
        assertEquals(1, reads)
        assertEquals(30000L, scheduler.entries[1].delay)

        retry.update(true, keepObserving = true)
        assertEquals(true, scheduler.entries[1].cancelled)
        assertEquals(500L, scheduler.entries[2].delay)
        scheduler.entries[1].callback()
        assertEquals(1, reads)
        scheduler.entries[2].callback()
        assertEquals(2, reads)
        assertEquals(30000L, scheduler.entries[3].delay)
        retry.cancel()
        scheduler.entries[3].callback()
        assertEquals(2, reads)
    }

    @Test
    fun stopAndGenerationChangeRejectAlreadyQueuedCallbacks() {
        val scheduler = Scheduler()
        var allowed = true
        var generation = 7L
        var reads = 0
        val retry = EntryPageObservationRetry(scheduler::schedule, { generation }, { allowed }) { reads++ }
        retry.update(true)
        generation++
        scheduler.entries[0].callback()
        assertEquals(0, reads)
        retry.update(true)
        assertEquals(500L, scheduler.entries[1].delay)
        allowed = false
        retry.cancel()
        scheduler.entries[1].callback()
        retry.update(true)
        assertEquals(0, reads)
        assertEquals(2, scheduler.entries.size)
    }
}
