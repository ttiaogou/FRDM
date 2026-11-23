package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AccessibilityNodeInspectionIndexTest {
    @Test
    fun hitTestChoosesSmallestDeepestVisibleNode() {
        val root = node(path = emptyList(), bounds = ScreenRect(0, 0, 500, 800))
        val container = node(path = listOf(0), bounds = ScreenRect(20, 20, 400, 500))
        val child = node(path = listOf(0, 1), bounds = ScreenRect(100, 100, 220, 180))
        val index = index(root, container, child)

        assertSame(child, index.nodeAt(120, 130))
        assertSame(container, index.nodeAt(30, 30))
        assertSame(root, index.nodeAt(450, 700))
    }

    @Test
    fun hiddenAndEmptyBoundsAreNotSpatialTargets() {
        val visible = node(path = emptyList(), bounds = ScreenRect(0, 0, 100, 100))
        val hidden = node(path = listOf(0), bounds = ScreenRect(10, 10, 90, 90), visible = false)
        val empty = node(path = listOf(1), bounds = ScreenRect(20, 20, 20, 40))
        val index = index(visible, hidden, empty)

        assertEquals(listOf(visible), index.visibleBoundedNodes)
        assertSame(visible, index.nodeAt(30, 30))
    }

    @Test
    fun exposesDirectParentAndChildrenWithoutGuessingMissingNodes() {
        val root = node(path = emptyList(), bounds = ScreenRect(0, 0, 500, 800))
        val parent = node(path = listOf(2), bounds = ScreenRect(0, 0, 300, 500))
        val firstChild = node(path = listOf(2, 0), bounds = ScreenRect(10, 10, 100, 100))
        val grandchild = node(path = listOf(2, 0, 1), bounds = ScreenRect(20, 20, 60, 60))
        val secondChild = node(path = listOf(2, 1), bounds = ScreenRect(120, 10, 200, 100))
        val index = index(root, parent, firstChild, grandchild, secondChild)

        assertSame(parent, index.parentOf(firstChild.path.childIndices))
        assertEquals(listOf(firstChild, secondChild), index.childrenOf(parent.path.childIndices))
        assertEquals(emptyList<AccessibilityNodeSnapshot>(), index.childrenOf(secondChild.path.childIndices))
        assertNull(index.parentOf(emptyList()))
        assertNull(index.parentOf(listOf(9, 1)))
    }

    @Test
    fun ancestorsAreReturnedFromRootToDirectParent() {
        val root = node(path = emptyList(), bounds = ScreenRect(0, 0, 500, 800))
        val parent = node(path = listOf(2), bounds = ScreenRect(0, 0, 300, 500))
        val selected = node(path = listOf(2, 4, 1), bounds = ScreenRect(20, 20, 80, 80))
        val index = index(root, parent, selected)

        assertEquals(listOf(root, parent), index.ancestorsOf(selected.path.childIndices))
        assertNull(index.find(listOf(9)))
    }

    private fun index(vararg nodes: AccessibilityNodeSnapshot): AccessibilityNodeInspectionIndex =
        AccessibilityNodeInspectionIndex(
            AccessibilityTreeSnapshot(
                window = WindowRef("com.example", "ExampleActivity", 1L),
                nodes = nodes.toList(),
                visitedNodeCount = nodes.size,
                truncated = false
            )
        )

    private fun node(
        path: List<Int>,
        bounds: ScreenRect,
        visible: Boolean = true,
        drawingOrder: Int = path.lastOrNull() ?: 0
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = "android.view.View",
        viewId = "",
        text = null,
        contentDescription = null,
        bounds = bounds,
        visible = visible,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = false,
        longClickable = false,
        scrollable = false,
        editable = false,
        selected = false,
        focused = false,
        childCount = 0,
        drawingOrder = drawingOrder
    )
}
