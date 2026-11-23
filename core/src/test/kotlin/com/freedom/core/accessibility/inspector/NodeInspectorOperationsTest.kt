package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeInspectorOperationsTest {
    private val windowA = WindowRef("target.app", "target.app.Main", 1L, 10)
    private val windowB = WindowRef("target.app", "target.app.Dialog", 1L, 11)
    private val bounds = ScreenRect(10, 20, 80, 90)

    private fun entry(window: WindowRef, path: List<Int>, rect: ScreenRect = bounds) =
        NodeInspectorEntry(NodeInspectorIdentity(window, NodePath(path)), rect)

    @Test
    fun selectsImmediateParentWithinTheSameWindow() {
        val parent = entry(windowA, listOf(0, 2))
        val ancestor = entry(windowA, listOf(0))
        val selected = entry(windowA, listOf(0, 2, 1))
        assertEquals(parent.identity, NodeInspectorOperations.parentOf(selected.identity, listOf(ancestor, parent, selected)))
    }

    @Test
    fun doesNotSelectParentAcrossWindowsOrForRoot() {
        val selected = entry(windowA, listOf(1))
        val otherWindowParent = entry(windowB, emptyList())
        assertNull(NodeInspectorOperations.parentOf(selected.identity, listOf(otherWindowParent, selected)))
        assertNull(NodeInspectorOperations.parentOf(entry(windowA, emptyList()).identity, listOf(otherWindowParent)))
    }

    @Test
    fun hidesOnlyEqualBoundsNodesInTheSelectedWindow() {
        val first = entry(windowA, listOf(0))
        val same = entry(windowA, listOf(0, 0))
        val different = entry(windowA, listOf(0, 1), ScreenRect(0, 0, 5, 5))
        val otherWindow = entry(windowB, listOf(0))
        assertEquals(setOf(first.identity, same.identity), NodeInspectorOperations.sameBounds(
            first.identity, first.bounds, listOf(first, same, different, otherWindow)
        ))
    }

    @Test
    fun appliesHiddenAndWindowFiltersWithoutChangingTheSourceEntries() {
        val first = entry(windowA, listOf(0))
        val second = entry(windowA, listOf(1))
        val third = entry(windowB, listOf(0))
        val hidden = setOf(second.identity)
        assertEquals(listOf(first, third), NodeInspectorOperations.visibleEntries(listOf(first, second, third), hidden))
        assertEquals(listOf(first), NodeInspectorOperations.visibleEntries(listOf(first, second, third), hidden, windowA))
        assertEquals(3, listOf(first, second, third).size)
    }
}
