package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus

/**
 * Page-specific, read-only recognizer for a manually opened Received Greetings list.
 * Stability evidence remains owned by the existing Momo adapter and its verified fingerprint.
 */
internal class MomoReceivedGreetingsPageRecognizer(
    private val inspectPage: () -> ReceivedGreetingsPageArrivalStatus
) {
    fun recognize(): ReceivedGreetingsPageArrivalStatus = runCatching { inspectPage() }
        .getOrDefault(ReceivedGreetingsPageArrivalStatus.UNKNOWN)
}
