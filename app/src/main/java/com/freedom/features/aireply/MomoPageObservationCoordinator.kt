package com.freedom.features.aireply

import com.freedom.core.runtime.PageObservationCoordinator as SharedPageObservationCoordinator
import com.freedom.core.runtime.PageObservationSubscription
import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * AI Reply registrations in the service-scoped page-observation coordinator plus its greetings
 * arrival scheduler. The generic coalescing mechanism is shared with AccountBrowse; page
 * recognition and stability confirmation are supplied by the unified AI Reply observation path.
 */
internal class MomoPageObservationCoordinator(
    scheduler: TaskScheduler,
    fullInspectionDebounceMs: Long,
    regionRefreshDebounceMs: Long,
    maxEventBurstInspectionWaitMs: Long,
    runFullInspection: () -> Unit,
    runRegionRefresh: () -> Unit,
    onReceivedGreetingsConfirmed: () -> Unit,
    onFallbackCheckRequested: () -> Unit,
    onSnapshotRequested: () -> Unit = {},
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
        onPageConfirmed = onReceivedGreetingsConfirmed,
        onFallbackCheckRequested = onFallbackCheckRequested,
        onSnapshotRequested = onSnapshotRequested,
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

    fun onPageSnapshotAvailable(status: ReceivedGreetingsPageArrivalStatus): ReceivedGreetingsPageArrivalStatus =
        receivedGreetingsWatcher.onPageSnapshotAvailable(status)

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
