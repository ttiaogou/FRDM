package com.freedom.adapters.accessibility.android

/** Bounded request gate for Android's rate-limited AccessibilityService screenshot API. */
internal class ScreenshotCaptureThrottle(
    private val minimumIntervalMillis: Long,
    private val nowMillis: () -> Long
) {
    private var lastRequestAtMillis: Long? = null
    private var inFlight = false

    init {
        require(minimumIntervalMillis >= 0L)
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowMillis()
        val previous = lastRequestAtMillis
        if (inFlight || (previous != null && now - previous < minimumIntervalMillis)) {
            return false
        }
        lastRequestAtMillis = now
        inFlight = true
        return true
    }

    @Synchronized
    fun retryDelayMillis(): Long {
        val previous = lastRequestAtMillis ?: return 1L
        val elapsed = (nowMillis() - previous).coerceAtLeast(0L)
        val intervalRemaining = (minimumIntervalMillis - elapsed).coerceAtLeast(1L)
        // If a screenshot is still in flight after the normal interval, poll gently rather
        // than immediately failing the queued OCR read.
        return if (inFlight) maxOf(intervalRemaining, IN_FLIGHT_RETRY_POLL_MS)
        else intervalRemaining
    }

    @Synchronized
    fun complete() {
        inFlight = false
    }

    private companion object {
        const val IN_FLIGHT_RETRY_POLL_MS = 100L
    }
}
