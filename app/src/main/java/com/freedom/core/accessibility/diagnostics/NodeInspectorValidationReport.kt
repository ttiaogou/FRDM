package com.freedom.core.accessibility.diagnostics

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ScreenRect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Device facts collected only when the user explicitly asks to copy a node-inspector report. */
data class NodeInspectorDeviceContext(
    val targetPackage: String,
    val androidApiLevel: Int,
    val manufacturer: String,
    val model: String,
    val appVersionName: String?,
    /** System setting says the service is enabled; this does not prove it is currently bound. */
    val accessibilityServiceEnabled: Boolean,
    /** Applies to the regular floating launcher, not TYPE_ACCESSIBILITY_OVERLAY. */
    val floatingWindowPermissionGranted: Boolean,
    val displayWidthPixels: Int,
    val displayHeightPixels: Int,
    val density: Float,
    /** 1=portrait, 2=landscape, other values are reported as undefined. */
    val orientation: Int
)

/**
 * Builds a privacy-conscious, platform-neutral report for real-device acceptance.
 * Node text, content descriptions, hints, resource identifiers and user content are never emitted.
 */
object NodeInspectorValidationReportBuilder {
    const val STALE_SNAPSHOT_WARNING_AFTER_MS = 120_000L

    fun build(
        source: NodeCaptureSource,
        snapshots: List<AccessibilityTreeSnapshot>,
        device: NodeInspectorDeviceContext,
        generatedAtEpochMs: Long = System.currentTimeMillis()
    ): String = buildString {
        appendLine("Freedom 节点检查器真机验收报告")
        appendLine("生成时间：${formatTimestamp(generatedAtEpochMs)}（epochMs=$generatedAtEpochMs）")
        appendLine("目标包名：${device.targetPackage.ifBlank { "UNKNOWN" }}")
        appendLine("采集来源：${sourceLabel(source)}")
        appendLine()

        appendLine("[设备与权限]")
        appendLine("Android API：${device.androidApiLevel}")
        appendLine("设备：${safe(device.manufacturer)} ${safe(device.model)}")
        appendLine("Freedom 版本：${device.appVersionName?.takeIf(String::isNotBlank) ?: "UNKNOWN"}")
        appendLine("屏幕：${device.displayWidthPixels}x${device.displayHeightPixels}px；density=${formatDensity(device.density)}；方向=${orientationLabel(device.orientation)}")
        appendLine("无障碍服务系统开关：${yesNo(device.accessibilityServiceEnabled)}（仅表示已启用，不代表当前已连接）")
        appendLine("普通悬浮窗权限：${yesNo(device.floatingWindowPermissionGranted)}（用于浮动启动按钮；无障碍覆盖层使用独立系统窗口类型）")
        appendLine()

        appendLine("[采集结果]")
        appendLine("采集到的目标窗口数：${snapshots.size}")
        if (snapshots.isEmpty()) {
            appendLine("[待检查] 当前来源没有可用节点快照。请返回目标 App，通过浮动按钮执行“当前窗口采集”后刷新检查器。")
        } else {
            val knownIds = snapshots.map { it.windowId }.filter { it >= 0 }
            val duplicateIds = knownIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicateIds.isNotEmpty()) {
                appendLine("[警告] 存在重复 Window ID：${duplicateIds.sorted().joinToString()}")
            }
            val unknownWindowIds = snapshots.count { it.windowId < 0 }
            if (unknownWindowIds > 0) appendLine("[警告] Window ID 不可用的窗口数：$unknownWindowIds")

            var totalNodes = 0
            var totalVisited = 0
            var visibleBounds = 0
            var truncatedSnapshots = 0
            var staleSnapshots = 0
            var rootPackageMismatches = 0
            var nodePackageMismatches = 0
            var nodeWindowMismatches = 0
            var generatedAtBeforeCapture = 0

            snapshots.forEachIndexed { index, snapshot ->
                val nodes = snapshot.nodes
                val ageMs = generatedAtEpochMs - snapshot.capturedAtEpochMs
                val rootMismatch = snapshot.window.packageName != device.targetPackage
                val packageMismatchCount = nodes.count {
                    it.packageName.isNotBlank() && it.packageName != device.targetPackage
                }
                val windowMismatchCount = nodes.count {
                    it.windowId >= 0 && snapshot.windowId >= 0 && it.windowId != snapshot.windowId
                }
                val visibleBounded = nodes.count { it.visible && !it.bounds.isEmpty }
                val truncated = snapshot.truncated || snapshot.walkTruncated || snapshot.depthTruncated
                if (rootMismatch) rootPackageMismatches++
                nodePackageMismatches += packageMismatchCount
                nodeWindowMismatches += windowMismatchCount
                totalNodes += nodes.size
                totalVisited += snapshot.visitedNodeCount
                visibleBounds += visibleBounded
                if (truncated) truncatedSnapshots++
                if (ageMs > STALE_SNAPSHOT_WARNING_AFTER_MS) staleSnapshots++
                if (ageMs < 0L) generatedAtBeforeCapture++

                appendLine("窗口 ${index + 1}：Window ID=${snapshot.windowId}；包名=${safe(snapshot.window.packageName)}；Activity=${safe(snapshot.window.activityClass)}")
                appendLine("  节点=${nodes.size}；遍历数=${snapshot.visitedNodeCount}；请求上限=${snapshot.requestedMaxNodes}；可见有效边界=$visibleBounded")
                appendLine("  采集时间=${snapshot.capturedAtEpochMs}；快照年龄=${ageLabel(ageMs)}；generation=${snapshot.window.generation}")
                appendLine("  截断=${yesNo(truncated)}；stopReason=${snapshot.stopReason ?: "NONE"}；rootPackageMismatch=${yesNo(rootMismatch)}")
                if (packageMismatchCount > 0 || windowMismatchCount > 0) {
                    appendLine("  [错误] 节点元数据不一致：packageMismatch=$packageMismatchCount；windowMismatch=$windowMismatchCount")
                }
            }

            appendLine("汇总：节点=$totalNodes；遍历节点=$totalVisited；可见有效边界=$visibleBounds")
            if (rootPackageMismatches > 0) appendLine("[错误] 发现 $rootPackageMismatches 个窗口根包名与当前目标包名不一致。")
            if (nodePackageMismatches > 0) appendLine("[错误] 发现 $nodePackageMismatches 个节点包名与当前目标包名不一致。")
            if (nodeWindowMismatches > 0) appendLine("[错误] 发现 $nodeWindowMismatches 个节点 Window ID 与所属快照不一致。")
            if (truncatedSnapshots > 0) appendLine("[警告] $truncatedSnapshots 个窗口快照不完整；不能用其证明全树唯一性，也不能视为完整边界清单。")
            if (staleSnapshots > 0) appendLine("[警告] $staleSnapshots 个窗口快照超过 ${STALE_SNAPSHOT_WARNING_AFTER_MS / 1000} 秒；页面变化后应重新采集。")
            if (generatedAtBeforeCapture > 0) appendLine("[警告] 有 $generatedAtBeforeCapture 个快照时间晚于报告时间；请检查设备系统时间。")
            if (visibleBounds == 0) appendLine("[警告] 当前快照没有可见且非空的节点边界；请在目标 App 的实际内容页重新采集。")
            if (snapshots.size == 1) appendLine("[提示] 当前只观察到一个同包名窗口；请打开目标 App 自身的 Dialog/PopupWindow 后再次采集，确认系统是否暴露额外窗口根节点。")
        }

