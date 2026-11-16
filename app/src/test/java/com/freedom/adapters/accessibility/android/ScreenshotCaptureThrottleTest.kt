package com.freedom.adapters.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotCaptureThrottleTest {
    @Test
    fun computesDelayForAQueuedCaptureInsteadOfDroppingItImmediately() {
        var now = 10_000L
        val throttle = ScreenshotCaptureThrottle(1_100L) { now }

        assertTrue(throttle.tryAcquire())
        now += 400L
        assertEquals(700L, throttle.retryDelayMillis())
        now += 750L
        assertEquals(100L, throttle.retryDelayMillis()) // still in flight
        throttle.complete()
        assertEquals(1L, throttle.retryDelayMillis())
        assertTrue(throttle.tryAcquire())
        throttle.complete()
    }

    @Test
    fun preventsParallelAndTooFrequentScreenshotRequests() {
        var now = 5_000L
        val throttle = ScreenshotCaptureThrottle(1_100L) { now }

        assertTrue(throttle.tryAcquire())
        assertFalse(throttle.tryAcquire()) // request is still in flight
        throttle.complete()
        assertFalse(throttle.tryAcquire()) // minimum interval has not elapsed

        now += 1_100L
        assertTrue(throttle.tryAcquire())
        throttle.complete()
    }
}
