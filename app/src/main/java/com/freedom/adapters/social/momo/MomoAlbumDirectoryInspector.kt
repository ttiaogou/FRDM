package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef

internal data class MomoFreedomDirectoryTarget(
    val window: WindowRef,
    val namePath: NodePath,
    val nameBounds: ScreenRect,
    val imageCount: Int?
)

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

    /**
     * Transaction-local lookup, not a page fingerprint. The caller already opened the
     * album selector and checks the fresh child window against the source album.
     * The count is advisory here; selection still verifies the actual single-photo grid.
     */
    fun resolveFreedomName(snapshot: AccessibilityTreeSnapshot, targetPackage: String): MomoFreedomDirectoryTarget? {
        if (snapshot.truncated || snapshot.timeBudgetExceeded ||
            snapshot.window.packageName != targetPackage || snapshot.window.windowId < 0 ||
            snapshot.textCapture != NodeTextCapture.EPHEMERAL
        ) return null
        val nodes = snapshot.nodes.filter {
            it.packageName == targetPackage && it.windowId == snapshot.window.windowId
        }
        val name = nodes.singleOrNull {
            it.usable() && it.text?.trim()?.equals("Freedom", ignoreCase = true) == true
        } ?: return null
        val count = nodes.singleOrNull {
            it.path.parent() == name.path.parent() && it.usable() &&
                it.viewId == "$targetPackage:id/image_count"
        }?.text?.trim()?.removeSuffix("张")?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
        return MomoFreedomDirectoryTarget(snapshot.window, name.path, name.bounds, count)
    }

    /** The name is the only allowed tap region; its clickable parent merely proves the row. */
    fun resolveFreedom(snapshot: AccessibilityTreeSnapshot, targetPackage: String): MomoFreedomDirectoryTarget? {
        if (!isConfirmed(snapshot, targetPackage)) return null
        val nodes = snapshot.nodes.filter {
            it.packageName == targetPackage && it.windowId == snapshot.window.windowId
        }
        val byPath = nodes.associateBy { it.path }
        val name = nodes.singleOrNull {
            it.viewId == "$targetPackage:id/diretory_name" &&
                it.text?.trim()?.equals("Freedom", ignoreCase = true) == true
        } ?: return null
        if (!name.usable() || name.className != "android.widget.TextView") return null
        val labels = byPath[name.path.parent()] ?: return null
        val row = byPath[labels.path.parent()] ?: return null
        val list = byPath[row.path.parent()] ?: return null
        if (labels.className != "android.widget.LinearLayout" || !labels.usable() ||
            row.className != "android.widget.RelativeLayout" || !row.clickable || !row.usable() ||
            list.viewId != "$targetPackage:id/recycler_view" ||
            !list.className.endsWith(".RecyclerView") || !list.usable() || !list.scrollable ||
            !row.bounds.contains(name.bounds) || !labels.bounds.contains(name.bounds) ||
            !list.bounds.contains(name.bounds)
        ) return null
        val count = nodes.singleOrNull {
            it.path.parent() == labels.path && it.viewId == "$targetPackage:id/image_count" &&
                it.className == "android.widget.TextView" && it.usable()
        } ?: return null
        val quantity = count.text?.trim()?.removeSuffix("张")?.trim()?.toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        return MomoFreedomDirectoryTarget(snapshot.window, name.path, name.bounds, quantity)
    }

    private fun ScreenRect.contains(other: ScreenRect): Boolean =
        !isEmpty && !other.isEmpty && other.left >= left && other.right <= right &&
            other.top >= top && other.bottom <= bottom

    private fun NodePath.parent(): NodePath? =
        childIndices.takeIf { it.isNotEmpty() }?.dropLast(1)?.let(::NodePath)

    private fun AccessibilityNodeSnapshot.usable(): Boolean = visible && enabled && !bounds.isEmpty
}
