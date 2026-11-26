package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.features.aireply.policy.ConversationFilterEvidence
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.query.NodeSelector
import com.freedom.core.accessibility.query.NodeSelectorExplainer
import com.freedom.core.accessibility.query.NodeTreeIndex
import com.freedom.core.accessibility.query.NodeQueryBudget
import com.freedom.core.accessibility.diagnostics.NodeSelectorDiagnosticsBuffer

/**
 * Read-only inspector for the two Momo pages confirmed by the supplied device trace.
 * Text is kept in-memory only; callers must not persist or emit raw profile text to logs.
 */
internal data class MomoSayHiPageInspection(
    val kind: MomoSayHiPageKind,
    val activityClass: String,
    val nodeCount: Int,
    val visibleNodeCount: Int,
    val containerFound: Boolean,
    val containerNodeCount: Int,
    val textNodeCount: Int,
    val profile: MomoSayHiProfile?,
    val listRows: List<MomoSayHiListRow>,
    /** Number of top recommendation cards deliberately excluded from all greeting candidates. */
    val excludedRecommendationCardCount: Int = 0,
    val truncated: Boolean,
    val diagnostic: String,
    val containerMatchCount: Int = 0,
    val containerMatchCountExact: Boolean = true,
    val containerAmbiguous: Boolean = false,
    val queryBudgetExceeded: Boolean = false,
    val queryVisitedNodeCount: Int = 0,
    val queryElapsedMs: Long = 0L,
    val queryStopReason: String? = null,
    val containerResolutionStrategy: String = "UNRESOLVED"
)

internal data class MomoSayHiListRow(
    val rowPath: List<Int>,
    val bounds: com.freedom.core.accessibility.api.ScreenRect,
    val displayName: FieldEvidence<String>,
    val age: FieldEvidence<Int>,
    val gender: FieldEvidence<Gender>,
    val distanceText: FieldEvidence<String>,
    val distanceKm: FieldEvidence<Double>,
    val online: FieldEvidence<Boolean>,
    val greetingText: FieldEvidence<String>,
    val timestampText: FieldEvidence<String>,
    /** Presence of hilist_tv_red inside this exact user row means an unread greeting. */
    val unread: FieldEvidence<Boolean>,
    val hasAvatarNode: Boolean,
    val namePath: NodePath? = null,
    val nameBounds: com.freedom.core.accessibility.api.ScreenRect? = null,
    val avatarTapTarget: MomoSayHiTapTarget? = null,
    val rowTapTarget: MomoSayHiTapTarget? = null,
    /** Strict filter-only evidence; never part of the existing row identity or send journal. */
    val filterEvidence: ConversationFilterEvidence = ConversationFilterEvidence()
)

/**
 * One visible node's structural facts used by both snapshot and live-root fingerprint checks.
 * Ancestors are ordered from the root toward the direct parent and contain normalized local IDs.
 */
internal data class MomoReceivedGreetingsFingerprintFact(
    val id: String,
    val className: String = "",
    val text: String = "",
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val scrollable: Boolean = false,
    val boundsEmpty: Boolean = false,
    val childCount: Int = 0,
    val ancestors: List<String> = emptyList()
)

/**
 * Shared received-greetings page identity policy. The unique, non-empty, vertically scrollable
 * content/list shell is mandatory. Only A (section title) and B (greeting-row schema) are valid
 * page-specific routes; toolbar controls are not sufficient to identify the work area.
 */

