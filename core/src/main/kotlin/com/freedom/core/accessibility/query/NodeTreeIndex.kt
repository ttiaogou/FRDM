package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodeIdentityFingerprint
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.NodeTargetRelocationPolicy

/** Result of querying one immutable accessibility-tree snapshot. */
data class NodeQueryResult(
    val matchedPaths: List<NodePath>,
    /** Exact for findAll; a lower bound for early-stop queries. */
    val matchCount: Int,
    val matchCountExact: Boolean,
    val visitedNodeCount: Int,
    val truncated: Boolean,
    val stopReason: String?,
    val ambiguous: Boolean,
    val queryElapsedMs: Long,
    val selectorDescription: String
) {
    val firstPath: NodePath? get() = matchedPaths.firstOrNull()
}

/**
 * An index over one immutable snapshot. It never retains Android node handles and is invalidated
 * by its window generation. Construction is O(n); subsequent lookups reuse the same node order.
 */
class NodeTreeIndex(val snapshot: AccessibilityTreeSnapshot) {
    private val orderedNodes = snapshot.nodes.toList()
    private val byPath = LinkedHashMap<NodePath, AccessibilityNodeSnapshot>(orderedNodes.size)
    private val childrenByPath = LinkedHashMap<NodePath, MutableList<NodePath>>()

    init {
        orderedNodes.forEach { node -> byPath.putIfAbsent(node.path, node) }
        orderedNodes.forEach { node ->
            val parent = parentPath(node.path) ?: return@forEach
            if (parent in byPath) childrenByPath.getOrPut(parent) { ArrayList() }.add(node.path)
        }
    }

    val nodeCount: Int get() = orderedNodes.size
    val windowGeneration: Long get() = snapshot.window.generation

    fun nodeAt(path: NodePath): AccessibilityNodeSnapshot? = byPath[path]

    fun parentOf(path: NodePath): AccessibilityNodeSnapshot? =
        parentPath(path)?.let(byPath::get)

    /**
     * Builds a privacy-preserving fingerprint from fields that were actually captured.
     * Fallback values from failed or skipped getters must never become identity evidence.
     */
    fun identityFingerprint(path: NodePath): NodeIdentityFingerprint? {
        val node = byPath[path] ?: return null
        val parent = parentOf(path)
        fun wasCaptured(source: AccessibilityNodeSnapshot?, field: AccessibilityNodeField): Boolean =
            source != null && source.captureState(field, snapshot.textCapture) in setOf(
                AccessibilityFieldCaptureState.VALUE_PRESENT,
                AccessibilityFieldCaptureState.EMPTY
            )

        return NodeIdentityFingerprint.create(
            className = node.className.takeIf { wasCaptured(node, AccessibilityNodeField.CLASS_NAME) },
            viewId = node.viewId.takeIf { wasCaptured(node, AccessibilityNodeField.VIEW_ID) },
            text = node.text.takeIf { wasCaptured(node, AccessibilityNodeField.TEXT) },
            contentDescription = node.contentDescription.takeIf {
                wasCaptured(node, AccessibilityNodeField.CONTENT_DESCRIPTION)
            },
            childCount = node.childCount.takeIf { wasCaptured(node, AccessibilityNodeField.CHILD_COUNT) },
            parentClassName = parent?.className?.takeIf { wasCaptured(parent, AccessibilityNodeField.CLASS_NAME) },
            parentViewId = parent?.viewId?.takeIf { wasCaptured(parent, AccessibilityNodeField.VIEW_ID) },
            windowId = node.windowId.takeIf { wasCaptured(node, AccessibilityNodeField.WINDOW_ID) } ?: -1
        )
    }

    /** Creates a target tied to this snapshot and eligible for unique same-window relocation. */
    fun targetFor(
        path: NodePath,
        relocationPolicy: NodeTargetRelocationPolicy = NodeTargetRelocationPolicy.UNIQUE_MATCH_IN_SAME_WINDOW
    ): NodeTarget? {
        val node = byPath[path] ?: return null
        val expectedViewId = node.viewId.takeIf {
            it.isNotBlank() && node.hasCapturedField(AccessibilityNodeField.VIEW_ID)
        }
        val expectedClassName = node.className.takeIf {
            it.isNotBlank() && node.hasCapturedField(AccessibilityNodeField.CLASS_NAME)
        }
        return NodeTarget(
            window = snapshot.window,
            path = path,
            expectedViewId = expectedViewId,
            expectedClassName = expectedClassName,
            identityFingerprint = identityFingerprint(path),
            relocationPolicy = relocationPolicy
        )
    }

