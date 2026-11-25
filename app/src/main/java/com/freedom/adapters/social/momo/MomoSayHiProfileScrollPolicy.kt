package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.ScreenRect

/**
 * Selects the profile content RecyclerView from the observed profile hierarchy. Identity
 * extraction is independent from display-name and toolbar nodes; this viewport is only used
 * for the single recovery scroll after an ID read misses.
 *
 * The viewport is intersected with the captured display before it is used for a gesture. Momo's
 * accessibility bounds can extend below DisplayMetrics.heightPixels (for example 1600 vs 1554),
 * so rejecting the un-clipped Rect would incorrectly discard the only valid content scroller.
 */
internal object MomoSayHiProfileScrollPolicy {
    // Identical five view IDs to the existing business profile fingerprint.
    private val PROFILE_SURFACE_IDS = setOf(
        "root_layout", "personal_profile_name", "toolbar_id", "tabs", "pagertabcontent"
    )

    fun matchesProfileSurface(viewIds: Iterable<String>): Boolean {
        val observed = viewIds.map { it.substringAfterLast('/') }.toSet()
        return PROFILE_SURFACE_IDS.all(observed::contains)
    }

    fun permitsPendingProfileScroll(
        mode: com.freedom.core.runtime.RunMode,
        pendingOnCurrentWindow: Boolean,
        activeWindowId: Int,
        currentRootViewIds: Iterable<String>
    ): Boolean =
        mode == com.freedom.core.runtime.RunMode.AI_REPLY &&
            pendingOnCurrentWindow && activeWindowId >= 0 &&
            matchesProfileSurface(currentRootViewIds)

    /** Shared permit for both identity tasks; exact window and pending transaction are mandatory. */
    fun permitsIdentityProfileScroll(
        mode: com.freedom.core.runtime.RunMode,
        pendingOnCurrentWindow: Boolean,
        activeWindowId: Int,
        expectedWindowId: Int
    ): Boolean =
        mode == com.freedom.core.runtime.RunMode.AI_REPLY && pendingOnCurrentWindow &&
            activeWindowId >= 0 && activeWindowId == expectedWindowId

    data class Decision(
        val viewport: ScreenRect?,
        val candidateCount: Int,
        val reason: String,
        val targetPath: List<Int>? = null
    )

    /**
     * Prefer the previously verified viewport when available. If optional layout
     * containers are absent, accept only one current, usable vertical content scroller.
     * Never fall back to fixed coordinates, horizontal pagers, or ambiguous candidates.
     */
    fun resolveIdentityViewport(
        nodes: List<AccessibilityNodeSnapshot>,
        displayWidth: Int,
        displayHeight: Int
    ): Decision {
        val existing = resolveViewport(nodes, displayWidth, displayHeight)
        if (existing.viewport != null || displayWidth <= 0 || displayHeight <= 0) return existing
        val candidates = nodes.mapNotNull { node ->
            if (!node.visible || !node.enabled || !node.scrollable ||
                node.className.substringAfterLast('.') !in setOf(
                    "RecyclerView", "ScrollView", "NestedScrollView", "ListView"
                )
            ) return@mapNotNull null
            val clipped = intersectWithDisplay(node.bounds, displayWidth, displayHeight)
                ?: return@mapNotNull null
            if (!hasUsableViewport(clipped, displayWidth, displayHeight)) return@mapNotNull null
            node to clipped
        }
        val selected = candidates.singleOrNull() ?: return Decision(
            null, candidates.size, "IDENTITY_SCROLL_VIEWPORT_NOT_UNIQUE"
        )
        return Decision(selected.second, 1, "IDENTITY_SCROLL_VIEWPORT_UNIQUE", selected.first.path.childIndices)
    }

    fun resolveViewport(
        nodes: List<AccessibilityNodeSnapshot>,
        displayWidth: Int,
        displayHeight: Int
    ): Decision {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return Decision(null, 0, "INVALID_DISPLAY_BOUNDS")
        }

        // The observed ViewPager must uniquely own the content RecyclerView. Do not require
        // nickname/meta/toolbar nodes: they are not the identity result and may be temporarily
        // absent after navigation or after the profile has been scrolled.
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

    private const val PROFILE_PAGER_VIEW_ID = "pagertabcontent"
    private const val PROFILE_CONTENT_HEADING = "个人信息"
    private const val MIN_WIDTH_RATIO = 0.60f
    private const val MIN_HEIGHT_RATIO = 0.25f
    private const val MIN_AREA_RATIO = 0.15f
}
