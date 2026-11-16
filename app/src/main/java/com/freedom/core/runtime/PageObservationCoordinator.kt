package com.freedom.core.runtime

/**
 * Coordinates event-assisted requests for fresh page snapshots without knowing any feature or
 * target-app page types. Feature runtimes supply the snapshot callbacks and decide how to use the
 * resulting evidence.
 *
 * Full-page and known-region observations are separate channels because a feature may need to
 * invalidate the narrower request when navigation evidence arrives. Both channels coalesce event
 * bursts and enforce a maximum wait through [EventAssistedDebouncer].
 */
class PageObservationCoordinator(
    scheduler: TaskScheduler,
    fullPageDebounceMs: Long,
    knownRegionDebounceMs: Long,
    maxEventBurstWaitMs: Long,
    runFullPageSnapshot: () -> Unit,
    runKnownRegionSnapshot: () -> Unit,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val fullPage = EventAssistedDebouncer(
        scheduler = scheduler,
        quietPeriodMs = fullPageDebounceMs,
        maxWaitMs = maxEventBurstWaitMs,
        nowMillis = nowMillis,
        task = runFullPageSnapshot
    )
    private val knownRegion = EventAssistedDebouncer(
        scheduler = scheduler,
        quietPeriodMs = knownRegionDebounceMs,
        maxWaitMs = maxEventBurstWaitMs,
        nowMillis = nowMillis,
        task = runKnownRegionSnapshot
    )

    fun requestFullPageSnapshot() = fullPage.signal()

    fun requestKnownRegionSnapshot() = knownRegion.signal()

    fun cancelFullPageSnapshot() = fullPage.cancel()

    fun cancelKnownRegionSnapshot() = knownRegion.cancel()

    fun isFullPageSnapshotPending(): Boolean = fullPage.isPending()

    fun isKnownRegionSnapshotPending(): Boolean = knownRegion.isPending()

    /** Cancels all pending observation requests at a feature/runtime lifecycle boundary. */
    fun reset() {
        fullPage.cancel()
        knownRegion.cancel()
    }
}