internal object MomoReceivedGreetingsFingerprintPolicy {
    fun matches(facts: List<MomoReceivedGreetingsFingerprintFact>): Boolean {
        val visible = facts.filter { it.visible }
        val content = visible.filter { it.id == "layout_content" }.singleOrNull() ?: return false
        if (content.boundsEmpty) return false

        // Count every visible listview below the unique content root before checking usability.
        // A valid candidate must not be accepted when another listview makes the structure ambiguous.
        val list = visible.filter {
            it.id == "listview" && "layout_content" in it.ancestors
        }.singleOrNull() ?: return false
        val listUsable = list.className.endsWith("RecyclerView") && list.scrollable &&
            list.enabled && !list.boundsEmpty && list.childCount > 0
        if (!listUsable) return false

        // The nested card_list is a horizontal recommendation carousel, not the vertical
        // greeting rows. It remains part of the tree for completeness/uniqueness checks, but
        // its descendants cannot satisfy the title or row fingerprint.
        fun isGreetingListContent(fact: MomoReceivedGreetingsFingerprintFact): Boolean =
            "listview" in fact.ancestors && "card_list" !in fact.ancestors
        fun hasDescendant(id: String): Boolean = visible.any {
            it.id == id && isGreetingListContent(it)
        }
        val allGreetingsTitle = visible.any {
            it.id == "title" && it.text.trim() == "全部招呼" && isGreetingListContent(it)
        }
        val greetingRows = hasDescendant("userlist_item_iv_face") &&
            hasDescendant("userlist_item_tv_name") &&
            (hasDescendant("hi_message_timestamp") || hasDescendant("hilist_tv_msgcontent"))

        return MomoAlternativeFingerprintPolicy.matchesAny(
            listOf(
                MomoFingerprintPath("GREETING_LIST_AND_ALL_TITLE", listOf(listUsable, allGreetingsTitle)),
                MomoFingerprintPath("GREETING_LIST_AND_ROW_SCHEMA", listOf(listUsable, greetingRows))
            )
        )
    }

    /**
     * A subtree snapshot is admissible only after the owning full window was confirmed and
     * reacquired through its exact listview NodeTarget. Its root must therefore be the unique
     * listview itself; the full-page layout_content ancestor is intentionally absent.
     * Unlike the full-page A/B alternatives, a subtree must retain the greeting-row schema so
     * an empty or unrelated RecyclerView cannot inherit the prior page identity.
     */
    fun matchesConfirmedListSubtree(facts: List<MomoReceivedGreetingsFingerprintFact>): Boolean {
        val visible = facts.filter { it.visible }
        val listCandidates = visible.filter { it.id == "listview" }
        val list = listCandidates.singleOrNull()?.takeIf { it.ancestors.isEmpty() } ?: return false
        val listUsable = list.className.endsWith("RecyclerView") && list.scrollable &&
            list.enabled && !list.boundsEmpty && list.childCount > 0
        if (!listUsable) return false

        fun isGreetingListContent(fact: MomoReceivedGreetingsFingerprintFact): Boolean =
            "listview" in fact.ancestors && "card_list" !in fact.ancestors
        fun hasDescendant(id: String): Boolean = visible.any {
            it.id == id && isGreetingListContent(it)
        }
        val greetingRows = hasDescendant("userlist_item_iv_face") &&
            hasDescendant("userlist_item_tv_name") &&
            (hasDescendant("hi_message_timestamp") || hasDescendant("hilist_tv_msgcontent"))
        return listUsable && greetingRows
    }
}

