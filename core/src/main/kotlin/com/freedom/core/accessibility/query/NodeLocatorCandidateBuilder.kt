package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.NodePath

/** Evidence family used to construct a locator candidate from the selected snapshot node. */
enum class NodeLocatorCandidateKind {
    VIEW_ID_AND_CLASS,
    VIEW_ID,
    TEXT_AND_CLASS,
    CONTENT_DESCRIPTION_AND_CLASS,
    STRUCTURAL_ATTRIBUTES
}

/** Result of checking one candidate against the complete captured tree and its query budget. */
enum class NodeLocatorCandidateState {
    UNIQUE_IN_SNAPSHOT,
    AMBIGUOUS,
    NO_MATCH,
    INDETERMINATE,
    TARGET_NOT_MATCHED
}

enum class NodeLocatorEvidenceStability {
    RESOURCE_IDENTIFIER,
    CONTENT_VALUE,
    STRUCTURAL_ATTRIBUTES
}

data class NodeLocatorCandidate(
    val kind: NodeLocatorCandidateKind,
    val selector: NodeSelector,
    val evidenceFields: List<AccessibilityNodeField>,
    val stability: NodeLocatorEvidenceStability,
    val state: NodeLocatorCandidateState,
    /** Lower bound when the query stopped early; exact only when [matchCountExact] is true. */
    val matchCount: Int,
    val matchCountExact: Boolean,
    val matchedPaths: List<NodePath>,
    val visitedNodeCount: Int,
    val stopReason: String?
)

private data class CandidateSpec(
    val selector: NodeSelector,
    val kind: NodeLocatorCandidateKind,
    val evidenceFields: List<AccessibilityNodeField>,
    val stability: NodeLocatorEvidenceStability
)

data class NodeLocatorAnalysis(
    val selectedPath: NodePath,
    val snapshotComplete: Boolean,
    val candidates: List<NodeLocatorCandidate>,
    val reason: String? = null
) {
    val hasUniqueCandidate: Boolean
        get() = candidates.any { it.state == NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT }
}

/**
 * Creates locator candidates using only values actually present in the selected node snapshot.
 * Each candidate is verified against the same immutable tree; a unique result is explicitly
 * scoped to that snapshot and is not a promise of cross-page stability.
 */
object NodeLocatorCandidateBuilder {
    private const val DEFAULT_MAX_VISITED_NODES = 5_000
    private const val DEFAULT_TIMEOUT_MS = 100L

    val DEFAULT_BUDGET = NodeQueryBudget(
        maxVisitedNodes = DEFAULT_MAX_VISITED_NODES,
        timeoutMs = DEFAULT_TIMEOUT_MS
    )

