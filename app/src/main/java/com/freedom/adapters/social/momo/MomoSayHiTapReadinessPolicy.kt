package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect

/** The tap decision keeps geometric deferral separate from stale-snapshot rejection. */
internal enum class MomoSayHiTapReadiness {
    READY,
    NEEDS_SCROLL,
    REACQUIRE_SNAPSHOT
}

internal object MomoSayHiTapReadinessPolicy {
    fun evaluate(
        snapshotFresh: Boolean,
        packageMatches: Boolean,
        windowCurrent: Boolean,
        targetBounds: ScreenRect,
        preferredBounds: ScreenRect,
        displayBounds: ScreenRect
    ): MomoSayHiTapReadiness {
        if (!snapshotFresh || !packageMatches || !windowCurrent ||
            targetBounds.isEmpty || preferredBounds.isEmpty || displayBounds.isEmpty
        ) {
            return MomoSayHiTapReadiness.REACQUIRE_SNAPSHOT
        }
        if (!contains(displayBounds, targetBounds) || !contains(displayBounds, preferredBounds)) {
            return MomoSayHiTapReadiness.NEEDS_SCROLL
        }
        return MomoSayHiTapReadiness.READY
    }

    private fun contains(outer: ScreenRect, inner: ScreenRect): Boolean =
        !outer.isEmpty && !inner.isEmpty && inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom
}
