package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiListSnapshotCoordinatorTest {
    private class FakeScheduler : MomoSayHiSnapshotTaskScheduler {
        data class Scheduled(val task: Runnable, val delayMs: Long)
        val scheduled = mutableListOf<Scheduled>()
        val removed = mutableListOf<Runnable>()

        override fun postDelayed(task: Runnable, delayMs: Long) {
            scheduled += Scheduled(task, delayMs)
        }

        override fun remove(task: Runnable) {
            removed += task
            scheduled.removeAll { it.task === task }
        }

        fun runNext() {
            val next = scheduled.removeAt(0)
            next.task.run()
        }
    }

    @Test
    fun scheduledInspectionWaitsForGestureAndThenReacquiresFreshSnapshot() {
        val scheduler = FakeScheduler()
        val navigationRelevant = true
        val gestureBusy = false
        var nowElapsedMs = 1_000L
        var inspections = 0
        var freshBeforeInspection = 0
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        policy.tryBeginGesture(nowElapsedMs)
        policy.onGestureFinished(nowElapsedMs)
        val coordinator = MomoSayHiListSnapshotCoordinator(
            recoveryPolicy = policy,
            isNavigationRelevant = { navigationRelevant },
            isGestureBusy = { gestureBusy },
            beforeFreshInspection = { freshBeforeInspection++ },
            inspectFreshSnapshot = { inspections++ },
            onLog = {},
            taskScheduler = scheduler,
            elapsedRealtimeMs = { nowElapsedMs }
        )

        coordinator.scheduleInspection(100L, "TEST", retry = true)
        coordinator.scheduleInspection(200L, "DUPLICATE", retry = false)
        assertTrue(coordinator.isPending)
        assertEquals(1, scheduler.scheduled.size)
        assertEquals(100L, scheduler.scheduled.single().delayMs)

        scheduler.runNext()
        assertEquals(250L, scheduler.scheduled.single().delayMs)
        assertEquals(0, inspections)

        nowElapsedMs += 250L
        scheduler.runNext()
        assertEquals(1, inspections)
        assertEquals(1, freshBeforeInspection)
        assertFalse(coordinator.isPending)
    }

    @Test
    fun incompleteTargetedSnapshotEnablesFullWindowFallbackAfterSecondRetry() {
        val scheduler = FakeScheduler()
        val logs = mutableListOf<String>()
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        val coordinator = MomoSayHiListSnapshotCoordinator(
            recoveryPolicy = policy,
            isNavigationRelevant = { true },
            isGestureBusy = { false },
            beforeFreshInspection = {},
            inspectFreshSnapshot = {},
            onLog = logs::add,
            taskScheduler = scheduler,
            elapsedRealtimeMs = { 0L }
        )

        coordinator.handleIncompleteSnapshot("window-a", "SUBTREE_TRUNCATED", 100, true)
        assertFalse(coordinator.forceFullListSnapshot)
        coordinator.handleIncompleteSnapshot("window-a", "SUBTREE_TRUNCATED", 100, true)

        assertTrue(coordinator.forceFullListSnapshot)
        assertEquals(1, scheduler.scheduled.size)
        assertTrue(logs.last().contains("ACTIVE_WINDOW_FALLBACK"))
    }

    @Test
    fun resetCancelsPendingTaskAndClearsFallbackAndPolicyState() {
        val scheduler = FakeScheduler()
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        policy.onSnapshotIncomplete("window-a")
        val coordinator = MomoSayHiListSnapshotCoordinator(
            recoveryPolicy = policy,
            isNavigationRelevant = { true },
            isGestureBusy = { false },
            beforeFreshInspection = {},
            inspectFreshSnapshot = {},
            onLog = {},
            taskScheduler = scheduler,
            elapsedRealtimeMs = { 0L }
        )
        coordinator.requestFullListFallback()
        coordinator.scheduleInspection(100L, "TEST", retry = true)
        val pending = scheduler.scheduled.single().task

        coordinator.reset()

        assertTrue(scheduler.removed.single() === pending)
        assertTrue(scheduler.scheduled.isEmpty())
        assertFalse(coordinator.isPending)
        assertFalse(coordinator.forceFullListSnapshot)
        assertEquals(0, policy.retryAttemptCount())
    }
}
