package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.core.accessibility.api.ScreenRect

/**
 * Shared low-level read fallbacks for the existing Momo chat readers.
 * No tree traversal, node ownership, page recognition, text handling or UI action.
 * Keep the original fallback values and exception scope; callers own their read budgets.
 */
internal fun momoReadBooleanOrFalse(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
internal fun momoReadIntOrZero(block: () -> Int): Int = runCatching(block).getOrDefault(0)
internal fun momoReadNodeOrNull(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

internal fun momoReadBounds(node: AccessibilityNodeInfo): ScreenRect {
    val rect = Rect()
    runCatching { node.getBoundsInScreen(rect) }
    return ScreenRect(rect.left, rect.top, rect.right, rect.bottom)
}
