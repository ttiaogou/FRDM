package com.freedom.features.accountbrowse

/** Pure decision rules for accepting a refreshed dynamic feed as a new browsing baseline. */
object FeedRefreshVerificationPolicy {
    const val MIN_RECYCLER_CONTENT_CHANGE_EVENTS = 2

    enum class TimeoutAction {
        RETRY,
        SAFE_PAUSE
    }

    fun hasReloadEvidence(
        snapshotChanged: Boolean,
        recyclerContentChangeEvents: Int
    ): Boolean {
        return snapshotChanged ||
            recyclerContentChangeEvents >= MIN_RECYCLER_CONTENT_CHANGE_EVENTS
    }

    fun canResumeBrowsing(
        isDynamicFeedPage: Boolean,
        snapshotChanged: Boolean,
        recyclerContentChangeEvents: Int,
        stableSamples: Int,
        requiredStableSamples: Int
    ): Boolean {
        if (!isDynamicFeedPage || requiredStableSamples < 1) return false
        if (stableSamples < requiredStableSamples) return false
        return hasReloadEvidence(
            snapshotChanged = snapshotChanged,
            recyclerContentChangeEvents = recyclerContentChangeEvents
        )
    }

    /** The first expired attempt gets exactly one retry; the final expired attempt pauses. */
    fun timeoutAction(completedAttempts: Int, maxAttempts: Int): TimeoutAction =
        if (maxAttempts > 0 && completedAttempts < maxAttempts) {
            TimeoutAction.RETRY
        } else {
            TimeoutAction.SAFE_PAUSE
        }
}
