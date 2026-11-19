package com.freedom.features.aireply

/** Progressive cadence for the unified received-greetings recovery transaction. */
internal object ReceivedGreetingsRetrySchedule {
    private val delaysMs = longArrayOf(500L, 1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

    fun delayForRetry(retryNumber: Int): Long {
        require(retryNumber >= 1) { "retryNumber must be >= 1" }
        return delaysMs[(retryNumber - 1).coerceAtMost(delaysMs.lastIndex)]
    }
}
