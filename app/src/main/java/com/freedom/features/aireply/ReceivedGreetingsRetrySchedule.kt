package com.freedom.features.aireply

/** Progressive, bounded retry cadence shared by received-greetings navigation recovery. */
internal object ReceivedGreetingsRetrySchedule {
    private val delaysMs = longArrayOf(500L, 1_000L, 1_500L, 2_000L, 3_000L, 5_000L)

    fun delayForRetry(retryNumber: Int): Long {
        require(retryNumber >= 1) { "retryNumber must be >= 1" }
        return delaysMs[(retryNumber - 1).coerceAtMost(delaysMs.lastIndex)]
    }
}
