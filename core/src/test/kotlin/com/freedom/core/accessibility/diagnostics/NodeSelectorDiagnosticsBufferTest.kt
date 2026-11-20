package com.freedom.core.accessibility.diagnostics

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.query.NodeSelector
import com.freedom.core.accessibility.query.NodeSelectorExplainer
import com.freedom.core.accessibility.query.NodeTreeIndex
import org.junit.Assert.*
import org.junit.Test

class NodeSelectorDiagnosticsBufferTest {
    @Test
    fun exportsBoundedCountsAndPathsWithoutRawTextSelectorValues() {
        NodeSelectorDiagnosticsBuffer.clear()
        val snapshot = AccessibilityTreeSnapshot(
            window = WindowRef("com.example", "MainActivity", 9),
            nodes = listOf(
                node(emptyList(), "Root", childCount = 1),
                node(listOf(0), "TextView", text = "private name")
            ),
            visitedNodeCount = 2,
            truncated = false
        )
        val index = NodeTreeIndex(snapshot)
        val selector = NodeSelector(textContains = "private name")
        val result = index.findUnique(selector)
        val explanation = NodeSelectorExplainer.explain(index, selector)
        NodeSelectorDiagnosticsBuffer.record(snapshot.window, "test selector", result, explanation)
        val jsonl = NodeSelectorDiagnosticsBuffer.toJsonLines()

        assertTrue(jsonl.contains("\"matchCount\":1"))
        assertTrue(jsonl.contains("\"matchedPaths\":[\"0\"]"))
        assertTrue(jsonl.contains("[REDACTED]"))
        assertFalse(jsonl.contains("private name"))
        NodeSelectorDiagnosticsBuffer.clear()
    }


    @Test
    fun exposesHowManyOlderDiagnosticsWereEvicted() {
        NodeSelectorDiagnosticsBuffer.clear()
        val snapshot = AccessibilityTreeSnapshot(
            window = WindowRef("com.example", "MainActivity", 10),
            nodes = listOf(node(emptyList(), "Root")),
            visitedNodeCount = 1,
            truncated = false
        )
        val index = NodeTreeIndex(snapshot)
        val selector = NodeSelector(className = "Root")
        val result = index.findUnique(selector)
        val explanation = NodeSelectorExplainer.explain(index, selector)
        repeat(85) {
            NodeSelectorDiagnosticsBuffer.record(snapshot.window, "bounded selector", result, explanation)
        }

        assertEquals(5L, NodeSelectorDiagnosticsBuffer.droppedCount())
        assertEquals(80, NodeSelectorDiagnosticsBuffer.snapshot().size)
        NodeSelectorDiagnosticsBuffer.clear()
        assertEquals(0L, NodeSelectorDiagnosticsBuffer.droppedCount())
    }

    private fun node(path: List<Int>, className: String, text: String? = null, childCount: Int = 0) =
        AccessibilityNodeSnapshot(
            path = NodePath(path), className = className, viewId = "",
            text = text, contentDescription = null, bounds = ScreenRect(0, 0, 10, 10),
            visible = true, enabled = true, checkable = false, checked = false,
            clickable = false, longClickable = false, scrollable = false, editable = false,
            selected = false, focused = false, childCount = childCount
        )
}
