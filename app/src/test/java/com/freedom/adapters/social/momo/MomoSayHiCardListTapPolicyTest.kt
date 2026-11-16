package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiCardListTapPolicyTest {
    @Test
    fun acceptsOnlyClickableCloseContainerWithExactListLabel() {
        val tree = cardTree()
        val result = MomoSayHiCardListTapPolicy.evaluate(tree, 512, 910)
        assertTrue(result is MomoSayHiCardListTapDecision.Ready)
        result as MomoSayHiCardListTapDecision.Ready
        assertEquals(listOf(0), result.controlPath)
        assertEquals(listOf(0, 1), result.labelPath)
        assertEquals(rect(418, 22, 490, 58), result.tapBounds)
        assertEquals(rect(405, 8, 500, 72), result.controlBounds)
    }

    @Test
    fun rejectsMissingOrAmbiguousListLabel() {
        val missing = cardTree(labelText = "关闭")
        val ambiguous = cardTree(duplicateLabel = true)
        assertEquals(
            "LIST_LABEL_COUNT=0",
            (MomoSayHiCardListTapPolicy.evaluate(missing, 512, 910) as MomoSayHiCardListTapDecision.Rejected).reason
        )
        assertEquals(
            "LIST_LABEL_COUNT=2",
            (MomoSayHiCardListTapPolicy.evaluate(ambiguous, 512, 910) as MomoSayHiCardListTapDecision.Rejected).reason
        )
    }

    @Test
    fun rejectsNonClickableOrDuplicateCloseContainer() {
        val notClickable = cardTree(controlClickable = false)
        val duplicate = cardTree(duplicateControl = true)
        assertEquals(
            "CLOSE_CONTAINER_NOT_VISIBLE_ENABLED_CLICKABLE_OR_BOUNDED",
            (MomoSayHiCardListTapPolicy.evaluate(notClickable, 512, 910) as MomoSayHiCardListTapDecision.Rejected).reason
        )
        assertEquals(
            "CLOSE_CONTAINER_COUNT=2",
            (MomoSayHiCardListTapPolicy.evaluate(duplicate, 512, 910) as MomoSayHiCardListTapDecision.Rejected).reason
        )
    }

    @Test
    fun rejectsCardActionControlWhenActivityOrBoundsDoNotMatch() {
        val wrongActivity = cardTree().copy(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 1L, 7)
        )
        val outside = cardTree(controlBounds = rect(405, 8, 530, 72), labelBounds = rect(418, 22, 490, 58))
        assertEquals(
            "NOT_SAYHI_CARD_ACTIVITY",
            (MomoSayHiCardListTapPolicy.evaluate(wrongActivity, 512, 910) as MomoSayHiCardListTapDecision.Rejected).reason
        )
        assertEquals(
            "CLOSE_CONTAINER_OUTSIDE_SCREEN",
            (MomoSayHiCardListTapPolicy.evaluate(outside, 512, 910) as MomoSayHiCardListTapDecision.Rejected).reason
        )
    }

    @Test
    fun requiresTwoFreshSnapshotsToPreserveSameTargetGeometry() {
        val firstTree = cardTree(capturedAt = 100L)
        val movedTree = cardTree(capturedAt = 200L, controlBounds = rect(400, 8, 495, 72))
        val first = MomoSayHiCardListTapPolicy.evaluate(firstTree, 512, 910) as MomoSayHiCardListTapDecision.Ready
        val moved = MomoSayHiCardListTapPolicy.evaluate(movedTree, 512, 910) as MomoSayHiCardListTapDecision.Ready
        assertTrue(!MomoSayHiCardListTapPolicy.hasStableTargetPosition(firstTree, first, movedTree, moved))

        val latestTree = cardTree(capturedAt = 200L)
        val latest = MomoSayHiCardListTapPolicy.evaluate(latestTree, 512, 910) as MomoSayHiCardListTapDecision.Ready
        assertTrue(MomoSayHiCardListTapPolicy.hasStableTargetPosition(firstTree, first, latestTree, latest))
    }

    private fun cardTree(
        labelText: String = "列表",
        duplicateLabel: Boolean = false,
        controlClickable: Boolean = true,
        duplicateControl: Boolean = false,
        controlBounds: ScreenRect = rect(405, 8, 500, 72),
        labelBounds: ScreenRect = rect(418, 22, 490, 58),
        capturedAt: Long = 100L
    ): AccessibilityTreeSnapshot {
        val nodes = mutableListOf(
            node(listOf(0), "android.widget.LinearLayout", MomoSayHiPageContract.CARD_CLOSE_VIEW_ID,
                controlBounds, clickable = controlClickable, childCount = 2),
            node(listOf(0, 0), "android.widget.ImageView", "com.immomo.momo:id/iv_close_top",
                rect(410, 20, 440, 55)),
            node(listOf(0, 1), "android.widget.TextView", "", labelBounds, text = labelText)
        )
        if (duplicateLabel) nodes += node(
            listOf(0, 2), "android.widget.TextView", "", labelBounds, text = "列表"
        )
        if (duplicateControl) nodes += node(
            listOf(1), "android.widget.LinearLayout", MomoSayHiPageContract.CARD_CLOSE_VIEW_ID,
            rect(300, 8, 390, 72), clickable = true, childCount = 1
        )
        return AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 1L, 7),
            nodes = nodes,
            visitedNodeCount = nodes.size,
            truncated = false,
            capturedAtEpochMs = capturedAt,
            windowId = 7
        )
    }

    private fun node(
        path: List<Int>,
        className: String,
        viewId: String,
        bounds: ScreenRect,
        text: String? = null,
        clickable: Boolean = false,
        childCount: Int = 0
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = className, viewId = viewId,
        text = text, contentDescription = null, bounds = bounds,
        visible = true, enabled = true, checkable = false, checked = false,
        clickable = clickable, longClickable = false, scrollable = false,
        editable = false, selected = false, focused = false, childCount = childCount
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        ScreenRect(left, top, right, bottom)
}
