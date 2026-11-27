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
        if (root.packageName?.toString() != targetPackage) return null
        val owned = mutableListOf<AccessibilityNodeInfo>()
        try {
            val titles = root.findAccessibilityNodeInfosByViewId("$targetPackage:id/tab_title").orEmpty()
                .also { owned.addAll(it) }
            val title = titles.singleOrNull() ?: return null
            fun usable(node: AccessibilityNodeInfo): Boolean =
                node.packageName?.toString() == targetPackage && node.isVisibleToUser && node.isEnabled
            if (!usable(title) || title.className?.toString() != "android.widget.TextView") return null
            val titleBounds = Rect()
            title.getBoundsInScreen(titleBounds)
            if (titleBounds.isEmpty) return null
            // Find the local clickable selector, not a whole album page fingerprint.
            var current = title
            repeat(4) {
                if (!usable(current)) return null
                if (current.isClickable) {
                    val bounds = Rect()
                    current.getBoundsInScreen(bounds)
                    if (bounds.isEmpty || !bounds.contains(titleBounds)) return null
                    return MomoAlbumSelectorEvidence(title.text?.toString().orEmpty(),
                        bounds.left, bounds.top, bounds.right, bounds.bottom)
                }
                current = current.parent?.also { owned.add(it) } ?: return null
            }
            return null
        } finally {
            owned.forEach { runCatching { it.recycle() } }
        }
    }.getOrNull()
}
