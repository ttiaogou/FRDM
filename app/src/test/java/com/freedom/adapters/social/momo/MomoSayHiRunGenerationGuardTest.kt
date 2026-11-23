package com.freedom.adapters.social.momo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiRunGenerationGuardTest {
    @Test
    fun stoppingRunMakesAllPendingOldCallbacksStale() {
        val guard = MomoSayHiRunGenerationGuard()
        guard.invalidatePreviousRun()
        val oldGestureCallback = guard.capture()

        guard.invalidatePreviousRun() // Finish session: old gesture may still return.
        assertFalse(guard.isCurrent(oldGestureCallback))
    }

    @Test
    fun startingNextRunDoesNotRevalidatePreviousCallbacks() {
        val guard = MomoSayHiRunGenerationGuard()
        val old = guard.invalidatePreviousRun()
        guard.invalidatePreviousRun() // Stop
        val current = guard.invalidatePreviousRun() // New run

        assertFalse(guard.isCurrent(old))
        assertTrue(guard.isCurrent(current))
    }

    @Test
    fun callbacksInSameRunRemainAccepted() {
        val guard = MomoSayHiRunGenerationGuard()
        guard.invalidatePreviousRun()
        val first = guard.capture()
        val second = guard.capture()
        assertTrue(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }
}
