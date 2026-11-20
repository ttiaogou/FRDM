package com.freedom.core.runtime

/**
 * Shared retry cadence for manual hand-off recovery in autonomous modes.
 *
 * Each failed recovery check advances from 5s to 15s to 60s. A new manual interaction
 * restarts at the first stage. Returning null from [advanceAfterUnmetCondition] means
 * the final 60s stage has also failed and the caller must latch a persistent review hold.
 */
class TieredManualHandoffQuietPeriod {
    companion object {
        val DELAYS_MS: List<Long> = listOf(5_000L, 15_000L, 60_000L)
    }

    private var stageIndex = 0

    val stageNumber: Int
        get() = stageIndex + 1

    val currentDelayMs: Long
        get() = DELAYS_MS[stageIndex]

    fun restart(): Long {
        stageIndex = 0
        return currentDelayMs
    }

    /** Returns the next delay, or null after the third-stage check has failed. */
    fun advanceAfterUnmetCondition(): Long? {
        if (stageIndex >= DELAYS_MS.lastIndex) return null
        stageIndex += 1
        return currentDelayMs
    }

    fun reset() {
        stageIndex = 0
    }
}
