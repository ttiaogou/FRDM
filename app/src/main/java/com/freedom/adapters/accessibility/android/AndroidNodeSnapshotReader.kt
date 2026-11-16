package com.freedom.adapters.accessibility.android

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.core.accessibility.api.AccessibilityActionSnapshot
import com.freedom.core.accessibility.api.AccessibilityCollectionItemSnapshot
import com.freedom.core.accessibility.api.AccessibilityCollectionSnapshot
import com.freedom.core.accessibility.api.AccessibilityNodeCaptureEvidence
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.TreeInspectionRequest
import com.freedom.core.accessibility.api.WindowRef
import java.util.ArrayDeque

/** Converts short-lived Android nodes into bounded, detached platform-neutral tree snapshots. */
internal class AndroidNodeSnapshotReader(
    private val createWindowRef: (AccessibilityNodeInfo, Int) -> WindowRef?,
    private val recycleNode: (AccessibilityNodeInfo?) -> Unit
) {
    fun inspectRoot(
        root: AccessibilityNodeInfo?,
        request: TreeInspectionRequest,
        windowIdFallback: Int = -1,
        deadlineNanos: Long,
        initialPath: List<Int> = emptyList()
    ): AccessibilityTreeSnapshot? {
        root ?: return null
        val window = createWindowRef(root, windowIdFallback) ?: run {
            recycleNode(root)
            return null
        }
        val rootWindowId = safeInt { root.windowId }.takeIf { it >= 0 } ?: windowIdFallback
        return try {
            val queue = ArrayDeque<Triple<AccessibilityNodeInfo, List<Int>, Int>>()
            queue.add(Triple(AccessibilityNodeInfo.obtain(root), initialPath, 0))
            val snapshots = ArrayList<AccessibilityNodeSnapshot>(minOf(request.maxNodes, 256))
            var visited = 0
            var walkTruncated = false
            var nodeBudgetTruncated = false
            var depthTruncated = false
            var timeBudgetExceeded = false
            var childReadFailed = false
            try {
                while (queue.isNotEmpty() && visited < request.maxNodes && System.nanoTime() < deadlineNanos) {
                    val (node, path, depth) = queue.removeFirst()
                    visited += 1
                    try {
                        val failedFields = linkedSetOf<AccessibilityNodeField>()
                        val unsupportedFields = linkedSetOf<AccessibilityNodeField>()
                        val notCapturedFields = linkedSetOf<AccessibilityNodeField>()

                        fun <T> readField(
                            field: AccessibilityNodeField,
                            fallback: T,
                            getter: () -> T
                        ): T = try {
                            getter()
                        } catch (_: Throwable) {
                            failedFields += field
                            fallback
                        }

                        fun <T> readNullableField(
                            field: AccessibilityNodeField,
                            getter: () -> T?
                        ): T? = try {
                            getter()
                        } catch (_: Throwable) {
                            failedFields += field
                            null
                        }

                        val bounds = android.graphics.Rect()
                        readField(AccessibilityNodeField.BOUNDS, Unit) { node.getBoundsInScreen(bounds) }
                        val children = readField(AccessibilityNodeField.CHILD_COUNT, 0) {
                            node.childCount.coerceAtLeast(0)
                        }
                        if (AccessibilityNodeField.CHILD_COUNT in failedFields) {
                            childReadFailed = true
                            walkTruncated = true
                            failedFields += AccessibilityNodeField.CHILD_NODES
                        }
                        val className = readField(AccessibilityNodeField.CLASS_NAME, "") {
                            node.className?.toString().orEmpty()
                        }
                        val viewId = readField(AccessibilityNodeField.VIEW_ID, "") {
                            node.viewIdResourceName.orEmpty()
                        }
                        val packageName = readField(AccessibilityNodeField.PACKAGE_NAME, "") {
                            node.packageName?.toString().orEmpty()
                        }
                        val windowId = readField(AccessibilityNodeField.WINDOW_ID, -1) {
                            node.windowId
                        }.takeIf { it >= 0 } ?: rootWindowId

                        val text = if (request.textCapture == NodeTextCapture.EPHEMERAL) {
                            readField(AccessibilityNodeField.TEXT, null as String?) {
                                node.text?.toString()?.takeIf(String::isNotBlank)
                            }
                        } else null
                        val contentDescription = if (request.textCapture == NodeTextCapture.EPHEMERAL) {
                            readField(AccessibilityNodeField.CONTENT_DESCRIPTION, null as String?) {
                                node.contentDescription?.toString()?.takeIf(String::isNotBlank)
                            }
                        } else null
                        val hintText = if (request.textCapture == NodeTextCapture.EPHEMERAL) {
                            readField(AccessibilityNodeField.HINT_TEXT, null as String?) {
                                node.hintText?.toString()?.takeIf(String::isNotBlank)
                            }
                        } else null
                        val stateDescription = when {
                            request.textCapture != NodeTextCapture.EPHEMERAL -> null
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> {
                                unsupportedFields += AccessibilityNodeField.STATE_DESCRIPTION
                                null
                            }
                            else -> readField(AccessibilityNodeField.STATE_DESCRIPTION, null as String?) {
                                node.stateDescription?.toString()?.takeIf(String::isNotBlank)
                            }
                        }
                        val tooltipText = when {
                            request.textCapture != NodeTextCapture.EPHEMERAL -> null
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> {
                                unsupportedFields += AccessibilityNodeField.TOOLTIP_TEXT
                                null
                            }
                            else -> readField(AccessibilityNodeField.TOOLTIP_TEXT, null as String?) {
                                node.tooltipText?.toString()?.takeIf(String::isNotBlank)
                            }
                        }
                        if (request.textCapture == NodeTextCapture.NONE) {
                            notCapturedFields += setOf(
                                AccessibilityNodeField.TEXT,
                                AccessibilityNodeField.CONTENT_DESCRIPTION,
                                AccessibilityNodeField.HINT_TEXT,
                                AccessibilityNodeField.STATE_DESCRIPTION,
                                AccessibilityNodeField.TOOLTIP_TEXT,
                                AccessibilityNodeField.ACTION_LABELS
                            )
                        }

                        val collection = readNullableField(AccessibilityNodeField.COLLECTION) {
                            node.collectionInfo?.let { info ->
                                AccessibilityCollectionSnapshot(
                                    rowCount = info.rowCount,
                                    columnCount = info.columnCount,
                                    hierarchical = info.isHierarchical
                                )
                            }
                        }
                        val collectionItem = readNullableField(AccessibilityNodeField.COLLECTION_ITEM) {
                            node.collectionItemInfo?.let { info ->
                                AccessibilityCollectionItemSnapshot(
                                    rowIndex = info.rowIndex,
                                    rowSpan = info.rowSpan,
                                    columnIndex = info.columnIndex,
                                    columnSpan = info.columnSpan,
                                    heading = info.isHeading,
                                    selected = info.isSelected
                                )
                            }
                        }
                        val actions = readField(AccessibilityNodeField.ACTIONS, emptyList()) {
                            node.actionList.map { action ->
                                val label = if (request.textCapture == NodeTextCapture.EPHEMERAL) {
                                    try {
                                        action.label?.toString()?.takeIf(String::isNotBlank)
                                    } catch (_: Throwable) {
                                        failedFields += AccessibilityNodeField.ACTION_LABELS
                                        null
                                    }
                                } else null
                                AccessibilityActionSnapshot(id = action.id, label = label)
                            }
                        }

                        val dismissable = readField(AccessibilityNodeField.DISMISSABLE, false) { node.isDismissable }
                        val accessibilityFocused = readField(
                            AccessibilityNodeField.ACCESSIBILITY_FOCUSED, false
                        ) { node.isAccessibilityFocused }
                        val multiLine = readField(AccessibilityNodeField.MULTI_LINE, false) { node.isMultiLine }
                        val contentInvalid = readField(AccessibilityNodeField.CONTENT_INVALID, false) { node.isContentInvalid }
                        val importantForAccessibility = readField(
                            AccessibilityNodeField.IMPORTANT_FOR_ACCESSIBILITY, false
                        ) { node.isImportantForAccessibility }
                        val screenReaderFocusable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            readField(AccessibilityNodeField.SCREEN_READER_FOCUSABLE, false) {
                                node.isScreenReaderFocusable
                            }
                        } else {
                            unsupportedFields += AccessibilityNodeField.SCREEN_READER_FOCUSABLE
                            false
                        }
                        val heading = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            readField(AccessibilityNodeField.HEADING, false) { node.isHeading }
                        } else {
                            unsupportedFields += AccessibilityNodeField.HEADING
                            false
                        }
                        val showingHintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            readField(AccessibilityNodeField.SHOWING_HINT_TEXT, false) { node.isShowingHintText }
                        } else {
                            unsupportedFields += AccessibilityNodeField.SHOWING_HINT_TEXT
                            false
                        }

                        val visible = readField(AccessibilityNodeField.VISIBLE, false) { node.isVisibleToUser }
                        val enabled = readField(AccessibilityNodeField.ENABLED, false) { node.isEnabled }
                        val checkable = readField(AccessibilityNodeField.CHECKABLE, false) { node.isCheckable }
                        val checked = readField(AccessibilityNodeField.CHECKED, false) { node.isChecked }
                        val clickable = readField(AccessibilityNodeField.CLICKABLE, false) { node.isClickable }
                        val longClickable = readField(AccessibilityNodeField.LONG_CLICKABLE, false) { node.isLongClickable }
                        val scrollable = readField(AccessibilityNodeField.SCROLLABLE, false) { node.isScrollable }
                        val editable = readField(AccessibilityNodeField.EDITABLE, false) { node.isEditable }
                        val selected = readField(AccessibilityNodeField.SELECTED, false) { node.isSelected }
                        val focused = readField(AccessibilityNodeField.FOCUSED, false) { node.isFocused }
                        val focusable = readField(AccessibilityNodeField.FOCUSABLE, false) { node.isFocusable }
                        val contextClickable = readField(
                            AccessibilityNodeField.CONTEXT_CLICKABLE, false
                        ) { node.isContextClickable }
                        val drawingOrder = readField(AccessibilityNodeField.DRAWING_ORDER, 0) { node.drawingOrder }
                        val password = readField(AccessibilityNodeField.PASSWORD, false) { node.isPassword }
                        val indexInParent = path.lastOrNull() ?: -1

                        if (depth >= request.maxDepth) {
                            if (children > 0) {
                                depthTruncated = true
                                notCapturedFields += AccessibilityNodeField.CHILD_NODES
                            }
                        } else {
                            for (index in 0 until children) {
                                if (System.nanoTime() >= deadlineNanos) {
                                    timeBudgetExceeded = true
                                    walkTruncated = true
                                    notCapturedFields += AccessibilityNodeField.CHILD_NODES
                                    break
                                }
                                if (visited + queue.size >= request.maxNodes) {
                                    walkTruncated = true
                                    nodeBudgetTruncated = true
                                    notCapturedFields += AccessibilityNodeField.CHILD_NODES
                                    break
                                }
                                val child = try {
                                    node.getChild(index)
                                } catch (_: Throwable) {
                                    failedFields += AccessibilityNodeField.CHILD_NODES
                                    childReadFailed = true
                                    null
                                }
                                if (child != null) {
                                    queue.add(Triple(child, path + index, depth + 1))
                                } else {
                                    failedFields += AccessibilityNodeField.CHILD_NODES
                                    childReadFailed = true
                                    walkTruncated = true
                                }
                            }
                        }

                        snapshots += AccessibilityNodeSnapshot(
                            path = NodePath(path),
                            className = className,
                            viewId = viewId,
                            text = text,
                            contentDescription = contentDescription,
                            bounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
                            visible = visible,
                            enabled = enabled,
                            checkable = checkable,
                            checked = checked,
                            clickable = clickable,
                            longClickable = longClickable,
                            scrollable = scrollable,
                            editable = editable,
                            selected = selected,
                            focused = focused,
                            childCount = children,
                            packageName = packageName,
                            windowId = windowId,
                            indexInParent = indexInParent,
                            focusable = focusable,
                            contextClickable = contextClickable,
                            drawingOrder = drawingOrder,
                            password = password,
                            hintText = hintText,
                            stateDescription = stateDescription,
                            tooltipText = tooltipText,
                            collection = collection,
                            collectionItem = collectionItem,
                            actions = actions,
                            dismissable = dismissable,
                            accessibilityFocused = accessibilityFocused,
                            multiLine = multiLine,
                            contentInvalid = contentInvalid,
                            importantForAccessibility = importantForAccessibility,
                            screenReaderFocusable = screenReaderFocusable,
                            heading = heading,
                            showingHintText = showingHintText,
                            captureEvidence = AccessibilityNodeCaptureEvidence(
                                failedFields = failedFields.toSet(),
                                unsupportedFields = unsupportedFields.toSet(),
                                notCapturedFields = notCapturedFields.toSet()
                            )
                        )
                    } finally {
                        recycleNode(node)
                    }
                }
                // Reaching the exact node budget is not itself truncation. A non-empty queue
                // means there are nodes already discovered but not visited; dropped children
                // are marked at the point they are omitted above.
                if (queue.isNotEmpty()) {
                    walkTruncated = true
                    if (System.nanoTime() >= deadlineNanos) timeBudgetExceeded = true
                    else nodeBudgetTruncated = true
                }
            } finally {
                while (queue.isNotEmpty()) recycleNode(queue.removeFirst().first)
            }
            AccessibilityTreeSnapshot(
                window = window,
                nodes = snapshots,
                visitedNodeCount = visited,
                truncated = walkTruncated || depthTruncated || timeBudgetExceeded,
                requestedMaxNodes = request.maxNodes,
                requestedMaxDepth = request.maxDepth,
                walkTruncated = walkTruncated,
                depthTruncated = depthTruncated,
                windowId = rootWindowId,
                textCapture = request.textCapture,
                timeBudgetExceeded = timeBudgetExceeded,
                requestedMaxDurationMs = request.maxDurationMs,
                stopReason = when {
                    timeBudgetExceeded -> "TIME_BUDGET"
                    childReadFailed && nodeBudgetTruncated && depthTruncated -> "CHILD_READ_NODE_AND_DEPTH_INCOMPLETE"
                    childReadFailed && nodeBudgetTruncated -> "CHILD_READ_AND_NODE_BUDGET_INCOMPLETE"
                    childReadFailed && depthTruncated -> "CHILD_READ_AND_DEPTH_INCOMPLETE"
                    childReadFailed -> "CHILD_NODE_READ_FAILED"
                    nodeBudgetTruncated && depthTruncated -> "NODE_AND_DEPTH_BUDGET"
                    nodeBudgetTruncated -> "NODE_BUDGET"
                    depthTruncated -> "DEPTH_BUDGET"
                    else -> null
                }
            )
        } finally {
            recycleNode(root)
        }
    }


    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
}
