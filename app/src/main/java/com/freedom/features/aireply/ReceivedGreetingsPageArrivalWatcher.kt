package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * Owns only the Received Greetings page-arrival observation scheduling lifecycle.
 *
 * Page identity is supplied by the unified page-observation path. This class never invokes a
 * recognizer or decides page identity; it only requests another fresh observation, performs the
 * existing bounded retry cadence, and keeps the low-frequency idle watchdog armed.
 */
internal class ReceivedGreetingsPageArrivalWatcher(
    private val scheduler: TaskScheduler,
    private val onPageConfirmed: () -> Unit,
    private val onFallbackCheckRequested: () -> Unit,
    private val onSnapshotRequested: () -> Unit = {},
    private val retryDelayMs: Long = 350L,
    private val maxStabilityRetries: Int = 8,
    private val fallbackDelayMs: () -> Long
) {
    private var retryTask: ScheduledTask? = null
    private var fallbackTask: ScheduledTask? = null
    private var generation = 0L
    private var fallbackGeneration = 0L
    private var stabilityRetryCount = 0

    init {
        require(retryDelayMs > 0L)
        require(maxStabilityRetries >= 1)
    }

    /** A relevant page event cancels the idle watchdog but does not bypass snapshot coalescing. */
    fun onPageChangeEvent() {
        cancelFallbackCheck()
    }

    /** Called with the result produced by the unified page-observation path. */
    fun onPageSnapshotAvailable(status: ReceivedGreetingsPageArrivalStatus): ReceivedGreetingsPageArrivalStatus {
        if (retryTask != null) return ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING
        cancelFallbackCheck()
        return handleStatus(status)
    }

    fun reset() {
        generation++
        retryTask?.cancel()
        retryTask = null
        cancelFallbackCheck()
        stabilityRetryCount = 0
    }

    private fun handleStatus(status: ReceivedGreetingsPageArrivalStatus): ReceivedGreetingsPageArrivalStatus {
        when (status) {
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED -> {
                reset()
                onPageConfirmed()
            }
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING -> {
                if (stabilityRetryCount < maxStabilityRetries) {
                    scheduleStabilityRetry()
                } else {
                    // One initial observation plus at most maxStabilityRetries quick retries.
                    // Exhaustion moves to the recurring low-frequency watchdog, never a busy loop.
                    stabilityRetryCount = 0
                    scheduleFallbackCheck()
                }
            }
            else -> {
                stabilityRetryCount = 0
                scheduleFallbackCheck()
            }
        }
        return status
    }

    private fun scheduleStabilityRetry() {
        if (retryTask != null) return
        stabilityRetryCount++
        val expectedGeneration = generation
        retryTask = scheduler.schedule(retryDelayMs) retry@{
            if (expectedGeneration != generation) return@retry
            retryTask = null
            onSnapshotRequested()
        }
    }

    private fun scheduleFallbackCheck() {
        if (fallbackTask != null) return
        val delayMs = fallbackDelayMs().coerceAtLeast(1L)
        val expectedGeneration = generation
        val expectedFallbackGeneration = ++fallbackGeneration
        fallbackTask = scheduler.schedule(delayMs) fallback@{
            if (expectedGeneration != generation ||
                expectedFallbackGeneration != fallbackGeneration
            ) return@fallback
            fallbackTask = null
            onFallbackCheckRequested()
            // Keep a watchdog armed after each bounded check until a page event or confirmation.
            scheduleFallbackCheck()
        }
    }

    private fun cancelFallbackCheck() {
        fallbackGeneration++
        fallbackTask?.cancel()
        fallbackTask = null
    }
}
