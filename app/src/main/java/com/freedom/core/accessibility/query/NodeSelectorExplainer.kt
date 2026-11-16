package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.NodePath

/** Per-filter explanation mirroring AutoJs6 SelectorExplainer's matched/cumulative diagnostics. */
data class NodeSelectorExplanation(
    val selectorDescription: String,
    val visitedNodeCount: Int,
    val snapshotTruncated: Boolean,
    val steps: List<Step>,
    val matches: List<NodePath>,
    val totalMatchCount: Int,
    val matchCountExact: Boolean,
    val nearMisses: List<NodePath>,
    val failingFilter: String?,
    val elapsedMs: Long,
    val queryTruncated: Boolean = false,
    val stopReason: String? = null
) {
    data class Step(
        val index: Int,
        val filter: String,
        val matched: Int,
        val cumulative: Int
    )

    val matchCount: Int get() = steps.lastOrNull()?.cumulative ?: totalMatchCount
    val found: Boolean get() = matchCount > 0
}

object NodeSelectorExplainer {
    const val DEFAULT_KEEP = 8

    fun explain(
        index: NodeTreeIndex,
        selector: NodeSelector,
        rootPath: NodePath? = null,
        keep: Int = DEFAULT_KEEP,
        budget: NodeQueryBudget = NodeQueryBudget.DEFAULT
    ): NodeSelectorExplanation {
        require(keep >= 0) { "keep cannot be negative" }
        val started = System.nanoTime()
        val criteria = selector.criteria()
        val candidates = if (rootPath == null) {
            index.snapshot.nodes.asSequence()
        } else {
            if (index.nodeAt(rootPath) == null) emptySequence()
            else index.descendantSequenceOf(rootPath, includeSelf = true)
        }
        val matched = IntArray(criteria.size)
        val cumulative = IntArray(criteria.size)
        val matches = ArrayList<NodePath>()
        val missesByFilter = Array(criteria.size) { ArrayList<NodePath>() }
        var visited = 0
        var totalMatches = 0
        var stopReason: String? = null
        val timeoutNanos = if (budget.timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE
            else budget.timeoutMs.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        for (node in candidates) {
            if (visited >= budget.maxVisitedNodes) {
                stopReason = "QUERY_NODE_BUDGET"
                break
            }
            if (timeoutNanos != Long.MAX_VALUE && System.nanoTime() - started >= timeoutNanos) {
                stopReason = "QUERY_TIME_BUDGET"
                break
            }
            visited++
            var alive = true
            criteria.forEachIndexed { filterIndex, criterion ->
                val accepted = criterion.matches(node)
                if (accepted) matched[filterIndex]++
                if (alive) {
                    if (accepted) cumulative[filterIndex]++
                    else {
                        alive = false
                        if (missesByFilter[filterIndex].size < keep) {
                            missesByFilter[filterIndex] += node.path
                        }
                    }
                }
            }
            if (alive) {
                totalMatches++
                if (matches.size < keep) matches += node.path
            }
        }
        val steps = criteria.mapIndexed { indexInList, criterion ->
            NodeSelectorExplanation.Step(
                index = indexInList,
                filter = criterion.description,
                matched = matched[indexInList],
                cumulative = cumulative[indexInList]
            )
        }
        val failingIndex = steps.indexOfFirst { it.cumulative == 0 }
        val nearMisses = if (failingIndex >= 1) missesByFilter[failingIndex].toList() else emptyList()
        return NodeSelectorExplanation(
            selectorDescription = selector.describe(),
            visitedNodeCount = visited,
            snapshotTruncated = index.snapshot.truncated,
            steps = steps,
            matches = matches,
            totalMatchCount = totalMatches,
            matchCountExact = !index.snapshot.truncated && stopReason == null,
            nearMisses = nearMisses,
            failingFilter = steps.getOrNull(failingIndex)?.filter,
            elapsedMs = ((System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L),
            queryTruncated = stopReason != null,
            stopReason = stopReason
        )
    }
}