    fun childrenOf(path: NodePath): List<AccessibilityNodeSnapshot> =
        childrenByPath[path].orEmpty().mapNotNull(byPath::get)

    /** Nearest ancestor first; the node itself is excluded. */
    fun ancestorsOf(path: NodePath): List<AccessibilityNodeSnapshot> {
        val result = ArrayList<AccessibilityNodeSnapshot>()
        var parent = parentPath(path)
        while (parent != null) {
            byPath[parent]?.let(result::add)
            parent = parentPath(parent)
        }
        return result
    }

    /** Lazily exposes descendants in the snapshot's original traversal order. */
    fun descendantSequenceOf(
        path: NodePath,
        includeSelf: Boolean = false
    ): Sequence<AccessibilityNodeSnapshot> = orderedNodes.asSequence().filter { node ->
        if (includeSelf && node.path == path) true else isWithin(node.path, path) && node.path != path
    }

    fun descendantsOf(path: NodePath, includeSelf: Boolean = false): List<AccessibilityNodeSnapshot> =
        descendantSequenceOf(path, includeSelf).toList()

    /** Compares this (current) index with a previous complete snapshot without retaining node handles. */
    fun diffFrom(previous: NodeTreeIndex): NodeTreeDelta {
        if (snapshot.window != previous.snapshot.window) {
            return NodeTreeDelta(comparable = false, reason = "WINDOW_CHANGED")
        }
        if (snapshot.truncated || previous.snapshot.truncated) {
            return NodeTreeDelta(comparable = false, reason = "INCOMPLETE_SNAPSHOT")
        }
        val oldByPath = previous.orderedNodes.associateBy { it.path }
        val currentByPath = orderedNodes.associateBy { it.path }
        val added = ArrayList<NodePath>()
        val changed = ArrayList<NodePath>()
        var unchanged = 0
        orderedNodes.forEach { current ->
            val old = oldByPath[current.path]
            when {
                old == null -> added += current.path
                old.structuralState() == current.structuralState() -> unchanged++
                else -> changed += current.path
            }
        }
        val removed = previous.orderedNodes.asSequence()
            .map { it.path }
            .filter { it !in currentByPath }
            .toList()
        return NodeTreeDelta(
            comparable = true,
            addedPaths = added,
            removedPaths = removed,
            changedPaths = changed,
            unchangedNodeCount = unchanged
        )
    }

    fun commonAncestor(first: NodePath, second: NodePath): AccessibilityNodeSnapshot? {
        val common = commonPrefix(first.childIndices, second.childIndices)
        return byPath[NodePath(common)]
    }

    fun nearestClickableAncestor(path: NodePath, includeSelf: Boolean = true, maxLevels: Int = Int.MAX_VALUE): AccessibilityNodeSnapshot? {
        require(maxLevels >= 0) { "maxLevels cannot be negative" }
        var current: NodePath? = if (includeSelf) path else parentPath(path)
        var levels = 0
        while (current != null && levels <= maxLevels) {
            val node = byPath[current]
            if (node?.clickable == true) return node
            current = parentPath(current)
            levels++
        }
        return null
    }

    /** Returns true only when both paths descend from the same direct child of listRoot. */
    fun sameRow(first: NodePath, second: NodePath, listRoot: NodePath): Boolean {
        val firstRow = directChildUnder(first, listRoot) ?: return false
        val secondRow = directChildUnder(second, listRoot) ?: return false
        return firstRow == secondRow
    }

    fun isWithin(path: NodePath, rootPath: NodePath): Boolean {
        val candidate = path.childIndices
        val root = rootPath.childIndices
        return candidate.size >= root.size && candidate.subList(0, root.size) == root
    }

