package com.freedom.features.aireply

import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.PageObservationSubscription
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * AI Reply registrations in the service-scoped page-observation coordinator plus its greetings
 * arrival scheduler. The generic coalescing mechanism is shared with AccountBrowse; page
 * recognition and stability confirmation are supplied by the unified AI Reply observation path.
 */
internal class MomoPageObservationCoordinator(
    sharedCoordinator: PageObservationCoordinator,
    fullInspectionDebounceMs: Long,
    regionRefreshDebounceMs: Long,
    maxEventBurstInspectionWaitMs: Long,
    runFullInspection: () -> Unit,
    runRegionRefresh: () -> Unit,
    onFallbackCheckRequested: () -> Unit,
    onSnapshotRequested: () -> Unit = {},
    retryDelayMs: Long = 350L,
    maxStabilityRetries: Int = 8,
    fallbackDelayMs: () -> Long,
    isObservationActive: () -> Boolean = { true }
) {
    private val observationCoordinator = sharedCoordinator
    private val fullPageChannel: PageObservationSubscription = observationCoordinator.registerChannel(
        channelId = FULL_INSPECTION_CHANNEL,
        quietPeriodMs = fullInspectionDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        onObservationRequested = runFullInspection,
        isEnabled = isObservationActive
    )
    private val regionRefreshChannel: PageObservationSubscription = observationCoordinator.registerChannel(
        channelId = REGION_REFRESH_CHANNEL,
        quietPeriodMs = regionRefreshDebounceMs,
        maxWaitMs = maxEventBurstInspectionWaitMs,
        onObservationRequested = runRegionRefresh,
        isEnabled = isObservationActive
    )
    private val receivedGreetingsWatcher = ReceivedGreetingsPageArrivalWatcher(
        scheduleReadOnlyObservation = observationCoordinator::scheduleReadOnlyObservation,
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
