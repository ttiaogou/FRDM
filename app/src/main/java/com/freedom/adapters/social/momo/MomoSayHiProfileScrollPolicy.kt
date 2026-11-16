package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.ScreenRect

/**
 * Selects the profile content RecyclerView from two independent fingerprints:
 * (1) the visible profile header identifies the current page, and
 * (2) pagertabcontent -> scrollable RecyclerView identifies the content viewport.
 *
 * The viewport is intersected with the captured display before it is used for a gesture. Momo's
 * accessibility bounds can extend below DisplayMetrics.heightPixels (for example 1600 vs 1554),
 * so rejecting the un-clipped Rect would incorrectly discard the only valid content scroller.
 */
internal object MomoSayHiProfileScrollPolicy {
    data class Decision(
        val viewport: ScreenRect?,
        val candidateCount: Int,
        val reason: String,
        val targetPath: List<Int>? = null
    )

    fun resolveViewport(
        nodes: List<AccessibilityNodeSnapshot>,
        displayWidth: Int,
        displayHeight: Int
    ): Decision {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return Decision(null, 0, "INVALID_DISPLAY_BOUNDS")
        }

        // Fingerprint group A: two stable profile-header resource IDs, each unique and visible.
        if (!hasUniqueVisibleTextAnchor(nodes, PROFILE_NAME_VIEW_ID) ||
            !hasUniqueVisibleTextAnchor(nodes, PROFILE_META_VIEW_ID)
        ) {
            return Decision(null, 0, "PROFILE_PAGE_FINGERPRINT_NOT_CONFIRMED")
        }

        // Fingerprint group B: the observed ViewPager must uniquely own the content RecyclerView.
        val pagers = nodes.filter { node ->
            node.visible && node.scrollable &&
                localId(node.viewId) == PROFILE_PAGER_VIEW_ID &&
                node.className.substringAfterLast('.') == "ViewPager"
        }
        val pager = pagers.singleOrNull() ?: return Decision(
            null,
            pagers.size,
            if (pagers.isEmpty()) "PROFILE_PAGER_FINGERPRINT_NOT_FOUND"
            else "PROFILE_PAGER_FINGERPRINT_AMBIGUOUS"
        )

        val structuralNodes = nodes.filter { node ->
            node.visible && node.scrollable &&
                node.className.substringAfterLast('.') == "RecyclerView" &&
                isDescendant(pager.path.childIndices, node.path.childIndices) &&
                contains(pager.bounds, node.bounds)
        }
        if (structuralNodes.isEmpty()) {
            return Decision(null, 0, "PROFILE_CONTENT_RECYCLERVIEW_NOT_FOUND")
        }

        val structuralCandidates = structuralNodes.mapNotNull { node ->
            val clipped = intersectWithDisplay(node.bounds, displayWidth, displayHeight)
                ?: return@mapNotNull null
            node.takeIf { hasUsableViewport(clipped, displayWidth, displayHeight) }
                ?.let { it to clipped }
        }
        if (structuralCandidates.isEmpty()) {
            return Decision(
                null,
                structuralNodes.size,
                "PROFILE_CONTENT_RECYCLERVIEW_BOUNDS_INVALID"
            )
        }

        // Primary: the visible '个人信息' section heading is inside the candidate RecyclerView.
        val anchoredCandidates = structuralCandidates.filter { (candidate, _) ->
            nodes.any { heading ->
                heading.visible && heading.text?.trim() == PROFILE_CONTENT_HEADING &&
                    isDescendant(candidate.path.childIndices, heading.path.childIndices)
            }
        }

        val selected: Pair<AccessibilityNodeSnapshot, ScreenRect>
        val selectionReason: String
        when {
            anchoredCandidates.size == 1 -> {
                selected = anchoredCandidates.single()
                selectionReason = "PROFILE_VIEWPORT_FINGERPRINT_CONFIRMED"
            }
            anchoredCandidates.size > 1 -> return Decision(
                null, anchoredCandidates.size, "AMBIGUOUS_PROFILE_CONTENT_RECYCLERVIEWS"
            )
            // Fallback: the heading may be outside the visible portion after the user has
            // already scrolled. A single visible, scrollable RecyclerView under the exact
            // profile ViewPager remains a sufficient viewport fingerprint. Never fall back to
            // the outer ViewPager or an unrelated full-screen scroller.
            structuralCandidates.size == 1 -> {
                selected = structuralCandidates.single()
                selectionReason = "PROFILE_VIEWPORT_FINGERPRINT_FALLBACK"
            }
            else -> return Decision(
                null, structuralCandidates.size, "AMBIGUOUS_PROFILE_CONTENT_RECYCLERVIEWS"
            )
        }

        return Decision(
            viewport = selected.second,
            candidateCount = structuralCandidates.size,
            reason = selectionReason,
            targetPath = selected.first.path.childIndices
        )
    }

    private fun hasUniqueVisibleTextAnchor(
        nodes: List<AccessibilityNodeSnapshot>,
        expectedLocalId: String
    ): Boolean {
        val matches = nodes.filter { node ->
            node.visible && localId(node.viewId) == expectedLocalId &&
                node.className.substringAfterLast('.') == "TextView"
        }
        return matches.size == 1
    }

    private fun localId(viewId: String): String = viewId.substringAfterLast('/')

    private fun isDescendant(ancestor: List<Int>, descendant: List<Int>): Boolean =
        descendant.size > ancestor.size && descendant.take(ancestor.size) == ancestor

    private fun contains(parent: ScreenRect, child: ScreenRect): Boolean =
        !parent.isEmpty && !child.isEmpty &&
            child.left >= parent.left && child.top >= parent.top &&
            child.right <= parent.right && child.bottom <= parent.bottom

    private fun intersectWithDisplay(
        bounds: ScreenRect,
        displayWidth: Int,
        displayHeight: Int
    ): ScreenRect? {
        val clipped = ScreenRect(
            left = bounds.left.coerceAtLeast(0),
            top = bounds.top.coerceAtLeast(0),
            right = bounds.right.coerceAtMost(displayWidth),
            bottom = bounds.bottom.coerceAtMost(displayHeight)
        )
        return clipped.takeUnless { it.isEmpty }
    }

    private fun hasUsableViewport(
        bounds: ScreenRect,
        displayWidth: Int,
        displayHeight: Int
    ): Boolean =
        bounds.width >= (displayWidth * MIN_WIDTH_RATIO).toInt() &&
            bounds.height >= (displayHeight * MIN_HEIGHT_RATIO).toInt() &&
            bounds.width.toLong() * bounds.height >=
            (displayWidth.toLong() * displayHeight * MIN_AREA_RATIO).toLong()

    private const val PROFILE_NAME_VIEW_ID = "personal_profile_name"
    private const val PROFILE_META_VIEW_ID = "tv_multi_info"
    private const val PROFILE_PAGER_VIEW_ID = "pagertabcontent"
    private const val PROFILE_CONTENT_HEADING = "个人信息"
    private const val MIN_WIDTH_RATIO = 0.60f
    private const val MIN_HEIGHT_RATIO = 0.25f
    private const val MIN_AREA_RATIO = 0.15f
}
