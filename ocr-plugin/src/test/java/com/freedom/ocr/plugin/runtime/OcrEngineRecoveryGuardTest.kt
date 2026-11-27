package com.freedom.ocr.plugin.runtime

import org.junit.Assert.*
import org.junit.Test
import com.paddle.ocr.engine.OcrInferenceDeadline
import com.paddle.ocr.engine.OcrInferenceTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class OcrEngineRecoveryGuardTest {
    @Test fun coldLoadHasIndependentBudgetAndDoesNotConsumeHotInferenceBudget() {
        var now = 100L
        val reasons = mutableListOf<String>()
        val cold = OcrEngineRecoveryGuard(now, { now }, { reasons.add(it) },
            OcrEngineRecoveryGuard.Phase.COLD_LOAD)
        now = 29_999L
        assertFalse(cold.escalateIfDue())
        assertTrue(cold.finish())
        val hot = OcrEngineRecoveryGuard(now, { now }, { reasons.add(it) })
        now += 6999L
        assertFalse(cold.escalateIfDue())
        assertFalse(hot.escalateIfDue())
        now += 1L
        assertTrue(hot.escalateIfDue())
        assertEquals(listOf("EXIT_GRACE_EXPIRED"), reasons)
    }

    @Test fun blockedColdLoadEscalatesOnceAtHardDeadline() {
        val now = AtomicLong(0L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val reasons = mutableListOf<String>()
        val cold = OcrEngineRecoveryGuard(0L, now::get, { reasons.add(it) },
            OcrEngineRecoveryGuard.Phase.COLD_LOAD)
        val worker = Thread {
            entered.countDown()
            release.await()
            cold.finish()
            exited.countDown()
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            now.set(29_999L)
            assertFalse(cold.escalateIfDue())
            now.set(30_000L)
            assertTrue(cold.escalateIfDue())
            assertFalse(cold.escalateIfDue())
            assertTrue(worker.isAlive)
        } finally {
            release.countDown()
            worker.join(2000L)
        }
        assertEquals(0L, exited.count)
        assertFalse(cold.finish())
        assertEquals(listOf("COLD_LOAD_DEADLINE_EXPIRED"), reasons)
    }

    @Test fun lateAcquisitionCannotSucceedEvenWhenWatchdogCallbackHasNotRun() {
        var now = 0L
        val reasons = mutableListOf<String>()
        val cold = OcrEngineRecoveryGuard(0L, { now }, { reasons.add(it) },
            OcrEngineRecoveryGuard.Phase.COLD_LOAD)
        now = 30_000L
        assertFalse(cold.finish())
        assertFalse(cold.escalateIfDue())
        assertEquals(listOf("COLD_LOAD_DEADLINE_EXPIRED"), reasons)
    }

    @Test fun lateHotExitCannotSucceedBeforeDelayedWatchdog() {
        val reasons = mutableListOf<String>()
        val hot = OcrEngineRecoveryGuard(0L, { 7000L }, { reasons.add(it) })
        assertFalse(hot.finish())
        assertFalse(hot.escalateIfDue())
        assertEquals(listOf("EXIT_GRACE_EXPIRED"), reasons)
    }

    @Test fun softCancelAndHardRecoveryShareStartAndRetireOnlyAfterCleanup() {
        var now = 100L
        val events = mutableListOf<String>()
        val deadline = OcrInferenceDeadline({ now }, { events.add("terminate") }, startedAtMs = now)
        val guard = OcrEngineRecoveryGuard(now, { now }, { events.add(it) })
        now = 5100L
        assertTrue(deadline.requestTerminationIfDue())
        assertThrows(OcrInferenceTimeout::class.java) { deadline.complete() }
        deadline.retire()
        now = 6000L
        assertFalse(guard.escalateIfDue())
        events += "actual-exit-and-cleanup"
        assertTrue(guard.finish())
        now = 7100L
        assertFalse(guard.escalateIfDue())
        assertFalse(deadline.requestTerminationIfDue())
        assertEquals(listOf("terminate", "actual-exit-and-cleanup"), events)
    }

    @Test fun blockedNativeTerminationCannotBlockIndependentHardRecovery() {
        val now = AtomicLong(0L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hardExit = CountDownLatch(1)
        val deadline = OcrInferenceDeadline(now::get, {
            entered.countDown()
            release.await()
        })
        val guard = OcrEngineRecoveryGuard(0L, now::get, { hardExit.countDown() })
        now.set(5000L)
        val softWorker = Thread { deadline.requestTerminationIfDue() }.apply {
            isDaemon = true
            start()
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            now.set(7000L)
            // Deliberately do not call deadline.checkActive: the native cancellation owns
            // that lock. A separate guard must remain callable while it is blocked.
            assertTrue(guard.escalateIfDue())
            assertEquals(0L, hardExit.count)
            assertFalse(guard.finish())
            assertTrue(softWorker.isAlive)
        } finally {
            release.countDown()
            softWorker.join(2000L)
        }
        assertFalse(softWorker.isAlive)
        deadline.retire()
        assertFalse(guard.escalateIfDue())
    }

    @Test fun hardRecoveryWaitsForSoftBudgetAndExitGrace() {
        var now = 100L
        val reasons = mutableListOf<String>()
        val guard = OcrEngineRecoveryGuard(now, { now }, { reasons.add(it) })
        now = 7099L
        assertFalse(guard.escalateIfDue())
        now = 7100L
        assertTrue(guard.escalateIfDue())
        assertFalse(guard.escalateIfDue())
        assertFalse(guard.finish())
        assertEquals(listOf("EXIT_GRACE_EXPIRED"), reasons)
    }

    @Test fun actualExitAndCleanupRetireOldWatchdog() {
        var now = 0L
        val reasons = mutableListOf<String>()
        val old = OcrEngineRecoveryGuard(now, { now }, { reasons.add(it) })
        now = 5500L
        assertTrue(old.finish())
        val next = OcrEngineRecoveryGuard(now, { now }, { reasons.add(it) })
        now = 7000L
        assertFalse(old.escalateIfDue())
        assertFalse(next.escalateIfDue())
        assertTrue(reasons.isEmpty())
    }

    @Test fun failedReleaseEscalatesImmediatelyAndCannotPublishSuccess() {
        val reasons = mutableListOf<String>()
        val guard = OcrEngineRecoveryGuard(0L, { 5500L }, { reasons.add(it) })
        assertTrue(guard.escalateReleaseFailure())
        assertFalse(guard.escalateReleaseFailure())
        assertFalse(guard.finish())
        assertEquals(listOf("ENGINE_RELEASE_FAILED"), reasons)
    }
}
