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
    @Test
    fun pendingReadOnlyRecoveryCannotRunAfterTaskSwitchOrReset() {
        val scheduler = FakeScheduler()
        var navigationRelevant = true
        var inspections = 0
        val coordinator = MomoSayHiListSnapshotCoordinator(
            recoveryPolicy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first }),
            isNavigationRelevant = { navigationRelevant },
            isGestureBusy = { false },
            beforeFreshInspection = {},
            inspectFreshSnapshot = { inspections++ },
            onLog = {},
            taskScheduler = scheduler,
            elapsedRealtimeMs = { 1_000L }
        )
        coordinator.scheduleInspection(30_000L, "DEFERRED_SAFE_TARGET_READ_ONLY_POLL", retry = false)
        val stale = scheduler.scheduled.single().task
        assertTrue(coordinator.isPending)
        navigationRelevant = false
        scheduler.runNext()
        assertEquals(0, inspections)
        assertFalse(coordinator.isPending)

        navigationRelevant = true
        coordinator.scheduleInspection(30_000L, "DEFERRED_SAFE_TARGET_READ_ONLY_POLL", retry = false)
        val afterSwitch = scheduler.scheduled.single().task
        coordinator.reset()
        assertFalse(coordinator.isPending)
        assertTrue(scheduler.scheduled.isEmpty())
        // Simulate platform dispatching a cancelled Runnable after task reset.
        stale.run()
        afterSwitch.run()
        assertEquals(0, inspections)

        coordinator.scheduleInspection(30_000L, "DEFERRED_SAFE_TARGET_READ_ONLY_POLL", retry = false)
        scheduler.runNext()
        assertEquals(1, inspections)
    }

    @Test
    fun delayedListRetryDoesNotScanChatAndCanRestartOnListReturn() {
        val scheduler = FakeScheduler()
        var listNavigationRelevant = true
        var inspections = 0
        val policy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first })
        val coordinator = MomoSayHiListSnapshotCoordinator(
            recoveryPolicy = policy,
            isNavigationRelevant = { listNavigationRelevant },
            isGestureBusy = { false },
            beforeFreshInspection = {},
            inspectFreshSnapshot = { inspections++ },
            onLog = {},
            taskScheduler = scheduler,
            elapsedRealtimeMs = { 1_000L }
        )
        coordinator.handleIncompleteSnapshot("list-window", "LIST_INCOMPLETE", 0, false)
        assertEquals(1, policy.retryAttemptCount())
        listNavigationRelevant = false
        scheduler.runNext()
        assertEquals(0, inspections)
        assertFalse(coordinator.isPending)
        assertEquals(1, policy.retryAttemptCount())
        coordinator.scheduleInspection(500L, "CHAT_EVENT", retry = false)
        assertTrue(scheduler.scheduled.isEmpty())

        listNavigationRelevant = true
        coordinator.scheduleInspection(500L, "LIST_RETURN", retry = false)
        scheduler.runNext()
        assertEquals(1, inspections)
        assertFalse(coordinator.isPending)
    }

    @Test
    fun gestureInFlightDefersReadOnlyPollWithoutInspectingOrChangingRetryBudget() {
        val scheduler = FakeScheduler()
        var gestureBusy = true
        var inspections = 0
        var freshPreparations = 0
        val coordinator = MomoSayHiListSnapshotCoordinator(
            recoveryPolicy = MomoSayHiListSnapshotRecoveryPolicy(sampleDelayMs = { it.first }),
            isNavigationRelevant = { true },
            isGestureBusy = { gestureBusy },
            beforeFreshInspection = { freshPreparations++ },
            inspectFreshSnapshot = { inspections++ },
            onLog = {},
            taskScheduler = scheduler,
            elapsedRealtimeMs = { 1_000L }
        )
        coordinator.scheduleInspection(30_000L, "DEFERRED_SAFE_TARGET_READ_ONLY_POLL", retry = false)
        scheduler.runNext()
        assertEquals(0, inspections)
        assertEquals(0, freshPreparations)
        assertEquals(250L, scheduler.scheduled.single().delayMs)
        gestureBusy = false
        scheduler.runNext()
        assertEquals(1, inspections)
        assertEquals(1, freshPreparations)
        assertFalse(coordinator.isPending)
    }


}
