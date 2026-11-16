package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ScreenRect

/** A validated semantic target region for the received-greetings label. */
internal sealed class MomoReceivedGreetingsEntryTapDecision {
    data class Ready(
        val labelPath: List<Int>,
        val clickableAncestorPath: List<Int>,
        val tapBounds: ScreenRect,
        val clickableAncestorBounds: ScreenRect
    ) : MomoReceivedGreetingsEntryTapDecision()

    data class Rejected(val reason: String) : MomoReceivedGreetingsEntryTapDecision()
}

/**
 * Validates the received-greetings entry against one fresh accessibility tree.
 * A full-width row is allowed when it is within the screen. The uniquely identified label bounds
 * are returned as the preferred region; Gesture Policy Pipeline samples the final safe touch point.
 */
internal object MomoReceivedGreetingsEntryTapPolicy {
    private const val RECEIVED_GREETINGS_LABEL = "收到的招呼"
    private const val MAX_CLICK_ANCESTOR_HEIGHT_RATIO = 0.48f
    private val FORBIDDEN_ACTION_LABELS = setOf("拒绝", "通过", "接受")

    fun evaluate(
        tree: AccessibilityTreeSnapshot,
        evidence: MomoSayHiNavigationEvidence,
        screenWidth: Int,
        screenHeight: Int
    ): MomoReceivedGreetingsEntryTapDecision {
        if (tree.truncated) return rejected("ACCESSIBILITY_TREE_TRUNCATED")
        if (screenWidth <= 0 || screenHeight <= 0) return rejected("INVALID_SCREEN_METRICS")
        if (evidence.activityClass != tree.window.activityClass) {
            return rejected("EVIDENCE_ACTIVITY_MISMATCH")
        }

        val labels = evidence.receivedGreetingsEntryTargets
        if (labels.size != 1) return rejected("ENTRY_LABEL_COUNT=${labels.size}")
        val label = labels.single()
        if (!label.visible || !label.enabled || !label.hasReceivedGreetingsLabel || label.bounds.isEmpty) {
            return rejected("ENTRY_LABEL_NOT_VISIBLE_ENABLED_OR_BOUNDED")
        }
        if (!isInsideScreen(label.bounds, screenWidth, screenHeight)) {
            return rejected("ENTRY_LABEL_OUTSIDE_SCREEN")
        }

        val labelNode = tree.nodes.singleOrNull { it.path.childIndices == label.path }
            ?: return rejected("ENTRY_LABEL_NODE_NOT_UNIQUE_IN_CURRENT_TREE")
        if (!labelNode.visible || !labelNode.enabled || !hasReceivedGreetingsText(labelNode)) {
            return rejected("ENTRY_LABEL_NODE_NO_LONGER_MATCHES_EVIDENCE")
        }

        val ancestorPath = label.clickableAncestorPath
            ?: return rejected("ENTRY_HAS_NO_CLICKABLE_ANCESTOR")
        val ancestorNode = tree.nodes.singleOrNull { it.path.childIndices == ancestorPath }
            ?: return rejected("CLICKABLE_ANCESTOR_NOT_UNIQUE_IN_CURRENT_TREE")
        if (!ancestorNode.visible || !ancestorNode.enabled || !ancestorNode.clickable ||
            ancestorNode.bounds.isEmpty
        ) {
            return rejected("CLICKABLE_ANCESTOR_NOT_VISIBLE_ENABLED_CLICKABLE")
        }
        if (!isSameOrDescendant(label.path, ancestorPath)) {
            return rejected("ENTRY_LABEL_NOT_INSIDE_CLICKABLE_ANCESTOR")
        }
        if (!contains(ancestorNode, labelNode)) {
            return rejected("CLICKABLE_ANCESTOR_DOES_NOT_CONTAIN_ENTRY_LABEL")
        }
        if (!isInsideScreen(ancestorNode.bounds, screenWidth, screenHeight)) {
            return rejected("CLICKABLE_ANCESTOR_OUTSIDE_SCREEN")
        }
        // A row may legitimately span the complete screen width (720/720 in the supplied
        // device trace). Screen containment is the bound; there is no 96%-width rejection.
        if (ancestorNode.bounds.height > screenHeight * MAX_CLICK_ANCESTOR_HEIGHT_RATIO) {
            return rejected("CLICKABLE_ANCESTOR_TOO_TALL")
        }

        val containsForbiddenAction = tree.nodes.any { node ->
            node.visible && isSameOrDescendant(node.path.childIndices, ancestorPath) &&
                hasForbiddenActionText(node)
        }
        if (containsForbiddenAction) return rejected("CLICKABLE_ANCESTOR_CONTAINS_FORBIDDEN_ACTION")

        if (label.bounds.isEmpty || label.bounds.width <= 1 || label.bounds.height <= 1) {
            return rejected("LABEL_HAS_NO_SAFE_INNER_TAP_REGION")
        }

        return MomoReceivedGreetingsEntryTapDecision.Ready(
            labelPath = label.path,
            clickableAncestorPath = ancestorPath,
            tapBounds = label.bounds,
            clickableAncestorBounds = ancestorNode.bounds
        )
    }

    /**
     * Requires two independently reacquired trees to identify the same unique semantic label at
     * the same label and clickable-parent geometry. A list move or page transition between reads
     * invalidates the candidate; callers must wait for a later fresh inspection instead of using
     * either snapshot's old coordinates.
     */
    fun hasStableTargetPosition(
        firstTree: AccessibilityTreeSnapshot,
        first: MomoReceivedGreetingsEntryTapDecision.Ready,
        latestTree: AccessibilityTreeSnapshot,
        latest: MomoReceivedGreetingsEntryTapDecision.Ready
    ): Boolean {
        if (firstTree.truncated || latestTree.truncated ||
            firstTree.window != latestTree.window || firstTree.windowId != latestTree.windowId ||
            latestTree.capturedAtEpochMs < firstTree.capturedAtEpochMs
        ) return false
        return first.labelPath == latest.labelPath &&
            first.clickableAncestorPath == latest.clickableAncestorPath &&
            first.tapBounds == latest.tapBounds &&
            first.clickableAncestorBounds == latest.clickableAncestorBounds
    }

    private fun hasReceivedGreetingsText(node: AccessibilityNodeSnapshot): Boolean =
        listOfNotNull(node.text, node.contentDescription).any {
            it.contains(RECEIVED_GREETINGS_LABEL)
        }

    private fun hasForbiddenActionText(node: AccessibilityNodeSnapshot): Boolean =
        listOfNotNull(node.text, node.contentDescription).any { text ->
            FORBIDDEN_ACTION_LABELS.any(text::contains)
        }

    private fun contains(ancestor: AccessibilityNodeSnapshot, child: AccessibilityNodeSnapshot): Boolean =
        ancestor.bounds.left <= child.bounds.left && ancestor.bounds.top <= child.bounds.top &&
            ancestor.bounds.right >= child.bounds.right && ancestor.bounds.bottom >= child.bounds.bottom

    private fun isInsideScreen(bounds: com.freedom.core.accessibility.api.ScreenRect, width: Int, height: Int): Boolean =
        !bounds.isEmpty && bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= width && bounds.bottom <= height

    private fun isSameOrDescendant(path: List<Int>, ancestor: List<Int>): Boolean =
        path.size >= ancestor.size && path.take(ancestor.size) == ancestor

    private fun rejected(reason: String) = MomoReceivedGreetingsEntryTapDecision.Rejected(reason)
}
