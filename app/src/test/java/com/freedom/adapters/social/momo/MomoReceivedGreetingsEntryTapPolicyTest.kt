package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoReceivedGreetingsEntryTapPolicyTest {
    private val inspector = MomoSayHiNavigationEvidenceInspector()

    @Test
    fun fullWidthClickableRowSamplesInsideTheLabelInsteadOfTheRow() {
        val tree = tree(
            node(
                path = listOf(0), className = "android.widget.RelativeLayout",
                viewId = "com.immomo.momo:id/item_layout", bounds = rect(0, 990, 720, 1120),
                clickable = true
            ),
            node(
                path = listOf(0, 0), className = "android.widget.TextView",
                viewId = "com.immomo.momo:id/chatlist_item_tv_name", bounds = rect(157, 1010, 327, 1050),
                text = "收到的招呼"
            )
        )
        val evidence = inspector.inspect(tree, screenWidth = 720, screenHeight = 1600)

        val result = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            tree = tree,
            evidence = evidence,
            screenWidth = 720,
            screenHeight = 1600
        )

        assertTrue(result is MomoReceivedGreetingsEntryTapDecision.Ready)
        result as MomoReceivedGreetingsEntryTapDecision.Ready
        assertEquals(listOf(0, 0), result.labelPath)
        assertEquals(listOf(0), result.clickableAncestorPath)
        assertEquals(rect(157, 1010, 327, 1050), result.tapBounds)
        assertEquals(rect(0, 990, 720, 1120), result.clickableAncestorBounds)
    }

    @Test
    fun freshSnapshotMustPreserveUniqueLabelAndClickableParentPositionBeforeTap() {
        val firstTree = entryTree(labelBounds = rect(157, 863, 327, 903), rowBounds = rect(0, 840, 720, 970))
        val latestTree = entryTree(labelBounds = rect(157, 913, 327, 953), rowBounds = rect(0, 890, 720, 1020))
        val firstEvidence = inspector.inspect(firstTree, screenWidth = 720, screenHeight = 1600)
        val latestEvidence = inspector.inspect(latestTree, screenWidth = 720, screenHeight = 1600)
        val first = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            firstTree, firstEvidence, 720, 1600
        ) as MomoReceivedGreetingsEntryTapDecision.Ready
        val latest = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            latestTree, latestEvidence, 720, 1600
        ) as MomoReceivedGreetingsEntryTapDecision.Ready

        assertTrue(!MomoReceivedGreetingsEntryTapPolicy.hasStableTargetPosition(
            firstTree, first, latestTree, latest
        ))
    }

    @Test
    fun stableFreshSnapshotsAllowTapOnlyAtTheLatestValidatedLabelBounds() {
        val firstTree = entryTree(labelBounds = rect(157, 863, 327, 903), rowBounds = rect(0, 840, 720, 970))
        val latestTree = entryTree(labelBounds = rect(157, 863, 327, 903), rowBounds = rect(0, 840, 720, 970))
        val first = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            firstTree, inspector.inspect(firstTree, 720, 1600), 720, 1600
        ) as MomoReceivedGreetingsEntryTapDecision.Ready
        val latest = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            latestTree, inspector.inspect(latestTree, 720, 1600), 720, 1600
        ) as MomoReceivedGreetingsEntryTapDecision.Ready

        assertTrue(MomoReceivedGreetingsEntryTapPolicy.hasStableTargetPosition(
            firstTree, first, latestTree, latest
        ))
        assertEquals(latestTree.nodes[1].bounds, latest.tapBounds)
    }

    @Test
    fun olderSecondSnapshotCannotAuthorizeAReceivedGreetingsTap() {
        val original = entryTree(labelBounds = rect(157, 863, 327, 903), rowBounds = rect(0, 840, 720, 970))
        val firstTree = original.copy(capturedAtEpochMs = 200L)
        val latestTree = original.copy(capturedAtEpochMs = 100L)
        val first = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            firstTree, inspector.inspect(firstTree, 720, 1600), 720, 1600
        ) as MomoReceivedGreetingsEntryTapDecision.Ready
        val latest = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            latestTree, inspector.inspect(latestTree, 720, 1600), 720, 1600
        ) as MomoReceivedGreetingsEntryTapDecision.Ready

        assertTrue(!MomoReceivedGreetingsEntryTapPolicy.hasStableTargetPosition(
            firstTree, first, latestTree, latest
        ))
    }

    @Test
    fun entryIsRejectedWhenClickableRowContainsForbiddenAction() {
        val tree = tree(
            node(
                path = listOf(0), className = "android.widget.RelativeLayout",
                viewId = "entry_row", bounds = rect(0, 990, 720, 1120), clickable = true
            ),
            node(
                path = listOf(0, 0), className = "android.widget.TextView",
                viewId = "entry_label", bounds = rect(157, 1010, 327, 1050), text = "收到的招呼"
            ),
            node(
                path = listOf(0, 1), className = "android.widget.TextView",
                viewId = "row_action", bounds = rect(580, 1010, 700, 1050), text = "拒绝"
            )
        )
        val evidence = inspector.inspect(tree, screenWidth = 720, screenHeight = 1600)

        val result = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            tree = tree,
            evidence = evidence,
            screenWidth = 720,
            screenHeight = 1600
        )

        assertTrue(result is MomoReceivedGreetingsEntryTapDecision.Rejected)
        result as MomoReceivedGreetingsEntryTapDecision.Rejected
        assertEquals("CLICKABLE_ANCESTOR_CONTAINS_FORBIDDEN_ACTION", result.reason)
    }

    @Test
    fun entryIsRejectedWhenSafeInwardTapRegionCannotProduceAUsableStroke() {
        val tree = tree(
            node(
                path = listOf(0), className = "android.widget.RelativeLayout",
                viewId = "entry_row", bounds = rect(0, 100, 720, 180), clickable = true
            ),
            node(
                path = listOf(0, 0), className = "android.widget.TextView",
                viewId = "entry_label", bounds = rect(100, 120, 101, 121), text = "收到的招呼"
            )
        )
        val evidence = inspector.inspect(tree, screenWidth = 720, screenHeight = 1600)

        val result = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            tree = tree,
            evidence = evidence,
            screenWidth = 720,
            screenHeight = 1600
        )

        assertTrue(result is MomoReceivedGreetingsEntryTapDecision.Rejected)
        result as MomoReceivedGreetingsEntryTapDecision.Rejected
        assertEquals("LABEL_HAS_NO_SAFE_INNER_TAP_REGION", result.reason)
    }

    @Test
    fun ambiguousReceivedGreetingsLabelsAreNotClicked() {
        val tree = tree(
            node(
                path = listOf(0), className = "android.widget.RelativeLayout",
                viewId = "entry_row_a", bounds = rect(0, 300, 720, 420), clickable = true
            ),
            node(
                path = listOf(0, 0), className = "android.widget.TextView",
                viewId = "entry_label_a", bounds = rect(100, 330, 300, 370), text = "收到的招呼"
            ),
            node(
                path = listOf(1), className = "android.widget.RelativeLayout",
                viewId = "entry_row_b", bounds = rect(0, 500, 720, 620), clickable = true
            ),
            node(
                path = listOf(1, 0), className = "android.widget.TextView",
                viewId = "entry_label_b", bounds = rect(100, 530, 300, 570), text = "收到的招呼"
            )
        )
        val evidence = inspector.inspect(tree, screenWidth = 720, screenHeight = 1600)

        val result = MomoReceivedGreetingsEntryTapPolicy.evaluate(
            tree = tree,
            evidence = evidence,
            screenWidth = 720,
            screenHeight = 1600
        )

        assertTrue(result is MomoReceivedGreetingsEntryTapDecision.Rejected)
        result as MomoReceivedGreetingsEntryTapDecision.Rejected
        assertEquals("ENTRY_LABEL_COUNT=2", result.reason)
    }

    private fun entryTree(labelBounds: ScreenRect, rowBounds: ScreenRect) = tree(
        node(
            path = listOf(0), className = "android.widget.RelativeLayout",
            viewId = "com.immomo.momo:id/item_layout", bounds = rowBounds, clickable = true
        ),
        node(
            path = listOf(0, 0), className = "android.widget.TextView",
            viewId = "com.immomo.momo:id/chatlist_item_tv_name", bounds = labelBounds,
            text = "收到的招呼"
        )
    )

    private fun tree(vararg nodes: AccessibilityNodeSnapshot) = AccessibilityTreeSnapshot(
        window = WindowRef("com.immomo.momo", "com.immomo.momo.maintab.MaintabActivity", 1L),
        nodes = nodes.toList(),
        visitedNodeCount = nodes.size,
        truncated = false
    )

    private fun node(
        path: List<Int>,
        className: String,
        viewId: String,
        bounds: ScreenRect,
        text: String? = null,
        clickable: Boolean = false
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = null,
        bounds = bounds,
        visible = true,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = false,
        selected = false,
        focused = false,
        childCount = 0
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        ScreenRect(left, top, right, bottom)
}
