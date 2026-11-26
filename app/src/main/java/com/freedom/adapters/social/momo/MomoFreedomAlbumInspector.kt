package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef

internal data class MomoAlbumImageCandidate(val path: NodePath, val imageBounds: ScreenRect, val selectBounds: ScreenRect)
internal data class MomoAlbumFinishTarget(val path: NodePath, val bounds: ScreenRect)
internal data class MomoFreedomAlbumEvidence(
    val window: WindowRef,
    val selectionCount: Int,
    val entireGridVisible: Boolean,
    val images: List<MomoAlbumImageCandidate>,
    val finish: MomoAlbumFinishTarget
)

/** Read-only structural evidence for the actual Freedom album, not the general camera roll. */
internal object MomoFreedomAlbumInspector {
    fun selectionCount(text: String?): Int? {
        val normalized = text?.trim()?.replace('（', '(')?.replace('）', ')') ?: return null
        if (normalized == "发送") return 0
        return Regex("^发送\\(([0-9]+)\\)$").matchEntire(normalized)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun inspect(snapshot: AccessibilityTreeSnapshot, targetPackage: String): MomoFreedomAlbumEvidence? {
        if (targetPackage != "com.immomo.momo" || snapshot.window.packageName != targetPackage ||
            snapshot.window.windowId < 0 || snapshot.truncated || snapshot.timeBudgetExceeded ||
            snapshot.textCapture != NodeTextCapture.EPHEMERAL) return null
        val nodes = snapshot.nodes.filter { it.packageName == targetPackage && it.windowId == snapshot.window.windowId }
        fun anchor(id: String): AccessibilityNodeSnapshot? =
            nodes.singleOrNull { it.viewId == "$targetPackage:id/$id" && it.usable() }
        val title = anchor("tab_title") ?: return null
        if (title.className != "android.widget.TextView" || !title.text.orEmpty().trim().equals("Freedom", true)) return null
        if (anchor("tv_navigation") == null) return null
        val finish = anchor("finish") ?: return null
        if (finish.className != "android.widget.TextView" || !finish.clickable) return null
        val count = selectionCount(finish.text) ?: return null
        val grid = anchor("rl_recycler") ?: return null
        if (!grid.className.endsWith(".RecyclerView")) return null
        val byPath = nodes.associateBy { it.path }
        val candidates = nodes.filter { it.viewId == "$targetPackage:id/iv_item_select" && it.usable() }
            .mapNotNull { select ->
                if (select.className != "android.view.View" || !select.clickable) return@mapNotNull null
                val item = byPath[select.path.parent()] ?: return@mapNotNull null
                if (item.viewId != "$targetPackage:id/item_layout" || item.className != "android.widget.FrameLayout" ||
                    item.path.parent() != grid.path || !item.usable() || !item.bounds.contains(select.bounds) ||
                    !grid.bounds.contains(select.bounds)) return@mapNotNull null
                val children = nodes.filter { it.path.parent() == item.path }
                if (children.count { it.viewId == "$targetPackage:id/iv_item_select" } != 1 ||
                    children.any { it.viewId.substringAfterLast('/') in setOf("take_photo", "iv_item_video", "iv_item_flash") }
                ) return@mapNotNull null
                val image = children.singleOrNull {
                    it.viewId == "$targetPackage:id/iv_item_image" && it.className == "android.widget.ImageView" &&
                        it.usable() && it.clickable && item.bounds.contains(it.bounds) && grid.bounds.contains(it.bounds)
                } ?: return@mapNotNull null
                MomoAlbumImageCandidate(select.path, image.bounds, select.bounds)
            }
        // Never reinterpret a partially visible/ambiguous grid as the unique image case.
        val selectableNodes = nodes.count { it.viewId == "$targetPackage:id/iv_item_select" }
        if (candidates.size != selectableNodes || candidates.map { it.path }.distinct().size != candidates.size) return null
        val entireGridVisible = !grid.scrollable && grid.actions.none { it.id in setOf(4096, 8192) }
        return MomoFreedomAlbumEvidence(snapshot.window, count, entireGridVisible, candidates,
            MomoAlbumFinishTarget(finish.path, finish.bounds))
    }

    private fun AccessibilityNodeSnapshot.usable(): Boolean = visible && enabled && !bounds.isEmpty
    private fun NodePath.parent(): NodePath? = childIndices.takeIf { it.isNotEmpty() }?.dropLast(1)?.let(::NodePath)
    private fun ScreenRect.contains(other: ScreenRect): Boolean =
        !isEmpty && !other.isEmpty && other.left >= left && other.right <= right &&
            other.top >= top && other.bottom <= bottom
}
