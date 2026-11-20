package com.freedom.core.accessibility.overlay

import com.freedom.core.accessibility.api.AccessibilityNodeCaptureEvidence
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert
import org.junit.Test

class NodeBoundsHighlightPlannerTest {
    @Test
    fun createsPlanFromCapturedBoundsAndAncestorChain() {
        val snapshot = snapshot(
            listOf(
                node(emptyList(), "android.widget.FrameLayout", ScreenRect(0, 0, 400, 800)),
                node(listOf(0), "android.widget.LinearLayout", ScreenRect(10, 20, 390, 700)),
                node(listOf(0, 0), "android.widget.Button", ScreenRect(30, 60, 160, 110))
            )
        )

        val plan = NodeBoundsHighlightPlanner.create(snapshot, NodePath(listOf(0, 0)))

        Assert.assertNotNull(plan)
        Assert.assertEquals(ScreenRect(30, 60, 160, 110), plan!!.selectedBounds)
        Assert.assertEquals(
            listOf(ScreenRect(0, 0, 400, 800), ScreenRect(10, 20, 390, 700)),
            plan.ancestorBounds
        )
        Assert.assertTrue(plan.label.contains("Button"))
        Assert.assertTrue(plan.label.contains("path=0/0"))
    }

    @Test
    fun rejectsEmptyOrFailedBoundsInsteadOfGuessing() {
        val emptyBounds = snapshot(listOf(node(emptyList(), "android.widget.Button", ScreenRect(0, 0, 0, 0))))
        val failedBoundsNode = node(
            emptyList(),
            "android.widget.Button",
            ScreenRect(0, 0, 20, 20),
            evidence = AccessibilityNodeCaptureEvidence(failedFields = setOf(AccessibilityNodeField.BOUNDS))
        )
        val failedBounds = snapshot(listOf(failedBoundsNode))

        Assert.assertNull(NodeBoundsHighlightPlanner.create(emptyBounds, NodePath(emptyList())))
        Assert.assertNull(NodeBoundsHighlightPlanner.create(failedBounds, NodePath(emptyList())))
    }

    @Test
    fun capsAncestorCountAndDoesNotIncludeNodeTextInLabel() {
        val snapshot = snapshot(
            listOf(
                node(emptyList(), "Root", ScreenRect(0, 0, 500, 900)),
                node(listOf(0), "Parent", ScreenRect(5, 5, 495, 895)),
                node(listOf(0, 0), "Target", ScreenRect(20, 20, 120, 80), text = "private text")
            ),
            textCapture = NodeTextCapture.EPHEMERAL
        )

        val plan = NodeBoundsHighlightPlanner.create(snapshot, NodePath(listOf(0, 0)), maxAncestors = 1)

        Assert.assertNotNull(plan)
        Assert.assertEquals(listOf(ScreenRect(5, 5, 495, 895)), plan!!.ancestorBounds)
        Assert.assertFalse(plan.label.contains("private text"))
    }

    private fun snapshot(
        nodes: List<AccessibilityNodeSnapshot>,
        textCapture: NodeTextCapture = NodeTextCapture.NONE
    ) = AccessibilityTreeSnapshot(
        window = WindowRef("target.pkg", "TargetActivity", 7L),
        nodes = nodes,
        visitedNodeCount = nodes.size,
        truncated = false,
        textCapture = textCapture
    )

    private fun node(
        path: List<Int>,
        className: String,
        bounds: ScreenRect,
        text: String? = null,
        evidence: AccessibilityNodeCaptureEvidence = AccessibilityNodeCaptureEvidence()
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = "",
        text = text,
        contentDescription = null,
        bounds = bounds,
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
        childCount = if (path.size < 2) 1 else 0,
        packageName = "target.pkg",
        windowId = 11,
        captureEvidence = evidence
    )
}
