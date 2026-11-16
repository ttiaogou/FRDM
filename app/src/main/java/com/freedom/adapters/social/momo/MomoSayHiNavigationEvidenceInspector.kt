package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.query.NodeTreeIndex

/**
 * Read-only structural evidence for navigating the Momo received-greetings flow and locating
 * message-interception controls. It deliberately returns evidence only; callers must not treat
 * these candidates as approved click targets until a separate, page-specific policy verifies them.
 */
internal data class MomoNavigationTargetEvidence(
    val path: List<Int>,
    val className: String,
    val viewId: String,
    val bounds: ScreenRect,
    val visible: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val clickableAncestorPath: List<Int>? = null,
    val clickableAncestorViewId: String? = null,
    val clickableAncestorClassName: String? = null,
    val hasReceivedGreetingsLabel: Boolean = false,
    val hasListRelatedLabel: Boolean = false,
    val relatedToInterceptLabel: Boolean = false
) {
    fun structuralKey(): String = listOf(
        path.joinToString("/"), className, viewId,
        bounds.left, bounds.top, bounds.right, bounds.bottom,
        visible, enabled, clickable, checkable, checked,
        clickableAncestorPath?.joinToString("/").orEmpty(), clickableAncestorViewId.orEmpty(),
        clickableAncestorClassName.orEmpty(),
        hasReceivedGreetingsLabel, hasListRelatedLabel, relatedToInterceptLabel
    ).joinToString(":")
}

internal data class MomoSayHiNavigationEvidence(
    val activityClass: String,
    val visitedNodeCount: Int,
    val truncated: Boolean,
    val receivedGreetingsEntryTargets: List<MomoNavigationTargetEvidence>,
    val topRightActionTargets: List<MomoNavigationTargetEvidence>,
    val interceptLabelNodeCount: Int,
    val toggleTargets: List<MomoNavigationTargetEvidence>
) {
    fun structuralKey(): String = listOf(
        activityClass,
        visitedNodeCount,
        truncated,
        receivedGreetingsEntryTargets.joinToString("|") { it.structuralKey() },
        topRightActionTargets.joinToString("|") { it.structuralKey() },
        interceptLabelNodeCount,
        toggleTargets.joinToString("|") { it.structuralKey() }
    ).joinToString("#")
}

