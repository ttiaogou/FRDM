package com.freedom.application.ports

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.diagnostics.NodeCaptureSource
import com.freedom.core.accessibility.diagnostics.NodeInspectorDeviceContext

/** Application boundary used by the node-inspector UI to read captured snapshots and manage highlights. */
interface NodeCapturePresentationPort {
    fun latestSnapshot(source: NodeCaptureSource): AccessibilityTreeSnapshot?

    /** Runtime/device metadata used only for an explicitly requested, privacy-safe acceptance report. */
    fun deviceContext(): NodeInspectorDeviceContext? = null

    /** All captured windows of the currently selected target app, each as a detached tree. */
    fun latestSnapshots(source: NodeCaptureSource): List<AccessibilityTreeSnapshot> =
        latestSnapshot(source)?.let(::listOf).orEmpty()

    /** Requests a screen overlay; the service refreshes and revalidates this path in its window. */
    fun requestNodeBoundsHighlight(source: NodeCaptureSource, path: NodePath, windowId: Int = -1)

    /** Requests a fresh full-screen outline preview for the chosen target-app window. */
    fun requestScreenLayoutPreview(source: NodeCaptureSource, windowId: Int) = Unit

    /** Opens Freedom's existing document-export flow for the selected source/window snapshot. */
    fun requestLayoutExport(source: NodeCaptureSource, windowId: Int) = Unit

    /** Removes the short-lived screen highlight without performing any target-app action. */
    fun clearNodeBoundsHighlight()
}
