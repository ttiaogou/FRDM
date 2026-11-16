package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoPageObservationCoordinatorTest {
    @Test
    fun coalescesFullAndRegionSnapshotsThroughOneCoordinator() {
        val scheduler = FakeScheduler()
        var fullInspections = 0
        var regionRefreshes = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(listOf(ReceivedGreetingsPageArrivalStatus.NOT_TARGET)),
            runFullInspection = { fullInspections++ },
            runRegionRefresh = { regionRefreshes++ }
        )

        coordinator.signalFullInspection()
        coordinator.signalFullInspection()
        coordinator.signalRegionRefresh()

        assertEquals(2, scheduler.pendingCount())
        assertTrue(coordinator.isFullInspectionPending())
        scheduler.runNextWithDelay(250L)
        assertEquals(1, fullInspections)
        assertEquals(0, regionRefreshes)
        scheduler.runNextWithDelay(450L)
        assertEquals(1, fullInspections)
        assertEquals(1, regionRefreshes)
        assertFalse(coordinator.isFullInspectionPending())
    }

    @Test
    fun centralCoordinatorPreservesBoundedGreetingStabilityRetriesAndConfirmation() {
        val scheduler = FakeScheduler()
        val statuses = ArrayDeque(
            listOf(
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
        )
        var recognizerCalls = 0
        var confirmed = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = statuses,
            onConfirmed = { confirmed++ },
            onFallback = {},
            inspect = { recognizerCalls++; statuses.removeFirst() }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            coordinator.onPageSnapshotAvailable()
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(350L, scheduler.nextDelay())

        coordinator.onPageChangeEvent()
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            coordinator.onPageSnapshotAvailable()
        )
        assertEquals(1, recognizerCalls)
        assertEquals(1, scheduler.pendingCount())

        scheduler.runNext()
        assertEquals(2, recognizerCalls)
        assertEquals(1, confirmed)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun unknownPagesUseRecurringLowFrequencyFallbackAndEventsCancelIt() {
        val scheduler = FakeScheduler()
        var fallbackRequests = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(listOf(ReceivedGreetingsPageArrivalStatus.UNKNOWN)),
            onFallback = { fallbackRequests++ }
        )

        assertEquals(
            ReceivedGreetingsPageArrivalStatus.UNKNOWN,
            coordinator.onPageSnapshotAvailable()
        )
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        scheduler.runNext()
        assertEquals(1, fallbackRequests)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())

        coordinator.onPageChangeEvent()
        assertEquals(0, scheduler.pendingCount())
        assertEquals(1, fallbackRequests)
    }

    @Test
    fun quickRetryBudgetIsBoundedAndFallbackContinuesAfterExhaustion() {
        val scheduler = FakeScheduler()
        var inspections = 0
        var fallbackRequests = 0
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(),
            onFallback = { fallbackRequests++ },
            inspect = {
                inspections++
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING
            }
        )

        coordinator.onPageSnapshotAvailable()
        repeat(8) {
            assertEquals(350L, scheduler.nextDelay())
            scheduler.runNext()
        }

        assertEquals(9, inspections)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())
        scheduler.runNext()
        assertEquals(1, fallbackRequests)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(60_000L, scheduler.nextDelay())
    }

    @Test
    fun resetCancelsSnapshotDebouncersAndArrivalWatchdog() {
        val scheduler = FakeScheduler()
        val coordinator = createCoordinator(
            scheduler = scheduler,
            statuses = ArrayDeque(listOf(ReceivedGreetingsPageArrivalStatus.NOT_TARGET))
        )
        coordinator.signalFullInspection()
        coordinator.onPageSnapshotAvailable()
        assertEquals(2, scheduler.pendingCount())

        coordinator.reset()

        assertEquals(0, scheduler.pendingCount())
        assertFalse(coordinator.isFullInspectionPending())
    }

    private fun createCoordinator(
        scheduler: FakeScheduler,
        statuses: ArrayDeque<ReceivedGreetingsPageArrivalStatus>,
        runFullInspection: () -> Unit = {},
        runRegionRefresh: () -> Unit = {},
        onConfirmed: () -> Unit = {},
        onFallback: () -> Unit = {},
        inspect: (() -> ReceivedGreetingsPageArrivalStatus)? = null
    ): MomoPageObservationCoordinator = MomoPageObservationCoordinator(
        scheduler = scheduler,
        fullInspectionDebounceMs = 250L,
        regionRefreshDebounceMs = 450L,
        maxEventBurstInspectionWaitMs = 1_200L,
        receivedGreetingsPageRecognizer = MomoReceivedGreetingsPageRecognizer {
            inspect?.invoke() ?: statuses.removeFirst()
        },
        runFullInspection = runFullInspection,
        runRegionRefresh = runRegionRefresh,
        onReceivedGreetingsConfirmed = onConfirmed,
        onFallbackCheckRequested = onFallback,
        fallbackDelayMs = { 60_000L }
    )

    private class FakeScheduler : TaskScheduler {
        private data class Entry(
            val delay: Long,
            val task: () -> Unit,
            var cancelled: Boolean = false
        )

        private val entries = mutableListOf<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingCount(): Int = entries.count { !it.cancelled }
        fun nextDelay(): Long = entries.firstOrNull { !it.cancelled }?.delay ?: -1L

        fun runNext() {
            val entry = entries.firstOrNull { !it.cancelled } ?: error("No pending task")
            entry.cancelled = true
            entry.task()
        }

        fun runNextWithDelay(delayMillis: Long) {
            val entry = entries.firstOrNull { !it.cancelled && it.delay == delayMillis }
                ?: error("No pending task with delay=$delayMillis")
            entry.cancelled = true
            entry.task()
        }
    }
}
