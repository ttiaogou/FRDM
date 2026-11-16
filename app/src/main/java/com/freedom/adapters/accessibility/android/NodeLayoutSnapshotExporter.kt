package com.freedom.adapters.accessibility.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ScreenRect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import kotlin.concurrent.thread

/** Saves the selected detached window in the AutoJs6-compatible `.layout.json` envelope. */
internal object NodeLayoutSnapshotExporter {
    private const val TAG = "FreedomLayoutExporter"
    private const val SUFFIX = ".layout.json"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun save(
        context: Context,
        snapshot: AccessibilityTreeSnapshot,
        onComplete: (Result<File>) -> Unit
    ) {
        val app = context.applicationContext
        thread(name = "FreedomLayoutSnapshotSave", isDaemon = true) {
            val result = runCatching {
                val directory = (app.getExternalFilesDir("layouts") ?: File(app.filesDir, "layouts"))
                    .also { check(it.exists() || it.mkdirs()) { "无法创建布局目录" } }
                val stamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US).format(Date())
                var destination = File(directory, stamp + SUFFIX)
                var suffix = 1
                while (destination.exists()) {
                    destination = File(directory, "${stamp}_${suffix++}$SUFFIX")
                }
                destination.writeText(buildDocument(snapshot).toString(), Charsets.UTF_8)
                destination
            }
            result.onFailure { Log.e(TAG, "Could not save captured layout", it) }
            mainHandler.post { onComplete(result) }
        }
    }

    private class NodeBuilder(var node: AccessibilityNodeSnapshot? = null) {
        val children = TreeMap<Int, NodeBuilder>()
    }

    private fun buildDocument(snapshot: AccessibilityTreeSnapshot): JSONObject {
        val root = NodeBuilder()
        snapshot.nodes.forEach { node ->
            var current = root
            node.path.childIndices.forEach { childIndex ->
                current = current.children.getOrPut(childIndex) { NodeBuilder() }
            }
            current.node = node
        }
        check(root.node != null) { "布局快照缺少根节点" }
        val rootJson = root.toJson(depth = 0, parentBounds = null)
        return JSONObject().apply {
            put("version", 1)
            put("generator", "Freedom")
            put("capturedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            put("packageName", snapshot.window.packageName)
            put("activityClass", snapshot.window.activityClass)
            put("windowId", snapshot.windowId)
            put("truncated", snapshot.truncated)
            put("textCapture", "NONE")
            put("root", rootJson)
        }
    }

    private fun NodeBuilder.toJson(depth: Int, parentBounds: ScreenRect?): JSONObject {
        val source = node
        val bounds = source?.bounds
        val childrenJson = JSONArray()
        children.values.forEach { child -> childrenJson.put(child.toJson(depth + 1, bounds)) }
        val boundsInParent = if (bounds != null && parentBounds != null) {
            intArrayJson(
                bounds.left - parentBounds.left,
                bounds.top - parentBounds.top,
                bounds.right - parentBounds.left,
                bounds.bottom - parentBounds.top
            )
        } else intArrayJson(0, 0, 0, 0)
        return JSONObject().apply {
            put("packageName", source?.packageName ?: "")
            put("id", source?.viewId?.substringAfterLast(":id/") ?: "")
            put("fullId", source?.viewId ?: "")
            put("idHex", JSONObject.NULL)
            put("desc", JSONObject.NULL)
            // Text and description were intentionally excluded from the capture request.
            put("text", "")
            put("className", source?.className ?: "")
            put("bounds", intArrayJson(
                bounds?.left ?: 0, bounds?.top ?: 0, bounds?.right ?: 0, bounds?.bottom ?: 0
            ))
            put("boundsInParent", boundsInParent)
            put("center", JSONArray().put(
                ((bounds?.left ?: 0) + (bounds?.right ?: 0)) / 2.0
            ).put(((bounds?.top ?: 0) + (bounds?.bottom ?: 0)) / 2.0))
            put("clickable", source?.clickable ?: false)
            put("longClickable", source?.longClickable ?: false)
            put("scrollable", source?.scrollable ?: false)
            put("indexInParent", source?.indexInParent ?: -1)
            put("childCount", source?.childCount ?: children.size)
            put("depth", depth)
            put("checked", source?.checked ?: false)
            put("enabled", source?.enabled ?: false)
            put("editable", source?.editable ?: false)
            put("focusable", source?.focusable ?: false)
            put("checkable", source?.checkable ?: false)
            put("selected", source?.selected ?: false)
            put("dismissable", source?.dismissable ?: false)
            put("visibleToUser", source?.visible ?: false)
            put("contextClickable", source?.contextClickable ?: false)
            put("focused", source?.focused ?: false)
            put("accessibilityFocused", source?.accessibilityFocused ?: false)
            put("rowCount", source?.collection?.rowCount ?: 0)
            put("columnCount", source?.collection?.columnCount ?: 0)
            put("row", source?.collectionItem?.rowIndex ?: -1)
            put("column", source?.collectionItem?.columnIndex ?: -1)
            put("rowSpan", source?.collectionItem?.rowSpan ?: -1)
            put("columnSpan", source?.collectionItem?.columnSpan ?: -1)
            put("drawingOrder", source?.drawingOrder ?: 0)
            put("actionNames", JSONArray().apply {
                source?.actions.orEmpty().forEach { action ->
                    put(action.label ?: action.id.toString())
                }
            })
            put("summary", "")
            put("children", childrenJson)
        }
    }

    private fun intArrayJson(vararg values: Int) = JSONArray().apply { values.forEach(::put) }
}
