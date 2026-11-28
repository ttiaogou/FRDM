package com.freedom.adapters.accessibility.diagnostics

import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.diagnostics.NodeCaptureSource

/** In-memory snapshots kept separately by capture origin; no node content is written to preferences. */
object LatestAccessibilityNodeDump {
    @Volatile
    private var latestManualSnapshots: List<AccessibilityTreeSnapshot> = emptyList()
    @Volatile
    private var latestAutomaticSnapshots: List<AccessibilityTreeSnapshot> = emptyList()

    fun update(snapshot: AccessibilityTreeSnapshot, source: NodeCaptureSource = NodeCaptureSource.AUTOMATIC) =
        updateAll(listOf(snapshot), source)

    fun updateAll(snapshots: List<AccessibilityTreeSnapshot>, source: NodeCaptureSource) {
        val detached = snapshots.toList()
        when (source) {
            NodeCaptureSource.MANUAL -> latestManualSnapshots = detached
            NodeCaptureSource.AUTOMATIC -> latestAutomaticSnapshots = detached
        }
    }

    fun latest(source: NodeCaptureSource): AccessibilityTreeSnapshot? = latestAll(source).firstOrNull()

    fun latestAll(source: NodeCaptureSource): List<AccessibilityTreeSnapshot> = when (source) {
        NodeCaptureSource.MANUAL -> latestManualSnapshots
        NodeCaptureSource.AUTOMATIC -> latestAutomaticSnapshots
    }

    fun clear() {
        latestManualSnapshots = emptyList()
        latestAutomaticSnapshots = emptyList()
    }
}
