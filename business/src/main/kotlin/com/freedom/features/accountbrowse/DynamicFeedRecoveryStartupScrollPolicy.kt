package com.freedom.features.accountbrowse

/**
 * A RecyclerView may emit layout-driven scroll callbacks while Momo's main page is
 * still being created. During the explicit feed-refresh recovery startup, such a
 * callback is not enough to infer user input unless independent touch evidence exists.
 * The actual filtering predicate is shared with DynamicTabStartupScrollPolicy so both startup
 * paths cannot drift into subtly different conditions.
 */
internal object DynamicFeedRecoveryStartupScrollPolicy {
    fun shouldIgnore(
        isScrollEvent: Boolean,
        isRecoveryActive: Boolean,
        isWaitingForDynamicTab: Boolean,
        isMainActivity: Boolean,
        isRecyclerView: Boolean,
        hasTouchEvidence: Boolean
    ): Boolean = DynamicTabStartupScrollPolicy.shouldIgnore(
        isScrollEvent = isScrollEvent,
        isDynamicTabWait = isWaitingForDynamicTab,
        isFeedRecoveryActive = isRecoveryActive,
        isMainActivity = isMainActivity,
        isRecyclerView = isRecyclerView,
        elapsedSinceWaitStartedMs = if (isRecoveryActive) 0L else -1L,
        hasTouchEvidence = hasTouchEvidence
    )
}
