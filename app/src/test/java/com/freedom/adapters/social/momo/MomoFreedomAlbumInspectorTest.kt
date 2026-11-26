package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoFreedomAlbumInspectorTest {
    private val pkg = "com.immomo.momo"
    private val window = WindowRef(pkg, "AlbumActivity", 10L, 3)
    private val selectBounds = ScreenRect(400, 280, 450, 330)
    private val finishBounds = ScreenRect(500, 170, 680, 240)
    private val gridBounds = ScreenRect(0, 260, 720, 1550)
    private val itemBounds = ScreenRect(240, 265, 480, 500)
    private val nodes = listOf(
        node("tv_navigation", "android.widget.TextView", listOf(0)),
        node("tab_title", "android.widget.TextView", listOf(1), text = "Freedom"),
        node("finish", "android.widget.TextView", listOf(2), finishBounds, "发送", true),
        node("rl_recycler", "androidx.recyclerview.widget.RecyclerView", listOf(3), gridBounds),
        node("item_layout", "android.widget.FrameLayout", listOf(3, 0), ScreenRect(3, 265, 237, 500), clickable = true),
        node("take_photo", "android.widget.ImageView", listOf(3, 0, 0), ScreenRect(3, 265, 237, 500), clickable = true),
        node("item_layout", "android.widget.FrameLayout", listOf(3, 1), itemBounds),
        node("iv_item_image", "android.widget.ImageView", listOf(3, 1, 0), itemBounds, clickable = true),
        node("iv_item_select", "android.view.View", listOf(3, 1, 1), selectBounds, clickable = true)
    )

    @Test
    fun resolvesOnlyPhotoSelectControlAndExactFinishBounds() {
        val result = MomoFreedomAlbumInspector.inspect(snapshot(nodes), pkg)!!
        assertEquals(0, result.selectionCount)
        assertTrue(result.entireGridVisible)
        assertEquals(selectBounds, result.images.single().selectBounds)
        assertEquals(finishBounds, result.finish.bounds)
        val after = nodes.map { if (it.viewId.endsWith(":id/finish")) it.copy(text = "发送（1)") else it }
        assertEquals(1, MomoFreedomAlbumInspector.inspect(snapshot(after), pkg)?.selectionCount)
    }

    @Test
    fun rejectsOtherAlbumsIncompleteTreesAndUnprovenMediaItems() {
        assertNull(MomoFreedomAlbumInspector.inspect(snapshot(nodes.map {
            if (it.viewId.endsWith(":id/tab_title")) it.copy(text = "相册") else it
        }), pkg))
        assertNull(MomoFreedomAlbumInspector.inspect(snapshot(nodes).copy(truncated = true), pkg))
        assertNull(MomoFreedomAlbumInspector.inspect(snapshot(nodes.dropLast(2) + nodes.last()), pkg))
        assertNull(MomoFreedomAlbumInspector.selectionCount("发送(1)其他"))
        assertEquals(2, MomoFreedomAlbumInspector.selectionCount("发送(2)"))
    }

    private fun snapshot(nodes: List<AccessibilityNodeSnapshot>) =
        AccessibilityTreeSnapshot(window, nodes, nodes.size, false, textCapture = NodeTextCapture.EPHEMERAL)

    private fun node(id: String, cls: String, path: List<Int>, bounds: ScreenRect = ScreenRect(10, 10, 150, 100),
                     text: String? = null, clickable: Boolean = false) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = cls, viewId = "$pkg:id/$id", text = text, contentDescription = null,
        bounds = bounds, visible = true, enabled = true, checkable = false, checked = false,
        clickable = clickable, longClickable = false, scrollable = false, editable = false,
        selected = false, focused = false, childCount = 0, packageName = pkg, windowId = window.windowId
    )
}