internal class MomoSayHiAccessibilityInspector(
    private val queryBudget: NodeQueryBudget = NodeQueryBudget(
        maxVisitedNodes = MAX_QUERY_VISITED_NODES,
        timeoutMs = MAX_QUERY_TIME_MS
    )
) {
    private companion object {
        const val MAX_QUERY_VISITED_NODES = 1_200
        const val MAX_QUERY_TIME_MS = 350L
    }
    private val nameIds = setOf("userlist_item_tv_name", "tv_name")
    private val distanceIds = setOf("hilist_tv_distance")
    private val greetingIds = setOf("hilist_tv_msgcontent")
    private val timestampIds = setOf("hi_message_timestamp")
    private val unreadMarkerId = "hilist_tv_red"
    private val avatarIds = setOf("userlist_item_iv_face", "chatlist_item_iv_face", "img_avatar", "icon")
    // These IDs were observed in the confirmed NewSayHiCardActivity snapshot. Keep their
    // values separately keyed instead of guessing semantic roles from arbitrary screen text.
    private val profileTextFieldIds = linkedSetOf(
        "tv_name",
        "tv_recommend_text",
        "tv_plain_text",
        "location_distance",
        "user_online_status_container"
    )
    fun inspect(
        tree: AccessibilityTreeSnapshot,
        /** True only when this exact window's full-page list fingerprint was confirmed earlier. */
        receivedGreetingsListSubtreeConfirmed: Boolean = false
    ): MomoSayHiPageInspection {
        val index = NodeTreeIndex(tree)
        val visibleQuery = index.findAll(NodeSelector(visible = true), budget = queryBudget)
        val visibleNodes = if (visibleQuery.truncated) emptyList() else {
            visibleQuery.matchedPaths.mapNotNull(index::nodeAt)
        }
        val visibleIds = visibleNodes.asSequence()
            .map { it.viewId }
            .filter(String::isNotBlank)
            .toSet()
        // A budget-limited visible-node query cannot establish page identity. Fail closed instead
        // of classifying from a partial set of resource IDs.
        // Structural identity comes from the complete captured tree itself. A separate
        // visible-node query may exhaust its extraction budget on a long list; that must not
        // erase a page fingerprint already verified directly from the same snapshot.
        val receivedListFingerprint = !tree.truncated && (
            hasReceivedGreetingsListFingerprint(tree) ||
                (receivedGreetingsListSubtreeConfirmed && hasReceivedGreetingsListSubtreeFingerprint(tree))
            )
        val kind = if (tree.truncated) MomoSayHiPageKind.UNKNOWN else
            MomoSayHiPageContract.classify(
                tree.window.activityClass,
                visibleIds,
                receivedGreetingsListFingerprintConfirmed = receivedListFingerprint
            )
        val targetContainerId = when (kind) {
            MomoSayHiPageKind.RECEIVED_GREETINGS_LIST -> MomoSayHiPageContract.LIST_VIEW_ID
            MomoSayHiPageKind.GREETING_PROFILE_CARD -> MomoSayHiPageContract.CARD_CONTENT_VIEW_ID
            MomoSayHiPageKind.UNKNOWN -> null
        }
        val containerSelector = targetContainerId?.let { id ->
            NodeSelector(visible = true, viewId = id)
        }
        val containerQuery = containerSelector?.let { index.findUnique(it, budget = queryBudget) }
        val containerExplanation = containerSelector?.let {
            NodeSelectorExplainer.explain(index, it, keep = 8, budget = queryBudget)
        }
        if (containerQuery != null && containerExplanation != null) {
            NodeSelectorDiagnosticsBuffer.record(
                window = tree.window,
                queryLabel = "收到招呼页面容器唯一性查找",
                queryResult = containerQuery,
                explanation = containerExplanation
            )
        }
        val queryStopReasons = listOfNotNull(
            visibleQuery.stopReason,
            containerQuery?.stopReason,
            containerExplanation?.stopReason
        )
        val queryBudgetExceeded = queryStopReasons.any {
            it == "QUERY_NODE_BUDGET" || it == "QUERY_TIME_BUDGET"
        }
        val queryStopReason = listOfNotNull(
            visibleQuery.stopReason,
            containerQuery?.stopReason,
            containerExplanation?.stopReason
        ).firstOrNull()
        val containerMatchCountExact = when {
            containerExplanation != null -> containerExplanation.matchCountExact
            containerQuery != null -> containerQuery.matchCountExact
            else -> true
        }
        val containerMatchCount = containerExplanation?.matchCount
            ?: containerQuery?.matchCount ?: 0
        val containerAmbiguous = containerQuery?.ambiguous == true || containerMatchCount > 1
        // Only an exact, complete, single match can scope profile/list text. Two matches prove
        // ambiguity immediately; one match from a truncated query is never treated as unique.
        val candidateSetComplete = containerExplanation != null &&
            containerExplanation.matchCountExact &&
            !containerExplanation.queryTruncated &&
            !tree.truncated &&
            containerExplanation.matches.size == containerExplanation.totalMatchCount
        val activeCardResolution = if (kind == MomoSayHiPageKind.GREETING_PROFILE_CARD &&
            candidateSetComplete && containerExplanation != null
        ) {
            MomoSayHiActiveCardResolver.resolve(
                tree = tree,
                index = index,
                candidatePaths = containerExplanation.matches,
                candidatesExact = true
            )
        } else null
        val containerPath = when {
            containerQuery != null && containerQuery.matchCount == 1 &&
                !containerQuery.ambiguous && !containerQuery.truncated &&
                containerQuery.matchCountExact && candidateSetComplete -> containerQuery.firstPath
            activeCardResolution?.selectedPath != null -> activeCardResolution.selectedPath
            else -> null
        }
        val container = containerPath?.let(index::nodeAt)
        val scopedNodes = container?.let { index.descendantsOf(it.path, includeSelf = true) }.orEmpty()
        val textNodes = scopedNodes.mapNotNull { node ->
            sequenceOf(node.text, node.contentDescription)
                .filterNotNull()
                .map(String::trim)
                .firstOrNull(String::isNotBlank)
        }.distinct()
        val profileNodeTextFields = if (
            kind == MomoSayHiPageKind.GREETING_PROFILE_CARD && container != null
        ) {
            extractProfileNodeTextFields(scopedNodes)
        } else emptyMap()
        val profile = if (kind == MomoSayHiPageKind.GREETING_PROFILE_CARD && container != null) {
            val parsed = MomoSayHiProfileParser.parse(textNodes)
            // Prefer the exact observed nickname node. If it exists but is empty or ambiguous,
            // do not silently substitute an unrelated first text node as the person's name.
            val exactNameEvidence = profileNodeTextFields["tv_name"]?.evidence
            parsed.copy(
                displayName = exactNameEvidence?.takeUnless {
                    it.status == EvidenceStatus.MISSING
                } ?: parsed.displayName,
                nodeTextFields = profileNodeTextFields
            )
        } else null
        val excludedRecommendationCardCount = if (kind == MomoSayHiPageKind.RECEIVED_GREETINGS_LIST) {
            countExcludedRecommendationCards(scopedNodes)
        } else 0
        val listRows = if (kind == MomoSayHiPageKind.RECEIVED_GREETINGS_LIST && container != null) {
            extractListRows(scopedNodes, index, completeTree = !tree.truncated && !queryBudgetExceeded)
        } else emptyList()
        val containerDiagnostic = when {
            queryBudgetExceeded -> "节点查询预算耗尽，拒绝使用不完整结果"
            tree.truncated -> "节点树采集截断，无法证明容器唯一性"
            activeCardResolution?.selectedPath != null ->
                "检测到${activeCardResolution.candidateCount}个资料卡容器候选；依据 slidecard_layout 父容器、可见状态、非空子节点及唯一最大卡片边界确认当前卡片"
            containerAmbiguous -> "检测到多个容器候选，且无法唯一确认前景资料卡，拒绝自动选择"
            containerQuery != null && !containerMatchCountExact -> "当前只观察到候选节点，但唯一性未证实"
            containerQuery?.matchCount == 0 -> "容器节点缺失"
            container == null -> "容器节点无法安全确认"
            else -> "容器唯一性已确认"
        }
        val diagnostic = when {
            queryBudgetExceeded -> "节点查询达到工作预算；$containerDiagnostic"
            tree.truncated -> "节点树采集不完整；$containerDiagnostic"
            kind == MomoSayHiPageKind.RECEIVED_GREETINGS_LIST ->
                "收到的招呼列表 Activity 已匹配；$containerDiagnostic"
            kind == MomoSayHiPageKind.GREETING_PROFILE_CARD ->
                "资料卡 Activity 已匹配；$containerDiagnostic" +
                    if (container != null) "；字段仅按显式节点文本解析" else ""
            else -> "当前页面不属于已确认的收到招呼列表或资料卡页面"
        }
        val queryVisitedNodeCount = visibleQuery.visitedNodeCount +
            (containerQuery?.visitedNodeCount ?: 0) + (containerExplanation?.visitedNodeCount ?: 0)
        val queryElapsedMs = visibleQuery.queryElapsedMs +
            (containerQuery?.queryElapsedMs ?: 0L) + (containerExplanation?.elapsedMs ?: 0L)
        return MomoSayHiPageInspection(
            kind = kind,
            activityClass = tree.window.activityClass,
            nodeCount = tree.visitedNodeCount,
            visibleNodeCount = visibleNodes.size,
            containerFound = container != null,
            containerNodeCount = scopedNodes.size,
            textNodeCount = textNodes.size,
            profile = profile,
            listRows = listRows,
            excludedRecommendationCardCount = excludedRecommendationCardCount,
            truncated = tree.truncated || queryBudgetExceeded,
            diagnostic = diagnostic,
            containerMatchCount = containerMatchCount,
            containerMatchCountExact = containerMatchCountExact,
            containerAmbiguous = containerAmbiguous,
            queryBudgetExceeded = queryBudgetExceeded,
            queryVisitedNodeCount = queryVisitedNodeCount,
            queryElapsedMs = queryElapsedMs,
            queryStopReason = queryStopReason,
            containerResolutionStrategy = when {
                containerQuery?.matchCount == 1 && container != null -> "UNIQUE_SELECTOR_MATCH"
                activeCardResolution?.selectedPath != null -> activeCardResolution.reason
                else -> activeCardResolution?.reason ?: "UNRESOLVED"
            }
        )
    }

    /**
     * Received-greetings list page fingerprint shared with the live AccessibilityNodeInfo path.
     * The unique, non-empty content/list shell is mandatory; only the section title or greeting-
     * row schema may identify the page on top of that shell. Toolbar-only evidence is rejected.
     */
    fun hasReceivedGreetingsListFingerprint(tree: AccessibilityTreeSnapshot): Boolean {
        if (tree.truncated || tree.window.packageName != MomoAppDescriptor.PACKAGE_NAME) return false
        return MomoReceivedGreetingsFingerprintPolicy.matches(toReceivedGreetingsFingerprintFacts(tree))
    }

    /**
     * Validates a targeted list subtree after the adapter has retained a confirmed list target.
     * This method deliberately does not accept Activity identity alone: the root must be the
     * listview RecyclerView and visible greeting-row structure must still be present.
     */
    fun hasReceivedGreetingsListSubtreeFingerprint(tree: AccessibilityTreeSnapshot): Boolean {
        // Activity names are supplied by the service's event tracker, not by the accessibility
        // window root. After BACK, Android can expose the restored list hierarchy while that
        // tracker still reports the previous ChatActivity. This method is only consulted after
        // the adapter has already confirmed the full list and reacquired its exact listview
        // NodeTarget in the same package/window generation; the subtree must still prove the
        // RecyclerView root and greeting-row schema below.
        if (tree.truncated || tree.window.packageName != MomoAppDescriptor.PACKAGE_NAME) return false
        return MomoReceivedGreetingsFingerprintPolicy.matchesConfirmedListSubtree(
            toReceivedGreetingsFingerprintFacts(tree)
        )
    }

    private fun toReceivedGreetingsFingerprintFacts(
        tree: AccessibilityTreeSnapshot
    ): List<MomoReceivedGreetingsFingerprintFact> {
        val nodes = tree.nodes.filter { it.visible && it.packageName == MomoAppDescriptor.PACKAGE_NAME }
        fun localId(node: AccessibilityNodeSnapshot): String =
            node.viewId.substringAfterLast(':').substringAfterLast('/')
        fun isDescendant(node: AccessibilityNodeSnapshot, ancestor: AccessibilityNodeSnapshot): Boolean =
            node.path.childIndices.size > ancestor.path.childIndices.size &&
                node.path.childIndices.take(ancestor.path.childIndices.size) == ancestor.path.childIndices
        return nodes.map { node ->
            val ancestors = tree.nodes.asSequence()
                .filter { candidate -> isDescendant(node, candidate) }
                .sortedBy { it.path.childIndices.size }
                .map(::localId)
                .filter(String::isNotBlank)
                .toList()
            MomoReceivedGreetingsFingerprintFact(
                id = localId(node), className = node.className,
                text = node.text?.trim().orEmpty(), visible = true,
                enabled = node.enabled, scrollable = node.scrollable,
                boundsEmpty = node.bounds.isEmpty, childCount = node.childCount,
                ancestors = ancestors
            )
        }
    }

    /**
     * Raw-root variant for gesture readiness before a tree snapshot is requested. It traverses
     * the bounded tree completely before evaluating uniqueness, so a provisional title/row
     * match cannot stop traversal before later list nodes have been collected.
     * Incomplete walks fail closed rather than making a page decision from a partial tree.
     */
    fun hasReceivedGreetingsListFingerprint(
        root: android.view.accessibility.AccessibilityNodeInfo,
        targetPackage: String
    ): Boolean {
        if (runCatching { root.packageName?.toString() }.getOrNull() != targetPackage ||
            targetPackage != MomoAppDescriptor.PACKAGE_NAME
        ) return false

        val queue = java.util.ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, List<String>>>()
        queue.add(android.view.accessibility.AccessibilityNodeInfo.obtain(root) to emptyList())
        val facts = ArrayList<MomoReceivedGreetingsFingerprintFact>()
        var visited = 0
        val deadline = System.nanoTime() + 350_000_000L
        var truncated = false
        try {
            while (queue.isNotEmpty() && visited < 1_200 && System.nanoTime() < deadline) {
                val (node, ancestors) = queue.removeFirst()
                visited++
                try {
                    val id = runCatching {
                        node.viewIdResourceName.orEmpty().substringAfterLast(':').substringAfterLast('/')
                    }.getOrDefault("")
                    val bounds = android.graphics.Rect()
                    runCatching { node.getBoundsInScreen(bounds) }
                    val packageName = runCatching { node.packageName?.toString().orEmpty() }.getOrDefault("")
                    facts += MomoReceivedGreetingsFingerprintFact(
                        id = id,
                        className = runCatching { node.className?.toString().orEmpty() }.getOrDefault(""),
                        text = runCatching { node.text?.toString()?.trim().orEmpty() }.getOrDefault(""),
                        visible = packageName == targetPackage &&
                            runCatching { node.isVisibleToUser }.getOrDefault(false),
                        enabled = runCatching { node.isEnabled }.getOrDefault(false),
                        scrollable = runCatching { node.isScrollable }.getOrDefault(false),
                        boundsEmpty = bounds.isEmpty,
                        childCount = runCatching { node.childCount }.getOrDefault(0),
                        ancestors = ancestors
                    )
                    val childCount = runCatching { node.childCount }.getOrDefault(0)
                    // Traverse the carousel too so uniqueness is based on the complete tree.
                    // The shared policy excludes card_list descendants from A/B page evidence.
                    for (i in 0 until childCount) {
                        if (visited + queue.size >= 1_200 || System.nanoTime() >= deadline) {
                            truncated = true
                            break
                        }
                        val child = runCatching { node.getChild(i) }.getOrNull()
                        if (child == null) {
                            truncated = true
                            break
                        }
                        queue.add(child to (ancestors + listOf(id).filter(String::isNotBlank)))
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            if (queue.isNotEmpty()) truncated = true
            while (queue.isNotEmpty()) runCatching { queue.removeFirst().first.recycle() }
        }

        if (truncated) return false
        return MomoReceivedGreetingsFingerprintPolicy.matches(facts)
    }

    private fun extractProfileNodeTextFields(
        nodes: List<AccessibilityNodeSnapshot>
    ): Map<String, MomoSayHiNodeTextFieldReadout> = profileTextFieldIds.associateWith { expectedId ->
        val matches = nodes.filter { it.visible && localId(it.viewId) == expectedId }
        val values = matches.flatMap { node ->
            sequenceOf(node.text, node.contentDescription)
                .filterNotNull()
                .map(String::trim)
                .filter(String::isNotBlank)
                .toList()
        }.distinct()
        val evidence = when {
            matches.isEmpty() -> FieldEvidence<String>(
                null, null, EvidenceStatus.MISSING, "ACCESSIBILITY_NODE_ID:$expectedId"
            )
            values.isEmpty() -> FieldEvidence<String>(
                null, null, EvidenceStatus.EMPTY, "ACCESSIBILITY_NODE_ID:$expectedId"
            )
            matches.size > 1 -> FieldEvidence<String>(
                null, values.joinToString(" | ").takeIf(String::isNotBlank),
                EvidenceStatus.MULTIPLE_MATCHES, "ACCESSIBILITY_NODE_ID:$expectedId"
            )
            values.size > 1 -> FieldEvidence<String>(
                null, values.joinToString(" | "), EvidenceStatus.CONFLICT,
                "ACCESSIBILITY_NODE_ID:$expectedId"
            )
            else -> FieldEvidence(
                values.single(), values.single(), EvidenceStatus.CONFIRMED,
                "ACCESSIBILITY_NODE_ID:$expectedId"
            )
        }
        MomoSayHiNodeTextFieldReadout(
            viewId = expectedId,
            candidateCount = matches.size,
            evidence = evidence
        )
    }

    /**
     * Counts only the top recommendation card containers for diagnostics. Their names, avatars,
     * bounds and tap targets are intentionally not returned as business data.
     */
    private fun countExcludedRecommendationCards(
        nodes: List<AccessibilityNodeSnapshot>
    ): Int {
        val carouselPaths = nodes.filter { localId(it.viewId) == localId(MomoSayHiPageContract.RECOMMENDATION_CAROUSEL_VIEW_ID) }
            .map { it.path.childIndices }
        if (carouselPaths.isEmpty()) return 0
        return nodes.count { candidate ->
            localId(candidate.viewId) == "card_container" &&
                carouselPaths.any { carouselPath ->
                    candidate.path.childIndices.size > carouselPath.size &&
                        isWithin(candidate.path.childIndices, carouselPath)
                }
        }
    }

    /**
     * The top header is not a greeting list. Exclude every card_list subtree and, when the
     * observed header labels are present, the complete common header subtree as well. This is
     * intentionally path-based so duplicate/recycled card_container IDs cannot leak candidates.
     */
    private fun recommendationExclusionRoots(
        nodes: List<AccessibilityNodeSnapshot>,
        index: NodeTreeIndex,
        listRootPath: List<Int>
    ): List<List<Int>> {
        val roots = LinkedHashSet<List<Int>>()
        nodes.filter { localId(it.viewId) == localId(MomoSayHiPageContract.RECOMMENDATION_CAROUSEL_VIEW_ID) }
            .forEach { roots += it.path.childIndices }

        val headerMarkerIds = setOf("title_unread", "title_check", "hilist_tv_count")
        val markerPaths = nodes.filter { localId(it.viewId) in headerMarkerIds }
            .map { it.path.childIndices }
        val commonHeaderPath = markerPaths.reduceOrNull { current, next ->
            index.commonAncestor(NodePath(current), NodePath(next))?.path?.childIndices ?: current
        }
        if (commonHeaderPath != null && commonHeaderPath.size > listRootPath.size &&
            isWithin(commonHeaderPath, listRootPath)
        ) {
            roots += commonHeaderPath
        }
        return roots.toList()
    }

    private fun extractListRows(
        nodes: List<AccessibilityNodeSnapshot>,
        index: NodeTreeIndex,
        completeTree: Boolean
    ): List<MomoSayHiListRow> {
        val visible = nodes.filter { it.visible }
        val listRootPath = nodes.firstOrNull { localId(it.viewId) == localId(MomoSayHiPageContract.LIST_VIEW_ID) }
            ?.path?.childIndices ?: emptyList()
        val listRootDepth = listRootPath.size
        val excludedRoots = recommendationExclusionRoots(nodes, index, listRootPath)
        fun isExcluded(path: List<Int>): Boolean = excludedRoots.any { root -> isWithin(path, root) }
        val names = visible.filter { nameNode ->
            localId(nameNode.viewId) in nameIds &&
                !(nameNode.text ?: nameNode.contentDescription).isNullOrBlank() &&
                !isExcluded(nameNode.path.childIndices)
        }
        return names.map { nameNode ->
            val relatedAncestors = nodes.filter {
                it.visible || localId(it.viewId) == unreadMarkerId
            }.mapNotNull { candidate ->
                if (candidate === nameNode || isExcluded(candidate.path.childIndices) ||
                    !isRecognizedListField(candidate)
                ) return@mapNotNull null
                index.commonAncestor(nameNode.path, candidate.path)
                    ?.path?.childIndices?.takeIf { it.size > listRootDepth }
            }
            // The row is the shallowest common ancestor below listview. Picking the deepest
            // ancestor can accidentally keep only a nested label wrapper and lose sibling fields.
            val rowPath = relatedAncestors.minByOrNull { it.size }
                ?: nameNode.path.childIndices.dropLast(1)
            val allRowNodes = index.descendantsOf(NodePath(rowPath), includeSelf = true)
            val rowNodes = allRowNodes.filter { it.visible }
            val hasUnreadMarker = allRowNodes.any { localId(it.viewId) == unreadMarkerId }
            val unread: FieldEvidence<Boolean> = when {
                hasUnreadMarker -> FieldEvidence(
                    true, null, EvidenceStatus.CONFIRMED, "ACCESSIBILITY_VIEW_ID:$unreadMarkerId"
                )
                completeTree -> FieldEvidence(
                    false, null, EvidenceStatus.CONFIRMED, "ABSENT_ACCESSIBILITY_VIEW_ID:$unreadMarkerId"
                )
                else -> FieldEvidence(
                    null, null, EvidenceStatus.UNKNOWN, "INCOMPLETE_ACCESSIBILITY_TREE:$unreadMarkerId"
                )
            }
            val rowTexts = rowNodes.flatMap { node ->
                sequenceOf(node.text, node.contentDescription)
                    .filterNotNull()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toList()
            }.distinct()
            val metadata = MomoSayHiProfileParser.parseListMetadata(rowTexts)
            val nodeDistanceText = firstEvidence(rowNodes, distanceIds)
            val distanceText = if (nodeDistanceText.status == EvidenceStatus.UNKNOWN &&
                metadata.distanceKm.status != EvidenceStatus.UNKNOWN
            ) {
                FieldEvidence(metadata.distanceKm.rawText, metadata.distanceKm.rawText, metadata.distanceKm.status)
            } else nodeDistanceText
            val rowNodePath = NodePath(rowPath)
            MomoSayHiListRow(
                rowPath = rowPath,
                bounds = index.nodeAt(rowNodePath)?.bounds ?: nameNode.bounds,
                displayName = textEvidence(nameNode.text ?: nameNode.contentDescription),
                age = metadata.age,
                gender = metadata.gender,
                distanceText = distanceText,
                distanceKm = metadata.distanceKm,
                online = metadata.online,
                greetingText = firstEvidence(rowNodes, greetingIds),
                timestampText = firstEvidence(rowNodes, timestampIds),
                unread = unread,
                hasAvatarNode = rowNodes.any { localId(it.viewId) in avatarIds },
                namePath = nameNode.path,
                nameBounds = nameNode.bounds,
                avatarTapTarget = MomoSayHiAvatarTargetPolicy.resolveAvatarTarget(index, rowNodePath),
                rowTapTarget = MomoSayHiAvatarTargetPolicy.resolveRowTarget(index, rowNodePath, nameNode.path),
                filterEvidence = if (completeTree) MomoConversationFilterEvidenceParser.fromListRow(rowNodes)
                    else ConversationFilterEvidence()
            )
        }.distinctBy { it.rowPath }
    }

    private fun isWithin(path: List<Int>, ancestor: List<Int>): Boolean =
        path.size >= ancestor.size && path.take(ancestor.size) == ancestor

    private fun isRecognizedListField(node: AccessibilityNodeSnapshot): Boolean =
        localId(node.viewId) in distanceIds || localId(node.viewId) in greetingIds ||
            localId(node.viewId) in timestampIds || localId(node.viewId) in avatarIds ||
            localId(node.viewId) == unreadMarkerId

    private fun firstEvidence(
        rowNodes: List<AccessibilityNodeSnapshot>, ids: Set<String>
    ): FieldEvidence<String> {
        val values = rowNodes.filter { localId(it.viewId) in ids }
            .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank)
                ?: it.contentDescription?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
        return when {
            values.isEmpty() -> FieldEvidence(null, null, EvidenceStatus.UNKNOWN)
            values.size > 1 -> FieldEvidence(null, values.joinToString(" | "), EvidenceStatus.CONFLICT)
            else -> FieldEvidence(values.first(), values.first(), EvidenceStatus.CONFIRMED)
        }
    }

    private fun textEvidence(value: String?): FieldEvidence<String> =
        value?.trim()?.takeIf(String::isNotBlank)?.let {
            FieldEvidence(it, it, EvidenceStatus.CONFIRMED)
        } ?: FieldEvidence(null, null, EvidenceStatus.UNKNOWN)

    private fun localId(viewId: String): String = viewId.substringAfterLast('/')

}
