package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Preserves the former arrival-listener regression contracts against the active watcher.
 * The watcher requests a fresh unified snapshot; it must not read/recognize the page itself.
 */
class ReceivedGreetingsPageArrivalListenerTest {
    @Test
    fun confirmsOnlyAfterAdapterReportsStableListAndDoesNotNavigate() {
        val scheduler = FakeScheduler()
        var snapshotRequests = 0
        val watcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = {},
            onSnapshotRequested = { snapshotRequests++ },
            fallbackDelayMs = { 60_000L }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        )
        assertEquals(0, snapshotRequests)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(350L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(1, snapshotRequests)

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED)
        )
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun unrelatedAndIntermediatePagesScheduleOnlyOneLowFrequencyFallback() {
        val scheduler = FakeScheduler()
        var fallbackRequests = 0
        val watcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = { fallbackRequests++ },
            fallbackDelayMs = { 60_000L }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION)
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(1, fallbackRequests)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        watcher.onPageChangeEvent()
        assertEquals(0, scheduler.pendingCount())

        val transitionWatcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = { fallbackRequests++ },
            fallbackDelayMs = { 60_000L }
        )
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE,
            transitionWatcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE)
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())
    }

    @Test
    fun stabilityRetriesAreBoundedThenLowFrequencyFallbackContinuesIndefinitely() {
        val scheduler = FakeScheduler()
        var snapshotRequests = 0
        var fallbacks = 0
        val watcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = { fallbacks++ },
            onSnapshotRequested = { snapshotRequests++ },
            retryDelayMs = 350L,
            maxStabilityRetries = 8,
            fallbackDelayMs = { 60_000L }
        )

        watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        repeat(8) {
            assertEquals(350L, scheduler.nextDelay())
            scheduler.runNext()
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        }

        assertEquals(8, snapshotRequests) // Initial sample plus eight requested fresh samples.
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(1, fallbacks)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(2, fallbacks)
        assertEquals(1, scheduler.pendingCount())
    }

    @Test
    fun pageChangeCancelsFallbackAndRepeatedEventsDoNotCreateConcurrentRetries() {
        val scheduler = FakeScheduler()
        var snapshotRequests = 0
        val watcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = {},
            onSnapshotRequested = { snapshotRequests++ },
            fallbackDelayMs = { 60_000L }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.NOT_TARGET)
        )
        assertEquals(60_000L, scheduler.nextDelay())

        watcher.onPageChangeEvent()
        assertEquals(0, scheduler.pendingCount())
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        )
        assertEquals(1, scheduler.pendingCount())

        // A fresh sample cancels the prior retry and schedules only one new retry.
        watcher.onPageChangeEvent()
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(0, snapshotRequests)

        scheduler.runNext()
        assertEquals(1, snapshotRequests)
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED)
        )
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun resetCancelsBothPendingTimersAndInvalidatesTheirCallbacks() {
        val scheduler = FakeScheduler()
        var fallbackRequests = 0
        var snapshotRequests = 0
        val watcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = { fallbackRequests++ },
            onSnapshotRequested = { snapshotRequests++ },
            fallbackDelayMs = { 60_000L }
        )

        watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        assertTrue(scheduler.pendingCount() > 0)
        watcher.reset()
        assertEquals(0, scheduler.pendingCount())
        assertFalse(scheduler.hasPendingDelay(350L))
        scheduler.runCancelledCallbacks()
        assertEquals(0, snapshotRequests)
        assertEquals(0, fallbackRequests)
        assertEquals(0, scheduler.pendingCount())

        val fallbackWatcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = { fallbackRequests++ },
            fallbackDelayMs = { 60_000L }
        )
        fallbackWatcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.NOT_TARGET)
        assertTrue(scheduler.hasPendingDelay(60_000L))
        fallbackWatcher.reset()
        assertEquals(0, scheduler.pendingCount())
        scheduler.runCancelledCallbacks()
        assertEquals(0, fallbackRequests)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun freshConfirmedSnapshotSupersedesPendingRetryWithoutDoubleConfirmation() {
        val scheduler = FakeScheduler()
        var snapshotRequests = 0
        val watcher = ReceivedGreetingsPageArrivalWatcher(
            scheduleReadOnlyObservation = { delayMs, callback -> scheduler.schedule(delayMs, callback) },
            onFallbackCheckRequested = {},
            onSnapshotRequested = { snapshotRequests++ },
            fallbackDelayMs = { 60_000L }
        )

        watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED,
            watcher.onPageSnapshotAvailable(ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED)
        )
        assertEquals(0, scheduler.pendingCount())
        scheduler.runCancelledCallbacks()
        assertEquals(0, snapshotRequests)
        assertEquals(0, scheduler.pendingCount())
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingCount(): Int = entries.count { !it.cancelled }
        fun nextDelay(): Long = entries.firstOrNull { !it.cancelled }?.delay ?: -1L
        fun hasPendingDelay(delayMillis: Long): Boolean = entries.any { !it.cancelled && it.delay == delayMillis }

        fun runNext() {
            val entry = entries.firstOrNull { !it.cancelled } ?: error("No pending task")
            entry.cancelled = true
            entry.task()
        }

        fun runCancelledCallbacks() {
            entries.filter { it.cancelled }.toList().forEach { it.task() }
        }
    }
}
