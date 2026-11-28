package com.freedom.application.monitor

import com.freedom.application.ports.AppLabelResolver
import com.freedom.application.ports.MonitorPresentationSnapshot
import com.freedom.application.ports.MonitorSnapshotRepository
import com.freedom.core.accessibility.api.ActiveWindowInspectionPort
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.api.AccessibilityTreeLimits
import java.util.ArrayDeque

/**
 * Application use case that renders the monitor's bounded accessibility preview.
 * Android nodes stay behind ActiveWindowInspectionPort; storage stays behind its repository port.
 */
internal class AccessibilityTreePreviewUseCase(
    private val appLabelResolver: AppLabelResolver,
    private val gateway: ActiveWindowInspectionPort,
    private val isRunning: () -> Boolean,
    private val snapshotRepository: MonitorSnapshotRepository,
    private val onLog: (String) -> Unit,
    private val onRefresh: () -> Unit,
    private val maxNodesPerScan: Int = AccessibilityTreeLimits.MAX_NODES_PER_SCAN,
    private val maxDisplayNodes: Int = AccessibilityTreeLimits.MAX_DISPLAY_NODES
) {
    private val scanLock = Any()
    private val pendingScans = ArrayDeque<Unit>()
    private var draining = false

    /** Scheduled program captures retain the serial scan queue. */
    fun scanActiveWindow() {
        val shouldDrain = synchronized(scanLock) {
            pendingScans.addLast(Unit)
            if (draining) false else {
                draining = true
                true
            }
        }
        if (shouldDrain) drainQueue()
    }

    private fun drainQueue() {
        while (true) {
            synchronized(scanLock) {
                if (pendingScans.isEmpty()) {
                    draining = false
                    return
                }
                pendingScans.removeFirst()
            }
            runCatching { capture() }.onFailure { error ->
                onLog("节点采集异常：source=AUTOMATIC；原因=${error.javaClass.simpleName}")
            }
        }
    }

    private fun capture() {
        if (!isRunning()) return
        val request = TreeInspectionRequest(
            maxNodes = maxNodesPerScan,
            // The monitor collects structure only. Text/description is never included in this view.
            textCapture = NodeTextCapture.NONE,
            allowVisibleWindowFallback = true
        )
        val tree = gateway.inspectActiveWindow(request)
        if (tree == null) {
            onLog("节点扫描未完成：source=AUTOMATIC；当前目标应用没有公开可访问的窗口根节点")
            return
        }

        val appLabel = appLabelResolver.resolve(tree.window.packageName)
        val displayLines = ArrayList<String>(maxDisplayNodes)
        for (node in tree.nodes) {
            if (displayLines.size >= maxDisplayNodes) break
            val fullClassName = node.className.takeIf(String::isNotBlank) ?: "Unknown"
            val rawClass = fullClassName.substringAfterLast('.')
            val label = chineseClass(rawClass)
            val state = buildList {
                if (node.clickable) add("可点击")
                if (node.longClickable) add("可长按")
                if (node.editable) add("可输入")
                if (node.scrollable) add("可滚动")
                if (node.checkable) add("可勾选")
                if (node.checked) add("已勾选")
                if (node.selected) add("已选中")
                if (node.focused) add("焦点")
                if (node.enabled) add("启用")
            }.joinToString("、")
            val line = buildString {
                append("  ".repeat(node.path.childIndices.size.coerceAtMost(10)))
                append(label)
                append(" <")
                append(fullClassName)
                append(">")
                if (node.viewId.isNotBlank()) append(" [ID:${node.viewId}]")
                append(" [${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]")
                if (state.isNotBlank()) append(" {$state}")
            }
            displayLines += line
        }

        snapshotRepository.save(
            MonitorPresentationSnapshot(
                appLabel = appLabel,
                pageLabel = "节点结构预览",
                nodePreview = displayLines,
                visitedNodeCount = tree.visitedNodeCount,
                truncated = tree.truncated
            )
        )
        onLog(
            "节点采集完成：source=AUTOMATIC；捕获目标应用窗口 1 个；" +
                "当前预览窗口访问 ${tree.visitedNodeCount} 个节点，显示 ${displayLines.size} 个；" +
                "未采集或持久化文本/描述" +
                if (tree.truncated) "；节点或窗口数量达到扫描上限，结果可能不完整" else ""
        )
        onRefresh()
    }


    private fun chineseClass(rawClass: String): String = when (rawClass) {
        "FrameLayout" -> "帧布局容器"
        "LinearLayout" -> "线性布局"
        "RelativeLayout" -> "相对布局"
        "ConstraintLayout" -> "约束布局"
        "TextView" -> "文本控件"
        "EditText" -> "文本输入框"
        "ImageView" -> "图片控件"
        "ImageButton" -> "图片按钮"
        "Button" -> "按钮"
        "CheckBox" -> "复选框"
        "RadioButton" -> "单选按钮"
        "Switch" -> "开关控件"
        "RecyclerView" -> "列表控件"
        "ScrollView" -> "滚动容器"
        "ViewPager" -> "分页容器"
        "ViewPager2" -> "分页容器"
        "WebView" -> "网页容器"
        "View" -> "普通视图"
        "Unknown" -> "未知控件"
        else -> "$rawClass 控件"
    }
}
