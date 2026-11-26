package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture

/** Read-only structural evidence for the separate album directory window. */
internal object MomoAlbumDirectoryInspector {
    fun isConfirmed(snapshot: AccessibilityTreeSnapshot, targetPackage: String): Boolean {
        if (snapshot.truncated || snapshot.timeBudgetExceeded ||
            snapshot.window.packageName != targetPackage || snapshot.window.windowId < 0 ||
            snapshot.textCapture != NodeTextCapture.EPHEMERAL
        ) return false

        val windowNodes = snapshot.nodes.filter {
            it.windowId == snapshot.window.windowId && it.packageName == targetPackage
        }
        val byPath = windowNodes.associateBy { it.path }
        val list = windowNodes.singleOrNull {
            it.viewId == "$targetPackage:id/recycler_view" &&
                it.className.endsWith(".RecyclerView") && it.usable() && it.scrollable
        } ?: return false
        val container = byPath[list.path.parent()] ?: return false
        if (container.viewId != "$targetPackage:id/recycler_layout" || !container.usable()) return false

        return windowNodes.any { name ->
            if (name.viewId != "$targetPackage:id/diretory_name" || !name.usable() ||
                name.className != "android.widget.TextView" || name.text.isNullOrBlank()
            ) return@any false
            val labels = byPath[name.path.parent()] ?: return@any false
            val row = byPath[labels.path.parent()] ?: return@any false
            row.path.parent() == list.path &&
                row.className == "android.widget.RelativeLayout" && row.clickable && row.usable() &&
                windowNodes.any { count ->
                    count.path.parent() == labels.path && count.usable() &&
                        count.viewId == "$targetPackage:id/image_count" &&
                        count.className == "android.widget.TextView" && !count.text.isNullOrBlank()
                }
        }
    }

    private fun NodePath.parent(): NodePath? =
        childIndices.takeIf { it.isNotEmpty() }?.dropLast(1)?.let(::NodePath)

    private fun AccessibilityNodeSnapshot.usable(): Boolean = visible && enabled && !bounds.isEmpty
}
