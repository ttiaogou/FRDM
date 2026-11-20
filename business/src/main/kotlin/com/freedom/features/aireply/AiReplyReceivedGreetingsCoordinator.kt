package com.freedom.features.aireply

import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/** Feature-owned Received Greetings page-arrival lifecycle, isolated from ordinary inbox traversal. */
internal class AiReplyReceivedGreetingsCoordinator(
    scheduler: TaskScheduler,
    observePage: () -> ReceivedGreetingsPageArrivalStatus,
    onConfirmed: () -> Unit,
    onFallbackCheckRequested: () -> Unit,
    fallbackDelayMs: () -> Long
) {
    private val listener = ReceivedGreetingsPageArrivalListener(
        scheduler = scheduler,
        observePage = observePage,
        onConfirmed = onConfirmed,
        onFallbackCheckRequested = onFallbackCheckRequested,
        fallbackDelayMs = fallbackDelayMs
    )

    fun reset() = listener.reset()
    fun onPageSnapshotAvailable(): ReceivedGreetingsPageArrivalStatus = listener.onPageSnapshotAvailable()
    fun onPageChangeEvent() = listener.onPageChangeEvent()
}
