package com.freedom.core.accessibility.diagnostics

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeInspectorValidationReportBuilderTest {
    private val device = NodeInspectorDeviceContext(
        targetPackage = "com.example.target",
        androidApiLevel = 35,
        manufacturer = "Example",
        model = "Device X",
        appVersionName = "1.2.3",
        accessibilityServiceEnabled = true,
        floatingWindowPermissionGranted = true,
        displayWidthPixels = 1080,
        displayHeightPixels = 2400,
        density = 3f,
        orientation = 1
    )

    private fun node(
        packageName: String = "com.example.target",
        windowId: Int = 7,
        text: String? = null,
        bounds: ScreenRect = ScreenRect(10, 20, 110, 80)
    ) = AccessibilityNodeSnapshot(
        path = NodePath(emptyList()),
        className = "android.widget.FrameLayout",
        viewId = "com.example.target:id/root",
        text = text,
        contentDescription = null,
        bounds = bounds,
        visible = true,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = false,
        longClickable = false,
        scrollable = false,
        editable = false,
        selected = false,
        focused = false,
        childCount = 0,
        packageName = packageName,
        windowId = windowId
    )

    private fun snapshot(
        windowId: Int = 7,
        packageName: String = "com.example.target",
        node: AccessibilityNodeSnapshot = node(windowId = windowId),
        capturedAt: Long = 9_900L,
        truncated: Boolean = false,
        stopReason: String? = null
    ) = AccessibilityTreeSnapshot(
        window = WindowRef(packageName, "com.example.target.MainActivity", 3L, windowId),
        nodes = listOf(node),
        visitedNodeCount = 1,
        truncated = truncated,
        requestedMaxNodes = 100,
        requestedMaxDepth = 40,
        walkTruncated = truncated,
        depthTruncated = false,
        capturedAtEpochMs = capturedAt,
        windowId = windowId,
        stopReason = stopReason
    )

    @Test
    fun emptyCaptureExplainsHowToCollectAndKeepsManualAcceptanceItems() {
        val report = NodeInspectorValidationReportBuilder.build(
            NodeCaptureSource.MANUAL, emptyList(), device, generatedAtEpochMs = 10_000L
        )

        assertTrue(report.contains("没有可用节点快照"))
        assertTrue(report.contains("Dialog 与 PopupWindow"))
        assertTrue(report.contains("点击、滚动和输入"))
        assertTrue(report.contains("截图/OCR"))
    }

    @Test
    fun validSamePackageWindowsAreSummarizedWithoutNodeText() {
        val snapshots = listOf(
            snapshot(windowId = 7, node = node(windowId = 7, text = "private chat text")),
            snapshot(windowId = 8, node = node(windowId = 8))
        )
        val report = NodeInspectorValidationReportBuilder.build(
            NodeCaptureSource.MANUAL, snapshots, device, generatedAtEpochMs = 10_000L
        )

        assertTrue(report.contains("采集到的目标窗口数：2"))
        assertTrue(report.contains("Window ID=7"))
        assertTrue(report.contains("Window ID=8"))
        assertTrue(report.contains("无障碍服务系统开关：是"))
        assertFalse(report.contains("private chat text"))
        assertFalse(report.contains("com.example.target:id/root"))
    }

    @Test
    fun rootAndNodePackageMismatchesAreReportedAsErrors() {
        val badNode = node(packageName = "com.example.other", windowId = 19)
        val badSnapshot = snapshot(
            windowId = 7,
            packageName = "com.example.other",
            node = badNode
        )
        val report = NodeInspectorValidationReportBuilder.build(
            NodeCaptureSource.MANUAL, listOf(badSnapshot), device, generatedAtEpochMs = 10_000L
        )

        assertTrue(report.contains("rootPackageMismatch=是"))
        assertTrue(report.contains("packageMismatch=1"))
        assertTrue(report.contains("windowMismatch=1"))
        assertTrue(report.contains("根包名与当前目标包名不一致"))
    }

    @Test
    fun truncatedAndStaleSnapshotsAreClearlyMarked() {
        val oldTruncated = snapshot(
            capturedAt = 1_000L,
            truncated = true,
            stopReason = "NODE_BUDGET"
        )
        val report = NodeInspectorValidationReportBuilder.build(
            NodeCaptureSource.MANUAL, listOf(oldTruncated), device, generatedAtEpochMs = 200_000L
        )

        assertTrue(report.contains("截断=是"))
        assertTrue(report.contains("stopReason=NODE_BUDGET"))
        assertTrue(report.contains("快照超过 120 秒"))
        assertTrue(report.contains("不能用其证明全树唯一性"))
    }

    @Test
    fun duplicateAndUnknownWindowIdsAreReported() {
        val report = NodeInspectorValidationReportBuilder.build(
            NodeCaptureSource.MANUAL,
            listOf(snapshot(windowId = 7), snapshot(windowId = 7), snapshot(windowId = -1)),
            device,
            generatedAtEpochMs = 10_000L
        )

        assertTrue(report.contains("存在重复 Window ID：7"))
        assertTrue(report.contains("Window ID 不可用的窗口数：1"))
    }

    @Test
    fun missingAccessibilityAndFloatingPermissionsAreDistinguished() {
        val restrictedDevice = device.copy(
            accessibilityServiceEnabled = false,
            floatingWindowPermissionGranted = false
        )
        val report = NodeInspectorValidationReportBuilder.build(
            NodeCaptureSource.MANUAL, emptyList(), restrictedDevice, generatedAtEpochMs = 10_000L
        )

        assertTrue(report.contains("[错误] Freedom 无障碍服务未在系统设置中启用"))
        assertTrue(report.contains("[警告] 普通悬浮窗权限未授予"))
        assertTrue(report.contains("无障碍覆盖层的实际显示能力仍需在设备上验证"))
    }

    @Test
    fun noVisibleBoundsAndFutureCaptureTimeAreReported() {
        val report = NodeInspectorValidationReportBuilder.build(
            NodeCaptureSource.MANUAL,
            listOf(snapshot(node = node(bounds = ScreenRect(0, 0, 0, 0)), capturedAt = 20_000L)),
            device,
            generatedAtEpochMs = 10_000L
        )

        assertTrue(report.contains("时间异常"))
        assertTrue(report.contains("系统时间"))
        assertTrue(report.contains("没有可见且非空的节点边界"))
    }
}