    fun findAll(
        selector: NodeSelector,
        rootPath: NodePath? = null,
        budget: NodeQueryBudget = NodeQueryBudget.DEFAULT
    ): NodeQueryResult = query(selector, rootPath, stopAfterMatches = Int.MAX_VALUE, budget = budget)

    fun findFirst(
        selector: NodeSelector,
        rootPath: NodePath? = null,
        budget: NodeQueryBudget = NodeQueryBudget.DEFAULT
    ): NodeQueryResult = query(selector, rootPath, stopAfterMatches = 1, budget = budget)

    /** Scans until either one result or a second result proves ambiguity. */
    fun findUnique(
        selector: NodeSelector,
        rootPath: NodePath? = null,
        budget: NodeQueryBudget = NodeQueryBudget.DEFAULT
    ): NodeQueryResult = query(selector, rootPath, stopAfterMatches = 2, budget = budget)

    fun queryWithin(
        rootPath: NodePath,
        selector: NodeSelector,
        budget: NodeQueryBudget = NodeQueryBudget.DEFAULT
    ): NodeQueryResult = findAll(selector, rootPath, budget)

    private fun query(
        selector: NodeSelector,
        rootPath: NodePath?,
        stopAfterMatches: Int,
        budget: NodeQueryBudget
    ): NodeQueryResult {
        val started = System.nanoTime()
        val rootExists = rootPath == null || rootPath in byPath
        if (!rootExists) {
            return NodeQueryResult(
                matchedPaths = emptyList(), matchCount = 0, matchCountExact = true,
                visitedNodeCount = 0, truncated = false, stopReason = "ROOT_NOT_FOUND",
                ambiguous = false, queryElapsedMs = elapsedMs(started), selectorDescription = selector.describe()
            )
        }
        val matches = ArrayList<NodePath>(minOf(stopAfterMatches, 8))
        var visited = 0
        var stoppedEarly = false
        var budgetStopReason: String? = null
        val timeoutNanos = if (budget.timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE
            else budget.timeoutMs.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        for (node in orderedNodes) {
            if (rootPath != null && !isWithin(node.path, rootPath)) continue
            if (visited >= budget.maxVisitedNodes) {
                budgetStopReason = "QUERY_NODE_BUDGET"
                break
            }
            if (timeoutNanos != Long.MAX_VALUE && System.nanoTime() - started >= timeoutNanos) {
                budgetStopReason = "QUERY_TIME_BUDGET"
                break
            }
            visited++
            if (selector.matches(node)) {
                matches += node.path
                if (matches.size >= stopAfterMatches) {
                    stoppedEarly = stopAfterMatches != Int.MAX_VALUE
                    break
                }
            }
        }
        val ambiguous = stopAfterMatches == 2 && matches.size > 1
        val queryTruncated = snapshot.truncated || budgetStopReason != null
        return NodeQueryResult(
            matchedPaths = matches,
            matchCount = matches.size,
            matchCountExact = !stoppedEarly && !queryTruncated,
            visitedNodeCount = visited,
            // Query budget exhaustion is distinct from early success and from source-tree truncation.
            truncated = queryTruncated,
            stopReason = when {
                ambiguous -> "MULTIPLE_MATCHES"
                stoppedEarly -> "EARLY_MATCH"
                budgetStopReason != null -> budgetStopReason
                snapshot.stopReason != null -> snapshot.stopReason
                else -> null
            },
            ambiguous = ambiguous,
            queryElapsedMs = elapsedMs(started),
            selectorDescription = selector.describe()
        )
    }

    private fun directChildUnder(path: NodePath, root: NodePath): NodePath? {
        if (!isWithin(path, root) || path.childIndices.size <= root.childIndices.size) return null
        return NodePath(path.childIndices.take(root.childIndices.size + 1))
    }

    private fun parentPath(path: NodePath): NodePath? =
        if (path.childIndices.isEmpty()) null else NodePath(path.childIndices.dropLast(1))

    private fun commonPrefix(first: List<Int>, second: List<Int>): List<Int> =
        first.zip(second).takeWhile { (left, right) -> left == right }.map { it.first }

    private fun elapsedMs(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L)
}
