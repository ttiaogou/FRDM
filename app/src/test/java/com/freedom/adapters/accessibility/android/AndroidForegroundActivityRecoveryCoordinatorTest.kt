package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidForegroundActivityRecoveryCoordinatorTest {
    private val proof = ActivityReadOnlyEvidence(
        "com.immomo.momo.maintab.MaintabActivity", 116, 0L, 1_795_289_513_266L
    )

    @Test
    fun twoIndependentSameWindowSamplesAreRequiredBeforeRestoringActivity() {
        val scheduler = FakeScheduler()
        var current = ""
        var accepted = 0
        val recovery = AndroidForegroundActivityRecoveryCoordinator(
            scheduler, { true }, { current },
            { ActivityIdentityProbeResult.Proven(proof) },
            { accepted++; current = it.activityClass; true }
        )
        recovery.ensureStarted()
        scheduler.runNext()
        assertEquals(0, accepted)
        assertEquals(listOf(0L, 350L), scheduler.delays)
        scheduler.runNext()
        assertEquals(1, accepted)
        assertEquals(proof.activityClass, current)
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun windowIdentityChangeResetsFirstSample() {
        val scheduler = FakeScheduler()
        var next = proof
        var accepted = 0
        val recovery = AndroidForegroundActivityRecoveryCoordinator(
            scheduler, { true }, { "" },
            { ActivityIdentityProbeResult.Proven(next) },
            { accepted++; true }
        )
        recovery.ensureStarted()
        scheduler.runNext()
        next = proof.copy(windowId = 117, windowGeneration = 1L)
        scheduler.runNext()
        assertEquals(0, accepted)
        scheduler.runNext()
        assertEquals(1, accepted)
    }

    @Test
    fun noUsageAccessNeverRestoresActivityAndUsesReadOnlyBackoff() {
        val scheduler = FakeScheduler()
        val logs = mutableListOf<String>()
        val recovery = AndroidForegroundActivityRecoveryCoordinator(
            scheduler, { true }, { "" },
            { ActivityIdentityProbeResult.Pending("USAGE_ACCESS_NOT_GRANTED") },
            { throw AssertionError("Must not accept missing evidence") },
            logs::add
        )
        recovery.ensureStarted()
        scheduler.runNext()
        assertEquals(5_000L, scheduler.delays.last())
        assertTrue(logs.any { it.contains("USAGE_ACCESS_NOT_GRANTED") })
        recovery.stop()
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun disconnectAndRunStopInvalidateOldCallbacks() {
        val scheduler = FakeScheduler()
        var runRequested = true
        var accepted = 0
        val recovery = AndroidForegroundActivityRecoveryCoordinator(
            scheduler, { runRequested }, { "" },
            { ActivityIdentityProbeResult.Proven(proof) },
            { accepted++; true }
        )
        recovery.ensureStarted()
        scheduler.runNext()
        runRequested = false
        scheduler.runNext()
        assertEquals(0, accepted)
        recovery.restart()
        assertEquals(0, scheduler.pendingCount)
    }

    @Test
    fun transitionRequiresNewWindowSample() {
        val scheduler = FakeScheduler()
        var accepted = 0
        val recovery = AndroidForegroundActivityRecoveryCoordinator(
            scheduler, { true }, { "" },
            { ActivityIdentityProbeResult.Proven(proof) },
            { accepted++; true }
        )
        recovery.ensureStarted()
        scheduler.runNext()
        recovery.onWindowTransition()
        scheduler.runNext()
        assertEquals(0, accepted)
        scheduler.runNext()
        assertEquals(1, accepted)
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        private val tasks = mutableListOf<Entry>()
        val delays get() = tasks.map { it.delay }
        val pendingCount get() = tasks.count { !it.cancelled }
        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val item = Entry(delayMillis, task)
            tasks += item
            return ScheduledTask { item.cancelled = true }
        }
        fun runNext() {
            val item = tasks.firstOrNull { !it.cancelled } ?: return
            item.cancelled = true
            item.task()
        }
    }
}
