package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoAlbumDirectoryInspectorTest {
    private val target = "com.immomo.momo"
    private val window = WindowRef(target, "", 3, 7)
    private val nodes = listOf(
        node("recycler_layout", "FrameLayout", emptyList()),
        node("recycler_view", "RecyclerView", listOf(0)).copy(scrollable = true),
        node("", "RelativeLayout", listOf(0, 0)).copy(clickable = true),
        node("", "LinearLayout", listOf(0, 0, 0)),
        node("diretory_name", "TextView", listOf(0, 0, 0, 0)).copy(text = "Freedom"),
        node("image_count", "TextView", listOf(0, 0, 0, 1)).copy(text = "1张")
    )
    private val snapshot = AccessibilityTreeSnapshot(
        window, nodes, nodes.size, false, textCapture = NodeTextCapture.EPHEMERAL
    )

    @Test
    fun confirmsDropdownFromUniqueListAndDirectoryRow() {
        assertTrue(MomoAlbumDirectoryInspector.isConfirmed(snapshot, target))
    }

    @Test
    fun rejectsTruncationAmbiguityOrDirectoryFromAnotherWindow() {
        val otherWindowRow = nodes.dropLast(1) + nodes.last().copy(windowId = 8)
        val duplicateList = nodes + nodes[1].copy(path = NodePath(listOf(1)))
        assertFalse(MomoAlbumDirectoryInspector.isConfirmed(snapshot.copy(truncated = true), target))
        assertFalse(MomoAlbumDirectoryInspector.isConfirmed(snapshot.copy(nodes = duplicateList), target))
        assertFalse(MomoAlbumDirectoryInspector.isConfirmed(snapshot.copy(nodes = otherWindowRow), target))
    }

    private fun node(id: String, type: String, path: List<Int>) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = if (type == "RecyclerView") "androidx.recyclerview.widget.RecyclerView"
            else "android.widget.$type",
        viewId = if (id.isEmpty()) "" else "$target:id/$id",
        text = null,
        contentDescription = null,
        bounds = ScreenRect(10, 10, 150, 100),
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
        childCount = 0,
        packageName = target,
        windowId = window.windowId
    )
}
