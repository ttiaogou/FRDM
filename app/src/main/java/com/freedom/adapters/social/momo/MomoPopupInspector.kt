package com.freedom.adapters.social.momo

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Read-only popup inspection with one total monotonic time budget across candidate discovery,
 * text extraction, close-target search and advertising-cue inspection. When the budget is
 * exhausted, it returns an explicit incomplete/blocked result rather than interpreting a partial
 * tree as proof that no blocking popup exists.
 */
internal class MomoPopupInspector(
    private val context: Context,
    private val maxScanNodes: Int,
    private val findClickableAncestor: (AccessibilityNodeInfo, Rect) -> AccessibilityNodeInfo?,
    private val maxScanDurationMs: Long = 800L,
    // Momo's profile/card view hierarchy can exceed 24 wrapper levels. Keep the traversal
    // bounded by the existing node and wall-clock budgets, but allow realistic nested pages.
    private val maxScanDepth: Int = MomoPopupScanDepthPolicy.DEFAULT_MAX_SCAN_DEPTH
) {
    init {
        require(maxScanNodes in 1..20_000)
        require(maxScanDurationMs in 1L..2_000L)
        require(maxScanDepth in 1..64)
    }

    private data class PendingNode(val node: AccessibilityNodeInfo, val depth: Int)
    private data class TextScanResult(val values: List<String>, val complete: Boolean)
    private data class CloseSearchResult(
        val inspection: PopupCloseTargetInspection?,
        val complete: Boolean
    )
    internal data class SafetyInspectionResult(
        val complete: Boolean,
        val blockingReason: String? = null,
        val visitedNodes: Int = 0,
        val elapsedMs: Long = 0L,
        val stopReason: String? = null,
        val popupCandidateCount: Int = 0,
        val expectedCardDeckCandidatesIgnored: Int = 0
    )

    private data class SafetyCandidate(
        val container: AccessibilityNodeInfo,
        val score: Int,
        val texts: MutableList<String> = ArrayList(),
        var closeTarget: AccessibilityNodeInfo? = null
    )

    private data class SafetyPendingNode(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val candidateIndexes: List<Int>,
        val insideExpectedSayHiCardDeck: Boolean,
        val insideKnownChatInlineCard: Boolean
    )

    fun inspect(root: AccessibilityNodeInfo): PopupInspection? =
        inspectWithDeadline(root, deadlineAfter(maxScanDurationMs))

    private fun inspectWithDeadline(root: AccessibilityNodeInfo, deadlineNanos: Long): PopupInspection? {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val queue = ArrayDeque<PendingNode>()
        val candidates = ArrayList<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(PendingNode(AccessibilityNodeInfo.obtain(root), 0))
        var visited = 0
        var incompleteReason: String? = null

        try {
            while (queue.isNotEmpty() && visited < maxScanNodes && System.nanoTime() < deadlineNanos) {
                val pending = queue.removeFirst()
                val node = pending.node
                visited++
                try {
                    if (node.isVisibleToUser) {
                        val className = node.className?.toString().orEmpty()
                        val simpleClass = className.substringAfterLast('.')
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        val dismissable = runCatching { node.isDismissable }.getOrDefault(false)
                        val dialogClass = simpleClass.contains("Dialog", ignoreCase = true) ||
                            simpleClass.contains("Popup", ignoreCase = true) ||
                            simpleClass.contains("BottomSheet", ignoreCase = true)
                        val centeredOverlay = bounds.width() >= (screenWidth * 0.55f).toInt() &&
                            bounds.width() < (screenWidth * 0.99f).toInt() &&
                            bounds.height() >= (screenHeight * 0.16f).toInt() &&
                            bounds.height() < (screenHeight * 0.88f).toInt() &&
                            kotlin.math.abs(bounds.centerX() - screenWidth / 2) <= (screenWidth * 0.20f).toInt() &&
                            bounds.centerY() in (screenHeight * 0.20f).toInt()..(screenHeight * 0.82f).toInt()
                        val score = when {
                            dismissable -> 100
                            dialogClass -> 90
                            centeredOverlay -> 20
                            else -> 0
                        }
                        if (score > 0 && candidates.size < 12) {
                            candidates.add(AccessibilityNodeInfo.obtain(node) to score)
                        }
                    }

                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    if (MomoPopupScanDepthPolicy.mustStopAt(pending.depth, childCount, maxScanDepth)) {
                        incompleteReason = "节点深度预算不足，无法完整检查弹层"
                    } else {
                        for (index in 0 until childCount) {
                            if (System.nanoTime() >= deadlineNanos) {
                                incompleteReason = "弹层节点检索超过时间预算"
                                break
                            }
                            if (visited + queue.size >= maxScanNodes) {
                                incompleteReason = "弹层节点检索超过节点预算"
                                break
                            }
                            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                            queue.add(PendingNode(child, pending.depth + 1))
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
                if (incompleteReason != null) break
            }
            if (incompleteReason == null && queue.isNotEmpty()) {
                incompleteReason = when {
                    System.nanoTime() >= deadlineNanos -> "弹层节点检索超过时间预算"
                    visited >= maxScanNodes -> "弹层节点检索超过节点预算"
                    else -> "弹层节点检索未完整结束"
                }
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().node.recycle() }
        }

        val ordered = candidates.sortedByDescending { it.second }
        if (incompleteReason != null) {
            recycleCandidates(ordered)
            return incompleteInspection(incompleteReason)
        }

        for ((container, score) in ordered) {
            if (System.nanoTime() >= deadlineNanos) {
                recycleCandidates(ordered)
                return incompleteInspection("弹层内容检索超过总时间预算")
            }
            val textScan = collectPopupTexts(container, deadlineNanos)
            if (!textScan.complete) {
                recycleCandidates(ordered)
                return incompleteInspection("弹层文本检索未能在时间/节点/深度预算内完成")
            }
            val texts = textScan.values
            val joinedText = texts.joinToString(" ").take(360)
            val closeSearch = findPopupCloseTarget(container, deadlineNanos)
            if (!closeSearch.complete) {
                recycleCandidates(ordered)
                closeSearch.inspection?.target?.let { runCatching { it.recycle() } }
                return incompleteInspection("弹层关闭目标检索未能在预算内完成")
            }
            val closeInspection = closeSearch.inspection
            val closeTarget = closeInspection?.target
            val popupCue = texts.any { text ->
                POPUP_CONTENT_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
            }
            val advertising = texts.any { text ->
                ADVERTISING_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
            }
            val hasModalSemantics = score >= 90
            if (!hasModalSemantics && (!popupCue || closeTarget == null)) {
                // Candidate containers are recycled together in recycleCandidates(); avoid
                // recycling the same AccessibilityNodeInfo twice on this rejection path.
                runCatching { closeTarget?.recycle() }
                continue
            }

            val critical = texts.any { text ->
                POPUP_CRITICAL_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
            }
            val dismissable = runCatching { container.isDismissable }.getOrDefault(false)
            val bounds = Rect()
            runCatching { container.getBoundsInScreen(bounds) }
            val description = joinedText.ifBlank {
                "${container.className?.toString()?.substringAfterLast('.') ?: "未知弹窗"}，区域=${bounds.toShortString()}"
            }
            val signature = listOf(
                container.className?.toString().orEmpty(),
                bounds.toShortString(),
                texts.take(5).joinToString("|")
            ).joinToString(":").hashCode().toString()
            recycleCandidates(ordered, keep = container)
            return PopupInspection(
                container = container,
                closeTarget = closeTarget,
                closeTapBounds = closeInspection?.tapBounds,
                dismissable = dismissable,
                critical = critical,
                signature = signature,
                description = description,
                advertising = advertising
            )
        }

        recycleCandidates(ordered)
        return null
    }

    private fun collectPopupTexts(
        container: AccessibilityNodeInfo,
        deadlineNanos: Long
    ): TextScanResult {
        val result = ArrayList<String>()
        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(AccessibilityNodeInfo.obtain(container), 0))
        var visited = 0
        var complete = true
        try {
            while (queue.isNotEmpty() && visited < MAX_DETAIL_SCAN_NODES && System.nanoTime() < deadlineNanos) {
                val pending = queue.removeFirst()
                val node = pending.node
                visited++
                try {
                    if (node.isVisibleToUser) {
                        val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                        val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                            .getOrDefault("")
                        for (value in listOf(text, description)) {
                            if (value.isNotBlank() && value !in result && result.size < 24) result.add(value.take(100))
                        }
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    if (MomoPopupScanDepthPolicy.mustStopAt(pending.depth, childCount, maxScanDepth)) complete = false
                    else for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos || visited + queue.size >= MAX_DETAIL_SCAN_NODES) {
                            complete = false
                            break
                        }
                        runCatching { node.getChild(index) }.getOrNull()?.let {
                            queue.add(PendingNode(it, pending.depth + 1))
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
                if (!complete) break
            }
            if (queue.isNotEmpty() || System.nanoTime() >= deadlineNanos || visited >= MAX_DETAIL_SCAN_NODES && queue.isNotEmpty()) {
                complete = false
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().node.recycle() }
        }
        return TextScanResult(result, complete)
    }

    private data class PopupCloseTargetInspection(val target: AccessibilityNodeInfo, val tapBounds: Rect)
    private fun findPopupCloseTarget(
        container: AccessibilityNodeInfo,
        deadlineNanos: Long
    ): CloseSearchResult {
        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(AccessibilityNodeInfo.obtain(container), 0))
        var visited = 0
        var complete = true
        try {
            while (queue.isNotEmpty() && visited < MAX_DETAIL_SCAN_NODES && System.nanoTime() < deadlineNanos) {
                val pending = queue.removeFirst()
                val node = pending.node
                visited++
                try {
                    if (node.isVisibleToUser) {
                        val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault("")
                        val description = runCatching { node.contentDescription?.toString()?.trim().orEmpty() }
                            .getOrDefault("")
                        val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
                        val label = sequenceOf(text, description).firstOrNull { it.isNotBlank() }.orEmpty()
                        val safeLabel = POPUP_SAFE_CLOSE_LABELS.any { label.equals(it, ignoreCase = true) }
                        val safeId = viewId.substringAfterLast('/').let { id ->
                            id.contains("close", ignoreCase = true) || id.contains("dismiss", ignoreCase = true) ||
                                id.contains("cancel", ignoreCase = true)
                        }
                        val dangerousLabel = POPUP_CRITICAL_CUES.any { label.contains(it, ignoreCase = true) }
                        if ((safeLabel || safeId) && !dangerousLabel) {
                            val bounds = Rect()
                            runCatching { node.getBoundsInScreen(bounds) }
                            val target = findClickableAncestor(node, bounds)
                            if (target != null && !bounds.isEmpty) {
                                return CloseSearchResult(PopupCloseTargetInspection(target, Rect(bounds)), true)
                            }
                            runCatching { target?.recycle() }
                        }
                    }
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    if (MomoPopupScanDepthPolicy.mustStopAt(pending.depth, childCount, maxScanDepth)) complete = false
                    else for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos || visited + queue.size >= MAX_DETAIL_SCAN_NODES) {
                            complete = false
                            break
                        }
                        runCatching { node.getChild(index) }.getOrNull()?.let {
                            queue.add(PendingNode(it, pending.depth + 1))
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
                if (!complete) break
            }
            if (queue.isNotEmpty() || System.nanoTime() >= deadlineNanos) complete = false
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().node.recycle() }
        }
        return CloseSearchResult(null, complete)
    }

    /**
     * Performs one dedicated safety traversal of the fresh active-window root.
     * Advertising cues, modal candidates, descendant text and safe-close evidence are collected
     * during the same bounded walk. The previous implementation traversed the complete tree once
     * for advertising and immediately traversed it again for popup candidates under the same
     * deadline; a normal 200-300 node chat list could therefore exhaust its own budget.
     */
    fun inspectBlockingOverlay(
        root: AccessibilityNodeInfo,
        currentActivity: String? = null
    ): SafetyInspectionResult {
        val startedAtNanos = System.nanoTime()
        val deadlineNanos = deadlineAfter(maxScanDurationMs)
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val queue = ArrayDeque<SafetyPendingNode>()
        val candidates = ArrayList<SafetyCandidate>()
        queue.add(SafetyPendingNode(AccessibilityNodeInfo.obtain(root), 0, emptyList(), false, false))
        var visited = 0
        var stopReason: String? = null
        var launchAdCue: String? = null
        var expectedCardDeckCandidatesIgnored = 0

        try {
            while (queue.isNotEmpty()) {
                if (visited >= maxScanNodes) {
                    stopReason = "POPUP_NODE_BUDGET"
                    break
                }
                if (System.nanoTime() >= deadlineNanos) {
                    stopReason = "POPUP_TIME_BUDGET"
                    break
                }

                val pending = queue.removeFirst()
                val node = pending.node
                visited++
                try {
                    val visible = runCatching { node.isVisibleToUser }.getOrElse {
                        stopReason = "NODE_VISIBILITY_READ_FAILED"
                        false
                    }
                    var activeCandidateIndexes = pending.candidateIndexes
                    var insideExpectedSayHiCardDeck = pending.insideExpectedSayHiCardDeck
                    var insideKnownChatInlineCard = pending.insideKnownChatInlineCard

                    if (visible && stopReason == null) {
                        val text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrElse {
                            stopReason = "NODE_TEXT_READ_FAILED"
                            ""
                        }
                        val description = runCatching {
                            node.contentDescription?.toString()?.trim().orEmpty()
                        }.getOrElse {
                            stopReason = "NODE_DESCRIPTION_READ_FAILED"
                            ""
                        }
                        val className = runCatching { node.className?.toString().orEmpty() }.getOrElse {
                            stopReason = "NODE_CLASS_READ_FAILED"
                            ""
                        }
                        val viewId = runCatching { node.viewIdResourceName.orEmpty() }.getOrElse {
                            stopReason = "NODE_VIEW_ID_READ_FAILED"
                            ""
                        }
                        val bounds = Rect()
                        if (!runCatching { node.getBoundsInScreen(bounds) }.isSuccess) {
                            stopReason = "NODE_BOUNDS_READ_FAILED"
                        }
                        val dismissable = runCatching { node.isDismissable }.getOrElse {
                            stopReason = "NODE_DISMISSABLE_READ_FAILED"
                            false
                        }
                        insideKnownChatInlineCard =
                            MomoPopupCandidatePolicy.isInsideKnownChatInlineCard(
                                activityClass = currentActivity,
                                inherited = pending.insideKnownChatInlineCard,
                                text = text,
                                description = description
                            )
                        insideExpectedSayHiCardDeck =
                            MomoPopupCandidatePolicy.isInsideExpectedSayHiCardDeck(
                                activityClass = currentActivity,
                                inherited = pending.insideExpectedSayHiCardDeck,
                                viewId = viewId
                            )

                        val textForCues = (text + " " + description).trim().take(240)
                        if (launchAdCue == null) {
                            launchAdCue = VISIBLE_LAUNCH_AD_CUES.firstOrNull { cue ->
                                textForCues.contains(cue, ignoreCase = true)
                            }
                        }

                        val simpleClass = className.substringAfterLast('.')
                        val dialogClass = simpleClass.contains("Dialog", ignoreCase = true) ||
                            simpleClass.contains("Popup", ignoreCase = true) ||
                            simpleClass.contains("BottomSheet", ignoreCase = true)
                        val centeredOverlay = bounds.width() >= (screenWidth * 0.55f).toInt() &&
                            bounds.width() < (screenWidth * 0.99f).toInt() &&
                            bounds.height() >= (screenHeight * 0.16f).toInt() &&
                            bounds.height() < (screenHeight * 0.88f).toInt() &&
                            kotlin.math.abs(bounds.centerX() - screenWidth / 2) <=
                                (screenWidth * 0.20f).toInt() &&
                            bounds.centerY() in
                                (screenHeight * 0.20f).toInt()..
                                (screenHeight * 0.82f).toInt()
                        val nodePackageName = runCatching { node.packageName?.toString().orEmpty() }.getOrDefault("")
                        val score = if (MomoPopupCandidatePolicy.isFreedomOwnedOverlayPackage(nodePackageName)) {
                            // Freedom's own floating action button is not a Momo modal. Keep walking
                            // its descendants for completeness, but do not count it as a popup.
                            0
                        } else if (currentActivity == "com.immomo.momo.message.activity.ChatActivity") {
                            MomoPopupCandidatePolicy.chatCandidateScore(
                                insideKnownInlineCard = insideKnownChatInlineCard,
                                dismissable = dismissable,
                                explicitDismissControl = viewId.substringAfterLast('/').let { id ->
                                    id.contains("close", true) || id.contains("dismiss", true) ||
                                        id.contains("cancel", true)
                                } || textForCues.contains("关闭") || textForCues.contains("取消"),
                                dialogClass = dialogClass,
                                centeredOverlay = centeredOverlay
                            )
                        } else {
                            MomoPopupCandidatePolicy.candidateScore(
                                activityClass = currentActivity,
                                insideExpectedSayHiCardDeck = insideExpectedSayHiCardDeck,
                                viewId = viewId,
                                dismissable = dismissable,
                                dialogClass = dialogClass,
                                centeredOverlay = centeredOverlay
                            )
                        }
                        if (score == 0 && MomoPopupCandidatePolicy.isExpectedDeckCandidateSuppressed(
                                activityClass = currentActivity,
                                insideExpectedSayHiCardDeck = insideExpectedSayHiCardDeck,
                                viewId = viewId,
                                dismissable = dismissable,
                                centeredOverlay = centeredOverlay,
                                dialogClass = dialogClass
                            )
                        ) {
                            expectedCardDeckCandidatesIgnored++
                        }

                        if (score > 0) {
                            if (candidates.size >= MAX_SAFETY_CANDIDATES) {
                                stopReason = "POPUP_CANDIDATE_BUDGET"
                            } else {
                                val candidateIndex = candidates.size
                                candidates.add(
                                    SafetyCandidate(
                                        container = AccessibilityNodeInfo.obtain(node),
                                        score = score
                                    )
                                )
                                activeCandidateIndexes = pending.candidateIndexes + candidateIndex
                            }
                        }

                        if (stopReason == null && activeCandidateIndexes.isNotEmpty()) {
                            for (candidateIndex in activeCandidateIndexes) {
                                val candidate = candidates[candidateIndex]
                                if (!appendSafetyText(candidate.texts, text) ||
                                    !appendSafetyText(candidate.texts, description)
                                ) {
                                    stopReason = "POPUP_TEXT_BUDGET"
                                    break
                                }
                            }

                            val label = sequenceOf(text, description).firstOrNull { it.isNotBlank() }.orEmpty()
                            val safeLabel = POPUP_SAFE_CLOSE_LABELS.any { label.equals(it, ignoreCase = true) }
                            val safeId = viewId.substringAfterLast('/').let { id ->
                                id.contains("close", ignoreCase = true) ||
                                    id.contains("dismiss", ignoreCase = true) ||
                                    id.contains("cancel", ignoreCase = true)
                            }
                            val dangerousLabel = POPUP_CRITICAL_CUES.any {
                                label.contains(it, ignoreCase = true)
                            }
                            if (stopReason == null && (safeLabel || safeId) && !dangerousLabel && !bounds.isEmpty) {
                                activeCandidateIndexes.forEach { candidateIndex ->
                                    val candidate = candidates[candidateIndex]
                                    if (candidate.closeTarget == null && stopReason == null) {
                                        val targetResult = runCatching {
                                            findClickableAncestor(node, Rect(bounds))
                                        }
                                        if (targetResult.isFailure) {
                                            stopReason = "POPUP_CLOSE_TARGET_READ_FAILED"
                                        } else {
                                            candidate.closeTarget = targetResult.getOrNull()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (stopReason == null) {
                        val childCount = runCatching { node.childCount }.getOrElse {
                            stopReason = "NODE_CHILD_COUNT_READ_FAILED"
                            0
                        }
                        if (MomoPopupScanDepthPolicy.mustStopAt(pending.depth, childCount, maxScanDepth)) {
                            stopReason = "POPUP_DEPTH_BUDGET"
                        } else {
                            for (index in 0 until childCount) {
                                if (visited + queue.size >= maxScanNodes) {
                                    stopReason = "POPUP_NODE_BUDGET"
                                    break
                                }
                                if (System.nanoTime() >= deadlineNanos) {
                                    stopReason = "POPUP_TIME_BUDGET"
                                    break
                                }
                                val child = runCatching { node.getChild(index) }.getOrNull()
                                if (child == null) {
                                    stopReason = "CHILD_NODE_READ_FAILED"
                                    break
                                }
                                queue.add(
                                    SafetyPendingNode(
                                        node = child,
                                        depth = pending.depth + 1,
                                        candidateIndexes = activeCandidateIndexes,
                                        insideExpectedSayHiCardDeck = insideExpectedSayHiCardDeck,
                                        insideKnownChatInlineCard = insideKnownChatInlineCard
                                    )
                                )
                            }
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
                if (stopReason != null || launchAdCue != null) break
            }
        } finally {
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().node.recycle() }
        }

        val elapsedMs = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
        if (launchAdCue != null) {
            recycleSafetyCandidates(candidates)
            return SafetyInspectionResult(
                complete = true,
                blockingReason = "检测到可见广告/推广内容：$launchAdCue",
                visitedNodes = visited,
                elapsedMs = elapsedMs,
                popupCandidateCount = candidates.size,
                expectedCardDeckCandidatesIgnored = expectedCardDeckCandidatesIgnored
            )
        }
        if (stopReason != null) {
            recycleSafetyCandidates(candidates)
            return SafetyInspectionResult(
                complete = false,
                visitedNodes = visited,
                elapsedMs = elapsedMs,
                stopReason = stopReason,
                popupCandidateCount = candidates.size,
                expectedCardDeckCandidatesIgnored = expectedCardDeckCandidatesIgnored
            )
        }

        try {
            val ordered = candidates.sortedByDescending { it.score }
            for (candidate in ordered) {
                val joinedText = candidate.texts.joinToString(" ").take(360)
                val popupCue = candidate.texts.any { text ->
                    POPUP_CONTENT_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
                }
                val advertising = candidate.texts.any { text ->
                    ADVERTISING_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
                }
                val critical = candidate.texts.any { text ->
                    POPUP_CRITICAL_CUES.any { cue -> text.contains(cue, ignoreCase = true) }
                }
                val hasModalSemantics = candidate.score >= 90
                if (!hasModalSemantics && (!popupCue || candidate.closeTarget == null)) continue

                val bounds = Rect()
                runCatching { candidate.container.getBoundsInScreen(bounds) }
                val description = joinedText.ifBlank {
                    "${candidate.container.className?.toString()?.substringAfterLast('.') ?: "未知弹窗"}，区域=${bounds.toShortString()}"
                }
                val blockingReason = when {
                    advertising -> "检测到广告/推广弹层：${description.take(100)}"
                    critical -> "检测到权限、账户或其他关键确认弹层：${description.take(100)}"
                    candidate.closeTarget == null -> "检测到弹层但没有已验证的安全关闭目标：${description.take(100)}"
                    else -> null
                }
                if (blockingReason != null) {
                    return SafetyInspectionResult(
                        complete = true,
                        blockingReason = blockingReason,
                        visitedNodes = visited,
                        elapsedMs = elapsedMs,
                        popupCandidateCount = candidates.size,
                        expectedCardDeckCandidatesIgnored = expectedCardDeckCandidatesIgnored
                    )
                }
            }
            return SafetyInspectionResult(
                complete = true,
                visitedNodes = visited,
                elapsedMs = elapsedMs,
                popupCandidateCount = candidates.size,
                expectedCardDeckCandidatesIgnored = expectedCardDeckCandidatesIgnored
            )
        } finally {
            recycleSafetyCandidates(candidates)
        }
    }

    private fun appendSafetyText(target: MutableList<String>, value: String): Boolean {
        if (value.isBlank() || value in target) return true
        if (target.size >= MAX_SAFETY_TEXT_VALUES) return false
        target.add(value.take(100))
        return true
    }

    private fun recycleSafetyCandidates(candidates: List<SafetyCandidate>) {
        candidates.forEach { candidate ->
            runCatching { candidate.container.recycle() }
            runCatching { candidate.closeTarget?.recycle() }
        }
    }

    private fun incompleteInspection(reason: String) = PopupInspection(
        container = null,
        closeTarget = null,
        closeTapBounds = null,
        dismissable = false,
        critical = true,
        signature = "incomplete:${reason.hashCode()}",
        description = "节点检索未完成，暂不能确认页面安全：$reason",
        advertising = false,
        inspectionIncomplete = true
    )

    private fun recycleCandidates(
        candidates: List<Pair<AccessibilityNodeInfo, Int>>,
        keep: AccessibilityNodeInfo? = null
    ) {
        candidates.forEach { (node, _) ->
            if (node !== keep) runCatching { node.recycle() }
        }
    }

    private fun deadlineAfter(durationMs: Long): Long {
        val now = System.nanoTime()
        val durationNanos = durationMs.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
        return if (Long.MAX_VALUE - now < durationNanos) Long.MAX_VALUE else now + durationNanos
    }

    fun recycle(popup: PopupInspection) {
        runCatching { popup.container?.recycle() }
        runCatching { popup.closeTarget?.recycle() }
    }

    private companion object {
        const val MAX_DETAIL_SCAN_NODES = 180
        const val MAX_SAFETY_CANDIDATES = 12
        const val MAX_SAFETY_TEXT_VALUES = 24
        private val POPUP_SAFE_CLOSE_LABELS = setOf(
            "关闭", "关闭弹窗", "关闭广告", "取消", "暂不", "暂不升级", "稍后", "稍后再说",
            "以后再说", "知道了", "我知道了", "×", "✕", "x", "X", "Close", "close", "Dismiss", "dismiss"
        )
        private val POPUP_CRITICAL_CUES = setOf(
            "权限", "授权", "允许", "同意", "隐私", "协议", "支付", "充值", "购买",
            "删除", "注销", "退出登录", "身份验证", "安全验证", "实名认证", "确认提交",
            "确定要", "是否删除", "是否退出", "登录", "绑定手机号", "开启定位", "开启相机"
        )
        private val POPUP_CONTENT_CUES = setOf(
            "温馨提示", "系统提示", "公告", "新版本", "版本升级", "弹窗",
            "广告", "跳过广告", "开屏", "推广", "sponsored", "advertisement", "立即下载", "立即安装"
        )
        private val ADVERTISING_CUES = setOf(
            "广告", "跳过广告", "开屏", "推广", "sponsored", "advertisement", "立即下载", "立即安装"
        )
        private val VISIBLE_LAUNCH_AD_CUES = setOf(
            "跳过广告", "开屏广告", "广告剩余", "广告即将结束", "广告倒计时"
        )
    }
}
