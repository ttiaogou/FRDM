package com.freedom.features.aireply

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * Watches the current Momo page for a structurally confirmed Received Greetings list.
 *
 * Event origin is deliberately not part of this listener's contract: a normalized page-change
 * event only requests a read-only probe. The adapter's page evidence and fresh-snapshot stability
 * checks decide whether the greetings task may take ownership. Short stability retries are bounded;
 * a randomized low-frequency probe keeps watching after that round is exhausted or a page is not
 * yet a target. Runtime event debouncing and this listener's single timer handles coalesce bursts.
 */
internal class ReceivedGreetingsPageArrivalListener(
    private val scheduler: TaskScheduler,
    private val observePage: () -> ReceivedGreetingsPageArrivalStatus,
    private val onConfirmed: () -> Unit,
    private val onFallbackCheckRequested: () -> Unit = {},
    private val retryDelayMs: Long = 350L,
    private val maxStabilityRetries: Int = 8,
    private val fallbackDelayMs: () -> Long = { 60_000L }
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

    /**
     * Called for every relevant target-app page-change event, without consulting InputOrigin.
     * An in-flight short retry is retained; the runtime debouncer coalesces the event burst.
     */
    fun onPageChangeEvent() {
        cancelFallbackCheck()
    }

    /** Called after the runtime has acquired a fresh page snapshot for an event or fallback. */
    fun onPageSnapshotAvailable(): ReceivedGreetingsPageArrivalStatus {
        if (retryTask != null) return ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING
        cancelFallbackCheck()
        return inspectFreshPage()
    }

    fun reset() {
        generation++
        retryTask?.cancel()
        retryTask = null
        cancelFallbackCheck()
        stabilityRetryCount = 0
    }

    private fun inspectFreshPage(): ReceivedGreetingsPageArrivalStatus {
        val status = runCatching { observePage() }
            .getOrDefault(ReceivedGreetingsPageArrivalStatus.UNKNOWN)
        when (status) {
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED -> {
                reset()
                onConfirmed()
            }
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING -> {
                if (stabilityRetryCount < maxStabilityRetries) {
                    scheduleStabilityRetry()
                } else {
                    // One immediate confirmation round is bounded to 1 + maxStabilityRetries
                    // observations. The watchdog continues at low frequency instead of stopping.
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
        retryTask = scheduler.schedule(retryDelayMs) {
            if (expectedGeneration != generation) return@schedule
            retryTask = null
            inspectFreshPage()
        }
    }

    private fun scheduleFallbackCheck() {
        if (fallbackTask != null) return
        val delayMs = fallbackDelayMs().coerceAtLeast(1L)
        val expectedGeneration = generation
        val expectedFallbackGeneration = ++fallbackGeneration
        fallbackTask = scheduler.schedule(delayMs) {
            if (expectedGeneration != generation ||
                expectedFallbackGeneration != fallbackGeneration
            ) return@schedule
            fallbackTask = null
            onFallbackCheckRequested()
            // Keep a watchdog armed. A fresh event/probe cancels it, and a confirmed list resets it.
            scheduleFallbackCheck()
        }
    }

    private fun cancelFallbackCheck() {
        fallbackGeneration++
        fallbackTask?.cancel()
        fallbackTask = null
    }
}
