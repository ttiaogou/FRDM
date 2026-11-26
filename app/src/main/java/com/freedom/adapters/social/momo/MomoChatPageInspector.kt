package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Bounded, read-only inspection of the currently visible Momo window.
 * It records structural evidence and node paths only; it never stores chat text.
 */
private data class PendingChatInspectionNode(
    val node: AccessibilityNodeInfo,
    val path: List<Int>,
    val parentInsideListCandidate: Boolean,
    val ancestorViewIds: List<String>
)

internal class MomoChatPageInspector(
    private val maxPageInspectionNodes: Int = MAX_PAGE_INSPECTION_NODES,
    private val maxRegionInspectionNodes: Int = MAX_REGION_INSPECTION_NODES,
    private val maxPageInspectionDurationMs: Long = 350L,
    private val maxRegionInspectionDurationMs: Long = 200L,
    private val maxInspectionDepth: Int = 32
) {
    init {
        require(maxPageInspectionNodes > 0 && maxRegionInspectionNodes > 0)
        require(maxPageInspectionDurationMs in 1L..2_000L)
        require(maxRegionInspectionDurationMs in 1L..1_000L)
        require(maxInspectionDepth in 1..64)
    }
    /** Takes ownership of a fresh root node obtained from the active-window gateway. */
    fun inspectRoot(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        activityClass: String
    ): MomoChatPageInspection {
        val windowPackage = safeString { root.packageName?.toString().orEmpty() }
        if (windowPackage != targetPackage) {
            runCatching { root.recycle() }
            return unknownInspection(activityClass, windowPackage)
        }

        // Activity is retained for diagnostics but no longer gates page fingerprint inspection.

        val queue = ArrayDeque<PendingChatInspectionNode>()
        queue.add(PendingChatInspectionNode(root, emptyList(), false, emptyList()))

        var visited = 0
        var editableInputCount = 0
        var messageListCount = 0
        var sendActionCount = 0
        var messageRelatedViewIdCount = 0
        var hasChatTab = false
        var messageTabSelected = false
        var chatTabEvidence: MomoChatNodeEvidence? = null
        val bottomTabWidgetPaths = ArrayList<String>()
        val chatTabCandidates = ArrayList<MomoChatNodeEvidence>()
        val chatBubbleCandidates = ArrayList<MomoChatNodeEvidence>()
        var inboxRecyclerViewCandidateCount = 0
        var inboxRecyclerViewUsableCount = 0
        var hasInboxRecyclerView = false
        var messageTopTabCandidateCount = 0
        var selectedMessageTopTab = false
        var hasMessageTopTab = false
        var messageTopTabSelected = false
        var sessionListHierarchyCandidateCount = 0
        var hasSessionListHierarchy = false
        var hasMessageSearchAndContactActions = false
        var hasSearchAction = false
        var hasContactAction = false
        var hasChatTitleAnchor = false
        var hasPeerMessageAvatarAnchor = false
        var hasMessageComposerAnchor = false
        var hasMessageEmotesAnchor = false
        var hasToolbarBackButton = false
        var messageListPath: List<Int>? = null
        var messageListClassName: String? = null
        var messageListViewId: String? = null
        var messageListPathScore = -1
        var inputFieldPath: List<Int>? = null
        var inputFieldPathScore = -1
        var chatTitleText: String? = null
        var chatHeaderDiagnostic: MomoChatHeaderNodeDiagnostic? = null
        val structureEvidence = ArrayList<MomoChatNodeEvidence>(MAX_STRUCTURE_EVIDENCE)
        val deadlineNanos = System.nanoTime() + maxPageInspectionDurationMs * 1_000_000L
        var timeBudgetExceeded = false
        var depthBudgetExceeded = false
        var nodeBudgetExceeded = false

        try {
            while (queue.isNotEmpty() && visited < maxPageInspectionNodes && System.nanoTime() < deadlineNanos) {
                val pending = queue.removeFirst()
                val node = pending.node
                val path = pending.path
                val parentInsideListCandidate = pending.parentInsideListCandidate
                val ancestorViewIds = pending.ancestorViewIds
                visited++
                try {
                    val className = safeString { node.className?.toString().orEmpty() }
                    val normalizedClass = className.substringAfterLast('.').lowercase()
                    val viewId = safeString { node.viewIdResourceName.orEmpty() }.lowercase()
                    // This delayed floating panel is not part of the main inbox page. Skip its
                    // complete subtree so its appearance/animation cannot perturb page evidence.
                    if (MomoChatListSessionPolicy.isAiFinderFloatingView(viewId)
                    ) {
                        visited--
                        continue
                    }
                    val text = safeString { node.text?.toString().orEmpty() }.trim()
                    val description = safeString { node.contentDescription?.toString().orEmpty() }.trim()
                    val nodeText = "$text $description".trim().lowercase()
                    fun hasAncestorId(id: String): Boolean = ancestorViewIds.any {
                        it == id || it.endsWith("/$id")
                    }
                    val isToolbarBackCandidate = className == "android.widget.ImageButton" &&
                        ancestorViewIds.lastOrNull() == MomoChatPageContract.CHAT_TOOLBAR_VIEW_ID &&
                        hasAncestorId("appbar_id")
                    val isChatFingerprintCandidate = viewId in setOf(
                        MomoChatPageContract.CHAT_TITLE_VIEW_ID,
                        MomoChatPageContract.CHAT_MESSAGE_AVATAR_VIEW_ID,
                        MomoChatPageContract.CHAT_COMPOSER_VIEW_ID,
                        MomoChatPageContract.CHAT_EMOTES_VIEW_ID
                    ) || isToolbarBackCandidate
                    val nodeVisible = isChatFingerprintCandidate && safeBoolean { node.isVisibleToUser }
                    val nodeBounds = Rect()
                    val nodeBoundsValid = isChatFingerprintCandidate &&
                        runCatching { node.getBoundsInScreen(nodeBounds) }.isSuccess &&
                        nodeBounds.right > nodeBounds.left && nodeBounds.bottom > nodeBounds.top

                    if (
                        viewId == MomoChatPageContract.CHAT_TITLE_VIEW_ID &&
                        className == "android.widget.TextView" &&
                        nodeVisible && nodeBoundsValid
                    ) {
                        hasChatTitleAnchor = true
                    }
                    if (
                        viewId == MomoChatPageContract.CHAT_MESSAGE_AVATAR_VIEW_ID &&
                        className == "android.widget.ImageView" &&
                        nodeVisible && nodeBoundsValid &&
                        hasAncestorId("message_iv_avatar_container") &&
                        hasAncestorId("message_root")
                    ) {
                        hasPeerMessageAvatarAnchor = true
                    }
                    if (
                        viewId == MomoChatPageContract.CHAT_COMPOSER_VIEW_ID &&
                        normalizedClass == "edittext" && safeBoolean { node.isEditable } &&
                        nodeVisible && nodeBoundsValid
                    ) {
                        hasMessageComposerAnchor = true
                    }
                    if (
                        viewId == MomoChatPageContract.CHAT_EMOTES_VIEW_ID &&
                        normalizedClass == "imageview" &&
                        nodeVisible && nodeBoundsValid
                    ) {
                        hasMessageEmotesAnchor = true
                    }
                    if (isToolbarBackCandidate &&
                        safeBoolean { node.isClickable } && nodeVisible && nodeBoundsValid
                    ) {
                        hasToolbarBackButton = true
                    }

                    if (viewId == MomoChatPageContract.CHAT_TITLE_VIEW_ID &&
                        chatHeaderDiagnostic == null
                    ) {
                        val parent = safeNode { node.parent }
                        val parentClass = parent?.let { safeString { it.className?.toString().orEmpty() } }.orEmpty()
                        val parentId = parent?.let { safeString { it.viewIdResourceName.orEmpty() } }.orEmpty()
                        val parentVisible = parent?.let { safeBoolean { it.isVisibleToUser } } ?: false
                        val parentBounds = Rect()
                        val parentBoundsValid = parent?.let {
                            runCatching { it.getBoundsInScreen(parentBounds) }.isSuccess &&
                                parentBounds.right > parentBounds.left && parentBounds.bottom > parentBounds.top
                        } ?: false
                        parent?.let { runCatching { it.recycle() } }
                        val readOutcome = when {
                            text.isNotBlank() -> "TEXT_READ"
                            description.isNotBlank() -> "DESCRIPTION_READ"
                            else -> "NODE_FOUND_BUT_TEXT_EMPTY"
                        }
                        chatTitleText = text.ifBlank { description }.takeIf(String::isNotBlank)
                        chatHeaderDiagnostic = MomoChatHeaderNodeDiagnostic(
                            nodePath = path.joinToString("/"),
                            found = true,
                            textLength = text.length,
                            contentDescriptionLength = description.length,
                            isVisibleToUser = nodeVisible,
                            left = nodeBounds.left,
                            top = nodeBounds.top,
                            right = nodeBounds.right,
                            bottom = nodeBounds.bottom,
                            boundsValid = nodeBoundsValid,
                            parentFound = parent != null,
                            parentClassName = parentClass,
                            parentViewId = parentId,
                            parentIsVisibleToUser = parentVisible,
                            parentBoundsValid = parentBoundsValid,
                            readOutcome = readOutcome
                        )
                    }

                    val editable = (
                        safeBoolean { node.isEditable } ||
                            normalizedClass == "edittext" ||
                            containsAny(viewId, INPUT_ID_HINTS)
                    )
                    if (editable) {
                        editableInputCount++
                        val inputScore = when {
                            viewId == MomoChatPageContract.CHAT_COMPOSER_VIEW_ID -> 100
                            containsAny(viewId, setOf("message_box", "msg_box", "chat_input", "composer")) -> 4
                            containsAny(viewId, setOf("input", "edit")) -> 3
                            safeBoolean { node.isFocused } -> 2
                            else -> 1
                        }
                        if (inputScore > inputFieldPathScore) {
                            inputFieldPath = path
                            inputFieldPathScore = inputScore
                        }
                    }

                    // Keep the exact RecyclerView locator for message extraction only. It is not
                    // required for chat-detail page identity, which is determined by the stable
                    // title/avatar/composer fingerprint above.
                    val messageList =
                        className == MomoChatPageContract.MESSAGE_LIST_CLASS_NAME &&
                        viewId == MomoChatPageContract.MESSAGE_LIST_VIEW_ID
                    val insideListCandidate = parentInsideListCandidate || messageList
                    if (messageList) {
                        messageListCount++
                        val listScore = if (messageList) 100 else 0
                        if (listScore > messageListPathScore) {
                            messageListPath = path
                            messageListClassName = className
                            messageListViewId = safeString { node.viewIdResourceName.orEmpty() }
                            messageListPathScore = listScore
                        }
                    }

                    // Momo exposes the same fl_bubble_container resource id under several
                    // bottom tabs. Resolve it only inside tabwidget -> maintab_layout_chat.
                    if (viewId.endsWith("/tabwidget") && hasAncestorId("maintab_bottom_container")) {
                        val bounds = Rect()
                        val valid = runCatching { node.getBoundsInScreen(bounds) }.isSuccess &&
                            bounds.right > bounds.left && bounds.bottom > bounds.top
                        if (valid && safeBoolean { node.isVisibleToUser }) {
                            bottomTabWidgetPaths += path.joinToString("/")
                        }
                    }
                    if (viewId.endsWith("maintab_layout_chat")) {
                        val tabBounds = Rect()
                        val valid = runCatching { node.getBoundsInScreen(tabBounds) }.isSuccess &&
                            tabBounds.right > tabBounds.left && tabBounds.bottom > tabBounds.top
                        if (valid && safeBoolean { node.isVisibleToUser } &&
                            safeBoolean { node.isEnabled } && safeBoolean { node.isClickable }
                        ) {
                            chatTabCandidates += MomoChatNodeEvidence(
                                path = path.joinToString("/"),
                                className = className,
                                viewId = viewId,
                                left = tabBounds.left,
                                top = tabBounds.top,
                                right = tabBounds.right,
                                bottom = tabBounds.bottom,
                                childCount = safeInt { node.childCount },
                                isEditable = false,
                                isScrollable = safeBoolean { node.isScrollable },
                                isClickable = true,
                                hasText = text.isNotBlank() || description.isNotBlank(),
                                insideListCandidate = false,
                                isVisibleToUser = true,
                                boundsValid = true,
                                isSelected = safeBoolean { node.isSelected }
                            )
                        }
                    }
                    if (viewId.endsWith("fl_bubble_container")) {
                        val bubbleBounds = Rect()
                        val valid = runCatching { node.getBoundsInScreen(bubbleBounds) }.isSuccess &&
                            bubbleBounds.right > bubbleBounds.left && bubbleBounds.bottom > bubbleBounds.top
                        if (valid && safeBoolean { node.isVisibleToUser } && safeBoolean { node.isEnabled }) {
                            chatBubbleCandidates += MomoChatNodeEvidence(
                                path = path.joinToString("/"),
                                className = className,
                                viewId = viewId,
                                left = bubbleBounds.left,
                                top = bubbleBounds.top,
                                right = bubbleBounds.right,
                                bottom = bubbleBounds.bottom,
                                childCount = safeInt { node.childCount },
                                isEditable = false,
                                isScrollable = safeBoolean { node.isScrollable },
                                isClickable = safeBoolean { node.isClickable },
                                hasText = text.isNotBlank() || description.isNotBlank(),
                                insideListCandidate = false,
                                isVisibleToUser = true,
                                boundsValid = true,
                                isSelected = safeBoolean { node.isSelected }
                            )
                        }
                    }
                    if (
                        className == MomoChatListSessionExtractor.RECYCLER_VIEW_CLASS &&
                        viewId == MomoChatListSessionExtractor.INBOX_RECYCLER_VIEW_ID &&
                        hasAncestorId("session_list_inner_container") &&
                        hasAncestorId("session_list_page_layout") &&
                        hasAncestorId("pagertabcontent") &&
                        safeBoolean { node.isVisibleToUser }
                    ) {
                        // Count the complete structural candidates, including unusable ones, so a
                        // second visible RecyclerView cannot be silently ignored as ambiguity.
                        inboxRecyclerViewCandidateCount++
                        val inboxBounds = Rect()
                        val inboxBoundsValid = runCatching { node.getBoundsInScreen(inboxBounds) }.isSuccess &&
                            inboxBounds.right > inboxBounds.left && inboxBounds.bottom > inboxBounds.top
                        if (inboxBoundsValid && safeBoolean { node.isScrollable } &&
                            safeBoolean { node.isEnabled } && safeInt { node.childCount } > 0
                        ) inboxRecyclerViewUsableCount++
                    }
                    if (viewId.endsWith("tab_title") && className == "android.widget.TextView" &&
                        text == "消息" && hasAncestorId("tablayout_id") && hasAncestorId("appbar_id") &&
                        safeBoolean { node.isVisibleToUser } && safeBoolean { node.isEnabled }
                    ) {
                        val tabBounds = Rect()
                        val tabBoundsValid = runCatching { node.getBoundsInScreen(tabBounds) }.isSuccess &&
                            tabBounds.right > tabBounds.left && tabBounds.bottom > tabBounds.top
                        if (tabBoundsValid) {
                            messageTopTabCandidateCount++
                            selectedMessageTopTab = safeBoolean { node.isSelected }
                        }
                    }
                    if (viewId.endsWith("session_list_page_layout") && hasAncestorId("pagertabcontent") &&
                        safeBoolean { node.isVisibleToUser }
                    ) {
                        val sessionBounds = Rect()
                        val sessionBoundsValid = runCatching { node.getBoundsInScreen(sessionBounds) }.isSuccess &&
                            sessionBounds.right > sessionBounds.left && sessionBounds.bottom > sessionBounds.top
                        if (sessionBoundsValid && safeInt { node.childCount } > 0) {
                            sessionListHierarchyCandidateCount++
                        }
                    }
                    if (viewId.endsWith("session_global_search") && hasAncestorId("appbar_id") &&
                        safeBoolean { node.isVisibleToUser } && safeBoolean { node.isClickable }
                    ) hasSearchAction = true
                    if (viewId.endsWith("action_jump_contact") && hasAncestorId("appbar_id") &&
                        safeBoolean { node.isVisibleToUser } && safeBoolean { node.isClickable }
                    ) hasContactAction = true
                    hasMessageSearchAndContactActions = hasSearchAction && hasContactAction

                    if (containsAny(viewId, MESSAGE_RELATED_ID_HINTS) &&
                        !viewId.endsWith("maintab_layout_chat")
                    ) {
                        messageRelatedViewIdCount++
                    }

                    val sendAction = nodeText == "发送" || nodeText == "send" ||
                        nodeText.contains("发送消息") || nodeText.contains("send message") ||
                        containsAny(viewId, SEND_ID_HINTS)
                    if (sendAction) sendActionCount++

                    val childCount = safeInt { node.childCount }
                    val scrollable = safeBoolean { node.isScrollable }
                    val clickable = safeBoolean { node.isClickable }
                    val evidenceCandidate = insideListCandidate || editable || messageList ||
                        scrollable || sendAction || viewId.endsWith("/tabwidget") ||
                        viewId.endsWith("fl_bubble_container") || viewId.endsWith("ll_tab_item_tv_label") ||
                        containsAny(
                            viewId,
                            setOf("chat", "message", "msg", "conversation", "talk", "input", "send")
                        )
                    if (evidenceCandidate && structureEvidence.size < MAX_STRUCTURE_EVIDENCE) {
                        val bounds = Rect()
                        runCatching { node.getBoundsInScreen(bounds) }
                        structureEvidence += MomoChatNodeEvidence(
                            path = path.joinToString("/"),
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
                            hasText = text.isNotBlank() || description.isNotBlank(),
                            insideListCandidate = insideListCandidate,
                            isVisibleToUser = safeBoolean { node.isVisibleToUser },
                            textLength = text.length,
                            contentDescriptionLength = description.length,
                            boundsValid = bounds.right > bounds.left && bounds.bottom > bounds.top,
                            isSelected = safeBoolean { node.isSelected }
                        )
                    }

                    // These three stable resource IDs are the complete chat-detail contract.
                    // Message cards, avatars, recycler contents, and Activity names are not
                    // prerequisites and therefore cannot block this classification.
                    if (hasChatTitleAnchor && hasMessageComposerAnchor && hasMessageEmotesAnchor) break

                    if (path.size >= maxInspectionDepth && childCount > 0) {
                        depthBudgetExceeded = true
                    } else for (index in 0 until childCount) {
                        if (System.nanoTime() >= deadlineNanos) {
                            timeBudgetExceeded = true
                            break
                        }
                        if (visited + queue.size >= maxPageInspectionNodes) {
                            nodeBudgetExceeded = true
                            break
                        }
                        val child = safeNode { node.getChild(index) } ?: continue
                        val childAncestors = if (viewId.isNotBlank()) ancestorViewIds + viewId else ancestorViewIds
                        queue.add(
                            PendingChatInspectionNode(
                                node = child,
                                path = path + index,
                                parentInsideListCandidate = insideListCandidate,
                                ancestorViewIds = childAncestors
                            )
                        )
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty()) {
                if (System.nanoTime() >= deadlineNanos) timeBudgetExceeded = true
                else if (visited >= maxPageInspectionNodes) nodeBudgetExceeded = true
            }
            while (queue.isNotEmpty()) {
                runCatching { queue.removeFirst().node.recycle() }
            }
        }

        fun isDescendantPath(candidate: String, ancestor: String): Boolean =
            candidate.startsWith("$ancestor/")

        fun isDirectChildPath(candidate: String, parent: String): Boolean =
            candidate.substringBeforeLast('/', missingDelimiterValue = "#") == parent

        hasInboxRecyclerView = inboxRecyclerViewCandidateCount == 1 &&
            inboxRecyclerViewUsableCount == 1
        hasSessionListHierarchy = sessionListHierarchyCandidateCount == 1
        hasMessageTopTab = messageTopTabCandidateCount == 1
        messageTopTabSelected = hasMessageTopTab && selectedMessageTopTab

        val verifiedTabPairs = if (bottomTabWidgetPaths.size == 1) {
            val widgetPath = bottomTabWidgetPaths.single()
            chatTabCandidates.filter { tab ->
                isDirectChildPath(tab.path, widgetPath) &&
                    chatBubbleCandidates.count { bubble -> isDescendantPath(bubble.path, tab.path) } == 1
            }.map { tab ->
                tab to chatBubbleCandidates.single { bubble -> isDescendantPath(bubble.path, tab.path) }
            }
        } else emptyList()
        if (verifiedTabPairs.size == 1) {
            val (tab, bubble) = verifiedTabPairs.single()
            hasChatTab = true
            chatTabEvidence = tab
            // Both the clickable tab and the proposed fl_bubble_container navigation anchor
            // must report selected before the page is considered the active message tab.
            messageTabSelected = tab.isSelected && bubble.isSelected
        }

        val signals = MomoChatPageSignals(
            packageMatches = true,
            activityClass = activityClass,
            hasChatTab = hasChatTab,
            messageTabSelected = messageTabSelected,
            hasInboxRecyclerView = hasInboxRecyclerView,
            editableInputCount = editableInputCount,
            messageListCount = messageListCount,
            sendActionCount = sendActionCount,
            messageRelatedViewIdCount = messageRelatedViewIdCount,
            hasCanonicalMessageList = messageListPath != null,
            hasChatTitleAnchor = hasChatTitleAnchor,
            hasPeerMessageAvatarAnchor = hasPeerMessageAvatarAnchor,
            hasMessageComposerAnchor = hasMessageComposerAnchor,
            hasMessageEmotesAnchor = hasMessageEmotesAnchor,
            hasToolbarBackButton = hasToolbarBackButton,
            hasMessageTopTab = hasMessageTopTab,
            messageTopTabSelected = messageTopTabSelected,
            hasSessionListHierarchy = hasSessionListHierarchy,
            hasMessageSearchAndContactActions = hasMessageSearchAndContactActions,
            inspectionIncomplete = timeBudgetExceeded || depthBudgetExceeded || nodeBudgetExceeded
        )
        return MomoChatPageInspection(
            pageType = MomoChatPageClassifier.classify(signals),
            windowPackage = windowPackage,
            activityClass = activityClass,
            scannedNodeCount = visited,
            editableInputCount = editableInputCount,
            messageListCount = messageListCount,
            sendActionCount = sendActionCount,
            messageRelatedViewIdCount = messageRelatedViewIdCount,
            hasChatTab = hasChatTab,
            messageListPath = messageListPath,
            inputFieldPath = inputFieldPath,
            structureEvidence = structureEvidence.toList(),
            messageListClassName = messageListClassName,
            messageListViewId = messageListViewId,
            hasCanonicalMessageList = messageListPath != null,
            hasChatTitleAnchor = hasChatTitleAnchor,
            hasPeerMessageAvatarAnchor = hasPeerMessageAvatarAnchor,
            hasMessageComposerAnchor = hasMessageComposerAnchor,
            hasMessageEmotesAnchor = hasMessageEmotesAnchor,
            hasToolbarBackButton = hasToolbarBackButton,
            chatTitleText = chatTitleText,
            chatHeaderDiagnostic = chatHeaderDiagnostic,
            chatTabEvidence = chatTabEvidence,
            messageTabSelected = messageTabSelected,
            hasMessageTopTab = hasMessageTopTab,
            messageTopTabSelected = messageTopTabSelected,
            hasSessionListHierarchy = hasSessionListHierarchy,
            hasInboxRecyclerView = hasInboxRecyclerView,
            hasMessageSearchAndContactActions = hasMessageSearchAndContactActions,
            timeBudgetExceeded = timeBudgetExceeded,
            depthBudgetExceeded = depthBudgetExceeded,
            requestedMaxDurationMs = maxPageInspectionDurationMs,
            nodeBudgetExceeded = nodeBudgetExceeded
        )
    }

    /**
     * Once a chat-detail candidate is known, refresh only the previously located message-list
     * and input subtrees on routine content events instead of walking the whole window again.
     * Takes ownership of a fresh root node; callers must reacquire it for every refresh.
     */
    fun inspectKnownChatRegions(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        previous: MomoChatPageInspection
    ): MomoChatRegionRefresh {
        val packageName = safeString { root.packageName?.toString().orEmpty() }
        if (packageName != targetPackage ||
            previous.pageType != MomoChatPageType.CHAT_DETAIL_CANDIDATE ||
            !previous.hasCanonicalMessageList
        ) {
            runCatching { root.recycle() }
            return MomoChatRegionRefresh(false, 0, 0, 0, 0, false)
        }

        val regionDeadlineNanos = System.nanoTime() + maxRegionInspectionDurationMs * 1_000_000L
        return try {
            val messageNode = previous.messageListPath?.let {
                nodeAtPath(AccessibilityNodeInfo.obtain(root), it)
            }
            val inputNode = previous.inputFieldPath?.let {
                nodeAtPath(AccessibilityNodeInfo.obtain(root), it)
            }
            val validatedMessageNode = messageNode?.takeIf(::isMessageListNode)
            if (messageNode != null && validatedMessageNode == null) {
                runCatching { messageNode.recycle() }
            }
            val validatedInputNode = inputNode?.takeIf(::isInputNode)
            if (inputNode != null && validatedInputNode == null) {
                runCatching { inputNode.recycle() }
            }
            val messageStats = validatedMessageNode?.let {
                inspectSubtree(it, maxRegionInspectionNodes, regionDeadlineNanos)
            }
            val inputStats = validatedInputNode?.let {
                inspectSubtree(it, maxRegionInspectionNodes, regionDeadlineNanos)
            }
            val timeExceeded = messageStats?.timeBudgetExceeded == true || inputStats?.timeBudgetExceeded == true ||
                System.nanoTime() >= regionDeadlineNanos
            val depthExceeded = messageStats?.depthBudgetExceeded == true || inputStats?.depthBudgetExceeded == true
            val nodeExceeded = messageStats?.nodeBudgetExceeded == true || inputStats?.nodeBudgetExceeded == true
            MomoChatRegionRefresh(
                packageMatches = true,
                messageRegionNodeCount = messageStats?.nodeCount ?: 0,
                messageRegionTextNodeCount = messageStats?.textNodeCount ?: 0,
                inputRegionNodeCount = inputStats?.nodeCount ?: 0,
                inputRegionEditableNodeCount = inputStats?.editableNodeCount ?: 0,
                pathsResolved = messageStats != null && inputStats != null &&
                    !timeExceeded && !depthExceeded && !nodeExceeded,
                timeBudgetExceeded = timeExceeded,
                depthBudgetExceeded = depthExceeded,
                nodeBudgetExceeded = nodeExceeded
            )
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * M02.1: read-only, on-demand locator. Owns the fresh root and returns no Android node.
     * The ordinary chat fingerprint and its early-exit scan remain unchanged.
     */
    fun inspectImageEntry(root: AccessibilityNodeInfo, targetPackage: String, activityClass: String): MomoImageEntryEvidence? {
        try {
            val page = inspectRoot(AccessibilityNodeInfo.obtain(root), targetPackage, activityClass)
            if (page.pageType != MomoChatPageType.CHAT_DETAIL_CANDIDATE ||
                !page.hasStableChatDetailFingerprint) return null
            val nodes = root.findAccessibilityNodeInfosByViewId(MomoImageEntryPolicy.ENTRY_ID).orEmpty()
            try {
                val candidates = nodes.map { node ->
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val parent = node.parent
                    val parentId = try { parent?.viewIdResourceName.orEmpty() }
                        finally { parent?.let { runCatching { it.recycle() } } }
                    MomoImageEntryEvidence(
                        node.viewIdResourceName.orEmpty(), parentId,
                        node.className?.toString().orEmpty(), node.packageName?.toString().orEmpty(),
                        node.isVisibleToUser, node.isEnabled, node.isClickable,
                        bounds.left, bounds.top, bounds.right, bounds.bottom
                    )
                }
                return MomoImageEntryPolicy.resolve(targetPackage, candidates)
            } finally {
                nodes.forEach { runCatching { it.recycle() } }
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { root.recycle() }
        }
    }

    private data class RegionStats(
        val nodeCount: Int,
        val textNodeCount: Int,
        val editableNodeCount: Int,
        val timeBudgetExceeded: Boolean,
        val depthBudgetExceeded: Boolean,
        val nodeBudgetExceeded: Boolean
    )

    private fun inspectSubtree(
        start: AccessibilityNodeInfo,
        maxNodes: Int,
        deadlineNanos: Long
    ): RegionStats {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(start)
        var count = 0
        var textCount = 0
        var editableCount = 0
        var timeBudgetExceeded = false
        var depthBudgetExceeded = false
        var nodeBudgetExceeded = false
        val paths = java.util.IdentityHashMap<AccessibilityNodeInfo, Int>()
        paths[start] = 0
        try {
            while (queue.isNotEmpty() && count < maxNodes && System.nanoTime() < deadlineNanos) {
                val node = queue.removeFirst()
                count++
                try {
                    if (safeString { node.text?.toString().orEmpty() }.isNotBlank()) textCount++
                    if (safeBoolean { node.isEditable } ||
                        safeString { node.className?.toString().orEmpty() }.substringAfterLast('.') == "EditText"
                    ) editableCount++
                    val children = safeInt { node.childCount }
                    val depth = paths.remove(node) ?: 0
                    if (depth >= maxInspectionDepth && children > 0) depthBudgetExceeded = true
                    else for (index in 0 until children) {
                        if (System.nanoTime() >= deadlineNanos) {
                            timeBudgetExceeded = true
                            break
                        }
                        if (count + queue.size >= maxNodes) {
                            nodeBudgetExceeded = true
                            break
                        }
                        safeNode { node.getChild(index) }?.let { child ->
                            paths[child] = depth + 1
                            queue.add(child)
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty()) {
                if (System.nanoTime() >= deadlineNanos) timeBudgetExceeded = true
                else if (count >= maxNodes) nodeBudgetExceeded = true
            }
            while (queue.isNotEmpty()) {
                val pending = queue.removeFirst()
                paths.remove(pending)
                runCatching { pending.recycle() }
            }
        }
        return RegionStats(
            count, textCount, editableCount, timeBudgetExceeded, depthBudgetExceeded, nodeBudgetExceeded
        )
    }

    /** Takes ownership of root and returns an owned node at the child-index path. */
    private fun nodeAtPath(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        if (path.size > maxInspectionDepth) {
            runCatching { root.recycle() }
            return null
        }
        var current: AccessibilityNodeInfo? = root
        for (index in path) {
            val parent = current ?: return null
            val next = safeNode { parent.getChild(index) }
            runCatching { parent.recycle() }
            current = next ?: return null
        }
        return current
    }

    private fun unknownInspection(activityClass: String, packageName: String = "") =
        MomoChatPageInspection(
            pageType = MomoChatPageType.UNKNOWN,
            windowPackage = packageName,
            activityClass = activityClass,
            scannedNodeCount = 0,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = false,
            messageListPath = null,
            inputFieldPath = null
        )

    private fun isMessageListNode(node: AccessibilityNodeInfo): Boolean {
        val className = safeString { node.className?.toString().orEmpty() }
        val viewId = safeString { node.viewIdResourceName.orEmpty() }
        return className == MomoChatPageContract.MESSAGE_LIST_CLASS_NAME &&
            viewId == MomoChatPageContract.MESSAGE_LIST_VIEW_ID
    }

    private fun isInputNode(node: AccessibilityNodeInfo): Boolean {
        val className = safeString { node.className?.toString().orEmpty() }.substringAfterLast('.').lowercase()
        val viewId = safeString { node.viewIdResourceName.orEmpty() }.lowercase()
        return safeBoolean { node.isEditable } || className == "edittext" || containsAny(viewId, INPUT_ID_HINTS)
    }

    private fun containsAny(value: String, hints: Set<String>): Boolean =
        hints.any { hint -> value.contains(hint) }
    private inline fun safeString(block: () -> String?): String =
        runCatching { block().orEmpty() }.getOrDefault("")
    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

    companion object {
        const val MAX_PAGE_INSPECTION_NODES = 800
        const val MAX_REGION_INSPECTION_NODES = 240
        const val MAX_STRUCTURE_EVIDENCE = 96

        private val INPUT_ID_HINTS = setOf("input", "edit", "composer", "message_box", "msg_box")
        private val MESSAGE_RELATED_ID_HINTS = setOf("message", "msg_", "_msg", "conversation", "chat_content", "chat_message", "talk_")
        private val SEND_ID_HINTS = setOf("send", "submit_message", "btn_send")
    }
}
