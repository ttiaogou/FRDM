package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodeTextCapture

/** Aggregate state of one field across the nodes in a single captured snapshot. */
enum class AccessibilityFieldCoverageState {
    AVAILABLE,
    PARTIAL,
    NOT_CAPTURED,
    UNSUPPORTED,
    READ_FAILED,
    NO_NODES
}

data class AccessibilityFieldCoverage(
    val field: AccessibilityNodeField,
    val nodeCount: Int,
    val valuePresentCount: Int,
    val emptyCount: Int,
    val notRequestedCount: Int,
    val unsupportedCount: Int,
    val readFailedCount: Int
) {
    val successfullyReadCount: Int get() = valuePresentCount + emptyCount
    val valueCoverage: Float
        get() = if (nodeCount == 0) 0f else valuePresentCount.toFloat() / nodeCount

    val state: AccessibilityFieldCoverageState
        get() = when {
            nodeCount == 0 -> AccessibilityFieldCoverageState.NO_NODES
            successfullyReadCount == nodeCount -> AccessibilityFieldCoverageState.AVAILABLE
            successfullyReadCount == 0 && notRequestedCount == nodeCount ->
                AccessibilityFieldCoverageState.NOT_CAPTURED
            successfullyReadCount == 0 && unsupportedCount == nodeCount ->
                AccessibilityFieldCoverageState.UNSUPPORTED
            successfullyReadCount == 0 && readFailedCount == nodeCount ->
                AccessibilityFieldCoverageState.READ_FAILED
            else -> AccessibilityFieldCoverageState.PARTIAL
        }
}

/**
 * Describes the real capture capability of one immutable tree snapshot.
 * It reports both field availability and value coverage; an empty value is not a read failure.
 */
data class AccessibilityCapabilityProfile(
    val nodeCount: Int,
    val visitedNodeCount: Int,
    val snapshotComplete: Boolean,
    val stopReason: String?,
    val requestedMaxNodes: Int,
    val requestedMaxDepth: Int,
    val textCapture: NodeTextCapture,
    val fields: Map<AccessibilityNodeField, AccessibilityFieldCoverage>
) {
    fun coverage(field: AccessibilityNodeField): AccessibilityFieldCoverage? = fields[field]

    companion object {
        fun from(snapshot: AccessibilityTreeSnapshot): AccessibilityCapabilityProfile {
            val nodeCount = snapshot.nodes.size
            val fields = AccessibilityNodeField.entries.associateWith { field ->
                var valuePresent = 0
                var empty = 0
                var notRequested = 0
                var unsupported = 0
                var failed = 0
                snapshot.nodes.forEach { node ->
                    when (node.captureState(field, snapshot.textCapture)) {
                        AccessibilityFieldCaptureState.VALUE_PRESENT -> valuePresent++
                        AccessibilityFieldCaptureState.EMPTY -> empty++
                        AccessibilityFieldCaptureState.NOT_REQUESTED -> notRequested++
                        AccessibilityFieldCaptureState.UNSUPPORTED -> unsupported++
                        AccessibilityFieldCaptureState.READ_FAILED -> failed++
                    }
                }
                AccessibilityFieldCoverage(
                    field = field,
                    nodeCount = nodeCount,
                    valuePresentCount = valuePresent,
                    emptyCount = empty,
                    notRequestedCount = notRequested,
                    unsupportedCount = unsupported,
                    readFailedCount = failed
                )
            }
            return AccessibilityCapabilityProfile(
                nodeCount = nodeCount,
                visitedNodeCount = snapshot.visitedNodeCount,
                snapshotComplete = !snapshot.truncated,
                stopReason = snapshot.stopReason,
                requestedMaxNodes = snapshot.requestedMaxNodes,
                requestedMaxDepth = snapshot.requestedMaxDepth,
                textCapture = snapshot.textCapture,
                fields = fields
            )
        }
    }
}
