package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.query.NodeTreeIndex

/**
 * Resolves the foreground card from Momo's stacked slide-card layout.
 *
 * The supplied Round5 device snapshot contains several visible rv_card_content nodes. Each is
 * hosted by a slidecard_layout; the foreground card is the unique, materially largest populated
 * card. This resolver deliberately refuses ties, missing parent evidence, empty cards, and
 * incomplete snapshots. It never falls back to traversal order or the first selector match.
 */
internal object MomoSayHiActiveCardResolver {
    private const val CARD_HOST_VIEW_ID = "com.immomo.momo:id/slidecard_layout"
    private const val MIN_FOREGROUND_AREA_RATIO = 1.05

    internal data class Resolution(
        val selectedPath: NodePath?,
        val candidateCount: Int,
        val eligibleCount: Int,
        val reason: String
    )

    fun resolve(
        tree: AccessibilityTreeSnapshot,
        index: NodeTreeIndex,
        candidatePaths: List<NodePath>,
        candidatesExact: Boolean
    ): Resolution {
        if (tree.truncated || !candidatesExact) {
            return Resolution(null, candidatePaths.size, 0, "INCOMPLETE_CANDIDATE_SET")
        }
        val eligible = candidatePaths.mapNotNull { path ->
            val node = index.nodeAt(path) ?: return@mapNotNull null
            val parent = index.parentOf(path) ?: return@mapNotNull null
            if (!node.visible || !parent.visible || node.childCount <= 0 ||
                node.className != "androidx.recyclerview.widget.RecyclerView" ||
                parent.viewId != CARD_HOST_VIEW_ID || node.bounds.isEmpty
            ) return@mapNotNull null
            val area = node.bounds.width.toLong() * node.bounds.height.toLong()
            if (area <= 0L) return@mapNotNull null
            path to area
        }
        if (eligible.isEmpty()) {
            return Resolution(null, candidatePaths.size, 0, "NO_POPULATED_CARD_HOST")
        }
        val ranked = eligible.sortedByDescending { it.second }
        val largest = ranked[0]
        val secondArea = ranked.getOrNull(1)?.second
        if (secondArea != null && largest.second.toDouble() / secondArea < MIN_FOREGROUND_AREA_RATIO) {
            return Resolution(null, candidatePaths.size, eligible.size, "FOREGROUND_CARD_NOT_DISTINCT")
        }
        return Resolution(largest.first, candidatePaths.size, eligible.size, "UNIQUE_LARGEST_POPULATED_CARD")
    }
}
