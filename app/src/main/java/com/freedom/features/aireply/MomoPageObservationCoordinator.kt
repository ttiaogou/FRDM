package com.freedom.features.aireply

import com.freedom.core.runtime.PageObservationCoordinator as SharedPageObservationCoordinator
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * AI Reply composition point for shared page-snapshot scheduling and the Received Greetings
 * arrival watcher. The generic event-coalescing mechanism lives in Core; the page-specific
 * retry/watchdog remains in the AI Reply feature.
 *
 * This facade retains the existing call surface so the current inbox workflow and its business
 * state transitions are not changed during the architectural extraction.
 */
internal class MomoPageObservationCoordinator(
    scheduler: TaskScheduler,
    fullInspectionDebounceMs: Long,
    regionRefreshDebounceMs: Long,
    maxEventBurstInspectionWaitMs: Long,
    receivedGreetingsPageRecognizer: MomoReceivedGreetingsPageRecognizer,
    runFullInspection: () -> Unit,
    runRegionRefresh: () -> Unit,
    onReceivedGreetingsConfirmed: () -> Unit,
    onFallbackCheckRequested: () -> Unit,
    retryDelayMs: Long = 350L,
    maxStabilityRetries: Int = 8,
    fallbackDelayMs: () -> Long
) {
    private val sharedCoordinator = SharedPageObservationCoordinator(
        scheduler = scheduler,
        fullPageDebounceMs = fullInspectionDebounceMs,
        knownRegionDebounceMs = regionRefreshDebounceMs,
        maxEventBurstWaitMs = maxEventBurstInspectionWaitMs,
        runFullPageSnapshot = runFullInspection,
        runKnownRegionSnapshot = runRegionRefresh
    )
    private val receivedGreetingsWatcher = ReceivedGreetingsPageArrivalWatcher(
        scheduler = scheduler,
        recognizer = receivedGreetingsPageRecognizer,
        onPageConfirmed = onReceivedGreetingsConfirmed,
        onFallbackCheckRequested = onFallbackCheckRequested,
        retryDelayMs = retryDelayMs,
        maxStabilityRetries = maxStabilityRetries,
        fallbackDelayMs = fallbackDelayMs
    )

    fun signalFullInspection() = sharedCoordinator.requestFullPageSnapshot()

    fun signalRegionRefresh() = sharedCoordinator.requestKnownRegionSnapshot()

    fun cancelFullInspection() = sharedCoordinator.cancelFullPageSnapshot()

    fun cancelRegionRefresh() = sharedCoordinator.cancelKnownRegionSnapshot()

    fun isFullInspectionPending(): Boolean = sharedCoordinator.isFullPageSnapshotPending()

    fun onPageChangeEvent() = receivedGreetingsWatcher.onPageChangeEvent()

    fun onPageSnapshotAvailable(): ReceivedGreetingsPageArrivalStatus =
        receivedGreetingsWatcher.onPageSnapshotAvailable()

    fun resetReceivedGreetingsWatch() = receivedGreetingsWatcher.reset()

    fun reset() {
        sharedCoordinator.reset()
        receivedGreetingsWatcher.reset()
    }
}