internal class MomoSayHiNavigationEvidenceInspector(
    private val maxCandidatesPerGroup: Int = 16
) {
    init {
        require(maxCandidatesPerGroup > 0)
    }

    fun inspect(
        tree: AccessibilityTreeSnapshot,
        screenWidth: Int,
        screenHeight: Int
    ): MomoSayHiNavigationEvidence {
        val index = NodeTreeIndex(tree)
        val visibleNodes = tree.nodes.filter { it.visible }
        val visibleWithBounds = visibleNodes.filter { hasValidBounds(it.bounds) }
        val receivedLabels = visibleNodes.filter { node ->
            textValues(node).any { it.contains(RECEIVED_GREETINGS_LABEL) }
        }
        val interceptLabels = visibleNodes.filter { node ->
            textValues(node).any { text -> INTERCEPT_LABEL_TOKENS.any(text::contains) }
        }
        val listLabels = visibleNodes.filter { node ->
            textValues(node).any { text -> LIST_LABEL_TOKENS.any(text::contains) }
        }

        val entryTargets = receivedLabels.map { label ->
            label.toEvidence(
                clickableAncestor = nearestClickableAncestor(label, index),
                hasReceivedGreetingsLabel = true,
                hasListRelatedLabel = false,
                relatedToInterceptLabel = false
            )
        }.distinctBy { it.path }.take(maxCandidatesPerGroup)

        val topRightTargets = if (screenWidth > 0 && screenHeight > 0) {
            visibleWithBounds.asSequence()
                .filter { node ->
                    node.clickable && node.enabled &&
                        node.bounds.left >= screenWidth * TOP_RIGHT_MIN_X_RATIO &&
                        node.bounds.centerY() <= screenHeight * TOP_RIGHT_MAX_Y_RATIO &&
                        node.bounds.width <= screenWidth * TOP_RIGHT_MAX_WIDTH_RATIO &&
                        node.bounds.height <= screenHeight * TOP_RIGHT_MAX_HEIGHT_RATIO
                }
                .sortedWith(
                    compareBy<AccessibilityNodeSnapshot> { it.bounds.centerY() }
                        .thenByDescending { it.bounds.centerX() }
                )
                .map { node ->
                    val relatedListLabel = listLabels.any { label ->
                        isSameOrDescendant(label.path.childIndices, node.path.childIndices) ||
                            isSameOrDescendant(node.path.childIndices, label.path.childIndices)
                    }
                    node.toEvidence(
                        clickableAncestor = node,
                        hasReceivedGreetingsLabel = false,
                        hasListRelatedLabel = relatedListLabel,
                        relatedToInterceptLabel = false
                    )
                }
                .distinctBy { it.path }
                .take(maxCandidatesPerGroup)
                .toList()
        } else emptyList()

        val toggles = visibleWithBounds.asSequence()
            .filter(::isToggleCandidate)
            .map { toggle ->
                val relatedToIntercept = interceptLabels.any { label ->
                    sharesMeaningfulAncestor(toggle, label)
                }
                toggle.toEvidence(
                    clickableAncestor = nearestClickableAncestor(toggle, index),
                    hasReceivedGreetingsLabel = false,
                    hasListRelatedLabel = false,
                    relatedToInterceptLabel = relatedToIntercept
                )
            }
            .distinctBy { it.path }
            .take(maxCandidatesPerGroup)
            .toList()

        return MomoSayHiNavigationEvidence(
            activityClass = tree.window.activityClass,
            visitedNodeCount = tree.visitedNodeCount,
            truncated = tree.truncated,
            receivedGreetingsEntryTargets = entryTargets,
            topRightActionTargets = topRightTargets,
            interceptLabelNodeCount = interceptLabels.size,
            toggleTargets = toggles
        )
    }

    private fun nearestClickableAncestor(
        node: AccessibilityNodeSnapshot,
        index: NodeTreeIndex
    ): AccessibilityNodeSnapshot? = (sequenceOf(node) + index.ancestorsOf(node.path).asSequence())
        .firstOrNull { candidate ->
            candidate.visible && candidate.clickable && candidate.enabled && hasValidBounds(candidate.bounds) &&
                candidate.bounds.left <= node.bounds.left &&
                candidate.bounds.top <= node.bounds.top &&
                candidate.bounds.right >= node.bounds.right &&
                candidate.bounds.bottom >= node.bounds.bottom
        }

    private fun isToggleCandidate(node: AccessibilityNodeSnapshot): Boolean {
        val className = node.className.substringAfterLast('.').lowercase()
        return node.checkable || className.contains("switch") ||
            className.contains("checkbox") || className.contains("togglebutton")
    }

    private fun textValues(node: AccessibilityNodeSnapshot): List<String> =
        listOfNotNull(node.text, node.contentDescription)
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)

    private fun hasValidBounds(bounds: ScreenRect): Boolean = !bounds.isEmpty

    private fun sharesMeaningfulAncestor(
        first: AccessibilityNodeSnapshot,
        second: AccessibilityNodeSnapshot
    ): Boolean {
        val firstPath = first.path.childIndices
        val secondPath = second.path.childIndices
        val commonDepth = firstPath.zip(secondPath).takeWhile { it.first == it.second }.size
        val sharesContainer = commonDepth > 0
        val sharesRowBand = first.bounds.top < second.bounds.bottom &&
            second.bounds.top < first.bounds.bottom
        return sharesContainer && sharesRowBand
    }

    private fun isSameOrDescendant(path: List<Int>, ancestor: List<Int>): Boolean =
        path.size >= ancestor.size && path.subList(0, ancestor.size) == ancestor

    private fun AccessibilityNodeSnapshot.toEvidence(
        clickableAncestor: AccessibilityNodeSnapshot? = null,
        hasReceivedGreetingsLabel: Boolean,
        hasListRelatedLabel: Boolean,
        relatedToInterceptLabel: Boolean
    ) = MomoNavigationTargetEvidence(
        path = path.childIndices,
        className = className,
        viewId = viewId,
        bounds = bounds,
        visible = visible,
        enabled = enabled,
        clickable = clickable,
        checkable = checkable,
        checked = checked,
        clickableAncestorPath = clickableAncestor?.path?.childIndices,
        clickableAncestorViewId = clickableAncestor?.viewId,
        clickableAncestorClassName = clickableAncestor?.className,
        hasReceivedGreetingsLabel = hasReceivedGreetingsLabel,
        hasListRelatedLabel = hasListRelatedLabel,
        relatedToInterceptLabel = relatedToInterceptLabel
    )

    private fun ScreenRect.centerX(): Int = left + width / 2
    private fun ScreenRect.centerY(): Int = top + height / 2

    private companion object {
        const val RECEIVED_GREETINGS_LABEL = "收到的招呼"
        val LIST_LABEL_TOKENS = setOf("列表", "list")
        val INTERCEPT_LABEL_TOKENS = setOf("拦截", "屏蔽消息", "消息拦截")
        const val TOP_RIGHT_MIN_X_RATIO = 0.68f
        const val TOP_RIGHT_MAX_Y_RATIO = 0.24f
        const val TOP_RIGHT_MAX_WIDTH_RATIO = 0.28f
        const val TOP_RIGHT_MAX_HEIGHT_RATIO = 0.16f
    }
}
