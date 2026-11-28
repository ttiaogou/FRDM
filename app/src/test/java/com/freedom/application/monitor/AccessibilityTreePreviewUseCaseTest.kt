package com.freedom.application.monitor

import com.freedom.application.ports.AppLabelResolver
import com.freedom.application.ports.MonitorPresentationSnapshot
import com.freedom.application.ports.MonitorSnapshotRepository
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.*
import org.junit.Test

class AccessibilityTreePreviewUseCaseTest {
    @Test
    fun previewRequestsNoTextAndPersistsOnlyStructuralFields() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.example", "ChatActivity", 2),
            nodes = listOf(
                node("android.widget.FrameLayout", "", null, childCount = 1),
                node("android.widget.TextView", "com.example:id/message", "private chat text")
            ),
            visitedNodeCount = 2,
            truncated = false
        )
        var request: TreeInspectionRequest? = null
        var saved: MonitorPresentationSnapshot? = null
        var refreshCount = 0
        val repository = object : MonitorSnapshotRepository {
            override fun updateAppPage(appLabel: String?, pageLabel: String?) = Unit
            override fun save(snapshot: MonitorPresentationSnapshot) { saved = snapshot }
            override fun read() = MonitorPresentationSnapshot("", "", emptyList(), 0, false)
        }
        val useCase = AccessibilityTreePreviewUseCase(
            appLabelResolver = AppLabelResolver { "Example" },
            gateway = object : com.freedom.core.accessibility.api.ActiveWindowInspectionPort {
                override fun displayMetrics() = com.freedom.core.accessibility.api.DisplayMetricsSnapshot(1080, 2400, 3f)
                override fun inspectActiveWindow(value: TreeInspectionRequest): AccessibilityTreeSnapshot? {
                    request = value
                    return tree
                }
                override fun isWindowCurrent(window: WindowRef) = true
            },
            isRunning = { true },
            snapshotRepository = repository,
            onLog = {},
            onRefresh = { refreshCount++ }
        )
        useCase.scanActiveWindow()

        assertEquals(NodeTextCapture.NONE, request?.textCapture)
        assertEquals("节点结构预览", saved?.pageLabel)
        assertTrue(saved?.nodePreview.orEmpty().none { it.contains("private chat text") })
        assertTrue(saved?.nodePreview.orEmpty().any { it.contains("com.example:id/message") })
        assertEquals(tree.visitedNodeCount, saved?.visitedNodeCount)
        assertEquals(tree.truncated, saved?.truncated)
        assertEquals(1, refreshCount)
    }

    @Test
    fun automaticPreviewDoesNotInspectOrPersistWhenBackgroundMonitorIsNotRunning() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.example", "ChatActivity", 3),
            nodes = listOf(node("android.widget.FrameLayout", "", null)),
            visitedNodeCount = 1,
            truncated = false
        )
        var inspectCount = 0
        var saved: MonitorPresentationSnapshot? = null
        val repository = object : MonitorSnapshotRepository {
            override fun updateAppPage(appLabel: String?, pageLabel: String?) = Unit
            override fun save(snapshot: MonitorPresentationSnapshot) { saved = snapshot }
            override fun read() = MonitorPresentationSnapshot("", "", emptyList(), 0, false)
        }
        val useCase = AccessibilityTreePreviewUseCase(
            appLabelResolver = AppLabelResolver { "Example" },
            gateway = object : com.freedom.core.accessibility.api.ActiveWindowInspectionPort {
                override fun displayMetrics() = com.freedom.core.accessibility.api.DisplayMetricsSnapshot(1080, 2400, 3f)
                override fun inspectActiveWindow(value: TreeInspectionRequest): AccessibilityTreeSnapshot? {
                    inspectCount++
                    assertEquals(NodeTextCapture.NONE, value.textCapture)
                    return tree
                }
                override fun isWindowCurrent(window: WindowRef) = true
            },
            isRunning = { false },
            snapshotRepository = repository,
            onLog = {},
            onRefresh = {}
        )

        useCase.scanActiveWindow()
        assertEquals(0, inspectCount)
        assertEquals(null, saved)
    }

    @Test
    fun automaticPreviewReadsOnlyActiveWindowAndKeepsWindowSummaryBounded() {
        val first = AccessibilityTreeSnapshot(
            window = WindowRef("com.example", "ChatActivity", 5, windowId = 11),
            nodes = listOf(node("android.widget.FrameLayout", "", null).copy(windowId = 11)),
            visitedNodeCount = 1,
            truncated = false,
            windowId = 11
        )
        val dialog = AccessibilityTreeSnapshot(
            window = WindowRef("com.example", "ChatActivity", 5, windowId = 12),
            nodes = listOf(node("android.widget.FrameLayout", "", null).copy(windowId = 12)),
            visitedNodeCount = 1,
            truncated = false,
            windowId = 12
        )
        var activeWindowCalled = false
        var targetWindowsCalled = false
        var saved: MonitorPresentationSnapshot? = null
        val repository = object : MonitorSnapshotRepository {
            override fun updateAppPage(appLabel: String?, pageLabel: String?) = Unit
            override fun save(snapshot: MonitorPresentationSnapshot) { saved = snapshot }
            override fun read() = MonitorPresentationSnapshot("", "", emptyList(), 0, false)
        }
        val useCase = AccessibilityTreePreviewUseCase(
            appLabelResolver = AppLabelResolver { "Example" },
            gateway = object : com.freedom.core.accessibility.api.ActiveWindowInspectionPort {
                override fun displayMetrics() = com.freedom.core.accessibility.api.DisplayMetricsSnapshot(1080, 2400, 3f)
                override fun inspectActiveWindow(value: TreeInspectionRequest): AccessibilityTreeSnapshot? {
                    activeWindowCalled = true
                    return first
                }
                override fun inspectTargetWindows(value: TreeInspectionRequest): List<AccessibilityTreeSnapshot> {
                    targetWindowsCalled = true
                    return listOf(first, dialog)
                }
                override fun isWindowCurrent(window: WindowRef) = true
            },
            isRunning = { true },
            snapshotRepository = repository,
            onLog = {},
            onRefresh = {}
        )

        useCase.scanActiveWindow()

        assertFalse(targetWindowsCalled)
        assertTrue(activeWindowCalled)
        assertEquals(first.visitedNodeCount, saved?.visitedNodeCount)
        assertEquals(first.truncated, saved?.truncated)
        assertEquals(1, saved?.nodePreview?.size)
    }

    private fun node(className: String, viewId: String, text: String?, childCount: Int = 0) =
        AccessibilityNodeSnapshot(
            path = if (className.endsWith("FrameLayout")) com.freedom.core.accessibility.api.NodePath(emptyList())
            else com.freedom.core.accessibility.api.NodePath(listOf(0)),
            className = className,
            viewId = viewId,
            text = text,
            contentDescription = "private description",
            bounds = ScreenRect(1, 2, 30, 40),
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
            childCount = childCount
        )
}
