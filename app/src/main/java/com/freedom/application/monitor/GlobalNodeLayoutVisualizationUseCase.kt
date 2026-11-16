package com.freedom.application.monitor

import com.freedom.core.accessibility.api.AccessibilityTreeLimits
import com.freedom.core.accessibility.api.ActiveWindowInspectionPort
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.overlay.OverlayPort

/**
 * Captures detached snapshots for every currently exposed window of the selected target app and
 * asks the overlay port to draw their screen-space bounds. No live Android node escapes the port.
 */
internal class GlobalNodeLayoutVisualizationUseCase(
    private val gateway: ActiveWindowInspectionPort,
    private val targetPackage: () -> String,
    private val overlay: OverlayPort,
    private val onLog: (String) -> Unit,
    private val maxNodesPerScan: Int = AccessibilityTreeLimits.MAX_NODES_PER_SCAN,
    private val maxBounds: Int = 600
) {
    @Volatile
    private var latestSnapshots: List<AccessibilityTreeSnapshot> = emptyList()

    fun snapshots(): List<AccessibilityTreeSnapshot> = latestSnapshots

    fun show(): Boolean {
        val expectedPackage = targetPackage().trim()
        if (expectedPackage.isBlank()) {
            onLog("全局节点可视化拒绝[NO_TARGET_PACKAGE]：尚未选择目标 App")
            latestSnapshots = emptyList()
            overlay.clearNodeBoundsHighlight()
            return false
        }

        val snapshots = runCatching {
            gateway.inspectTargetWindows(
                TreeInspectionRequest(
                    maxNodes = maxNodesPerScan,
                    // The inspector needs in-memory text evidence for keyword search. Snapshot exporter strips
                    // these fields before writing layout files; no text is persisted by this request.
                    textCapture = NodeTextCapture.EPHEMERAL,
                    allowVisibleWindowFallback = true
                )
            )
        }.getOrElse { error ->
            onLog("全局节点可视化失败[CAPTURE_EXCEPTION]：${error.javaClass.simpleName}")
            latestSnapshots = emptyList()
            overlay.clearNodeBoundsHighlight()
            return false
        }.filter { snapshot -> snapshot.window.packageName == expectedPackage }

        if (snapshots.isEmpty()) {
            latestSnapshots = emptyList()
            onLog("全局节点可视化失败[NO_TARGET_WINDOWS]：没有取得目标 App 的可访问窗口")
            overlay.clearNodeBoundsHighlight()
            return false
        }

        latestSnapshots = snapshots.toList()

        val visibleNodeCount = snapshots.asSequence()
            .flatMap { snapshot -> snapshot.nodes.asSequence() }
            .count { node -> node.visible && !node.bounds.isEmpty }
        if (visibleNodeCount == 0) {
            onLog("全局节点可视化失败[NO_VISIBLE_BOUNDS]：目标 App 当前窗口没有有效节点边界")
            overlay.clearNodeBoundsHighlight()
            return false
        }

        val truncatedWindows = snapshots.count { it.truncated }
        val shownNodeCount = visibleNodeCount.coerceAtMost(maxBounds.coerceAtLeast(1))
        val label = "${expectedPackage.substringAfterLast('.')} 节点边界 · $shownNodeCount/$visibleNodeCount" +
            if (truncatedWindows > 0 || visibleNodeCount > shownNodeCount) " · 部分节点未显示" else ""
        val shown = overlay.showInteractiveNodeLayoutBounds(snapshots, label)
        if (shown) {
            onLog(
                "全局节点可视化已显示[IN_PLACE]：package=$expectedPackage；" +
                    "windows=${snapshots.size}；nodes=$shownNodeCount/$visibleNodeCount；" +
                    "truncatedWindows=$truncatedWindows；mode=INTERACTIVE；source=FRESH_DETACHED_SNAPSHOT"
            )
        } else {
            onLog("全局节点可视化失败[OVERLAY_ATTACH_FAILED]：无法附加全屏交互边界层")
        }
        return shown
    }

    fun clear() {
        latestSnapshots = emptyList()
        overlay.clearNodeBoundsHighlight()
        onLog("全局节点边界层已清除[USER_REQUEST]")
    }
}
