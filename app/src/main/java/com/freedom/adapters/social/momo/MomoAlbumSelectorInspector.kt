package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal data class MomoAlbumSelectorEvidence(
    val title: String, val left: Int, val top: Int, val right: Int, val bottom: Int
)

internal object MomoAlbumSelectorInspector {
    fun resolve(title: String, titleCount: Int, arrowCount: Int, hierarchyMatches: Boolean,
                usable: Boolean, left: Int, top: Int, right: Int, bottom: Int): MomoAlbumSelectorEvidence? {
        if (title.isBlank() || titleCount != 1 || arrowCount != 1 || !hierarchyMatches ||
            !usable || right <= left || bottom <= top) return null
        return MomoAlbumSelectorEvidence(title, left, top, right, bottom)
    }

    /** Borrows root. All acquired nodes are released before returning immutable evidence. */
    fun inspect(root: AccessibilityNodeInfo, targetPackage: String): MomoAlbumSelectorEvidence? = runCatching {
        if (!MomoAlbumArrivalInspector.isConfirmed(root, targetPackage)) return null
        val owned = mutableListOf<AccessibilityNodeInfo>()
        try {
            fun find(id: String): List<AccessibilityNodeInfo> =
                root.findAccessibilityNodeInfosByViewId("$targetPackage:id/$id").orEmpty().also { owned.addAll(it) }
            fun parent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? = node.parent?.also { owned.add(it) }
            fun usable(node: AccessibilityNodeInfo): Boolean =
                node.packageName?.toString() == targetPackage && node.isVisibleToUser && node.isEnabled
            val titles = find("tab_title")
            val arrows = find("tab_coustom_img")
            val title = titles.singleOrNull() ?: return null
            val arrow = arrows.singleOrNull() ?: return null
            val group = parent(title) ?: return null
            val arrowGroup = parent(arrow) ?: return null
            val scale = parent(group) ?: return null
            val click = parent(scale) ?: return null
            val hierarchy = group == arrowGroup &&
                scale.viewIdResourceName == "$targetPackage:id/tab_title_scale_layout" &&
                title.className?.toString() == "android.widget.TextView" &&
                arrow.className?.toString() == "android.widget.ImageView" &&
                click.className?.toString() == "android.widget.RelativeLayout"
            val bounds = Rect()
            click.getBoundsInScreen(bounds)
            val titleBounds = Rect()
            title.getBoundsInScreen(titleBounds)
            val arrowBounds = Rect()
            arrow.getBoundsInScreen(arrowBounds)
            val valid = listOf(title, arrow, group, scale, click).all(::usable) &&
                click.isClickable && !titleBounds.isEmpty && !arrowBounds.isEmpty &&
                bounds.contains(titleBounds) && bounds.contains(arrowBounds)
            resolve(title.text?.toString().orEmpty(), titles.size, arrows.size, hierarchy, valid,
                bounds.left, bounds.top, bounds.right, bounds.bottom)
        } finally {
            owned.forEach { runCatching { it.recycle() } }
        }
    }.getOrNull()
}
