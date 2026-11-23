package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorSearchEngineTest {
    private val nodes = listOf(
        node(emptyList(), "android.widget.LinearLayout", id = "root", bounds = ScreenRect(0, 0, 400, 800)),
        node(listOf(0), "android.widget.Button", id = "btn_confirm", text = "确认", clickable = true, bounds = ScreenRect(10, 10, 110, 60)),
        node(listOf(1), "android.widget.EditText", id = "input_name", text = "姓名", editable = true, bounds = ScreenRect(10, 100, 210, 160)),
        node(listOf(2), "android.widget.TextView", id = "label_cancel", text = "取消", bounds = ScreenRect(250, 300, 360, 350))
    )

    @Test fun keywordSearchIsCaseInsensitiveAndRetainsAncestors() {
        val result = InspectorSearchEngine.evaluate(snapshot(), InspectorSearchCriteria(query = "BTN_CONFIRM"))
        assertEquals(setOf(listOf(0)), result.matchingPaths)
        assertEquals(setOf(emptyList(), listOf(0)), result.retainedPaths)
    }

    @Test fun textSearchUsesOnlyEphemeralTextCapture() {
        assertTrue(InspectorSearchEngine.evaluate(snapshot(NodeTextCapture.NONE), InspectorSearchCriteria("确认")).matchingPaths.isEmpty())
        assertEquals(setOf(listOf(0)), InspectorSearchEngine.evaluate(snapshot(NodeTextCapture.EPHEMERAL), InspectorSearchCriteria("确认")).matchingPaths)
    }

    @Test fun propertyFiltersAreCombinedAndRequireEverySelectedAttribute() {
        val result = InspectorSearchEngine.evaluate(snapshot(), InspectorSearchCriteria(attributes = setOf(
            InspectorNodeAttribute.CLICKABLE, InspectorNodeAttribute.ENABLED
        )))
        assertEquals(setOf(listOf(0)), result.matchingPaths)
        assertTrue(InspectorSearchEngine.evaluate(snapshot(), InspectorSearchCriteria(attributes = setOf(
            InspectorNodeAttribute.CLICKABLE, InspectorNodeAttribute.EDITABLE
        ))).matchingPaths.isEmpty())
    }

    @Test fun regionFilterRetainsOnlyNodesFullyInsideDragRectangle() {
        val result = InspectorSearchEngine.evaluate(snapshot(), InspectorSearchCriteria(region = ScreenRect(0, 0, 220, 170)))
        assertEquals(setOf(listOf(0), listOf(1)), result.matchingPaths)
        assertNotNull(InspectorSearchEngine.firstMatchRange(snapshot(NodeTextCapture.EPHEMERAL), nodes[1], "确认"))
    }

    @Test fun regularExpressionMatchesAndInvalidPatternIsReported() {
        val result = InspectorSearchEngine.evaluate(snapshot(NodeTextCapture.EPHEMERAL), InspectorSearchCriteria("确|取", regularExpression = true))
        assertEquals(setOf(listOf(0), listOf(2)), result.matchingPaths)
        val invalid = InspectorSearchEngine.evaluate(snapshot(), InspectorSearchCriteria("[", regularExpression = true))
        assertNotNull(invalid.error)
        assertTrue(invalid.matchingPaths.isEmpty())
    }


    @Test fun hiddenPathsAreExcludedFromSearchResults() {
        val result = InspectorSearchEngine.evaluate(
            snapshot(),
            InspectorSearchCriteria(query = ""),
            excludedPaths = setOf(listOf(0))
        )
        assertEquals(setOf(emptyList(), listOf(1), listOf(2)), result.matchingPaths)
        assertTrue(listOf(0) !in result.matchingPaths)
        assertTrue(listOf(0) !in result.retainedPaths)
    }

    private fun snapshot(capture: NodeTextCapture = NodeTextCapture.EPHEMERAL) = AccessibilityTreeSnapshot(
        window = WindowRef("com.example", "com.example.Main", 1L, 1), nodes = nodes,
        visitedNodeCount = nodes.size, truncated = false, windowId = 1, textCapture = capture
    )

    private fun node(
        path: List<Int>, className: String, id: String, text: String? = null,
        clickable: Boolean = false, editable: Boolean = false, bounds: ScreenRect
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = className, viewId = id, text = text,
        contentDescription = null, bounds = bounds, visible = true, enabled = true,
        checkable = false, checked = false, clickable = clickable, longClickable = false,
        scrollable = false, editable = editable, selected = false, focused = false,
        childCount = 0, packageName = "com.example", windowId = 1
    )
}
