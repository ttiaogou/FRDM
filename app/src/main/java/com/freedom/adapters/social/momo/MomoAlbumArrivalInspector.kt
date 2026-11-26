package com.freedom.adapters.social.momo

import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect

internal object MomoAlbumArrivalInspector {
    private val anchors = setOf("tv_navigation", "tab_title", "finish", "rl_recycler")

    fun matches(packageName: String, counts: Map<String, Int>): Boolean =
        packageName == "com.immomo.momo" && anchors.all { counts[it] == 1 }

    /** Exact visible album shell anchors; no dependency on selected media or album name. */
    fun isConfirmed(root: AccessibilityNodeInfo, targetPackage: String): Boolean = runCatching {
        if (root.packageName?.toString() != targetPackage) return false
        val counts = anchors.associateWith { id ->
            val nodes = root.findAccessibilityNodeInfosByViewId("$targetPackage:id/$id").orEmpty()
            try {
                nodes.count { node ->
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    node.packageName?.toString() == targetPackage &&
                        node.isVisibleToUser && bounds.width() > 0 && bounds.height() > 0
                }
            } finally {
                nodes.forEach { runCatching { it.recycle() } }
            }
        }
        matches(targetPackage, counts)
    }.getOrDefault(false)
}