        if (!device.accessibilityServiceEnabled) appendLine("[错误] Freedom 无障碍服务未在系统设置中启用。")
        if (!device.floatingWindowPermissionGranted) appendLine("[警告] 普通悬浮窗权限未授予；浮动按钮可能无法显示。无障碍覆盖层的实际显示能力仍需在设备上验证。")

        appendLine()
        appendLine("[仍需真机人工确认]")
        appendLine("1. 目标 App 的普通页面、同包名 Dialog 与 PopupWindow 是否都能采集到；其他 App/输入法/系统窗口是否被排除。")
        appendLine("2. 节点边界与屏幕实际控件是否对齐；屏幕旋转、分辨率变化和窗口切换后是否仍准确。")
        appendLine("3. 边界覆盖层出现时，目标 App 的点击、滚动和输入是否完全可穿透。")
        appendLine("4. 启动截图/OCR 时边界覆盖层是否已清理，识别图像中是否没有绿色边框。")
        appendLine("5. 返回、切换前后台、目标包名变更和无障碍服务中断后，覆盖层是否按预期清理并可恢复。")
        appendLine()
        appendLine("隐私说明：本报告不包含节点文本、contentDescription、hint、View ID、截图或聊天内容。")
    }

    private fun sourceLabel(source: NodeCaptureSource): String = when (source) {
        NodeCaptureSource.MANUAL -> "手动采集"
        NodeCaptureSource.AUTOMATIC -> "程序自主采集"
    }

    private fun orientationLabel(value: Int): String = when (value) {
        1 -> "portrait"
        2 -> "landscape"
        else -> "undefined($value)"
    }

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"
    private fun safe(value: String): String = value.ifBlank { "UNKNOWN" }.replace('\n', ' ')
    private fun formatDensity(value: Float): String =
        if (value.isFinite() && value > 0f) String.format(Locale.US, "%.2f", value) else "UNKNOWN"

    private fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(epochMs))

    private fun ageLabel(ageMs: Long): String = when {
        ageMs < 0L -> "时间异常"
        ageMs < 1_000L -> "${ageMs}ms"
        else -> "${ageMs / 1_000}s"
    }
}
