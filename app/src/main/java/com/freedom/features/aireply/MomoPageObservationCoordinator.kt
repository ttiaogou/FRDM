package com.freedom.features.aireply

import com.freedom.core.runtime.EventAssistedDebouncer
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * Coordinates read-only Momo page observations for the AI Reply feature.
 *
 * It is the single owner of full-page/known-region event coalescing and the Received Greetings
 * arrival retry/watchdog timers. Snapshot acquisition and business actions are supplied as
 * callbacks, so this coordinator never clicks, scrolls, navigates, or owns task state.
 */
internal class MomoPageObservationCoordinator(
    private val scheduler: TaskScheduler,
    fullInspectionDebounceMs: Long,
    regionRefreshDebounceMs: Long,
    maxEventBurstInspectionWaitMs: Long,
    private val receivedGreetingsPageRecognizer: MomoReceivedGreetingsPageRecognizer,
    runFullInspection: () -> Unit,
    runRegionRefresh: () -> Unit,
    private val onReceivedGreetingsConfirmed: () -> Unit,
    private val onFallbackCheckRequested: () -> Unit,
    private val retryDelayMs: Long = 350L,
    private val maxStabilityRetries: Int = 8,
    private val fallbackDelayMs: () -> Long
) {
    private val fullInspection = EventAssistedDebouncer(
        scheduler = scheduler,
        quietPeriodMs = fullInspectionDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        task = runFullInspection
    )
    private val regionRefresh = EventAssistedDebouncer(
        scheduler = scheduler,
        quietPeriodMs = regionRefreshDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        task = runRegionRefresh
    )

    private var retryTask: ScheduledTask? = null
    private var fallbackTask: ScheduledTask? = null
    private var generation = 0L
    private var fallbackGeneration = 0L
    private var stabilityRetryCount = 0

    init {
        require(retryDelayMs > 0L)
        require(maxStabilityRetries >= 1)
    }

    fun signalFullInspection() = fullInspection.signal()
    fun signalRegionRefresh() = regionRefresh.signal()
    fun cancelFullInspection() = fullInspection.cancel()
    fun cancelRegionRefresh() = regionRefresh.cancel()
    fun isFullInspectionPending(): Boolean = fullInspection.isPending()

    /** A relevant page event cancels the idle watchdog but never bypasses snapshot coalescing. */
    fun onPageChangeEvent() {
        cancelFallbackCheck()
    }

    /** Called only after the observation path has obtained a fresh page snapshot. */
    fun onPageSnapshotAvailable(): ReceivedGreetingsPageArrivalStatus {
        if (retryTask != null) return ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING
        cancelFallbackCheck()
        return inspectFreshPage()
    }

    /** Reset only the Received Greetings arrival watcher, preserving other page observations. */
    fun resetReceivedGreetingsWatch() {
        generation++
        retryTask?.cancel()
        retryTask = null
        cancelFallbackCheck()
        stabilityRetryCount = 0
    }

    /** Cancel all observation work at a runtime boundary. */
    fun reset() {
        fullInspection.cancel()
        regionRefresh.cancel()
        resetReceivedGreetingsWatch()
    }

    private fun inspectFreshPage(): ReceivedGreetingsPageArrivalStatus {
        val status = receivedGreetingsPageRecognizer.recognize()
        when (status) {
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED -> {
                resetReceivedGreetingsWatch()
                onReceivedGreetingsConfirmed()
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
            inspectFreshPage()
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
