package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHierarchyTreeTest {
    @Test
    fun visibleRowsStartAtRootsAndExpandInDepthFirstOrder() {
        val tree = AccessibilityHierarchyTree(listOf(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "First", childCount = 1),
            node(listOf(1), "Second"),
            node(listOf(0, 0), "Nested")
        ))

        assertEquals(listOf("Root"), labels(tree.visibleRows(emptySet())))
        assertEquals(
            listOf("Root", "First", "Second"),
            labels(tree.visibleRows(setOf(emptyList())))
        )
        assertEquals(
            listOf("Root", "First", "Nested", "Second"),
            labels(tree.visibleRows(setOf(emptyList(), listOf(0))))
        )
    }

    @Test
    fun reportsParentChildrenAndExpandablePaths() {
        val root = node(emptyList(), "Root", childCount = 1)
        val child = node(listOf(0), "Child", childCount = 1)
        val grandchild = node(listOf(0, 0), "Grandchild")
        val tree = AccessibilityHierarchyTree(listOf(root, child, grandchild))

        assertEquals(child, tree.childrenOf(emptyList()).single())
        assertEquals(root, tree.parentOf(listOf(0)))
        assertNull(tree.parentOf(emptyList()))
        assertEquals(setOf(emptyList(), listOf(0)), tree.expandablePaths)
        assertEquals(setOf(emptyList(), listOf(0)), tree.descendantExpandablePaths(emptyList()))
    }

    @Test
    fun missingParentsArePromotedToRootsAndDoNotDisappear() {
        val orphan = node(listOf(4, 2), "Orphan")
        val tree = AccessibilityHierarchyTree(listOf(orphan))

        assertEquals(listOf(orphan), tree.rootNodes)
        assertEquals(listOf("Orphan"), labels(tree.visibleRows(emptySet())))
    }

    @Test
    fun descendantCollapseScopeDoesNotIncludeSelectedNode() {
        val tree = AccessibilityHierarchyTree(listOf(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "Child", childCount = 1),
            node(listOf(0, 0), "Grandchild")
        ))

        assertEquals(setOf(listOf(0), listOf(0, 0)), tree.descendantPaths(emptyList()))
        assertFalse(tree.descendantPaths(emptyList()).contains(emptyList<Int>()))
        assertTrue(tree.visibleRows(setOf(emptyList(), listOf(0))).size == 3)
    }


    @Test
    fun filteredRowsRetainOnlyMatchesAndTheirAncestors() {
        val tree = AccessibilityHierarchyTree(listOf(
            node(emptyList(), "Root", childCount = 2),
            node(listOf(0), "Branch", childCount = 1),
            node(listOf(0, 0), "Match"),
            node(listOf(1), "Unrelated")
        ))
        val retained = setOf(emptyList(), listOf(0), listOf(0, 0))
        val rows = tree.visibleRows(setOf(emptyList(), listOf(0)), retained)
        assertEquals(listOf("Root", "Branch", "Match"), labels(rows))
    }


    @Test
    fun hiddenRowsAreNotSelectableButTheirCapturedDescendantsRemainReachable() {
        val tree = AccessibilityHierarchyTree(listOf(
            node(emptyList(), "Root", childCount = 1),
            node(listOf(0), "HiddenBranch", childCount = 1),
            node(listOf(0, 0), "VisibleChild")
        ))

        val rows = tree.visibleRows(
            expandedPaths = setOf(emptyList()),
            hiddenPaths = setOf(listOf(0))
        )
        assertEquals(listOf("Root", "VisibleChild"), labels(rows))
        assertEquals(1, rows.last().depth)
    }

    private fun labels(rows: List<AccessibilityHierarchyTree.Row>) = rows.map { it.node.className }

    private fun node(path: List<Int>, name: String, childCount: Int = 0) =
        AccessibilityNodeSnapshot(
            path = NodePath(path),
            className = name,
            viewId = "",
            text = null,
            contentDescription = null,
            bounds = ScreenRect(0, 0, 10, 10),
            visible = true,
            enabled = true,
            checkable = false,
            checked = false,
            clickable = false,
            longClickable = false,
            scrollable = false,
            editable = false,
            selected = false,
            focused = false,
            childCount = childCount
        )
}
