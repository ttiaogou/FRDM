package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import java.util.ArrayDeque

/**
 * Builds a navigable, immutable view of a detached accessibility snapshot.
 *
 * Paths are the structural identity: the builder does not retain AccessibilityNodeInfo instances
 * and remains safe when the original target-app window changes after capture.
 */
class AccessibilityHierarchyTree(nodes: List<AccessibilityNodeSnapshot>) {
    data class Row(
        val node: AccessibilityNodeSnapshot,
        val depth: Int,
        val hasChildren: Boolean,
        val expanded: Boolean
    )

    private val nodesByPath: Map<List<Int>, AccessibilityNodeSnapshot> =
        nodes.associateBy { it.path.childIndices.toList() }

    private val childrenByParent: Map<List<Int>, List<AccessibilityNodeSnapshot>> = buildMap {
        nodes.forEach { node ->
            val path = node.path.childIndices
            if (path.isNotEmpty()) {
                val parent = path.dropLast(1)
                if (parent in nodesByPath) {
                    val current = get(parent).orEmpty()
                    put(parent, current + node)
                }
            }
        }
    }

    val rootNodes: List<AccessibilityNodeSnapshot> = nodes.filter { node ->
        val path = node.path.childIndices
        path.isEmpty() || path.dropLast(1) !in nodesByPath
    }

    /** Every path that can be expanded, including paths whose captured children are invisible. */
    val expandablePaths: Set<List<Int>> = childrenByParent
        .filterValues { it.isNotEmpty() }
        .keys
        .mapTo(linkedSetOf()) { it.toList() }

    fun find(path: List<Int>): AccessibilityNodeSnapshot? = nodesByPath[path]

    fun parentOf(path: List<Int>): AccessibilityNodeSnapshot? =
        if (path.isEmpty()) null else find(path.dropLast(1))

    fun childrenOf(path: List<Int>): List<AccessibilityNodeSnapshot> =
        childrenByParent[path].orEmpty()

    /**
     * Returns the currently visible rows in depth-first order. The caller owns the expanded set,
     * allowing a view to preserve expansion state while selection and details are updated.
     */
    fun visibleRows(
        expandedPaths: Set<List<Int>>,
        retainedPaths: Set<List<Int>>? = null,
        hiddenPaths: Set<List<Int>> = emptySet()
    ): List<Row> {
        val result = ArrayList<Row>(nodesByPath.size)
        val stack = ArrayDeque<Pair<AccessibilityNodeSnapshot, Int>>()
        rootNodes.asReversed().filter { retainedPaths == null || pathOf(it) in retainedPaths }
            .forEach { stack.addLast(it to 0) }
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            val path = node.path.childIndices
            val children = childrenOf(path)
            val hidden = path in hiddenPaths
            val expanded = path in expandedPaths
            if (!hidden) result += Row(node, depth, children.isNotEmpty(), expanded)
            // A hidden row is transparent in the hierarchy: its captured descendants remain
            // reachable, but the hidden node itself cannot be selected or opened again.
            if (hidden || expanded) {
                children.asReversed()
                    .filter { retainedPaths == null || pathOf(it) in retainedPaths }
                    .forEach { child -> stack.addLast(child to if (hidden) depth else depth + 1) }
            }
        }
        return result
    }

    /** Expands the selected node and all descendants that have captured children. */
    fun descendantExpandablePaths(path: List<Int>): Set<List<Int>> =
        expandablePaths.filterTo(linkedSetOf()) { candidate ->
            candidate.size >= path.size && candidate.take(path.size) == path
        }

    /** Removes expansion state for descendants but leaves the selected node itself unchanged. */
    fun descendantPaths(path: List<Int>): Set<List<Int>> = nodesByPath.keys
        .filterTo(linkedSetOf()) { candidate ->
            candidate.size > path.size && candidate.take(path.size) == path
        }

    fun pathOf(node: AccessibilityNodeSnapshot): List<Int> = node.path.childIndices.toList()

    fun pathParent(path: List<Int>): List<Int>? = path.takeIf { it.isNotEmpty() }?.dropLast(1)
}
