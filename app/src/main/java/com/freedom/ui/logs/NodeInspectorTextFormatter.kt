package com.freedom.ui.logs

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.NodeTextCapture

/** Pure formatting for detached node snapshots; does not inspect Android Views or nodes. */
internal object NodeInspectorTextFormatter {
    fun formatTreeRow(node: AccessibilityNodeSnapshot, textCapture: NodeTextCapture): String {
        fun observedTrue(field: AccessibilityNodeField, value: Boolean): Boolean =
            value && node.captureState(field, textCapture) == AccessibilityFieldCaptureState.VALUE_PRESENT

        return buildString {
            append("  ".repeat(node.path.childIndices.size.coerceAtMost(10)))
            append(if (observedTrue(AccessibilityNodeField.CLICKABLE, node.clickable) ||
                observedTrue(AccessibilityNodeField.LONG_CLICKABLE, node.longClickable)) "● " else "└ ")
            append(node.className.substringAfterLast('.').ifBlank { "Unknown" })
            if (node.viewId.isNotBlank() &&
                node.captureState(AccessibilityNodeField.VIEW_ID, textCapture) == AccessibilityFieldCaptureState.VALUE_PRESENT
            ) append("\n").append("  ".repeat((node.path.childIndices.size + 1).coerceAtMost(11)))
                .append("ID: ").append(node.viewId)
            val flags = buildList {
                if (observedTrue(AccessibilityNodeField.CLICKABLE, node.clickable)) add("可点击")
                if (observedTrue(AccessibilityNodeField.LONG_CLICKABLE, node.longClickable)) add("可长按")
                if (observedTrue(AccessibilityNodeField.SCROLLABLE, node.scrollable)) add("可滚动")
                if (observedTrue(AccessibilityNodeField.EDITABLE, node.editable)) add("可编辑")
                if (observedTrue(AccessibilityNodeField.CHECKABLE, node.checkable)) add("可勾选")
                if (observedTrue(AccessibilityNodeField.CHECKED, node.checked)) add("已勾选")
                if (observedTrue(AccessibilityNodeField.SELECTED, node.selected)) add("已选中")
                if (observedTrue(AccessibilityNodeField.FOCUSED, node.focused)) add("焦点")
                if (observedTrue(AccessibilityNodeField.ENABLED, node.enabled)) add("启用")
                if (node.captureState(AccessibilityNodeField.VISIBLE, textCapture) == AccessibilityFieldCaptureState.VALUE_PRESENT && !node.visible) {
                    add("不可见")
                }
            }
            if (flags.isNotEmpty()) append("\n").append("  ".repeat((node.path.childIndices.size + 1).coerceAtMost(11)))
                .append(flags.joinToString(" · "))
            append("\n").append("  ".repeat((node.path.childIndices.size + 1).coerceAtMost(11)))
            append("bounds=[").append(node.bounds.left).append(',').append(node.bounds.top)
                .append(',').append(node.bounds.right).append(',').append(node.bounds.bottom).append(']')
            append("  path=").append(node.path.childIndices.joinToString("/"))
        }
    }

