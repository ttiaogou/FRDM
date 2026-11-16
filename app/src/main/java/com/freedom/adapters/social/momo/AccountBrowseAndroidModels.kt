package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Android-facing UI evidence shared between AccountBrowse and social adapters. */
internal data class PageFingerprint(
    val signature: String,
    val nodeCount: Int,
    val truncated: Boolean = false,
    val timeBudgetExceeded: Boolean = false,
    val depthBudgetExceeded: Boolean = false,
    val elapsedBudgetMs: Long = 0L,
    val nodeBudgetExceeded: Boolean = false
)

internal data class FeedReadinessInspection(
    val fingerprint: PageFingerprint?,
    val reason: String
)


internal data class TabLabelEvidence(
    val label: String,
    val bounds: Rect,
    val selected: Boolean
)

internal data class DynamicTabRowEvidence(
    val contentTop: Int,
    val diagnostic: String,
    val dynamicBounds: Rect,
    val dynamicSelected: Boolean,
    val selectionKnown: Boolean
)

internal data class HomeBottomNavEvidence(
    val homeBounds: Rect,
    val homeSelected: Boolean,
    val selectionKnown: Boolean,
    val diagnostic: String
)

internal data class DynamicTabInspection(
    val evidence: DynamicTabRowEvidence?,
    val reason: String
)

/** Android-side popup inspection; short-lived node handles never leave the social adapter. */
internal data class PopupInspection(
    val container: AccessibilityNodeInfo?,
    val closeTarget: AccessibilityNodeInfo?,
    /** Bounds of the original visible close label/icon, preferred for the tap point. */
    val closeTapBounds: Rect?,
    val dismissable: Boolean,
    val critical: Boolean,
    val signature: String,
    val description: String,
    val advertising: Boolean = false,
    /** True when the bounded scan could not establish a complete safe popup assessment. */
    val inspectionIncomplete: Boolean = false
)

internal data class DetailPageIdentityInspection(
    val isFeedPage: Boolean,
    val identityConfirmed: Boolean,
    val fingerprint: PageFingerprint,
    val reason: String
)

internal data class FeedGestureTarget(
    val bounds: Rect,
    val className: String
)

internal data class ScrollGestureProfile(
    val durationMs: Long
)

/** Result of selecting a candidate feed card; the returned node is owned by the caller. */
internal data class FeedCardSelectionInspection(
    val node: AccessibilityNodeInfo?,
    val inspectedCardCount: Int,
    val skippedLiveStreamCount: Int,
    val skippedAdvertisementCount: Int,
    val skippedUnknownCount: Int,
    val reason: String
)
