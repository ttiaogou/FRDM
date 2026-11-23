package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.NodeTargetRelocationPolicy

/** Explicit work allowance for resolving a target against one freshly captured snapshot. */
data class NodeTargetResolutionBudget(
    val maxVisitedNodes: Int = 3_000,
    val timeoutMs: Long = 250L
) {
    init {
        require(maxVisitedNodes > 0) { "maxVisitedNodes must be positive" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
    }

    companion object {
        val DEFAULT = NodeTargetResolutionBudget()
    }
}

/** Outcome of validating or relocating a short-lived node target against a fresh tree snapshot. */
enum class NodeTargetResolutionStatus {
    EXACT_PATH,
    RELOCATED,
    WINDOW_MISMATCH,
    WINDOW_STALE,
    WINDOW_ID_MISMATCH,
    PATH_MISMATCH,
    NOT_FOUND,
    AMBIGUOUS,
    INSUFFICIENT_IDENTITY,
    SNAPSHOT_TRUNCATED,
    QUERY_BUDGET_EXCEEDED
}

data class NodeTargetResolution(
    val status: NodeTargetResolutionStatus,
    val resolvedPath: NodePath? = null,
    val candidatePaths: List<NodePath> = emptyList(),
    val scannedNodeCount: Int = 0,
    val queryElapsedMs: Long = 0L,
    val stopReason: String? = null
) {
    val isResolved: Boolean
        get() = status == NodeTargetResolutionStatus.EXACT_PATH ||
            status == NodeTargetResolutionStatus.RELOCATED
}

/**
 * Resolves targets without retaining Android node handles. Relocation is deliberately fail-closed:
 * the package/activity/window generation must be identical, the new snapshot must be complete,
 * identity fields must have been captured successfully, and exactly one candidate must match.
 * A full uniqueness scan is bounded; budget exhaustion is never reported as a successful match.
 */
object NodeTargetResolver {
    fun resolve(
        target: NodeTarget,
        snapshot: AccessibilityTreeSnapshot,
        budget: NodeTargetResolutionBudget = NodeTargetResolutionBudget.DEFAULT
    ): NodeTargetResolution {
        val startedAtNanos = System.nanoTime()
        fun elapsedMs(): Long = ((System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L)

        if (target.window.packageName != snapshot.window.packageName ||
            target.window.activityClass != snapshot.window.activityClass
        ) {
            return NodeTargetResolution(NodeTargetResolutionStatus.WINDOW_MISMATCH, queryElapsedMs = elapsedMs())
        }
        if (target.window.generation != snapshot.window.generation) {
            return NodeTargetResolution(NodeTargetResolutionStatus.WINDOW_STALE, queryElapsedMs = elapsedMs())
        }

        val fingerprint = target.identityFingerprint
        val mayRelocate = target.relocationPolicy == NodeTargetRelocationPolicy.UNIQUE_MATCH_IN_SAME_WINDOW
        if (fingerprint != null && mayRelocate && snapshot.truncated) {
            return NodeTargetResolution(
                status = NodeTargetResolutionStatus.SNAPSHOT_TRUNCATED,
                scannedNodeCount = snapshot.nodes.size,
                queryElapsedMs = elapsedMs(),
                stopReason = snapshot.stopReason ?: "SOURCE_SNAPSHOT_INCOMPLETE"
            )
        }
        if (fingerprint != null && fingerprint.windowId >= 0 &&
            snapshot.windowId >= 0 && fingerprint.windowId != snapshot.windowId
        ) {
            return NodeTargetResolution(
                NodeTargetResolutionStatus.WINDOW_ID_MISMATCH,
                queryElapsedMs = elapsedMs()
            )
        }

        // Index construction and path lookup are also linear in snapshot size. Reject oversized
        // input up front for both legacy path checks and fingerprint-based relocation.
        if (snapshot.nodes.size > budget.maxVisitedNodes) {
            return NodeTargetResolution(
                status = NodeTargetResolutionStatus.QUERY_BUDGET_EXCEEDED,
                scannedNodeCount = 0,
                queryElapsedMs = elapsedMs(),
                stopReason = "QUERY_NODE_BUDGET"
            )
        }
        val timeoutNanos = if (budget.timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE
            else budget.timeoutMs.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        fun timeBudgetExceeded(): Boolean =
            timeoutNanos != Long.MAX_VALUE && System.nanoTime() - startedAtNanos >= timeoutNanos

        // Path-only targets retain their legacy contract and do not incur a whole-tree fingerprint scan.
        if (!mayRelocate) {
            var visited = 0
            var node: com.freedom.core.accessibility.api.AccessibilityNodeSnapshot? = null
            for (candidate in snapshot.nodes) {
                if (timeBudgetExceeded()) {
                    return budgetExceeded(emptyList(), visited, elapsedMs(), "QUERY_TIME_BUDGET")
                }
                visited++
                if (candidate.path == target.path) {
                    node = candidate
                    break
                }
            }
            val matches = node != null && matchesExpectedFields(target, node)
            return NodeTargetResolution(
                status = if (matches) NodeTargetResolutionStatus.EXACT_PATH
                    else NodeTargetResolutionStatus.PATH_MISMATCH,
                resolvedPath = target.path.takeIf { matches },
                candidatePaths = listOfNotNull(target.path.takeIf { matches }),
                scannedNodeCount = visited,
                queryElapsedMs = elapsedMs()
            )
        }

        // Build a snapshot-local path index only after the size guard above.
        val index = NodeTreeIndex(snapshot)
        if (timeBudgetExceeded()) {
            return budgetExceeded(emptyList(), 0, elapsedMs(), "QUERY_TIME_BUDGET")
        }
        val original = index.nodeAt(target.path)
        val originalFieldsMatch = original != null && matchesExpectedFields(target, original)

        // Without a fingerprint we can validate the old path, but cannot safely relocate it.
        if (fingerprint == null) {
            return if (originalFieldsMatch) {
                NodeTargetResolution(
                    status = NodeTargetResolutionStatus.EXACT_PATH,
                    resolvedPath = target.path,
                    candidatePaths = listOf(target.path),
                    scannedNodeCount = 1,
                    queryElapsedMs = elapsedMs()
                )
            } else {
                NodeTargetResolution(
                    status = NodeTargetResolutionStatus.INSUFFICIENT_IDENTITY,
                    scannedNodeCount = if (original == null) 0 else 1,
                    queryElapsedMs = elapsedMs()
                )
            }
        }

        val originalMatches = originalFieldsMatch &&
            index.identityFingerprint(target.path)?.let(fingerprint::matches) == true
        if (!fingerprint.isStrongEnoughForRelocation && !originalMatches) {
            return NodeTargetResolution(
                NodeTargetResolutionStatus.INSUFFICIENT_IDENTITY,
                scannedNodeCount = if (original == null) 0 else 1,
                queryElapsedMs = elapsedMs()
            )
        }

        val matches = ArrayList<NodePath>(2)
        var visited = 0
        for (candidate in snapshot.nodes) {
            if (visited >= budget.maxVisitedNodes) {
                return budgetExceeded(matches, visited, elapsedMs(), "QUERY_NODE_BUDGET")
            }
            if (timeBudgetExceeded()) {
                return budgetExceeded(matches, visited, elapsedMs(), "QUERY_TIME_BUDGET")
            }
            visited++
            if (!matchesExpectedFields(target, candidate)) continue
            val candidateFingerprint = index.identityFingerprint(candidate.path) ?: continue
            if (fingerprint.matches(candidateFingerprint)) {
                matches += candidate.path
                // Two matches are enough to prove ambiguity, so stop without exhausting the budget.
                if (matches.size == 2) {
                    return NodeTargetResolution(
                        status = NodeTargetResolutionStatus.AMBIGUOUS,
                        candidatePaths = matches.toList(),
                        scannedNodeCount = visited,
                        queryElapsedMs = elapsedMs(),
                        stopReason = "MULTIPLE_MATCHES"
                    )
                }
            }
        }

        return when (matches.size) {
            0 -> NodeTargetResolution(
                status = NodeTargetResolutionStatus.NOT_FOUND,
                scannedNodeCount = visited,
                queryElapsedMs = elapsedMs()
            )
            1 -> {
                val path = matches.single()
                NodeTargetResolution(
                    status = if (path == target.path) NodeTargetResolutionStatus.EXACT_PATH
                        else NodeTargetResolutionStatus.RELOCATED,
                    resolvedPath = path,
                    candidatePaths = matches,
                    scannedNodeCount = visited,
                    queryElapsedMs = elapsedMs()
                )
            }
            else -> error("Candidate scan exits as soon as ambiguity is proven")
        }
    }

    private fun budgetExceeded(
        partialMatches: List<NodePath>,
        visited: Int,
        elapsedMs: Long,
        reason: String
    ) = NodeTargetResolution(
        status = NodeTargetResolutionStatus.QUERY_BUDGET_EXCEEDED,
        candidatePaths = partialMatches.toList(),
        scannedNodeCount = visited,
        queryElapsedMs = elapsedMs,
        stopReason = reason
    )

    private fun matchesExpectedFields(
        target: NodeTarget,
        node: com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
    ): Boolean =
        (target.expectedViewId == null ||
            node.hasCapturedField(AccessibilityNodeField.VIEW_ID) && node.viewId == target.expectedViewId) &&
            (target.expectedClassName == null ||
                node.hasCapturedField(AccessibilityNodeField.CLASS_NAME) && node.className == target.expectedClassName)
}
