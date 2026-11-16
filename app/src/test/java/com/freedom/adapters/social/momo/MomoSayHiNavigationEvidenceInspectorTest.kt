package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiNavigationEvidenceInspectorTest {
    private val inspector = MomoSayHiNavigationEvidenceInspector()

    @Test
    fun receivedGreetingsLabelKeepsItsPathAndReportsClickableAncestorWithoutClicking() {
        val tree = tree(
            node(
                path = listOf(0), className = "android.widget.FrameLayout",
                viewId = "com.immomo.momo:id/entry_container", bounds = rect(0, 0, 500, 240),
                clickable = true
            ),
            node(
                path = listOf(0, 0), className = "android.widget.TextView",
                viewId = "com.immomo.momo:id/entry_title", text = "收到的招呼",
                bounds = rect(40, 60, 280, 120)
            )
        )

        val evidence = inspector.inspect(tree, screenWidth = 1_000, screenHeight = 2_000)
        val entry = evidence.receivedGreetingsEntryTargets.single()

        assertEquals(listOf(0, 0), entry.path)
        assertEquals(listOf(0), entry.clickableAncestorPath)
        assertEquals("com.immomo.momo:id/entry_container", entry.clickableAncestorViewId)
        assertTrue(entry.hasReceivedGreetingsLabel)
        assertFalse(entry.clickable)
    }

    @Test
    fun topRightClickableActionIsReportedAsEvidenceOnly() {
        val tree = tree(
            node(
                path = listOf(0), className = "android.widget.ImageButton",
                viewId = "com.immomo.momo:id/action_list", bounds = rect(920, 48, 984, 112),
                clickable = true
            )
        )

        val evidence = inspector.inspect(tree, screenWidth = 1_000, screenHeight = 2_000)
        val action = evidence.topRightActionTargets.single()

        assertEquals("com.immomo.momo:id/action_list", action.viewId)
        assertEquals(listOf(0), action.clickableAncestorPath)
        assertFalse(action.hasReceivedGreetingsLabel)
    }

    @Test
    fun interceptionSwitchIsAssociatedOnlyWithItsLocalLabelContainer() {
        val tree = tree(
            node(
                path = listOf(2, 3), className = "android.widget.LinearLayout",
                viewId = "com.immomo.momo:id/intercept_row", bounds = rect(20, 500, 980, 620)
            ),
            node(
                path = listOf(2, 3, 0), className = "android.widget.TextView",
                viewId = "com.immomo.momo:id/intercept_title", text = "消息拦截",
                bounds = rect(40, 520, 500, 580)
            ),
            node(
                path = listOf(2, 3, 1), className = "android.widget.Switch",
                viewId = "com.immomo.momo:id/intercept_switch", bounds = rect(840, 520, 940, 590),
                checkable = true, checked = true, clickable = true
            ),
            node(
                path = listOf(5, 0), className = "android.widget.Switch",
                viewId = "com.immomo.momo:id/unrelated_switch", bounds = rect(840, 800, 940, 870),
                checkable = true, checked = false, clickable = true
            )
        )

        val evidence = inspector.inspect(tree, screenWidth = 1_000, screenHeight = 2_000)
        val related = evidence.toggleTargets.single { it.viewId.endsWith("intercept_switch") }
        val unrelated = evidence.toggleTargets.single { it.viewId.endsWith("unrelated_switch") }

        assertEquals(1, evidence.interceptLabelNodeCount)
        assertTrue(related.relatedToInterceptLabel)
        assertTrue(related.checked)
        assertFalse(unrelated.relatedToInterceptLabel)
    }

    @Test
    fun hiddenOrInvalidEntryNodesAreIgnored() {
        val tree = tree(
            node(
                path = listOf(0), className = "android.widget.TextView",
                viewId = "hidden_entry", text = "收到的招呼", bounds = rect(0, 0, 200, 50),
                visible = false
            ),
            node(
                path = listOf(1), className = "android.widget.TextView",
                viewId = "invalid_entry", text = "收到的招呼", bounds = rect(0, 0, 0, 0)
            )
        )

        val evidence = inspector.inspect(tree, screenWidth = 1_000, screenHeight = 2_000)

        assertEquals(1, evidence.receivedGreetingsEntryTargets.size)
        assertTrue(evidence.receivedGreetingsEntryTargets.single().bounds.isEmpty)
        assertNull(evidence.receivedGreetingsEntryTargets.single().clickableAncestorPath)
        assertTrue(evidence.topRightActionTargets.isEmpty())
        assertTrue(evidence.toggleTargets.isEmpty())
    }

    private fun tree(vararg nodes: AccessibilityNodeSnapshot) = AccessibilityTreeSnapshot(
        window = WindowRef("com.immomo.momo", "MomoTestActivity", 1L),
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
        contentDescription: String? = null,
        visible: Boolean = true,
        enabled: Boolean = true,
        checkable: Boolean = false,
        checked: Boolean = false,
        clickable: Boolean = false,
        childCount: Int = 0
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = contentDescription,
        bounds = bounds,
        visible = visible,
        enabled = enabled,
        checkable = checkable,
        checked = checked,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = false,
        selected = false,
        focused = false,
        childCount = childCount
    )

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        ScreenRect(left, top, right, bottom)
}
