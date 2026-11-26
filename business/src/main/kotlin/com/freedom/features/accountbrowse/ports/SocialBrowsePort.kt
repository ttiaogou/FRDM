package com.freedom.features.accountbrowse.ports

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef

/** Platform-neutral page identity used by AccountBrowse policies and recovery logic. */
data class BrowsePageEvidence(
    val packageName: String,
    val activityClass: String,
    val pageKind: BrowsePageKind,
    val fingerprint: String,
    val confidence: Int,
    val reason: String,
    val nodeCount: Int = 0,
    val windowAvailable: Boolean = true,
    val windowId: Int? = null,
    val windowSessionKey: String? = null
)

enum class BrowsePageKind {
    UNKNOWN,
    HOME,
    DYNAMIC_FEED,
    DETAIL,
    IMAGE_VIEWER,
    OTHER;

    /** Both are normal dynamic content; the same detail lifecycle consumes either. */
    val isDynamicContent: Boolean
        get() = this == DETAIL || this == IMAGE_VIEWER
}

data class DynamicTabEvidence(
    /** Bounds of the visible tab label used as the preferred tap point. */
    val bounds: ScreenRect?,
    /** Bounds of the verified clickable ancestor; it is evidence only, never an action handle. */
    val targetBounds: ScreenRect?,
    val selected: Boolean,
    val selectionKnown: Boolean,
    val homeTabBounds: ScreenRect?,
    /** Bounds of the verified clickable Home ancestor. */
    val homeTabTargetBounds: ScreenRect?,
    val homeSelected: Boolean,
    val homeSelectionKnown: Boolean,
    val contentTop: Int?,
    val reason: String,
    /** Exact active-window snapshot from which all navigation bounds were read. */
    val targetWindow: WindowRef? = null
)

data class PopupEvidence(
    val signature: String,
    val dismissable: Boolean,
    val critical: Boolean,
    /** Semantic dismiss target; ACTION_DISMISS is not a screen tap or scroll. */
    val dismissTarget: com.freedom.core.accessibility.api.NodeTarget?,
    /** Bounds of the visible close label/icon; preferred tap location. */
    val closeBounds: ScreenRect?,
    /** Bounds of its verified clickable ancestor; geometry evidence only. */
    val closeTargetBounds: ScreenRect?,
    val description: String,
    /** Exact target window from which the popup and close bounds were captured. */
    val targetWindow: WindowRef? = null
)

data class FeedEvidence(
    val ready: Boolean,
    val fingerprint: String,
    val visibleCardCount: Int,
    /** Read-only evidence that the scrollable feed container is visible. */
    val scrollBounds: ScreenRect?,
    val reason: String
)

data class FeedCardCandidate(
    /** Visible card geometry used to calculate a safe coordinate tap. */
    val bounds: ScreenRect,
    val cardType: FeedCardType,
    val fingerprint: String,
    /** Exact target window from which [bounds] were captured. */
    val targetWindow: WindowRef? = null
)

enum class FeedCardType {
    NORMAL_SOCIAL,
    ADVERTISEMENT,
    LIVE_STREAM,
    UNKNOWN
}

data class FeedSelectionResult(
    val candidate: FeedCardCandidate?,
    val inspectedCount: Int,
    val skippedAdvertisements: Int,
    val skippedLiveStreams: Int,
    val skippedUnknown: Int,
    val reason: String,
    val sourceFingerprint: String = "",
    val sourceSnapshot: String = ""
)

/** Operational timing estimate for AccountBrowse scheduling; geometry policy remains in Runtime. */
data class FeedGestureProfile(
    val durationMillis: Long
)

data class FeedGesturePlan(
    /** Fresh semantic viewport only. The pipeline creates the final physical path. */
    val bounds: ScreenRect,
    val className: String,
    val profile: FeedGestureProfile,
    /** Exact window snapshot from which [bounds] was read. */
    val targetWindow: WindowRef
)

data class DetailEvidence(
    val isFeedPage: Boolean,
    val identityConfirmed: Boolean,
    val fingerprint: String,
    val reason: String,
    val nodeCount: Int = 0
)

data class FeedReturnEvidence(
    val restored: Boolean,
    val stable: Boolean,
    val fingerprint: String,
    val reason: String
)

/**
 * Application-owned port. A social adapter reads a bounded active-window snapshot internally,
 * then supplies platform-neutral evidence, verified screen bounds, and only the semantic locator
 * required for ACTION_DISMISS. Raw Android nodes/text never pass through AccountBrowse. AccountBrowse
 * retains all policies, deadlines, retry limits and state transitions.
 */
interface SocialBrowsePort {
    val detailActivityClass: String
    fun supportsTargetPackage(packageName: String): Boolean
    fun isMainActivity(activityClass: String): Boolean
    fun isDetailActivity(activityClass: String): Boolean
    fun isImageBrowserActivity(activityClass: String): Boolean
    fun inspectPage(): BrowsePageEvidence
    fun inspectDynamicTab(): DynamicTabEvidence
    fun inspectPopup(): PopupEvidence?
    /** Read-only evidence for a foreground system/other-app window; never returns action targets. */
    fun inspectExternalPopup(): PopupEvidence?
    fun inspectFeed(): FeedEvidence
    fun loadFeedGestureProfile(): FeedGestureProfile
    fun planFeedGesture(profile: FeedGestureProfile): FeedGesturePlan?
    /** Capture either the full active window or only the verified, scrollable feed list. */
    fun captureVisibleFeedSnapshot(listOnly: Boolean): String
    fun selectVisibleFeedCard(
        minimumWidthPx: Int,
        minimumHeightPx: Int
    ): FeedSelectionResult
    fun inspectDetail(expectedFingerprint: String): DetailEvidence
    /** Optional fresh business evidence, independent of page identity and BACK eligibility. */
    fun readImageIndex(expectedFingerprint: String): ImageIndexReadResult =
        ImageIndexReadResult.Unavailable(ImageIndexUnavailableReason.NOT_SUPPORTED)
    fun inspectFeedReturn(expectedFingerprint: String): FeedReturnEvidence
}
