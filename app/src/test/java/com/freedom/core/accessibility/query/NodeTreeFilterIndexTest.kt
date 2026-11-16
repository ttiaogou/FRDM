package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assert.*

class NodeTreeFilterIndexTest {
    @Test
    fun filtersClassAndViewIdCaseInsensitivelyInOriginalOrder() {
        val first = node(0, "android.widget.Button", "com.example:id/Save")
        val second = node(1, "android.widget.TextView", "com.example:id/title")
        val index = NodeTreeFilterIndex(listOf(first, second))

        val result = index.search("BUTTON", 10)

        assertEquals(listOf(first), result.nodes)
        assertEquals(1, result.matchedCount)
        assertTrue(result.matchCountExact)
        assertEquals(2, result.scannedNodeCount)
        assertFalse(result.truncated)
    }

    @Test
    fun capsRetainedRowsButReportsExactTotalMatches() {
        val nodes = (0 until 8).map { node(it, "Button$it", "button_$it") }
        val result = NodeTreeFilterIndex(nodes).search("button", 3)

        assertEquals(nodes.take(3), result.nodes)
        assertEquals(8, result.matchedCount)
        assertTrue(result.matchCountExact)
        assertTrue(result.truncated)
    }

    @Test
    fun blankKeywordReturnsFirstPageAndNeverUsesTextOrDescription() {
        val privateText = node(0, "TextView", "").copy(
            text = "private searchable phrase",
            contentDescription = "private description"
        )
        val index = NodeTreeFilterIndex(listOf(privateText, node(1, "Button", "go")))

        assertEquals(2, index.search("", 10).matchedCount)
        assertTrue(index.search("private", 10).nodes.isEmpty())
    }

    @Test
    fun rejectsZeroResultLimit() {
        val failure = runCatching { NodeTreeFilterIndex(emptyList()).search("", 0) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun node(index: Int, className: String, viewId: String) = AccessibilityNodeSnapshot(
        path = NodePath(listOf(index)), className = className, viewId = viewId,
        text = null, contentDescription = null, bounds = ScreenRect(0, 0, 10, 10),
        visible = true, enabled = true, checkable = false, checked = false,
        clickable = false, longClickable = false, scrollable = false, editable = false,
        selected = false, focused = false, childCount = 0
    )
}
