package com.freedom.adapters.accessibility.android

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.accountbrowse.FeedPositionMatcher
import com.freedom.features.accountbrowse.ports.FeedGestureProfile
import com.freedom.core.accessibility.gesture.GestureIntent
import kotlin.random.Random

/** Stateless scroll-command and return-position policies; it never owns AccountBrowse session data. */
internal class AccountBrowseScrollExecutor(
    private val random: Random = Random.Default
) {
    fun feedSwipeIntent(bounds: ScreenRect, profile: FeedGestureProfile): GestureIntent.FeedSwipe =
        GestureIntent.FeedSwipe(viewport = bounds, durationMs = profile.durationMillis)

    fun isFeedPositionRestored(expectedSnapshot: String, currentSnapshot: String): Boolean =
        FeedPositionMatcher.isPositionRestored(
            expectedSnapshot = expectedSnapshot,
            currentSnapshot = currentSnapshot,
            minimumMatches = MIN_RETURN_SNAPSHOT_MATCH_LINES,
            matchRatio = RETURN_SNAPSHOT_MATCH_RATIO
        )

    fun randomBrowseDelayMs(): Long = random.nextLong(MIN_BROWSE_DELAY_MS, MAX_BROWSE_DELAY_MS + 1L)

    /** Random pause between confirmed feed swipes. Both endpoints are inclusive. */
    fun randomScrollIntervalMs(): Long = random.nextLong(MIN_SCROLL_INTERVAL_MS, MAX_SCROLL_INTERVAL_MS + 1L)

    private companion object {
        const val MIN_BROWSE_DELAY_MS = 3_000L
        const val MAX_BROWSE_DELAY_MS = 15_000L
        const val MIN_SCROLL_INTERVAL_MS = 5_000L
        const val MAX_SCROLL_INTERVAL_MS = 8_000L
        const val MIN_RETURN_SNAPSHOT_MATCH_LINES = 3
        const val RETURN_SNAPSHOT_MATCH_RATIO = 0.70
    }
}
