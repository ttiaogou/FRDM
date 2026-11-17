package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatListUnreadDiagnostics
import com.freedom.features.aireply.ports.ChatListUnreadNodeEvidence
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatUnreadStatus
import java.util.ArrayDeque

internal data class MomoChatListSessionExtraction(
    val viewport: ScreenRect?,
    val candidates: List<ChatListCandidateSnapshot>,
    val scannedNodeCount: Int,
    val truncated: Boolean,
    val invalidRowBoundsCount: Int = 0,
    val unreadDiagnostics: ChatListUnreadDiagnostics = ChatListUnreadDiagnostics(),
    val reason: String? = null
)

/**
 * Read-only extraction of Momo's main inbox RecyclerView.
 *
 * The extractor only accepts the exact main Activity and RecyclerView ViewId observed in the
 * supplied device logs. It first resolves each holder's exact item_layout user container, then
 * groups avatar/name/preview/timestamp/unread nodes only inside that container. Raw text stays in
 * memory for the caller and is never written to ordinary logs.
 */
internal class MomoChatListSessionExtractor(
    private val maxRootNodes: Int = MAX_ROOT_NODES,
    private val maxRows: Int = MAX_ROWS,
    private val maxNodesPerRow: Int = MAX_NODES_PER_ROW
) {
    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val path: List<Int>,
        val ancestorViewIds: List<String> = emptyList()
    )
    private data class FoundList(
        val node: AccessibilityNodeInfo,
        val path: List<Int>,
        val visited: Int,
        val truncated: Boolean
    )
    private data class FoundUserContainer(
        val node: AccessibilityNodeInfo,
        val path: List<Int>,
        val className: String
    )
    private data class UserContainerSearchResult(
        val container: FoundUserContainer?,
        val visited: Int,
        val truncated: Boolean,
        val auxiliaryContentOnly: Boolean = false
    )

    private data class UnreadFieldObservation(
        val text: String?,
        val contentDescription: String?,
        val boundsValid: Boolean,
        val intersectsRow: Boolean,
        val containedInRow: Boolean,
        val visibleToUser: Boolean
    )

    private data class TextNodeSample(
        val value: String?,
        val source: MomoChatListTextSource,
        val path: List<Int>?,
        val bounds: ScreenRect?,
        val visibleToUser: Boolean,
        val intersectsViewport: Boolean,
        val fullyInsideViewport: Boolean,
        val boundsValid: Boolean
    )

    fun extractRoot(
        root: AccessibilityNodeInfo,
        targetPackage: String,
        activityClass: String,
        window: WindowRef
    ): MomoChatListSessionExtraction {
        val packageName = safeString { root.packageName?.toString().orEmpty() }
        if (packageName != targetPackage) {
            recycle(root)
            return empty("目标包名不匹配")
        }

        val found = findInboxRecyclerView(root)
            ?: return empty("未找到已验证的陌陌会话 RecyclerView")
        val listNode = found.node
        return try {
            val viewport = boundsOf(listNode)
            if (viewport.isEmpty) {
                return MomoChatListSessionExtraction(
                    viewport = viewport,
                    candidates = emptyList(),
                    scannedNodeCount = found.visited,
                    truncated = found.truncated,
                    reason = "RecyclerView 视口边界为空"
                )
            }

            if (!MomoChatListSessionPolicy.hasValidBounds(viewport)) {
                return MomoChatListSessionExtraction(
                    viewport = viewport,
                    candidates = emptyList(),
                    scannedNodeCount = found.visited,
                    truncated = found.truncated,
                    reason = "RecyclerView 视口边界无效"
                )
            }

            val rowCount = safeInt { listNode.childCount }.coerceAtMost(maxRows)
            var scanned = found.visited
            var truncated = found.truncated || safeInt { listNode.childCount } > maxRows
            var invalidRowBoundsCount = 0
            val candidates = ArrayList<RowCandidate>()
            var unreadDiagnostics = ChatListUnreadDiagnostics()

            for (rowIndex in 0 until rowCount) {
                val row = safeNode { listNode.getChild(rowIndex) } ?: continue
                val rowClass = safeString { row.className?.toString().orEmpty() }
                val rowViewId = safeString { row.viewIdResourceName.orEmpty() }
                // Momo's notification entry is a known non-session holder. It is intentionally
                // omitted from the private conversation audit rather than treated as an unknown row.
                if (MomoChatListSessionPolicy.isKnownNonSessionHolder(rowViewId)) {
                    recycle(row)
                    continue
                }
                // Only the exact active_rv observed in Momo is a verified auxiliary list.
                // An unfamiliar RecyclerView holder remains an incomplete audit rather than
                // silently disappearing from the no-unread evidence set.
                if (rowClass == RECYCLER_VIEW_CLASS) {
                    if (!MomoChatListSessionPolicy.isAuxiliaryRecyclerView(rowViewId)) {
                        truncated = true
                    }
                    recycle(row)
                    continue
                }
                val rowPath = found.path + rowIndex
                // Bind all user fields and the unread badge to the exact item_layout container.
                // The outer RecyclerView child is only a holder and is never treated as identity.
                val containerSearch = findUserContainer(row, rowPath)
                scanned += containerSearch.visited
                truncated = truncated || containerSearch.truncated
                val userContainer = containerSearch.container
                if (userContainer == null) {
                    if (!containerSearch.auxiliaryContentOnly) {
                        // An unknown holder without one exact item_layout cannot be assigned a
                        // per-user identity; retain the conservative incomplete-page behavior.
                        truncated = true
                    }
                    continue
                }
                val itemLayout = userContainer.node
                val rowBounds = boundsOf(itemLayout)
                val rowVisibleToUser = safeBoolean { itemLayout.isVisibleToUser }
                val rowBoundsValid = MomoChatListSessionPolicy.hasValidBounds(rowBounds)
                val inViewport = MomoChatListSessionPolicy.intersectsViewport(rowBounds, viewport)
                if (!rowBoundsValid) invalidRowBoundsCount++
                if (!rowBoundsValid || !inViewport) {
                    recycle(itemLayout)
                    continue
                }

                val rowResult = inspectRow(
                    row = itemLayout,
                    rowPath = userContainer.path,
                    rowIndex = rowIndex,
                    rowBounds = rowBounds,
                    rowClass = userContainer.className,
                    rowVisible = rowVisibleToUser,
                    viewport = viewport,
                    pageScanAlreadyTruncated = truncated
                )
                scanned += rowResult.scannedNodeCount
                truncated = truncated || rowResult.truncated
                unreadDiagnostics += rowResult.unreadDiagnostics
                if (rowResult.candidate != null) {
                    candidates += rowResult.candidate
                } else if (!rowResult.auxiliaryContentOnly) {
                    // A visible user container without enough row evidence cannot be excluded
                    // from the unread audit; known active-card holders are the sole exception.
                    truncated = true
                }
            }

            // Absence of status_new can prove READ only if the complete page audit succeeded.
            // A later row/container truncation invalidates earlier absence-based READ in this
            // same snapshot; explicit, visible numeric/empty badge evidence remains usable.
            val snapshots = candidates.map { candidate ->
                candidate.toSnapshot(window, viewport, allowAbsentRead = !truncated)
            }
            val absenceReadRows = if (truncated) candidates.count {
                it.unreadEvidence.source == com.freedom.features.aireply.ports.ChatUnreadEvidenceSource.STATUS_BADGE_ABSENT
            } else 0
            val finalUnreadDiagnostics = if (absenceReadRows > 0) {
                unreadDiagnostics.copy(
                    statusNewAbsentReadCount = 0,
                    statusNewUnknownCount = unreadDiagnostics.statusNewUnknownCount + absenceReadRows
                )
            } else unreadDiagnostics
            MomoChatListSessionExtraction(
                viewport = viewport,
                candidates = snapshots,
                scannedNodeCount = scanned,
                truncated = truncated,
                invalidRowBoundsCount = invalidRowBoundsCount,
                unreadDiagnostics = finalUnreadDiagnostics,
                reason = if (snapshots.isEmpty()) "视口内没有提取到会话候选" else null
            )
        } finally {
            recycle(listNode)
        }
    }

    private data class RowInspection(
        val candidate: RowCandidate?,
        val scannedNodeCount: Int,
        val truncated: Boolean,
        val unreadDiagnostics: ChatListUnreadDiagnostics = ChatListUnreadDiagnostics(),
        val auxiliaryContentOnly: Boolean = false
    )

    private data class RowCandidate(
        val rowIndex: Int,
        val rowPath: List<Int>,
        val rowClassName: String,
        val rowBounds: ScreenRect,
        val rowVisibleToUser: Boolean,
        val intersectsViewport: Boolean,
        val avatarPath: List<Int>?,
        val avatarBounds: ScreenRect?,
        val name: TextNodeSample,
        val preview: TextNodeSample,
        val timestamp: TextNodeSample,
        val classification: MomoChatListRowClassification,
        val unreadEvidence: MomoChatUnreadEvidence,
        val unreadNodeEvidence: List<ChatListUnreadNodeEvidence>,
        val statusNewNodePresent: Boolean,
        val statusNewNodeObservationCount: Int,
        val statusPointNodeObservationCount: Int,
        val statusPointVisibleNodeCount: Int,
        val statusPointValidBoundsNodeCount: Int,
        val statusPointContainedInRowNodeCount: Int,
        val firePath: List<Int>?,
        val fireBounds: ScreenRect?
    ) {
        fun toSnapshot(
            window: WindowRef,
            viewport: ScreenRect,
            allowAbsentRead: Boolean
        ): ChatListCandidateSnapshot {
            val absenceBasedRead = unreadEvidence.source ==
                com.freedom.features.aireply.ports.ChatUnreadEvidenceSource.STATUS_BADGE_ABSENT
            val effectiveUnread = if (!allowAbsentRead && absenceBasedRead) {
                MomoChatUnreadEvidence()
            } else unreadEvidence
            val fireStatus = classifyMomoFireStatus(
                firePresent = firePath != null,
                unreadStatus = effectiveUnread.status,
                unreadCount = effectiveUnread.count
            )
            return ChatListCandidateSnapshot(
            rowIndex = rowIndex,
            rowTarget = NodeTarget(
                window = window,
                path = NodePath(rowPath),
                expectedViewId = ITEM_LAYOUT_FULL_VIEW_ID,
                expectedClassName = rowClassName.takeIf(String::isNotBlank)
            ),
            rowBounds = rowBounds,
            rowVisibleToUser = rowVisibleToUser,
            intersectsViewport = intersectsViewport,
            fullyInsideViewport = MomoChatListSessionPolicy.fullyInsideViewport(rowBounds, viewport),
            rowBoundsValid = MomoChatListSessionPolicy.hasValidBounds(rowBounds),
            avatarBounds = avatarBounds,
            avatarPath = avatarPath?.let(::NodePath),
            nameText = name.value,
            nameSource = name.source.toGeneric(),
            nameNodePath = name.path?.let(::NodePath),
            nameBounds = name.bounds,
            nameNodeVisibleToUser = name.visibleToUser,
            nameBoundsIntersectViewport = name.intersectsViewport,
            nameBoundsFullyInsideViewport = name.fullyInsideViewport,
            nameBoundsValid = name.boundsValid,
            previewText = preview.value,
            previewSource = preview.source.toGeneric(),
            previewNodePath = preview.path?.let(::NodePath),
            previewBounds = preview.bounds,
            previewNodeVisibleToUser = preview.visibleToUser,
            previewBoundsIntersectViewport = preview.intersectsViewport,
            previewBoundsFullyInsideViewport = preview.fullyInsideViewport,
            previewBoundsValid = preview.boundsValid,
            timestampText = timestamp.value,
            timestampSource = timestamp.source.toGeneric(),
            timestampNodePath = timestamp.path?.let(::NodePath),
            timestampBounds = timestamp.bounds,
            timestampNodeVisibleToUser = timestamp.visibleToUser,
            selectionClass = classification.selectionClass,
            selectionReason = classification.reason,
            unreadStatus = effectiveUnread.status,
            unreadCount = effectiveUnread.count,
            unreadEvidenceSource = effectiveUnread.source,
            fireStatus = fireStatus,
            fireUnreadCount = effectiveUnread.count.takeIf { fireStatus == com.freedom.features.aireply.ports.ChatFireStatus.FIRE_UNREAD },
            fireNodePath = firePath?.let(::NodePath),
            fireBounds = fireBounds,
            unreadNodeEvidence = unreadNodeEvidence,
            statusNewNodePresent = statusNewNodePresent,
            statusNewNodeObservationCount = statusNewNodeObservationCount,
            statusPointNodeObservationCount = statusPointNodeObservationCount,
            statusPointVisibleNodeCount = statusPointVisibleNodeCount,
            statusPointValidBoundsNodeCount = statusPointValidBoundsNodeCount,
            statusPointContainedInRowNodeCount = statusPointContainedInRowNodeCount
            )
        }
    }

    /** Finds exactly one item_layout inside the RecyclerView holder and transfers its node. */
    private fun findUserContainer(
        root: AccessibilityNodeInfo,
        rootPath: List<Int>
    ): UserContainerSearchResult {
        val rootViewId = safeString { root.viewIdResourceName.orEmpty() }
        val rootClassName = safeString { root.className?.toString().orEmpty() }
        if (rootViewId.substringAfterLast('/') == ITEM_LAYOUT_VIEW_ID) {
            return UserContainerSearchResult(
                FoundUserContainer(root, rootPath, rootClassName),
                visited = 1,
                truncated = false
            )
        }

        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(root, rootPath))
        var visited = 0
        var truncated = false
        var selected: FoundUserContainer? = null
        var duplicateContainers = false
        var auxiliaryContentFound = false
        while (queue.isNotEmpty() && visited < maxNodesPerRow) {
            val pending = queue.removeFirst()
            val node = pending.node
            visited++
            val viewId = safeString { node.viewIdResourceName.orEmpty() }
            val className = safeString { node.className?.toString().orEmpty() }
            if (MomoChatListSessionPolicy.isAiFinderFloatingView(viewId)) {
                visited--
                recycle(node)
                continue
            }
            if (MomoChatListSessionPolicy.isKnownNonSessionHolder(viewId) ||
                MomoChatListSessionPolicy.isAuxiliaryRecyclerView(viewId)
            ) {
                auxiliaryContentFound = true
                recycle(node)
                continue
            }
            if (viewId.substringAfterLast('/') == ITEM_LAYOUT_VIEW_ID) {
                if (selected == null) {
                    // Transfer ownership: this node remains alive for inspectRow().
                    selected = FoundUserContainer(node, pending.path, className)
                } else {
                    duplicateContainers = true
                    recycle(node)
                }
                // A container's descendants are fields of that user, not sibling containers.
                continue
            }
            try {
                if (className == RECYCLER_VIEW_CLASS && pending.path != rootPath) continue
                val childCount = safeInt { node.childCount }
                for (index in 0 until childCount) {
                    if (visited + queue.size >= maxNodesPerRow) {
                        truncated = true
                        break
                    }
                    safeNode { node.getChild(index) }?.let { child ->
                        queue.add(
                            PendingNode(
                                child,
                                pending.path + index,
                                pending.ancestorViewIds + listOf(viewId.substringAfterLast(':').substringAfterLast('/')).filter(String::isNotBlank)
                            )
                        )
                    }
                }
            } finally {
                recycle(node)
            }
        }
        if (queue.isNotEmpty()) truncated = true
        while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
        if (duplicateContainers || truncated) {
            selected?.node?.let(::recycle)
            return UserContainerSearchResult(null, visited, true)
        }
        if (selected == null && auxiliaryContentFound) {
            return UserContainerSearchResult(null, visited, false, auxiliaryContentOnly = true)
        }
        return UserContainerSearchResult(selected, visited, false)
    }

    private fun inspectRow(
        row: AccessibilityNodeInfo,
        rowPath: List<Int>,
        rowIndex: Int,
        rowBounds: ScreenRect,
        rowClass: String,
        rowVisible: Boolean,
        viewport: ScreenRect,
        pageScanAlreadyTruncated: Boolean
    ): RowInspection {
        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(row, rowPath))
        var visited = 0
        var truncated = false
        var auxiliaryRecyclerFound = false
        var avatarPath: List<Int>? = null
        var avatarBounds: ScreenRect? = null
        var name = emptyTextSample()
        var preview = emptyTextSample()
        var timestamp = emptyTextSample()
        var unreadEvidence = MomoChatUnreadEvidence()
        val unreadNodeEvidence = ArrayList<ChatListUnreadNodeEvidence>()
        var statusNewNodePresent = false
        var statusNewNodeObservationCount = 0
        var statusPointNodeObservationCount = 0
        var statusPointVisibleNodeCount = 0
        var statusPointValidBoundsNodeCount = 0
        var statusPointContainedInRowNodeCount = 0
        var firePath: List<Int>? = null
        var fireBounds: ScreenRect? = null
        var statusPointContainerObservationCount = 0
        var statusPointContainerVisible = false
        var statusPointContainerBounds: ScreenRect? = null
        var statusPointContainedInVisibleContainerNodeCount = 0
        var unreadDiagnostics = ChatListUnreadDiagnostics()
        // Preview text is normally a message body and never an unread badge. Retain a
        // transient observation so only the exact system entry "收到的招呼" may interpret
        // an explicit greeting summary such as "有未读招呼" after row classification.
        val greetingPreviewUnreadObservations = ArrayList<UnreadFieldObservation>()

        try {
            while (queue.isNotEmpty() && visited < maxNodesPerRow) {
                val pending = queue.removeFirst()
                val node = pending.node
                visited++
                try {
                    val className = safeString { node.className?.toString().orEmpty() }
                    val viewId = safeString { node.viewIdResourceName.orEmpty() }
                    if (MomoChatListSessionPolicy.isAiFinderFloatingView(viewId)) {
                        visited--
                        continue
                    }
                    val nodeText = safeString { node.text?.toString() }
                    val nodeDescription = safeString { node.contentDescription?.toString() }
                    val nodeBounds = boundsOf(node)
                    val isStatusNewNode = viewId.substringAfterLast('/') == STATUS_NEW_VIEW_ID
                    if (MomoChatListSessionPolicy.isFireFeatureViewId(viewId) && firePath == null) {
                        firePath = pending.path
                        fireBounds = nodeBounds
                    }
                    if (isStatusNewNode) {
                        statusNewNodePresent = true
                        statusNewNodeObservationCount++
                    }
                    if (viewId.substringAfterLast('/') == STATUS_POINT_CONTAINER_VIEW_ID) {
                        statusPointContainerObservationCount++
                        val parentVisibleAndContained = safeBoolean { node.isVisibleToUser } &&
                            MomoChatListSessionPolicy.hasValidBounds(nodeBounds) &&
                            nodeBounds.left >= rowBounds.left && nodeBounds.top >= rowBounds.top &&
                            nodeBounds.right <= rowBounds.right && nodeBounds.bottom <= rowBounds.bottom
                        if (parentVisibleAndContained) {
                            statusPointContainerVisible = true
                            statusPointContainerBounds = nodeBounds
                        }
                    }
                    // The ImageView child may report visibleToUser=false even when its
                    // visible right-top parent holder is rendered. Keep its own geometry,
                    // and validate the visible parent after row classification.
                    if (viewId.substringAfterLast('/') == STATUS_POINT_VIEW_ID) {
                        statusPointNodeObservationCount++
                        val pointBoundsValid = MomoChatListSessionPolicy.hasValidBounds(nodeBounds)
                        val pointContainedInRow = pointBoundsValid &&
                            nodeBounds.left >= rowBounds.left && nodeBounds.top >= rowBounds.top &&
                            nodeBounds.right <= rowBounds.right && nodeBounds.bottom <= rowBounds.bottom
                        if (safeBoolean { node.isVisibleToUser }) statusPointVisibleNodeCount++
                        if (pointBoundsValid) statusPointValidBoundsNodeCount++
                        if (pointContainedInRow) statusPointContainedInRowNodeCount++
                        val visibleContainer = statusPointContainerBounds
                        if (pointBoundsValid && visibleContainer != null &&
                            nodeBounds.left >= visibleContainer.left &&
                            nodeBounds.top >= visibleContainer.top &&
                            nodeBounds.right <= visibleContainer.right &&
                            nodeBounds.bottom <= visibleContainer.bottom
                        ) statusPointContainedInVisibleContainerNodeCount++
                    }
                    val unreadSample = MomoChatListSessionPolicy.inspectUnreadEvidence(
                        viewId = viewId,
                        text = nodeText,
                        contentDescription = nodeDescription,
                        boundsValid = MomoChatListSessionPolicy.hasValidBounds(nodeBounds),
                        intersectsRow = MomoChatListSessionPolicy.intersectsViewport(nodeBounds, rowBounds),
                        isKnownContentField = viewId == NAME_VIEW_ID ||
                            viewId == TIMESTAMP_VIEW_ID || viewId == PREVIEW_VIEW_ID,
                        containedInRow = MomoChatListSessionPolicy.hasValidBounds(nodeBounds) &&
                            nodeBounds.left >= rowBounds.left &&
                            nodeBounds.top >= rowBounds.top &&
                            nodeBounds.right <= rowBounds.right &&
                            nodeBounds.bottom <= rowBounds.bottom,
                        visibleToUser = safeBoolean { node.isVisibleToUser }
                    )
                    val isUnreadEvidenceNode = unreadSample.status != ChatUnreadStatus.UNKNOWN ||
                        unreadSample.statusNewDisposition != MomoStatusNewDisposition.NONE ||
                        viewId.contains("unread", ignoreCase = true) ||
                        viewId.contains("msg_badge", ignoreCase = true) ||
                        viewId.contains("message_badge", ignoreCase = true)
                    if (isUnreadEvidenceNode) {
                        unreadNodeEvidence += ChatListUnreadNodeEvidence(
                            viewIdResourceName = viewId,
                            status = unreadSample.status,
                            count = unreadSample.count,
                            source = unreadSample.source,
                            bounds = nodeBounds,
                            boundsValid = MomoChatListSessionPolicy.hasValidBounds(nodeBounds),
                            intersectsRow = MomoChatListSessionPolicy.intersectsViewport(nodeBounds, rowBounds),
                            containedInRow = MomoChatListSessionPolicy.hasValidBounds(nodeBounds) &&
                                nodeBounds.left >= rowBounds.left &&
                                nodeBounds.top >= rowBounds.top &&
                                nodeBounds.right <= rowBounds.right &&
                                nodeBounds.bottom <= rowBounds.bottom,
                            visibleToUser = safeBoolean { node.isVisibleToUser },
                            statusNewDisposition = unreadSample.statusNewDisposition.name
                        )
                    }
                    when (unreadSample.statusNewDisposition) {
                        MomoStatusNewDisposition.NONE -> Unit
                        MomoStatusNewDisposition.UNREAD -> unreadDiagnostics += ChatListUnreadDiagnostics(
                            statusNewNodeCount = 1, statusNewUnreadCount = 1
                        )
                        MomoStatusNewDisposition.READ -> unreadDiagnostics += ChatListUnreadDiagnostics(
                            statusNewNodeCount = 1, statusNewReadCount = 1
                        )
                        MomoStatusNewDisposition.UNKNOWN_INVALID_BOUNDS -> unreadDiagnostics += ChatListUnreadDiagnostics(
                            statusNewNodeCount = 1, statusNewUnknownCount = 1, statusNewInvalidBoundsCount = 1
                        )
                        MomoStatusNewDisposition.UNKNOWN_OUTSIDE_ROW -> unreadDiagnostics += ChatListUnreadDiagnostics(
                            statusNewNodeCount = 1, statusNewUnknownCount = 1, statusNewOutsideRowCount = 1
                        )
                        MomoStatusNewDisposition.UNKNOWN_NOT_VISIBLE -> unreadDiagnostics += ChatListUnreadDiagnostics(
                            statusNewNodeCount = 1, statusNewUnknownCount = 1, statusNewNotVisibleCount = 1
                        )
                        MomoStatusNewDisposition.READ_EMPTY_VALUE -> unreadDiagnostics += ChatListUnreadDiagnostics(
                            statusNewNodeCount = 1, statusNewReadCount = 1, statusNewEmptyValueCount = 1
                        )
                        MomoStatusNewDisposition.UNKNOWN_NON_NUMERIC_VALUE -> unreadDiagnostics += ChatListUnreadDiagnostics(
                            statusNewNodeCount = 1, statusNewUnknownCount = 1, statusNewNonNumericValueCount = 1
                        )
                    }
                    if (viewId == PREVIEW_VIEW_ID) {
                        greetingPreviewUnreadObservations += UnreadFieldObservation(
                            text = nodeText,
                            contentDescription = nodeDescription,
                            boundsValid = MomoChatListSessionPolicy.hasValidBounds(nodeBounds),
                            intersectsRow = MomoChatListSessionPolicy.intersectsViewport(nodeBounds, rowBounds),
                            containedInRow = MomoChatListSessionPolicy.hasValidBounds(nodeBounds) &&
                                nodeBounds.left >= rowBounds.left && nodeBounds.top >= rowBounds.top &&
                                nodeBounds.right <= rowBounds.right && nodeBounds.bottom <= rowBounds.bottom,
                            visibleToUser = safeBoolean { node.isVisibleToUser }
                        )
                    } else {
                        unreadEvidence = MomoChatListSessionPolicy.mergeUnreadEvidence(
                            unreadEvidence, unreadSample
                        )
                    }
                    if (viewId == AVATAR_VIEW_ID && avatarPath == null) {
                        avatarPath = pending.path
                        avatarBounds = boundsOf(node)
                    } else if (viewId == NAME_VIEW_ID) {
                        name = chooseBetterText(name, readTextSample(node, pending.path, viewport))
                    } else if (viewId == PREVIEW_VIEW_ID) {
                        preview = chooseBetterText(preview, readTextSample(node, pending.path, viewport))
                    } else if (viewId == TIMESTAMP_VIEW_ID) {
                        timestamp = chooseBetterText(timestamp, readTextSample(node, pending.path, viewport))
                    }

                    val childCount = safeInt { node.childCount }
                    // Stop at nested lists. The exact active_rv id identifies Momo's live
                    // horizontal cards, which are not inbox rows even when wrapped by item_layout.
                    if (pending.path != rowPath && className == RECYCLER_VIEW_CLASS) {
                        if (MomoChatListSessionPolicy.isAuxiliaryRecyclerView(viewId)) {
                            auxiliaryRecyclerFound = true
                        }
                        continue
                    }
                    for (index in 0 until childCount) {
                        if (visited + queue.size >= maxNodesPerRow) {
                            truncated = true
                            break
                        }
                        safeNode { node.getChild(index) }?.let { child ->
                            queue.add(
                                PendingNode(
                                    child,
                                    pending.path + index,
                                    pending.ancestorViewIds + listOf(
                                        viewId.substringAfterLast(':').substringAfterLast('/')
                                    ).filter(String::isNotBlank)
                                )
                            )
                        }
                    }
                } finally {
                    recycle(node)
                }
            }
            if (queue.isNotEmpty()) truncated = true
        } finally {
            while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
        }

        val hasAvatar = avatarPath != null
        if (!MomoChatListSessionPolicy.isSessionCandidate(hasAvatar, name.valueAsPolicy(), preview.valueAsPolicy())) {
            return RowInspection(
                candidate = null,
                scannedNodeCount = visited,
                truncated = truncated,
                unreadDiagnostics = unreadDiagnostics,
                auxiliaryContentOnly = auxiliaryRecyclerFound
            )
        }
        val timestampBoundsWithinRow = timestamp.bounds?.let { bounds ->
            MomoChatListSessionPolicy.hasValidBounds(bounds) &&
                bounds.left >= rowBounds.left && bounds.top >= rowBounds.top &&
                bounds.right <= rowBounds.right && bounds.bottom <= rowBounds.bottom
        } == true
        val classification = MomoChatListSessionPolicy.classifyRow(
            name.value,
            preview.value,
            timestamp.value,
            timestamp.boundsValid,
            timestampBoundsWithinRow
        )
        val hasOtherUnreadMarker = unreadNodeEvidence.any { evidence ->
            MomoChatListSessionPolicy.isOtherUnreadMarker(
                classification.selectionClass, evidence.viewIdResourceName
            )
        }
        val rowFullyVisible = rowVisible &&
            MomoChatListSessionPolicy.fullyInsideViewport(rowBounds, viewport)
        val nameFullyVisible = name.value != null && name.visibleToUser && name.boundsValid &&
            name.fullyInsideViewport && name.bounds?.let { bounds ->
                bounds.left >= rowBounds.left && bounds.top >= rowBounds.top &&
                    bounds.right <= rowBounds.right && bounds.bottom <= rowBounds.bottom
            } == true
        val previewFullyVisible = preview.value != null && preview.visibleToUser && preview.boundsValid &&
            preview.fullyInsideViewport && preview.bounds?.let { bounds ->
                bounds.left >= rowBounds.left && bounds.top >= rowBounds.top &&
                    bounds.right <= rowBounds.right && bounds.bottom <= rowBounds.bottom
            } == true
        val timestampFullyVisible = timestamp.value != null && timestamp.visibleToUser && timestamp.boundsValid &&
            timestamp.fullyInsideViewport && timestamp.bounds?.let { bounds ->
                bounds.left >= rowBounds.left && bounds.top >= rowBounds.top &&
                    bounds.right <= rowBounds.right && bounds.bottom <= rowBounds.bottom
            } == true
        if (MomoChatListSessionPolicy.missingStatusNewMeansRead(
                selectionClass = classification.selectionClass,
                statusNewNodeCount = statusNewNodeObservationCount,
                scanTruncated = truncated || pageScanAlreadyTruncated,
                otherUnreadMarkerObserved = hasOtherUnreadMarker,
                rowVisibleToUser = rowVisible,
                rowFullyInsideViewport = rowFullyVisible,
                nameFullyVisible = nameFullyVisible,
                previewFullyVisible = previewFullyVisible,
                timestampFullyVisible = timestampFullyVisible
            ) && unreadEvidence.status == ChatUnreadStatus.UNKNOWN
        ) {
            unreadEvidence = MomoChatUnreadEvidence(
                status = ChatUnreadStatus.READ,
                count = 0,
                source = com.freedom.features.aireply.ports.ChatUnreadEvidenceSource.STATUS_BADGE_ABSENT
            )
            unreadDiagnostics += ChatListUnreadDiagnostics(statusNewAbsentReadCount = 1)
        }
        if (MomoChatListSessionPolicy.isConfirmedSubscriptionEntryStatusPointUnread(
                selectionClass = classification.selectionClass,
                normalizedRowName = name.value,
                rowFullyVisible = rowFullyVisible,
                statusPointNodeCount = statusPointNodeObservationCount,
                statusPointBoundsValid = statusPointValidBoundsNodeCount == 1,
                statusPointContainedInVisibleContainer =
                    statusPointContainedInVisibleContainerNodeCount == 1,
                statusPointContainerVisible = statusPointContainerVisible &&
                    statusPointContainerObservationCount == 1
            )
        ) {
            unreadEvidence = MomoChatListSessionPolicy.mergeUnreadEvidence(
                unreadEvidence,
                MomoChatUnreadEvidence(
                    status = ChatUnreadStatus.UNREAD,
                    source = com.freedom.features.aireply.ports.ChatUnreadEvidenceSource.SYSTEM_ENTRY_STATUS_POINT
                )
            )
            unreadDiagnostics += ChatListUnreadDiagnostics(statusPointConfirmedSystemUnreadCount = 1)
        }
        if (name.value.orEmpty().trim().replace(Regex("\\s+"), " ") == "收到的招呼") {
            val greetingPreviewUnreadEvidence = greetingPreviewUnreadObservations.fold(
                MomoChatUnreadEvidence()
            ) { current, observation ->
                MomoChatListSessionPolicy.mergeUnreadEvidence(
                    current,
                    MomoChatListSessionPolicy.inspectUnreadEvidence(
                        viewId = PREVIEW_VIEW_ID,
                        text = observation.text,
                        contentDescription = observation.contentDescription,
                        boundsValid = observation.boundsValid,
                        intersectsRow = observation.intersectsRow,
                        isKnownContentField = false,
                        containedInRow = observation.containedInRow,
                        visibleToUser = observation.visibleToUser
                    )
                )
            }
            unreadEvidence = MomoChatListSessionPolicy.mergeUnreadEvidence(
                unreadEvidence, greetingPreviewUnreadEvidence
            )
        }
        return RowInspection(
            candidate = RowCandidate(
                rowIndex = rowIndex,
                rowPath = rowPath,
                rowClassName = rowClass,
                rowBounds = rowBounds,
                rowVisibleToUser = rowVisible,
                intersectsViewport = MomoChatListSessionPolicy.intersectsViewport(rowBounds, viewport),
                avatarPath = avatarPath,
                avatarBounds = avatarBounds,
                name = name,
                preview = preview,
                timestamp = timestamp,
                classification = classification,
                unreadEvidence = unreadEvidence,
                unreadNodeEvidence = unreadNodeEvidence.toList(),
                statusNewNodePresent = statusNewNodePresent,
                statusNewNodeObservationCount = statusNewNodeObservationCount,
            statusPointNodeObservationCount = statusPointNodeObservationCount,
            statusPointVisibleNodeCount = statusPointVisibleNodeCount,
            statusPointValidBoundsNodeCount = statusPointValidBoundsNodeCount,
                statusPointContainedInRowNodeCount = statusPointContainedInRowNodeCount,
                firePath = firePath,
                fireBounds = fireBounds
            ),
            scannedNodeCount = visited,
            truncated = truncated,
            unreadDiagnostics = unreadDiagnostics
        )
    }

    private fun findInboxRecyclerView(root: AccessibilityNodeInfo): FoundList? {
        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(root, emptyList(), emptyList()))
        var visited = 0
        var truncated = false
        while (queue.isNotEmpty() && visited < maxRootNodes) {
            val pending = queue.removeFirst()
            val node = pending.node
            visited++
            val className = safeString { node.className?.toString().orEmpty() }
            val viewId = safeString { node.viewIdResourceName.orEmpty() }
            if (MomoChatListSessionPolicy.isAiFinderFloatingView(viewId)) {
                visited--
                recycle(node)
                continue
            }
            if (className == RECYCLER_VIEW_CLASS && viewId == INBOX_RECYCLER_VIEW_ID &&
                "session_list_inner_container" in pending.ancestorViewIds &&
                "session_list_page_layout" in pending.ancestorViewIds &&
                "pagertabcontent" in pending.ancestorViewIds &&
                safeBoolean { node.isVisibleToUser } && safeBoolean { node.isScrollable }
            ) {
                while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
                return FoundList(node, pending.path, visited, truncated)
            }
            val childCount = safeInt { node.childCount }
            try {
                for (index in 0 until childCount) {
                    if (visited + queue.size >= maxRootNodes) {
                        truncated = true
                        break
                    }
                    safeNode { node.getChild(index) }?.let { child ->
                        queue.add(
                            PendingNode(
                                child,
                                pending.path + index,
                                pending.ancestorViewIds + listOf(viewId.substringAfterLast(':').substringAfterLast('/')).filter(String::isNotBlank)
                            )
                        )
                    }
                }
            } finally {
                recycle(node)
            }
        }
        while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
        return null
    }

    private fun readTextSample(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        viewport: ScreenRect
    ): TextNodeSample {
        val selection = MomoChatListSessionPolicy.selectText(
            text = safeString { node.text?.toString() },
            contentDescription = safeString { node.contentDescription?.toString() }
        )
        val bounds = boundsOf(node)
        return TextNodeSample(
            value = selection.value?.take(MAX_NODE_TEXT_LENGTH),
            source = selection.source,
            path = path,
            bounds = bounds,
            visibleToUser = safeBoolean { node.isVisibleToUser },
            intersectsViewport = MomoChatListSessionPolicy.intersectsViewport(bounds, viewport),
            fullyInsideViewport = MomoChatListSessionPolicy.fullyInsideViewport(bounds, viewport),
            boundsValid = MomoChatListSessionPolicy.hasValidBounds(bounds)
        )
    }

    private fun chooseBetterText(current: TextNodeSample, candidate: TextNodeSample): TextNodeSample {
        if (candidate.value.isNullOrBlank()) return current
        if (current.value.isNullOrBlank()) return candidate
        val currentScore = textScore(current)
        val candidateScore = textScore(candidate)
        return if (candidateScore > currentScore) candidate else current
    }

    private fun textScore(value: TextNodeSample): Int =
        (if (value.source == MomoChatListTextSource.NODE_TEXT) 4 else 2) +
            (if (value.visibleToUser) 1 else 0)

    private fun emptyTextSample() = TextNodeSample(
        value = null,
        source = MomoChatListTextSource.NONE,
        path = null,
        bounds = null,
        visibleToUser = false,
        intersectsViewport = false,
        fullyInsideViewport = false,
        boundsValid = false
    )

    private fun TextNodeSample.valueAsPolicy() = MomoChatListTextValue(value, source)

    private fun boundsOf(node: AccessibilityNodeInfo): ScreenRect {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return ScreenRect(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun empty(reason: String) = MomoChatListSessionExtraction(
        viewport = null,
        candidates = emptyList(),
        scannedNodeCount = 0,
        truncated = false,
        reason = reason
    )

    private fun recycle(node: AccessibilityNodeInfo) {
        runCatching { node.recycle() }
    }

    private inline fun safeString(block: () -> String?): String =
        runCatching { block().orEmpty() }.getOrDefault("")
    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? = runCatching(block).getOrNull()

    companion object {
        private const val STATUS_NEW_VIEW_ID = "chatlist_item_tv_status_new"
        private const val ITEM_LAYOUT_VIEW_ID = "item_layout"
        private const val ITEM_LAYOUT_FULL_VIEW_ID = "com.immomo.momo:id/item_layout"
        private const val STATUS_POINT_VIEW_ID = "chatlist_item_iv_status_point"
        private const val STATUS_POINT_CONTAINER_VIEW_ID = "chatlist_item_layout_righttop_part"
        const val MAX_ROOT_NODES = 800
        const val MAX_ROWS = 40
        const val MAX_NODES_PER_ROW = 80
        const val MAX_NODE_TEXT_LENGTH = 300

        const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
        const val INBOX_RECYCLER_VIEW_ID = "com.immomo.momo:id/recyclerview"
        const val AVATAR_VIEW_ID = "com.immomo.momo:id/chatlist_item_iv_face"
        const val NAME_VIEW_ID = "com.immomo.momo:id/chatlist_item_tv_name"
        const val PREVIEW_VIEW_ID = "com.immomo.momo:id/chatlist_item_tv_content"
        const val TIMESTAMP_VIEW_ID = "com.immomo.momo:id/chatlist_item_tv_timestamp"
    }
}

private fun MomoChatListTextSource.toGeneric(): ChatNodeTextSource = when (this) {
    MomoChatListTextSource.NODE_TEXT -> ChatNodeTextSource.NODE_TEXT
    MomoChatListTextSource.CONTENT_DESCRIPTION -> ChatNodeTextSource.CONTENT_DESCRIPTION
    MomoChatListTextSource.NONE -> ChatNodeTextSource.NONE
}
