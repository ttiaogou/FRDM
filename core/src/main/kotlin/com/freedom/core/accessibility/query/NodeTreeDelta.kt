package com.freedom.core.accessibility.query

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath

/** Structural difference between two complete snapshots from the same window generation. */
data class NodeTreeDelta(
    val comparable: Boolean,
    val addedPaths: List<NodePath> = emptyList(),
    val removedPaths: List<NodePath> = emptyList(),
    val changedPaths: List<NodePath> = emptyList(),
    val unchangedNodeCount: Int = 0,
    val reason: String? = null
) {
    val hasChanges: Boolean
        get() = addedPaths.isNotEmpty() || removedPaths.isNotEmpty() || changedPaths.isNotEmpty()
}

internal fun AccessibilityNodeSnapshot.structuralState(): List<Any?> = listOf(
    className, viewId, bounds, visible, enabled, checkable, checked, clickable,
    longClickable, scrollable, editable, selected, focused, childCount, packageName,
    windowId, indexInParent, focusable, contextClickable, drawingOrder, password,
    collection, collectionItem, actions.map { it.id }, dismissable, accessibilityFocused,
    multiLine, contentInvalid, importantForAccessibility, screenReaderFocusable, heading,
    showingHintText, captureEvidence
)
