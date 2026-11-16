package com.freedom.core.accessibility.overlay

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeBoundsHitTesterTest {
    @Test
    fun choosesSmallestContainingRectangleForNestedNodes() {
        val parent = candidate(1, listOf(0), ScreenRect(0, 0, 200, 200))
        val child = candidate(1, listOf(0, 2), ScreenRect(40, 40, 120, 120))
        val grandchild = candidate(1, listOf(0, 2, 1), ScreenRect(60, 60, 90, 90))

        assertEquals(grandchild, NodeBoundsHitTester.find(listOf(parent, child, grandchild), 70, 70))
    }

    @Test
    fun prefersDeeperNodeWhenBoundsAreIdentical() {
        val parent = candidate(4, listOf(1), ScreenRect(10, 10, 90, 90))
        val child = candidate(4, listOf(1, 0), ScreenRect(10, 10, 90, 90))

        assertEquals(child, NodeBoundsHitTester.find(listOf(parent, child), 10, 10))
    }

    @Test
    fun prefersLaterWindowForIdenticalOverlappingWindowBounds() {
        val earlier = NodeBoundsHitCandidate(5, NodePath(listOf(0)), ScreenRect(0, 0, 100, 100), windowOrder = 0)
        val later = NodeBoundsHitCandidate(6, NodePath(listOf(0)), ScreenRect(0, 0, 100, 100), windowOrder = 1)

        assertEquals(later, NodeBoundsHitTester.find(listOf(earlier, later), 50, 50))
    }

    @Test
    fun rightAndBottomEdgesAreExclusiveAndOutsidePointsDoNotHit() {
        val node = candidate(1, listOf(0), ScreenRect(10, 20, 30, 40))

        assertEquals(node, NodeBoundsHitTester.find(listOf(node), 10, 20))
        assertNull(NodeBoundsHitTester.find(listOf(node), 30, 30))
        assertNull(NodeBoundsHitTester.find(listOf(node), 20, 40))
        assertNull(NodeBoundsHitTester.find(listOf(node), 9, 20))
    }

    private fun candidate(windowId: Int, path: List<Int>, bounds: ScreenRect) =
        NodeBoundsHitCandidate(windowId, NodePath(path), bounds)
}
