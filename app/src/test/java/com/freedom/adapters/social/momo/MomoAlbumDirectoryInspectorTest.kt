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
    fun waitsForUnifiedWindowGenerationWithoutRequiringChildActivity() {
        val album = WindowRef(target, "AlbumActivity", 2, 6)
        val earlyRoot = WindowRef(target, "", 3, 6)
        assertFalse(MomoAlbumDirectoryInspector.isNewDirectoryWindow(album, earlyRoot, target))
        assertTrue(MomoAlbumDirectoryInspector.isNewDirectoryWindow(album, window, target))
        assertFalse(MomoAlbumDirectoryInspector.isNewDirectoryWindow(album, window.copy(windowId = 2), target))
        assertFalse(MomoAlbumDirectoryInspector.isNewDirectoryWindow(album, window.copy(packageName = "other"), target))
    }

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

    @Test
    fun freedomNameNotWholeRowIsTheTapTarget() {
        val name = ScreenRect(40, 35, 95, 55)
        val updated = nodes.map {
            if (it.viewId.endsWith(":id/diretory_name")) it.copy(bounds = name) else it
        }
        val targetEvidence = MomoAlbumDirectoryInspector.resolveFreedom(snapshot.copy(nodes = updated), target)
        org.junit.Assert.assertEquals(name, targetEvidence?.nameBounds)
        org.junit.Assert.assertEquals(1, targetEvidence?.imageCount)
        val duplicate = updated + updated[4].copy(path = NodePath(listOf(0, 0, 0, 2)))
        org.junit.Assert.assertNull(MomoAlbumDirectoryInspector.resolveFreedom(snapshot.copy(nodes = duplicate), target))
        org.junit.Assert.assertNull(MomoAlbumDirectoryInspector.resolveFreedom(
            snapshot.copy(nodes = updated.map { if (it.viewId.endsWith(":id/image_count")) it.copy(text = "0张") else it }), target))
    }

    @Test
    fun ownedChildFindsUniqueFreedomNameWithoutActivityOrDirectoryFingerprint() {
        val name = nodes[4].copy(viewId = "", text = "freedom", path = NodePath(listOf(4)))
        val simple = snapshot.copy(nodes = listOf(name))
        val found = MomoAlbumDirectoryInspector.resolveFreedomName(simple, target)
        org.junit.Assert.assertEquals(name.bounds, found?.nameBounds)
        org.junit.Assert.assertNull(found?.imageCount)
        org.junit.Assert.assertFalse(MomoAlbumDirectoryInspector.isConfirmed(simple, target))
        org.junit.Assert.assertNull(MomoAlbumDirectoryInspector.resolveFreedomName(
            simple.copy(nodes = listOf(name, name.copy(path = NodePath(listOf(5))))), target))
        org.junit.Assert.assertNull(MomoAlbumDirectoryInspector.resolveFreedomName(
            simple.copy(nodes = listOf(name.copy(windowId = 8))), target))
        org.junit.Assert.assertNull(MomoAlbumDirectoryInspector.resolveFreedomName(
            simple.copy(nodes = listOf(name.copy(visible = false))), target))
        org.junit.Assert.assertNull(MomoAlbumDirectoryInspector.resolveFreedomName(
            simple.copy(truncated = true), target))
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
