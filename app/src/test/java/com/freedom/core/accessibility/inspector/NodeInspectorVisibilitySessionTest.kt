package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.WindowRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeInspectorVisibilitySessionTest {
    private val windowA = WindowRef("target.app", "target.app.Main", 1L, 10)
    private val windowB = WindowRef("target.app", "target.app.Dialog", 1L, 11)
    private val nodeA = NodeInspectorIdentity(windowA, NodePath(listOf(0, 1)))
    private val nodeB = NodeInspectorIdentity(windowB, NodePath(listOf(0, 1)))

    @After fun cleanup() = NodeInspectorVisibilitySession.endSession()

    @Test fun hiddenStateIsScopedByWindowAndPath() {
        NodeInspectorVisibilitySession.beginSession()
        assertTrue(NodeInspectorVisibilitySession.hide(nodeA))
        assertTrue(NodeInspectorVisibilitySession.isHidden(nodeA))
        assertFalse(NodeInspectorVisibilitySession.isHidden(nodeB))
        assertEquals(setOf(listOf(0, 1)), NodeInspectorVisibilitySession.hiddenPaths(windowA))
        assertTrue(NodeInspectorVisibilitySession.hiddenPaths(windowB).isEmpty())
    }


    @Test fun hidesAllIdentitiesFromOneBoundsGroupWithoutCrossingWindows() {
        val secondNodeA = NodeInspectorIdentity(windowA, NodePath(listOf(0, 2)))
        NodeInspectorVisibilitySession.hideAll(listOf(nodeA, secondNodeA))
        assertTrue(NodeInspectorVisibilitySession.isHidden(nodeA))
        assertTrue(NodeInspectorVisibilitySession.isHidden(secondNodeA))
        assertFalse(NodeInspectorVisibilitySession.isHidden(nodeB))
        assertEquals(setOf(listOf(0, 1), listOf(0, 2)), NodeInspectorVisibilitySession.hiddenPaths(windowA))
    }

    @Test fun startingANewCaptureClearsPreviousHiddenNodes() {
        NodeInspectorVisibilitySession.hide(nodeA)
        NodeInspectorVisibilitySession.beginSession()
        assertFalse(NodeInspectorVisibilitySession.isHidden(nodeA))
    }

    @Test fun endingTheSessionNotifiesViewsAndClearsHiddenNodes() {
        var notifications = 0
        val unsubscribe = NodeInspectorVisibilitySession.observe { notifications++ }
        try {
            NodeInspectorVisibilitySession.hide(nodeA)
            assertEquals(1, notifications)
            NodeInspectorVisibilitySession.endSession()
            assertEquals(2, notifications)
            assertFalse(NodeInspectorVisibilitySession.isHidden(nodeA))
        } finally {
            unsubscribe()
        }
    }
}
