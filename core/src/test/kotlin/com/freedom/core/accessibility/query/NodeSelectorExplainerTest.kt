package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.*
import org.junit.Test

class NodeSelectorExplainerTest {
    @Test
    fun explainsTheFirstFailingFilterAndRetainsTotalMatchesBeyondKeepLimit() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 3),
            node(listOf(0), "TextView", viewId = "title", text = "Alice"),
            node(listOf(1), "TextView", viewId = "title", text = "Bob"),
            node(listOf(2), "Button", viewId = "action", text = "Open")
        )
        val explanation = NodeSelectorExplainer.explain(
            NodeTreeIndex(snapshot),
            NodeSelector(className = "TextView", viewId = "title"),
            keep = 1
        )
        assertEquals(2, explanation.matchCount)
        assertEquals(1, explanation.matches.size)
        assertTrue(explanation.matchCountExact)
        assertEquals(2, explanation.steps.last().cumulative)
        assertNull(explanation.failingFilter)
    }

    @Test
    fun selectorWithoutConditionsCountsAllVisitedNodesEvenWhenKeepIsZero() {
        val snapshot = snapshot(node(emptyList(), "Root", childCount = 1), node(listOf(0), "TextView"))
        val explanation = NodeSelectorExplainer.explain(NodeTreeIndex(snapshot), NodeSelector(), keep = 0)
        assertEquals(2, explanation.matchCount)
        assertTrue(explanation.matches.isEmpty())
        assertTrue(explanation.matchCountExact)
    }

    @Test
    fun incompleteSnapshotCannotClaimExactExplanationCount() {
        val snapshot = snapshot(node(emptyList(), "Root"), truncated = true)
        val explanation = NodeSelectorExplainer.explain(NodeTreeIndex(snapshot), NodeSelector())
        assertFalse(explanation.matchCountExact)
    }

    @Test
    fun queryBudgetMarksSelectorExplanationAsPartial() {
        val snapshot = snapshot(
            node(emptyList(), "Root", childCount = 3),
            node(listOf(0), "TextView", viewId = "title", text = "Alice"),
            node(listOf(1), "TextView", viewId = "title", text = "Bob"),
            node(listOf(2), "Button", viewId = "action", text = "Open")
        )
        val explanation = NodeSelectorExplainer.explain(
            NodeTreeIndex(snapshot),
            NodeSelector(className = "TextView"),
            budget = NodeQueryBudget(maxVisitedNodes = 2)
        )
        assertEquals(2, explanation.visitedNodeCount)
        assertTrue(explanation.queryTruncated)
        assertFalse(explanation.matchCountExact)
        assertEquals("QUERY_NODE_BUDGET", explanation.stopReason)
    }

    private fun snapshot(vararg nodes: AccessibilityNodeSnapshot, truncated: Boolean = false) =
        AccessibilityTreeSnapshot(
            window = WindowRef("com.example", "MainActivity", 1),
            nodes = nodes.toList(),
            visitedNodeCount = nodes.size,
            truncated = truncated,
            walkTruncated = truncated
        )

    private fun node(
        path: List<Int>,
        className: String,
        viewId: String = "",
        text: String? = null,
        childCount: Int = 0
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = className, viewId = viewId,
        text = text, contentDescription = null, bounds = ScreenRect(0, 0, 10, 10),
        visible = true, enabled = true, checkable = false, checked = false,
        clickable = false, longClickable = false, scrollable = false, editable = false,
        selected = false, focused = false, childCount = childCount
    )
}
