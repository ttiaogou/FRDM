package com.freedom.ocr.plugin.runtime

import org.junit.Assert.*
import org.junit.Test

class OcrEngineRecoveryGuardTest {
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