    fun formatNodeDetails(node: AccessibilityNodeSnapshot, textCapture: NodeTextCapture): String = buildString {
        fun appendField(label: String, field: AccessibilityNodeField, value: String) {
            append('\n').append(label).append(": ")
            append(when (node.captureState(field, textCapture)) {
                AccessibilityFieldCaptureState.VALUE_PRESENT -> value
                AccessibilityFieldCaptureState.EMPTY -> "EMPTY（已读取，值为空）"
                AccessibilityFieldCaptureState.NOT_REQUESTED -> "NOT_CAPTURED（本次未请求）"
                AccessibilityFieldCaptureState.UNSUPPORTED -> "UNSUPPORTED（平台不支持）"
                AccessibilityFieldCaptureState.READ_FAILED -> "READ_FAILED（读取失败）"
            })
        }

        append("path: ").append(node.path.childIndices.joinToString("/").ifBlank { "root" })
        appendField("className", AccessibilityNodeField.CLASS_NAME, node.className)
        appendField("viewId", AccessibilityNodeField.VIEW_ID, node.viewId)
        appendField("packageName", AccessibilityNodeField.PACKAGE_NAME, node.packageName)
        appendField("windowId", AccessibilityNodeField.WINDOW_ID, node.windowId.toString())
        appendField("bounds", AccessibilityNodeField.BOUNDS,
            "[${node.bounds.left}, ${node.bounds.top}, ${node.bounds.right}, ${node.bounds.bottom}]")
        appendField("visible", AccessibilityNodeField.VISIBLE, node.visible.toString())
        appendField("enabled", AccessibilityNodeField.ENABLED, node.enabled.toString())
        appendField("clickable", AccessibilityNodeField.CLICKABLE, node.clickable.toString())
        appendField("longClickable", AccessibilityNodeField.LONG_CLICKABLE, node.longClickable.toString())
        appendField("scrollable", AccessibilityNodeField.SCROLLABLE, node.scrollable.toString())
        appendField("editable", AccessibilityNodeField.EDITABLE, node.editable.toString())
        appendField("checkable", AccessibilityNodeField.CHECKABLE, node.checkable.toString())
        appendField("checked", AccessibilityNodeField.CHECKED, node.checked.toString())
        appendField("selected", AccessibilityNodeField.SELECTED, node.selected.toString())
        appendField("focused", AccessibilityNodeField.FOCUSED, node.focused.toString())
        appendField("focusable", AccessibilityNodeField.FOCUSABLE, node.focusable.toString())
        appendField("contextClickable", AccessibilityNodeField.CONTEXT_CLICKABLE, node.contextClickable.toString())
        appendField("dismissable", AccessibilityNodeField.DISMISSABLE, node.dismissable.toString())
        appendField("accessibilityFocused", AccessibilityNodeField.ACCESSIBILITY_FOCUSED, node.accessibilityFocused.toString())
        appendField("multiLine", AccessibilityNodeField.MULTI_LINE, node.multiLine.toString())
        appendField("contentInvalid", AccessibilityNodeField.CONTENT_INVALID, node.contentInvalid.toString())
        appendField("importantForAccessibility", AccessibilityNodeField.IMPORTANT_FOR_ACCESSIBILITY, node.importantForAccessibility.toString())
        appendField("screenReaderFocusable", AccessibilityNodeField.SCREEN_READER_FOCUSABLE, node.screenReaderFocusable.toString())
        appendField("heading", AccessibilityNodeField.HEADING, node.heading.toString())
        appendField("showingHintText", AccessibilityNodeField.SHOWING_HINT_TEXT, node.showingHintText.toString())
        appendField("password", AccessibilityNodeField.PASSWORD, node.password.toString())
        appendField("childCount", AccessibilityNodeField.CHILD_COUNT, node.childCount.toString())
        appendField("indexInParent", AccessibilityNodeField.INDEX_IN_PARENT, node.indexInParent.toString())
        appendField("drawingOrder", AccessibilityNodeField.DRAWING_ORDER, node.drawingOrder.toString())
        appendField("text", AccessibilityNodeField.TEXT, node.text.orEmpty())
        appendField("contentDescription", AccessibilityNodeField.CONTENT_DESCRIPTION, node.contentDescription.orEmpty())
        appendField("hintText", AccessibilityNodeField.HINT_TEXT, node.hintText.orEmpty())
        appendField("stateDescription", AccessibilityNodeField.STATE_DESCRIPTION, node.stateDescription.orEmpty())
        appendField("tooltipText", AccessibilityNodeField.TOOLTIP_TEXT, node.tooltipText.orEmpty())
        appendField("actions", AccessibilityNodeField.ACTIONS, node.actions.joinToString { action ->
            if (action.label.isNullOrBlank()) action.id.toString() else "${action.id}:${action.label}"
        })
        appendField("actionLabels", AccessibilityNodeField.ACTION_LABELS, node.actions.mapNotNull { it.label }.joinToString())
        appendField("collection", AccessibilityNodeField.COLLECTION, node.collection?.let {
            "rows=${it.rowCount}, columns=${it.columnCount}, hierarchical=${it.hierarchical}"
        }.orEmpty())
        appendField("collectionItem", AccessibilityNodeField.COLLECTION_ITEM, node.collectionItem?.let {
            "row=${it.rowIndex}, column=${it.columnIndex}, rowSpan=${it.rowSpan}, columnSpan=${it.columnSpan}, " +
                "heading=${it.heading}, selected=${it.selected}"
        }.orEmpty())
    }

}
