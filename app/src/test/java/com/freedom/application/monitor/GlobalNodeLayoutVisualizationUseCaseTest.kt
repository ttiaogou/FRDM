package com.freedom.application.monitor

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ActiveWindowInspectionPort
import com.freedom.core.accessibility.api.DisplayMetricsSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.overlay.OverlayPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalNodeLayoutVisualizationUseCaseTest {
    @Test
    fun capturesOnlyTargetPackageWindowsAndPassesNodeSnapshotsToInteractiveOverlay() {
        val first = snapshot("com.example.target", 11, listOf(
            node("com.example.target", 11, listOf(0), ScreenRect(0, 10, 100, 60)),
            node("com.example.target", 11, listOf(1), ScreenRect(0, 10, 100, 60)),
            node("com.example.target", 11, listOf(2), ScreenRect(0, 0, 0, 0), visible = false)
        ))
        val second = snapshot("com.example.target", 12, listOf(
            node("com.example.target", 12, listOf(0), ScreenRect(100, 10, 200, 60))
        ))
        val foreign = snapshot("com.example.other", 13, listOf(
            node("com.example.other", 13, listOf(0), ScreenRect(200, 10, 300, 60))
        ))
        val gateway = FakeGateway(listOf(first, second, foreign))
        val overlay = FakeOverlay()
        val useCase = GlobalNodeLayoutVisualizationUseCase(
            gateway = gateway,
            targetPackage = { "com.example.target" },
            overlay = overlay,
            onLog = {},
            maxNodesPerScan = 50
        )

        assertTrue(useCase.show())
        assertEquals(NodeTextCapture.EPHEMERAL, gateway.request?.textCapture)
        assertEquals(1, gateway.targetWindowCalls)
        assertEquals(0, gateway.activeWindowCalls)
        assertEquals(listOf(11, 12), useCase.snapshots().map { it.windowId })
        assertEquals(2, useCase.snapshots().size)
        assertEquals(3, overlay.snapshots.flatMap { it.nodes }.count { it.visible && !it.bounds.isEmpty })
        assertEquals(1, overlay.showCount)
        assertEquals(0, overlay.clearCount)
    }

    @Test
    fun failedFreshCaptureClearsOldOverlayAndSnapshotCache() {
        val gateway = FakeGateway(listOf(snapshot("com.example.target", 11, listOf(
            node("com.example.target", 11, listOf(0), ScreenRect(0, 0, 50, 50))
        ))))
        val overlay = FakeOverlay()
        val useCase = GlobalNodeLayoutVisualizationUseCase(
            gateway = gateway,
            targetPackage = { "com.example.target" },
            overlay = overlay,
            onLog = {}
        )
        assertTrue(useCase.show())

        gateway.windows = emptyList()
        assertFalse(useCase.show())
        assertTrue(useCase.snapshots().isEmpty())
        assertEquals(1, overlay.clearCount)
    }

    @Test
    fun explicitClearRemovesSnapshotCacheAndPersistentBounds() {
        val gateway = FakeGateway(listOf(snapshot("com.example.target", 11, listOf(
            node("com.example.target", 11, listOf(0), ScreenRect(0, 0, 50, 50))
        ))))
        val overlay = FakeOverlay()
        val useCase = GlobalNodeLayoutVisualizationUseCase(
            gateway = gateway,
            targetPackage = { "com.example.target" },
            overlay = overlay,
            onLog = {}
        )
        assertTrue(useCase.show())
        useCase.clear()
        assertTrue(useCase.snapshots().isEmpty())
        assertEquals(1, overlay.clearCount)
    }

    private class FakeGateway(var windows: List<AccessibilityTreeSnapshot>) : ActiveWindowInspectionPort {
        var request: TreeInspectionRequest? = null
        var activeWindowCalls = 0
        var targetWindowCalls = 0
        override fun displayMetrics() = DisplayMetricsSnapshot(1080, 1920, 3f)
        override fun inspectActiveWindow(request: TreeInspectionRequest): AccessibilityTreeSnapshot? {
            activeWindowCalls++
            return windows.firstOrNull()
        }
        override fun inspectTargetWindows(request: TreeInspectionRequest): List<AccessibilityTreeSnapshot> {
            targetWindowCalls++
            this.request = request
            return windows
        }
        override fun isWindowCurrent(window: WindowRef) = true
    }

    private class FakeOverlay : OverlayPort {
        var bounds: List<ScreenRect> = emptyList()
        var snapshots: List<AccessibilityTreeSnapshot> = emptyList()
        var showCount = 0
        var clearCount = 0
        override fun isMonitorAttached() = false
        override fun touchStateDescription() = ""
        override fun setMonitorTouchPassthrough(enabled: Boolean, watchdogTimeoutMillis: Long?) = true
        override fun showFirstSwipeCountdown(secondsRemaining: Int, message: String) = true
        override fun hideFirstSwipeCountdown() = Unit
        override fun refreshMonitor(appLabel: String, pageLabel: String) = Unit
        override fun clearGestureTrail() = Unit
        override fun showNodeBoundsHighlight(selectedBounds: ScreenRect, ancestorBounds: List<ScreenRect>, label: String, durationMs: Long) = true
        override fun showNodeLayoutBounds(bounds: List<ScreenRect>, label: String, durationMs: Long) = true
        override fun showPersistentNodeLayoutBounds(bounds: List<ScreenRect>, label: String): Boolean {
            this.bounds = bounds
            showCount++
            return true
        }
        override fun showInteractiveNodeLayoutBounds(
            snapshots: List<AccessibilityTreeSnapshot>,
            label: String
        ): Boolean {
            this.snapshots = snapshots
            showCount++
            return true
        }
        override fun clearNodeBoundsHighlight() {
            bounds = emptyList()
            clearCount++
        }
    }

    private fun snapshot(packageName: String, windowId: Int, nodes: List<AccessibilityNodeSnapshot>) =
        AccessibilityTreeSnapshot(
            window = WindowRef(packageName, "${packageName}.MainActivity", 1L, windowId),
            nodes = nodes,
            visitedNodeCount = nodes.size,
            truncated = false,
            windowId = windowId,
            textCapture = NodeTextCapture.NONE
        )

    private fun node(
        packageName: String,
        windowId: Int,
        path: List<Int>,
        bounds: ScreenRect,
        visible: Boolean = true
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = "android.view.View",
        viewId = "${packageName}:id/view_${path.joinToString("_")}",
        text = null,
        contentDescription = null,
        bounds = bounds,
        visible = visible,
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
}
