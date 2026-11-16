package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ScreenRect

/** A validated target for the profile-card header's explicit "列表" navigation control. */
internal sealed class MomoSayHiCardListTapDecision {
    data class Ready(
        val labelPath: List<Int>,
        val controlPath: List<Int>,
        val tapBounds: ScreenRect,
        val controlBounds: ScreenRect
    ) : MomoSayHiCardListTapDecision()

    data class Rejected(val reason: String) : MomoSayHiCardListTapDecision()
}

/**
 * Validates the exact clickable close_container shown in the confirmed NewSayHiCardActivity
 * hierarchy. The child label must read exactly "列表"; other card actions are never candidates.
 */
internal object MomoSayHiCardListTapPolicy {
    private const val LIST_LABEL = "列表"
    private const val MAX_CONTROL_HEIGHT_RATIO = 0.16f

    fun evaluate(
        tree: AccessibilityTreeSnapshot,
        screenWidth: Int,
        screenHeight: Int
    ): MomoSayHiCardListTapDecision {
        if (tree.truncated) return rejected("ACCESSIBILITY_TREE_TRUNCATED")
        if (tree.window.packageName != MomoAppDescriptor.PACKAGE_NAME) return rejected("PACKAGE_MISMATCH")
        if (tree.window.activityClass != MomoSayHiPageContract.CARD_ACTIVITY) {
            return rejected("NOT_SAYHI_CARD_ACTIVITY")
        }
        if (screenWidth <= 0 || screenHeight <= 0) return rejected("INVALID_SCREEN_METRICS")

        val controls = tree.nodes.filter {
            it.visible && it.viewId == MomoSayHiPageContract.CARD_CLOSE_VIEW_ID
        }
        if (controls.size != 1) return rejected("CLOSE_CONTAINER_COUNT=${controls.size}")
        val control = controls.single()
        if (!control.visible || !control.enabled || !control.clickable || control.bounds.isEmpty) {
            return rejected("CLOSE_CONTAINER_NOT_VISIBLE_ENABLED_CLICKABLE_OR_BOUNDED")
        }
        if (!isInsideScreen(control.bounds, screenWidth, screenHeight)) {
            return rejected("CLOSE_CONTAINER_OUTSIDE_SCREEN")
        }
        if (control.bounds.top > screenHeight / 2 ||
            control.bounds.height > screenHeight * MAX_CONTROL_HEIGHT_RATIO
        ) {
            return rejected("CLOSE_CONTAINER_NOT_IN_EXPECTED_HEADER_REGION")
        }

        val labels = tree.nodes.filter { node ->
            node.visible && isSameOrDescendant(node.path.childIndices, control.path.childIndices) &&
                listOfNotNull(node.text, node.contentDescription).any { it.trim() == LIST_LABEL }
        }
        if (labels.size != 1) return rejected("LIST_LABEL_COUNT=${labels.size}")
        val label = labels.single()
        if (!label.enabled || label.bounds.isEmpty || !contains(control, label)) {
            return rejected("LIST_LABEL_NOT_ENABLED_OR_WITHIN_CLOSE_CONTAINER")
        }
        if (!isInsideScreen(label.bounds, screenWidth, screenHeight) ||
            label.bounds.width <= 1 || label.bounds.height <= 1
        ) {
            return rejected("LIST_LABEL_HAS_NO_SAFE_TAP_REGION")
        }

        return MomoSayHiCardListTapDecision.Ready(
            labelPath = label.path.childIndices,
            controlPath = control.path.childIndices,
            tapBounds = label.bounds,
            controlBounds = control.bounds
        )
    }

    fun hasStableTargetPosition(
        firstTree: AccessibilityTreeSnapshot,
        first: MomoSayHiCardListTapDecision.Ready,
        latestTree: AccessibilityTreeSnapshot,
        latest: MomoSayHiCardListTapDecision.Ready
    ): Boolean {
        if (firstTree.truncated || latestTree.truncated ||
            firstTree.window != latestTree.window || firstTree.windowId != latestTree.windowId ||
            latestTree.capturedAtEpochMs < firstTree.capturedAtEpochMs
        ) return false
        return first.labelPath == latest.labelPath &&
            first.controlPath == latest.controlPath &&
            first.tapBounds == latest.tapBounds &&
            first.controlBounds == latest.controlBounds
    }

    private fun contains(parent: AccessibilityNodeSnapshot, child: AccessibilityNodeSnapshot): Boolean =
        parent.bounds.left <= child.bounds.left && parent.bounds.top <= child.bounds.top &&
            parent.bounds.right >= child.bounds.right && parent.bounds.bottom >= child.bounds.bottom

    private fun isInsideScreen(bounds: ScreenRect, width: Int, height: Int): Boolean =
        !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= width && bounds.bottom <= height

    private fun isSameOrDescendant(path: List<Int>, ancestor: List<Int>): Boolean =
        path.size >= ancestor.size && path.take(ancestor.size) == ancestor

    private fun rejected(reason: String) = MomoSayHiCardListTapDecision.Rejected(reason)
}
