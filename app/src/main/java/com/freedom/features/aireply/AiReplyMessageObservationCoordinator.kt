package com.freedom.features.aireply

import com.freedom.core.runtime.EventAssistedDebouncer
import com.freedom.core.runtime.TaskScheduler

/** Coalesces message-page and known-region observation bursts without owning message/session state. */
internal class AiReplyMessageObservationCoordinator(
    scheduler: TaskScheduler,
    fullInspectionDebounceMs: Long,
    regionRefreshDebounceMs: Long,
    maxWaitMs: Long,
    runFullInspection: () -> Unit,
    runRegionRefresh: () -> Unit
) {
    private val fullInspection = EventAssistedDebouncer(
        scheduler = scheduler,
        quietPeriodMs = fullInspectionDebounceMs,
        maxWaitMs = maxWaitMs,
        task = runFullInspection
    )
    private val regionRefresh = EventAssistedDebouncer(
        scheduler = scheduler,
        quietPeriodMs = regionRefreshDebounceMs,
        maxWaitMs = maxWaitMs,
        task = runRegionRefresh
    )

    fun signalFullInspection() = fullInspection.signal()
    fun signalRegionRefresh() = regionRefresh.signal()
    fun cancelFullInspection() = fullInspection.cancel()
    fun cancelRegionRefresh() = regionRefresh.cancel()
    fun isFullInspectionPending(): Boolean = fullInspection.isPending()
    fun cancelAll() {
        fullInspection.cancel()
        regionRefresh.cancel()
    }
}
