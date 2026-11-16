package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import java.util.ArrayDeque

internal data class MomoChatNodeTreeDiagnostic(
    val evidence: List<MomoChatNodeEvidence>,
    val visitedNodeCount: Int,
    val walkTruncated: Boolean,
    val evidenceTruncated: Boolean,
    val snapshot: AccessibilityTreeSnapshot?
) {
    /** Compatibility summary for existing page-observation consumers. */
    val truncated: Boolean get() = walkTruncated || evidenceTruncated
}

/**
 * Read-only, bounded node-tree probe used to collect structural evidence for Momo's main inbox
 * and chat-detail layouts. It also retains unrelated RecyclerViews so the visualizer can show
 * their actual screen regions and ancestor hierarchy without treating them as production locators.
 * It records no node text, descriptions, contact identity, or message payload.
 * Alternate list roots are diagnostic candidates only; they are not production message locators.
 */
internal class MomoChatNodeTreeDiagnosticInspector(
    private val maxNodes: Int = MAX_NODES,
    private val maxEvidenceNodes: Int = MAX_EVIDENCE_NODES,
    private val maxDurationMs: Long = 350L,
    private val maxDepth: Int = 32
) {
    init {
        require(maxNodes > 0 && maxEvidenceNodes > 0)
        require(maxDurationMs in 1L..2_000L && maxDepth in 1..64)
    }
    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val path: List<Int>,
        val insideListProbe: Boolean
    )

    fun inspectRoot(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        activityClass: String,
        window: WindowRef
    ): MomoChatNodeTreeDiagnostic {
        val windowPackage = safeString { root.packageName?.toString().orEmpty() }
        val rootWindowId = safeInt { root.windowId }
        if (windowPackage != targetPackage ||
            activityClass !in setOf(
                MomoChatPageContract.MAIN_ACTIVITY,
                MomoChatPageContract.CHAT_DETAIL_ACTIVITY
            )
        ) {
            recycle(root)
            return MomoChatNodeTreeDiagnostic(emptyList(), 0, false, false, null)
        }

        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(root, emptyList(), false))
        val listEvidence = ArrayList<MomoChatNodeEvidence>()
        val nodeSnapshots = ArrayList<AccessibilityNodeSnapshot>(maxNodes)
        var visited = 0
        var walkTruncated = false
        var evidenceTruncated = false
        val deadlineNanos = System.nanoTime() + maxDurationMs * 1_000_000L
        var timeBudgetExceeded = false
        var depthBudgetExceeded = false

        try {
            while (queue.isNotEmpty() && visited < maxNodes && System.nanoTime() < deadlineNanos) {
                val pending = queue.removeFirst()
                val node = pending.node
                visited++
                try {
                    val className = safeString { node.className?.toString().orEmpty() }
                    val viewId = safeString { node.viewIdResourceName.orEmpty() }
                    // Avoid spending the bounded diagnostic scan budget on the delayed inbox
                    // overlay; it is not a conversation-list node or a page-identity anchor.
                    if (activityClass == MomoChatPageContract.MAIN_ACTIVITY &&
                        MomoChatListSessionPolicy.isAiFinderFloatingView(viewId)
                    ) {
                        visited--
                        continue
                    }
                    val isCanonicalListRoot = className == MomoChatPageContract.MESSAGE_LIST_CLASS_NAME &&
                        viewId == MomoChatPageContract.MESSAGE_LIST_VIEW_ID
                    val isAlternateListRoot = MomoChatNodeDiagnosticPolicy.isAlternateMessageListRoot(
                        activityClass = activityClass,
                        className = className,
                        viewId = viewId
                    )
                    val insideListProbe = pending.insideListProbe ||
                        isCanonicalListRoot || isAlternateListRoot
                    val rowCandidate = MomoChatNodeDiagnosticPolicy.isGreetingMessageRowCandidate(viewId)
                    val childCount = safeInt { node.childCount }
                    val bounds = Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val visible = safeBoolean { node.isVisibleToUser }
                    val enabled = safeBoolean { node.isEnabled }
                    val checkable = safeBoolean { node.isCheckable }
                    val checked = safeBoolean { node.isChecked }
                    val clickable = safeBoolean { node.isClickable }
                    val longClickable = safeBoolean { node.isLongClickable }
                    val scrollable = safeBoolean { node.isScrollable }
                    val editable = safeBoolean { node.isEditable }
                    val selected = safeBoolean { node.isSelected }
                    val focused = safeBoolean { node.isFocused }
                    val windowId = safeInt { node.windowId }.takeIf { it >= 0 } ?: rootWindowId
                    val actions = runCatching {
                        node.actionList.map { action ->
                            com.freedom.core.accessibility.api.AccessibilityActionSnapshot(
                                id = action.id,
                                label = null
                            )
                        }
                    }.getOrDefault(emptyList())
                    nodeSnapshots += AccessibilityNodeSnapshot(
                        path = NodePath(pending.path),
                        className = className,
                        viewId = viewId,
                        text = null,
                        contentDescription = null,
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
                        childCount = childCount,
                        packageName = safeString { node.packageName?.toString().orEmpty() },
                        windowId = windowId,
                        indexInParent = pending.path.lastOrNull() ?: -1,
                        focusable = safeBoolean { node.isFocusable },
                        contextClickable = safeBoolean { node.isContextClickable },
                        drawingOrder = safeInt { node.drawingOrder },
                        password = safeBoolean { node.isPassword },
                        actions = actions
                    )

                    if (insideListProbe && listEvidence.size < maxEvidenceNodes) {
                        listEvidence += MomoChatNodeEvidence(
                            path = pending.path.joinToString("/"),
                            className = className,
                            viewId = viewId,
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                            childCount = childCount,
                            isEditable = editable,
                            isScrollable = scrollable,
                            isClickable = clickable,
                            hasText = safeString { node.text?.toString().orEmpty() }.isNotBlank() ||
                                safeString { node.contentDescription?.toString().orEmpty() }.isNotBlank(),
                            insideListCandidate = true,
                            depth = pending.path.size,
                            parentPath = pending.path.dropLast(1).joinToString("/"),
                            isVisibleToUser = visible,
                            isListProbeRoot = isCanonicalListRoot || isAlternateListRoot,
                            isAlternateListProbe = isAlternateListRoot,
                            isMessageRowCandidate = rowCandidate
                        )
                    } else if (insideListProbe) {
                        evidenceTruncated = true
                    }

                    if (pending.path.size >= maxDepth && childCount > 0) {
                        depthBudgetExceeded = true
                        walkTruncated = true
                    } else for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos) {
                            timeBudgetExceeded = true
                            walkTruncated = true
                            break
                        }
                        if (visited + queue.size >= maxNodes) {
                            walkTruncated = true
                            break
                        }
                        safeNode { node.getChild(index) }?.let { child ->
                            queue.add(PendingNode(child, pending.path + index, insideListProbe))
                        }
                    }
                } finally {
                    recycle(node)
                }
            }
            if (queue.isNotEmpty()) {
                walkTruncated = true
                if (System.nanoTime() >= deadlineNanos) timeBudgetExceeded = true
            }
        } finally {
            while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
        }

        val snapshot = AccessibilityTreeSnapshot(
            window = window,
            nodes = nodeSnapshots,
            visitedNodeCount = visited,
            truncated = walkTruncated,
            requestedMaxNodes = maxNodes,
            requestedMaxDepth = maxDepth,
            walkTruncated = walkTruncated,
            depthTruncated = depthBudgetExceeded,
            windowId = nodeSnapshots.firstOrNull()?.windowId ?: -1,
            stopReason = when {
                timeBudgetExceeded -> "TIME_BUDGET"
                depthBudgetExceeded -> "DEPTH_BUDGET"
                walkTruncated -> "NODE_BUDGET"
                else -> null
            },
            timeBudgetExceeded = timeBudgetExceeded,
            requestedMaxDurationMs = maxDurationMs
        )
        return MomoChatNodeTreeDiagnostic(
            evidence = listEvidence,
            visitedNodeCount = visited,
            walkTruncated = walkTruncated,
            evidenceTruncated = evidenceTruncated,
            snapshot = snapshot
        )
    }

    private fun recycle(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }

    private inline fun safeString(block: () -> String?): String =
        runCatching { block().orEmpty() }.getOrDefault("")
    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

    companion object {
        const val MAX_NODES = 480
        const val MAX_EVIDENCE_NODES = 96
    }
}
