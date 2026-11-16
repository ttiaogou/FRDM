package com.freedom.features.aireply

import com.freedom.core.runtime.PageObservationCoordinator as SharedPageObservationCoordinator
import com.freedom.core.runtime.PageObservationSubscription
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * AI Reply registrations in the service-scoped page-observation coordinator plus its greetings
 * arrival watcher. The generic coalescing mechanism is shared with AccountBrowse; page recognition,
 * stability confirmation and low-frequency fallback remain owned by AI Reply.
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
    fallbackDelayMs: () -> Long,
    sharedCoordinator: SharedPageObservationCoordinator? = null
) {
    private val observationCoordinator = sharedCoordinator ?: SharedPageObservationCoordinator(scheduler)
    private val fullPageChannel: PageObservationSubscription = observationCoordinator.registerChannel(
        channelId = FULL_INSPECTION_CHANNEL,
        quietPeriodMs = fullInspectionDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        onObservationRequested = runFullInspection
    )
    private val regionRefreshChannel: PageObservationSubscription = observationCoordinator.registerChannel(
        channelId = REGION_REFRESH_CHANNEL,
        quietPeriodMs = regionRefreshDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        onObservationRequested = runRegionRefresh
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

    fun signalFullInspection() = fullPageChannel.requestSnapshot()

    fun signalRegionRefresh() = regionRefreshChannel.requestSnapshot()

    fun cancelFullInspection() = fullPageChannel.cancel()

    fun cancelRegionRefresh() = regionRefreshChannel.cancel()

    fun isFullInspectionPending(): Boolean = fullPageChannel.isPending()

    fun onPageChangeEvent() = receivedGreetingsWatcher.onPageChangeEvent()

    fun onPageSnapshotAvailable(): ReceivedGreetingsPageArrivalStatus =
        receivedGreetingsWatcher.onPageSnapshotAvailable()

    fun resetReceivedGreetingsWatch() = receivedGreetingsWatcher.reset()

    fun reset() {
        fullPageChannel.cancel()
        regionRefreshChannel.cancel()
        receivedGreetingsWatcher.reset()
    }

    private companion object {
        const val FULL_INSPECTION_CHANNEL = "AI_REPLY_FULL_INSPECTION"
        const val REGION_REFRESH_CHANNEL = "AI_REPLY_KNOWN_REGION_REFRESH"
    }
}
