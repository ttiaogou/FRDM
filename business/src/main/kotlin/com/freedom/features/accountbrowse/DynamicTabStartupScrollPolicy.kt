package com.freedom.features.accountbrowse

/**
 * Filters layout-driven RecyclerView scroll callbacks immediately after entering
 * the Home -> Dynamic tab identification phase. This grace period is limited to
 * the main Activity and is disabled whenever global touch evidence is available.
 */
internal object DynamicTabStartupScrollPolicy {
    const val STARTUP_GRACE_PERIOD_MS = 2_000L

    fun shouldIgnore(
        isScrollEvent: Boolean,
        isDynamicTabWait: Boolean,
        isFeedRecoveryActive: Boolean = false,
        isMainActivity: Boolean,
        isRecyclerView: Boolean,
        elapsedSinceWaitStartedMs: Long,
        hasTouchEvidence: Boolean
    ): Boolean =
        isScrollEvent &&
            isDynamicTabWait &&
            isMainActivity &&
            isRecyclerView &&
            !hasTouchEvidence &&
            (isFeedRecoveryActive || elapsedSinceWaitStartedMs in 0 until STARTUP_GRACE_PERIOD_MS)
}
