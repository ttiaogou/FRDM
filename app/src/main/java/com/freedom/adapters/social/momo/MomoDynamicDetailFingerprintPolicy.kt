package com.freedom.adapters.social.momo

/** Snapshot-local structural anchors for the four stable controls observed on dynamic detail. */
internal data class MomoDynamicDetailFingerprintSignals(
    val packageMatches: Boolean,
    val snapshotComplete: Boolean,
    val authorAvatarEntry: Boolean,
    val followAction: Boolean,
    val overflowAction: Boolean,
    val mainContentList: Boolean,
    val fixedBottomCommentAction: Boolean,
    /** Avatar, Follow and overflow must all belong to the same compact top header shell. */
    val headerControlsShareShell: Boolean,
    /** The fixed comment launcher must be a sibling/overlay outside the dynamic-content RecyclerView. */
    val commentActionOutsideContentList: Boolean
)

/**
 * The dynamic-detail identity is a single, strict four-control fingerprint. It deliberately does
 * not inspect post text, image count, like/comment counts, comment rows, or empty-comment copy.
 */
internal object MomoDynamicDetailFingerprintPolicy {
    fun matchedPath(signals: MomoDynamicDetailFingerprintSignals): String? {
        if (!signals.packageMatches || !signals.snapshotComplete) return null
        val complete = signals.authorAvatarEntry &&
            signals.followAction &&
            signals.overflowAction &&
            signals.fixedBottomCommentAction &&
            signals.mainContentList &&
            signals.headerControlsShareShell &&
            signals.commentActionOutsideContentList
        return "AUTHOR_HEADER_TRIO_AND_FIXED_BOTTOM_COMMENT".takeIf { complete }
    }

    fun isConfirmed(signals: MomoDynamicDetailFingerprintSignals): Boolean =
        matchedPath(signals) != null
}
