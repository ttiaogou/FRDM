package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot

/**
 * Read-only index for navigating a detached accessibility snapshot in the node inspector.
 *
 * This index never holds Android AccessibilityNodeInfo objects. Coordinates are interpreted in
 * the same physical-pixel screen space stored by AccessibilityNodeSnapshot.bounds.
 */
class AccessibilityNodeInspectionIndex(snapshot: AccessibilityTreeSnapshot) {
    val nodes: List<AccessibilityNodeSnapshot> = snapshot.nodes

    private val byPath: Map<List<Int>, AccessibilityNodeSnapshot> =
        nodes.associateBy { it.path.childIndices }

    /** Nodes that can be drawn and selected from the spatial map. */
    val visibleBoundedNodes: List<AccessibilityNodeSnapshot> = nodes.filter {
        it.visible && !it.bounds.isEmpty
    }

    fun find(path: List<Int>): AccessibilityNodeSnapshot? = byPath[path]

    /** Direct parent only; the root has no parent. */
    fun parentOf(path: List<Int>): AccessibilityNodeSnapshot? =
        if (path.isEmpty()) null else byPath[path.dropLast(1)]

    /** Direct children in the same order as the captured snapshot. */
    fun childrenOf(path: List<Int>): List<AccessibilityNodeSnapshot> = nodes.filter { node ->
        val childPath = node.path.childIndices
        childPath.size == path.size + 1 && childPath.take(path.size) == path
    }

    /** Returns ancestors from root toward the direct parent. */
    fun ancestorsOf(path: List<Int>): List<AccessibilityNodeSnapshot> {
        if (path.isEmpty()) return emptyList()
        return (0 until path.size).mapNotNull { depth -> byPath[path.take(depth)] }
    }

    /**
     * Resolve a screen point to the most specific visible node containing it.
     * Smaller bounds win; depth and drawing order break equal-area ties.
     */
    fun nodeAt(screenX: Int, screenY: Int): AccessibilityNodeSnapshot? =
        visibleBoundedNodes.asSequence()
            .filter { node ->
                val bounds = node.bounds
                screenX >= bounds.left && screenX < bounds.right &&
                    screenY >= bounds.top && screenY < bounds.bottom
            }
            .minWithOrNull(
                compareBy<AccessibilityNodeSnapshot> { area(it) }
                    .thenByDescending { it.path.childIndices.size }
                    .thenByDescending { it.drawingOrder }
            )

    private fun area(node: AccessibilityNodeSnapshot): Long =
        node.bounds.width.toLong() * node.bounds.height.toLong()
}