    fun build(
        index: NodeTreeIndex,
        selectedPath: NodePath,
        budget: NodeQueryBudget = DEFAULT_BUDGET
    ): NodeLocatorAnalysis {
        val node = index.nodeAt(selectedPath)
            ?: return NodeLocatorAnalysis(
                selectedPath = selectedPath,
                snapshotComplete = !index.snapshot.truncated,
                candidates = emptyList(),
                reason = "SELECTED_NODE_NOT_IN_SNAPSHOT"
            )
        val textMode = index.snapshot.textCapture
        val candidates = LinkedHashMap<NodeSelector, CandidateSpec>()

        fun add(
            selector: NodeSelector,
            kind: NodeLocatorCandidateKind,
            fields: List<AccessibilityNodeField>,
            stability: NodeLocatorEvidenceStability
        ) {
            if (selector.matches(node)) {
                candidates.putIfAbsent(
                    selector,
                    CandidateSpec(selector, kind, fields, stability)
                )
            }
        }

        val hasClassName = node.captureState(
            AccessibilityNodeField.CLASS_NAME, textMode
        ) == AccessibilityFieldCaptureState.VALUE_PRESENT && node.className.isNotBlank()
        val hasViewId = node.captureState(
            AccessibilityNodeField.VIEW_ID, textMode
        ) == AccessibilityFieldCaptureState.VALUE_PRESENT && node.viewId.isNotBlank()

        if (hasViewId) {
            if (hasClassName) {
                add(
                    NodeSelector(viewId = node.viewId, className = node.className),
                    NodeLocatorCandidateKind.VIEW_ID_AND_CLASS,
                    listOf(AccessibilityNodeField.VIEW_ID, AccessibilityNodeField.CLASS_NAME),
                    NodeLocatorEvidenceStability.RESOURCE_IDENTIFIER
                )
            }
            add(
                NodeSelector(viewId = node.viewId),
                NodeLocatorCandidateKind.VIEW_ID,
                listOf(AccessibilityNodeField.VIEW_ID),
                NodeLocatorEvidenceStability.RESOURCE_IDENTIFIER
            )
        }

        if (hasClassName && node.captureState(AccessibilityNodeField.TEXT, textMode) ==
            AccessibilityFieldCaptureState.VALUE_PRESENT && !node.text.isNullOrBlank()
        ) {
            add(
                NodeSelector(className = node.className, textEquals = node.text),
                NodeLocatorCandidateKind.TEXT_AND_CLASS,
                listOf(AccessibilityNodeField.CLASS_NAME, AccessibilityNodeField.TEXT),
                NodeLocatorEvidenceStability.CONTENT_VALUE
            )
        }

        if (hasClassName && node.captureState(AccessibilityNodeField.CONTENT_DESCRIPTION, textMode) ==
            AccessibilityFieldCaptureState.VALUE_PRESENT && !node.contentDescription.isNullOrBlank()
        ) {
            add(
                NodeSelector(className = node.className, contentDescriptionEquals = node.contentDescription),
                NodeLocatorCandidateKind.CONTENT_DESCRIPTION_AND_CLASS,
                listOf(AccessibilityNodeField.CLASS_NAME, AccessibilityNodeField.CONTENT_DESCRIPTION),
                NodeLocatorEvidenceStability.CONTENT_VALUE
            )
        }

        if (hasClassName) {
            fun isObserved(field: AccessibilityNodeField): Boolean = node.captureState(field, textMode) in setOf(
                AccessibilityFieldCaptureState.VALUE_PRESENT,
                AccessibilityFieldCaptureState.EMPTY
            )

            val clickable = node.clickable.takeIf { isObserved(AccessibilityNodeField.CLICKABLE) }
            val longClickable = node.longClickable.takeIf { isObserved(AccessibilityNodeField.LONG_CLICKABLE) }
            val scrollable = node.scrollable.takeIf { isObserved(AccessibilityNodeField.SCROLLABLE) }
            val editable = node.editable.takeIf { isObserved(AccessibilityNodeField.EDITABLE) }
            val checkable = node.checkable.takeIf { isObserved(AccessibilityNodeField.CHECKABLE) }
            val enabled = node.enabled.takeIf { isObserved(AccessibilityNodeField.ENABLED) }
            val evidence = buildList {
                if (clickable == true) add(AccessibilityNodeField.CLICKABLE)
                if (longClickable == true) add(AccessibilityNodeField.LONG_CLICKABLE)
                if (scrollable == true) add(AccessibilityNodeField.SCROLLABLE)
                if (editable == true) add(AccessibilityNodeField.EDITABLE)
                if (checkable == true) add(AccessibilityNodeField.CHECKABLE)
            }
            // Avoid manufacturing a weak class-only selector for ordinary, non-interactive nodes.
            if (evidence.isNotEmpty()) {
                val selector = NodeSelector(
                    className = node.className,
                    clickable = clickable,
                    longClickable = longClickable,
                    scrollable = scrollable,
                    editable = editable,
                    checkable = checkable,
                    enabled = enabled
                )
                add(
                    selector,
                    NodeLocatorCandidateKind.STRUCTURAL_ATTRIBUTES,
                    listOf(AccessibilityNodeField.CLASS_NAME) + evidence +
                        listOfNotNull(AccessibilityNodeField.ENABLED.takeIf { enabled != null }),
                    NodeLocatorEvidenceStability.STRUCTURAL_ATTRIBUTES
                )
            }
        }

        // Verify every candidate in one bounded pass over the immutable snapshot. Re-running a
        // whole-tree query once per candidate would multiply work on large accessibility trees.
        val specs = candidates.values.toList()
        val matches = List(specs.size) { ArrayList<NodePath>(2) }
        var visited = 0
        var budgetStopReason: String? = null
        val startedAtNanos = System.nanoTime()
        val timeoutNanos = if (budget.timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE
            else budget.timeoutMs.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        val sourceNodes = index.snapshot.nodes
        if (specs.isNotEmpty()) for (candidateNode in sourceNodes) {
            if (visited >= budget.maxVisitedNodes) {
                budgetStopReason = "QUERY_NODE_BUDGET"
                break
            }
            if (timeoutNanos != Long.MAX_VALUE && System.nanoTime() - startedAtNanos >= timeoutNanos) {
                budgetStopReason = "QUERY_TIME_BUDGET"
                break
            }
            visited++
            specs.forEachIndexed { candidateIndex, spec ->
                val candidateMatches = matches[candidateIndex]
                if (candidateMatches.size < 2 && spec.selector.matches(candidateNode)) {
                    candidateMatches += candidateNode.path
                }
            }
            // Two hits prove ambiguity. If every candidate has two hits, no further scan can
            // improve any candidate's verdict.
            if (matches.isNotEmpty() && matches.all { it.size >= 2 }) break
        }
        val sourceFullyVisited = budgetStopReason == null && visited == sourceNodes.size &&
            (matches.isEmpty() || matches.any { it.size < 2 })
        val verified = specs.mapIndexed { candidateIndex, spec ->
            val matchedPaths = matches[candidateIndex].toList()
            val ambiguous = matchedPaths.size > 1
            val matchCountExact = sourceFullyVisited && !index.snapshot.truncated && !ambiguous
            val state = when {
                ambiguous -> NodeLocatorCandidateState.AMBIGUOUS
                // A single/zero result is not conclusive when the source snapshot omitted part of
                // the tree or the shared scan stopped before reaching every captured node.
                index.snapshot.truncated || !sourceFullyVisited -> NodeLocatorCandidateState.INDETERMINATE
                matchedPaths.isEmpty() -> NodeLocatorCandidateState.NO_MATCH
                matchedPaths.singleOrNull() != selectedPath -> NodeLocatorCandidateState.TARGET_NOT_MATCHED
                else -> NodeLocatorCandidateState.UNIQUE_IN_SNAPSHOT
            }
            NodeLocatorCandidate(
                kind = spec.kind,
                selector = spec.selector,
                evidenceFields = spec.evidenceFields,
                stability = spec.stability,
                state = state,
                matchCount = matchedPaths.size,
                matchCountExact = matchCountExact,
                matchedPaths = matchedPaths,
                visitedNodeCount = visited,
                stopReason = when {
                    ambiguous -> "MULTIPLE_MATCHES"
                    index.snapshot.truncated -> index.snapshot.stopReason ?: "SOURCE_SNAPSHOT_INCOMPLETE"
                    !sourceFullyVisited -> budgetStopReason ?: "QUERY_INCOMPLETE"
                    else -> null
                }
            )
        }

        return NodeLocatorAnalysis(
            selectedPath = selectedPath,
            snapshotComplete = !index.snapshot.truncated,
            candidates = verified,
            reason = when {
                verified.isEmpty() -> "NO_CAPTURED_IDENTITY_OR_INTERACTION_EVIDENCE"
                index.snapshot.truncated -> "SOURCE_SNAPSHOT_INCOMPLETE"
                verified.any { it.state == NodeLocatorCandidateState.INDETERMINATE } -> "ONE_OR_MORE_QUERIES_INCOMPLETE"
                else -> null
            }
        )
    }
}
