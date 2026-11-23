package com.freedom.core.accessibility.overlay

import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.query.NodeTreeIndex

/** Immutable, text-free drawing data for one freshly validated node and its ancestor chain. */
data class NodeBoundsHighlightPlan(
    val selectedBounds: ScreenRect,
    val ancestorBounds: List<ScreenRect>,
    val label: String
)

/**
 * Builds a screen highlight plan only from a captured, non-empty bounds value. It never guesses
 * geometry from coordinates, text, or an unavailable field and never retains Android node data.
 */
object NodeBoundsHighlightPlanner {
    fun create(
        snapshot: AccessibilityTreeSnapshot,
        path: NodePath,
        maxAncestors: Int = 8
    ): NodeBoundsHighlightPlan? {
        require(maxAncestors >= 0) { "maxAncestors cannot be negative" }
        val index = NodeTreeIndex(snapshot)
        val selected = index.nodeAt(path) ?: return null
        if (selected.bounds.isEmpty ||
            selected.captureState(AccessibilityNodeField.BOUNDS, snapshot.textCapture) !=
            AccessibilityFieldCaptureState.VALUE_PRESENT
        ) return null

        val ancestors = if (maxAncestors == 0) emptyList() else {
            index.ancestorsOf(path)
                .asReversed()
                .map { it.bounds }
                .filterNot { it.isEmpty }
                .takeLast(maxAncestors)
        }
        val shortClass = selected.className.substringAfterLast('.').ifBlank { "Unknown" }
        val pathLabel = path.childIndices.joinToString("/").ifBlank { "root" }
        return NodeBoundsHighlightPlan(
            selectedBounds = selected.bounds,
            ancestorBounds = ancestors,
            label = "选中 · $shortClass · path=$pathLabel"
        )
    }
}
