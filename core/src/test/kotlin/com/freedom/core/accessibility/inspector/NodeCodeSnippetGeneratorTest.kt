package com.freedom.core.accessibility.inspector

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeCodeSnippetGeneratorTest {
    private fun node(
        path: List<Int>,
        id: String = "target.app:id/action",
        className: String = "android.widget.Button",
        clickable: Boolean = true,
        longClickable: Boolean = false,
        scrollable: Boolean = false,
        childCount: Int = 0
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = id,
        text = null,
        contentDescription = null,
        bounds = ScreenRect(1, 2, 30, 40),
        visible = true,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = clickable,
        longClickable = longClickable,
        scrollable = scrollable,
        editable = false,
        selected = false,
        focused = false,
        childCount = childCount,
        packageName = "target.app",
        windowId = 10
    )

    private fun snapshot(vararg nodes: AccessibilityNodeSnapshot) = AccessibilityTreeSnapshot(
        window = WindowRef("target.app", "target.app.Main", 1L, 10),
        nodes = nodes.toList(),
        visitedNodeCount = nodes.size,
        truncated = false,
        textCapture = NodeTextCapture.NONE
    )

    @Test
    fun generatesASelectorVerifiedUniqueInTheCapturedWindow() {
        val root = node(emptyList(), id = "", className = "android.widget.FrameLayout", clickable = false, childCount = 1)
        val selected = node(listOf(0))
        val captured = snapshot(root, selected)
        val code = NodeCodeSnippetGenerator.generate(selected, captured)
        assertTrue(code.contains("在本次捕获窗口内唯一"))
        assertTrue(code.contains("id(\"target.app:id/action\").className(\"android.widget.Button\")"))
        assertTrue(code.contains("target.click();"))
        assertTrue(code.contains(".findOne(10000)"))
        assertTrue(code.contains("文本和描述字段未请求采集"))
    }

    @Test
    fun warnsWhenASelectorMatchesMultipleCapturedNodes() {
        val root = node(emptyList(), id = "", className = "android.widget.FrameLayout", clickable = false, childCount = 2)
        val first = node(listOf(0))
        val duplicate = node(listOf(1))
        val code = NodeCodeSnippetGenerator.generate(first, snapshot(root, first, duplicate))
        assertTrue(code.contains("命中多个节点"))
    }

    @Test
    fun escapesJavaScriptStringLiterals() {
        val root = node(emptyList(), id = "", className = "android.widget.FrameLayout", clickable = false, childCount = 1)
        val selected = node(listOf(0), id = "pkg:id/a\\\"b\nnext")
        val code = NodeCodeSnippetGenerator.generate(selected, snapshot(root, selected))
        assertTrue(code.contains("a\\\\\\\"b\\nnext"))
    }

    @Test
    fun choosesTheAvailableInteractionAction() {
        val root = node(emptyList(), id = "", className = "android.widget.FrameLayout", clickable = false, childCount = 1)
        val longPress = node(listOf(0), clickable = false, longClickable = true)
        assertTrue(NodeCodeSnippetGenerator.generate(longPress, snapshot(root, longPress)).contains("target.longClick();"))
        val scroll = node(listOf(0), clickable = false, scrollable = true)
        assertTrue(NodeCodeSnippetGenerator.generate(scroll, snapshot(root, scroll)).contains("target.scrollForward();"))
    }

    @Test
    fun doesNotInventASelectorWhenNoCapturedIdentityEvidenceExists() {
        val root = node(emptyList(), id = "", className = "", clickable = false)
        val code = NodeCodeSnippetGenerator.generate(root, snapshot(root))
        assertTrue(code.contains("没有足够的已采集属性"))
        assertFalse(code.contains("findOne(10000)"))
    }
}
