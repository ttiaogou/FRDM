package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.adapters.accessibility.android.AndroidActiveWindowRootAccess
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.features.aireply.application.message.ChatMessageCandidate
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageStructureEvidence
import com.freedom.features.aireply.domain.message.ChatMessageOcrTarget
import com.freedom.features.aireply.domain.message.ChatMessageDirectionDiagnostic
import com.freedom.features.aireply.domain.message.ChatMessageIdentity
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.DirectionEvidence
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.MessageDirectionDiagnosticReason
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import java.util.ArrayDeque

/**
 * Momo-only, bounded and read-only extraction of visible chat rows. All Android node handles are
 * acquired, traversed and recycled inside this adapter. Message bodies are returned only as
 * short-lived in-memory values; this class never logs or persists them.
 */
internal class MomoChatMessageReader(
    private val rootAccess: AndroidActiveWindowRootAccess,
    private val targetPackage: String,
    private val parser: ChatMessageParser = ChatMessageParser(),
    private val maxVisibleRows: Int = MAX_VISIBLE_ROWS,
    private val maxNodesPerRow: Int = MAX_NODES_PER_ROW,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val ancestorBounds: List<ScreenRect>,
        val ancestorViewIds: List<String>,
        val path: List<Int>
    )

    private data class AvatarEvidence(
        val bounds: ScreenRect,
        val path: List<Int>,
        val ancestorBounds: List<ScreenRect>
    )

    private data class PendingListSearch(
        val node: AccessibilityNodeInfo
    )

    private data class MessageListResolution(
        val node: AccessibilityNodeInfo?,
        val scannedNodes: Int,
        val matchCount: Int,
        val truncated: Boolean,
        val failureReason: String? = null
    )

    private data class RowRead(
        val candidate: ChatMessageCandidate?,
        val wasTruncated: Boolean,
        val textEvidenceCount: Int,
        val descriptionEvidenceCount: Int,
        val unexposedMessageTextViewCount: Int = 0,
        val structureEvidence: ChatMessageStructureEvidence? = null,
        val ocrTarget: ChatMessageOcrTarget? = null
    )

    private data class ReadResult(
        val status: MessageReadStatus,
        val windowSessionKey: String?,
        val messages: List<com.freedom.features.aireply.domain.message.ChatMessage>,
        val scannedRows: Int,
        val candidateRows: Int,
        val truncated: Boolean,
        val textEvidenceNodeCount: Int = 0,
        val descriptionEvidenceNodeCount: Int = 0,
        val unrecognizedRows: Int = 0,
        val coordinateSpaceReliable: Boolean = true,
        val coordinateRootBounds: ScreenRect? = null,
        val coordinateListBounds: ScreenRect? = null,
        val directionDiagnostics: List<ChatMessageDirectionDiagnostic> = emptyList(),
        val unexposedMessageTextViewCount: Int = 0,
        val messageStructureEvidence: List<ChatMessageStructureEvidence> = emptyList(),
        val messageListUnavailableReason: String? = null,
        val ocrTargets: List<ChatMessageOcrTarget> = emptyList(),
        val completeTailRowBounds: ScreenRect? = null
    )

    init {
        require(maxVisibleRows > 0)
        require(maxNodesPerRow > 0)
    }

    fun readRecentMessages(page: ChatPageSnapshot, limit: Int): ChatMessageBatch {
        require(limit > 0) { "limit must be positive" }
        val timestamp = nowMillis()
        // Page kind is derived from the current-window structural fingerprint. Activity is
        // diagnostic metadata and must not veto a structurally confirmed chat detail.
        if (page.pageKind != ChatPageKind.CHAT_DETAIL_CANDIDATE) {
            return emptyBatch(MessageReadStatus.PAGE_NOT_CONFIRMED, null, timestamp)
        }
        val listTarget = page.messageListTarget
            ?: return emptyBatch(
                MessageReadStatus.MESSAGE_LIST_UNAVAILABLE,
                null,
                timestamp,
                "页面快照未提供消息列表定位目标"
            )
        if (page.pageKind != com.freedom.features.aireply.ports.ChatPageKind.CHAT_DETAIL_CANDIDATE ||
            page.conversationIdentityKey() == null ||
            listTarget.window.packageName != targetPackage || page.packageName != targetPackage ||
            listTarget.expectedClassName != MomoChatPageContract.MESSAGE_LIST_CLASS_NAME ||
            listTarget.expectedViewId != MomoChatPageContract.MESSAGE_LIST_VIEW_ID
        ) {
            return emptyBatch(MessageReadStatus.PAGE_NOT_CONFIRMED, null, timestamp)
        }

        val readResult = try {
            rootAccess.withActiveTargetRoot { root, currentWindow ->
            if (!sameWindowGeneration(currentWindow, listTarget.window) ||
                safeString { root.packageName?.toString().orEmpty() } != targetPackage
            ) {
                return@withActiveTargetRoot ReadResult(
                    MessageReadStatus.WINDOW_STALE,
                    windowKey(listTarget.window, page.chatTitleText),
                    emptyList(), 0, 0, false
                )
            }

            // Child-index paths are only hints from a previous snapshot. RecyclerView children
            // and surrounding wrappers change as messages load, so always locate the canonical
            // message list again inside the current window tree.
            val resolution = resolveCurrentMessageList(
                root = root,
                expectedClassName = listTarget.expectedClassName,
                expectedViewId = listTarget.expectedViewId
            )
            val listNode = resolution.node
                ?: return@withActiveTargetRoot ReadResult(
                    MessageReadStatus.MESSAGE_LIST_UNAVAILABLE,
                    windowKey(currentWindow, page.chatTitleText),
                    emptyList(), 0, 0, resolution.truncated,
                    messageListUnavailableReason = listOfNotNull(
                        resolution.failureReason ?: "当前窗口树中没有可用的消息列表节点",
                        "扫描节点=${resolution.scannedNodes}",
                        "可见标准列表匹配=${resolution.matchCount}"
                    ).joinToString("；")
                )
            try {
                if (!isExpectedMessageList(listNode, listTarget.expectedClassName, listTarget.expectedViewId)) {
                    return@withActiveTargetRoot ReadResult(
                        MessageReadStatus.MESSAGE_LIST_UNAVAILABLE,
                        windowKey(currentWindow, page.chatTitleText),
                        emptyList(), 0, 0, false,
                        messageListUnavailableReason = "定位路径解析成功，但节点 class/id 与消息列表契约不一致"
                    )
                }
                val rootBounds = boundsOf(root)
                val listBounds = boundsOf(listNode)
                if (listBounds.isEmpty || !safeBoolean { listNode.isVisibleToUser }) {
                    return@withActiveTargetRoot ReadResult(
                        MessageReadStatus.MESSAGE_LIST_UNAVAILABLE,
                        windowKey(currentWindow, page.chatTitleText),
                        emptyList(), 0, 0, false,
                        messageListUnavailableReason = if (listBounds.isEmpty) {
                            "消息列表节点边界为空"
                        } else {
                            "消息列表节点当前不可见"
                        }
                    )
                }
                val coordinateSpaceReliable = isListInsideRoot(rootBounds, listBounds)
                readRows(
                    listNode = listNode,
                    listBounds = listBounds,
                    window = currentWindow,
                    conversationTitle = page.chatTitleText,
                    limit = limit,
                    coordinateSpaceReliable = coordinateSpaceReliable,
                    rootBounds = rootBounds
                )
            } finally {
                recycle(listNode)
            }
        } ?: ReadResult(
            MessageReadStatus.WINDOW_STALE,
            windowKey(listTarget.window, page.chatTitleText),
            emptyList(), 0, 0, false
        )
        } catch (_: Exception) {
            ReadResult(
                MessageReadStatus.READ_FAILED,
                windowKey(listTarget.window, page.chatTitleText),
                emptyList(), 0, 0, false,
                messageListUnavailableReason = "读取过程异常（异常类型已隐藏）"
            )
        }

        return ChatMessageBatch(
            status = readResult.status,
            windowSessionKey = readResult.windowSessionKey,
            // Accessibility text is used only as structural evidence and OCR targeting input.
            // The target app's message body is produced by OCR, not by node text extraction.
            messages = emptyList(),
            scannedRowCount = readResult.scannedRows,
            candidateRowCount = readResult.candidateRows,
            truncated = readResult.truncated,
            capturedAtMillis = timestamp,
            textEvidenceNodeCount = readResult.textEvidenceNodeCount,
            descriptionEvidenceNodeCount = readResult.descriptionEvidenceNodeCount,
            unrecognizedRowCount = readResult.unrecognizedRows,
            coordinateSpaceReliable = readResult.coordinateSpaceReliable,
            coordinateRootBounds = readResult.coordinateRootBounds,
            coordinateListBounds = readResult.coordinateListBounds,
            directionDiagnostics = readResult.directionDiagnostics,
            unexposedMessageTextViewCount = readResult.unexposedMessageTextViewCount,
            messageStructureEvidence = readResult.messageStructureEvidence,
            messageListUnavailableReason = readResult.messageListUnavailableReason,
            ocrTargets = readResult.ocrTargets,
            completeTailRowBounds = readResult.completeTailRowBounds,
            observationBatchId = ChatMessageIdentity.observationBatchId(
                readResult.windowSessionKey, timestamp,
                (readResult.messages.map { it.bounds } + readResult.ocrTargets.map { it.rowBounds })
                    .distinct()
            ),
            windowSnapshotSignature = ChatMessageIdentity.windowSnapshotSignature(
                readResult.windowSessionKey,
                readResult.coordinateRootBounds,
                readResult.coordinateListBounds,
                (readResult.messages.map { it.bounds } + readResult.ocrTargets.map { it.rowBounds })
                    .distinct(),
                readResult.ocrTargets.mapNotNull { it.structureSignature }
            )
        )
    }

    /** Merges ephemeral local OCR output back through the existing platform-neutral parser. */
    fun applyOcrResults(
        batch: ChatMessageBatch,
        result: MomoChatOcrResult,
        limit: Int
    ): ChatMessageBatch {
        if (result.observationBatchId.isNotBlank() &&
            result.observationBatchId != batch.effectiveObservationBatchId
        ) {
            return batch.copy(
                status = MomoChatMessageReadStatusPolicy.afterOcrException(),
                ocrAttempted = true,
                ocrAttemptedTargetCount = batch.ocrTargets.size,
                ocrFailedTargetCount = batch.ocrTargets.size,
                ocrFailureReason = "OCR 结果所属批次已过期，拒绝合并"
            )
        }
        val listBounds = if (batch.coordinateSpaceReliable) {
            batch.coordinateListBounds ?: ScreenRect(0, 0, 0, 0)
        } else {
            ScreenRect(0, 0, 0, 0)
        }
        val candidates = MomoChatOcrCandidateMapper.candidates(
            targets = batch.ocrTargets,
            textByRowIndex = result.textByRowIndex,
            listBounds = listBounds
        )
        val ocrMessages = parser.parse(candidates, limit)
        val messages = (batch.messages + ocrMessages)
            .distinctBy { it.stableLogicalMessageId }
            .sortedBy { it.bounds.top }
            .takeLast(limit)
        val status = MomoChatMessageReadStatusPolicy.afterOcr(
            fallbackStatus = batch.status,
            hasMessages = messages.isNotEmpty(),
            allMessageDirectionsUnknown = messages.isNotEmpty() &&
                messages.all { it.direction == ChatMessageDirection.UNKNOWN },
            hasOcrTargets = batch.ocrTargets.isNotEmpty(),
            attemptedTargets = result.attemptedTargetCount,
            recognizedTargets = result.textByRowIndex.values.count(String::isNotBlank),
            failedTargets = result.failedTargetCount,
        )
        return batch.copy(
            status = status,
            messages = messages,
            candidateRowCount = batch.candidateRowCount + candidates.size,
            unrecognizedRowCount = (batch.scannedRowCount - messages.size).coerceAtLeast(0),
            ocrAttempted = true,
            ocrAttemptedTargetCount = result.attemptedTargetCount,
            ocrRecognizedTargetCount = result.textByRowIndex.values.count(String::isNotBlank),
            ocrFailedTargetCount = result.failedTargetCount,
            ocrCaptureDescription = result.captureDescription,
            ocrFailureReason = result.failureReason,
            sampleScreenshotPath = result.sampleScreenshotPath,
            sampleCropPathsByRowIndex = result.sampleCropPathsByRowIndex,
            ocrSampleTextByRowIndex = result.textByRowIndex,
            ocrConfidenceByRowIndex = result.confidenceByRowIndex,
            ocrLineCountByRowIndex = result.lineCountByRowIndex,
            ocrTokensByRowIndex = result.tokensByRowIndex
        )
    }

    private fun readRows(
        listNode: AccessibilityNodeInfo,
        listBounds: ScreenRect,
        window: WindowRef,
        conversationTitle: String?,
        limit: Int,
        coordinateSpaceReliable: Boolean,
        rootBounds: ScreenRect
    ): ReadResult {
        val candidates = ArrayList<ChatMessageCandidate>()
        val childCount = safeInt { listNode.childCount }
        if (childCount <= 0) {
            return ReadResult(
                MessageReadStatus.EMPTY_CONFIRMED,
                windowKey(window, conversationTitle), emptyList(), 0, 0, false,
                coordinateSpaceReliable = coordinateSpaceReliable,
                coordinateRootBounds = rootBounds,
                coordinateListBounds = listBounds
            )
        }

        val rowsToScan = minOf(childCount, maxVisibleRows)
        val firstIndex = (childCount - rowsToScan).coerceAtLeast(0)
        var truncated = childCount > rowsToScan
        var scannedRows = 0
        var completeTailRowBounds: ScreenRect? = null
        var textEvidenceNodes = 0
        var descriptionEvidenceNodes = 0
        var unexposedMessageTextViews = 0
        val messageStructureEvidence = ArrayList<ChatMessageStructureEvidence>()
        val ocrTargets = ArrayList<ChatMessageOcrTarget>()

        // RecyclerView exposes visible rows as direct children. A row can contain several wrapper
        // layers, so traversal below is bounded but deep enough for nested Momo message layouts.
        for (index in firstIndex until childCount) {
            val row = safeNode { listNode.getChild(index) } ?: continue
            scannedRows++
            val rowRead = readRow(row, index, listBounds, coordinateSpaceReliable)
            truncated = truncated || rowRead.wasTruncated
            if (index == childCount - 1 && !rowRead.wasTruncated && coordinateSpaceReliable) {
                val tailBounds = rowRead.candidate?.rowBounds ?: rowRead.ocrTarget?.rowBounds
                if (tailBounds != null && !tailBounds.isEmpty &&
                    tailBounds.top >= listBounds.top && tailBounds.bottom <= listBounds.bottom &&
                    tailBounds.left >= listBounds.left && tailBounds.right <= listBounds.right &&
                    (rowRead.candidate?.text?.length ?: 0) <= 4_000) {
                    completeTailRowBounds = tailBounds
                }
            }
            textEvidenceNodes += rowRead.textEvidenceCount
            descriptionEvidenceNodes += rowRead.descriptionEvidenceCount
            unexposedMessageTextViews += rowRead.unexposedMessageTextViewCount
            rowRead.structureEvidence?.let(messageStructureEvidence::add)
            rowRead.ocrTarget?.let(ocrTargets::add)
            rowRead.candidate?.let(candidates::add)
        }

        // Assign neighborhood evidence only after the complete visible batch is known. This
        // same map is copied to both structural candidates and OCR targets.
        val orderedRowBounds = (candidates.map { it.rowIndex to it.rowBounds } +
            ocrTargets.map { it.rowIndex to it.rowBounds })
            .distinctBy { it.first }
            .sortedBy { it.second.top }
        val allBounds = orderedRowBounds.map { it.second }
        val neighborByRow = orderedRowBounds.mapIndexed { index, (rowIndex, _) ->
            rowIndex to ChatMessageIdentity.neighborSignature(allBounds, index)
        }.toMap()
        val normalizedCandidates = candidates.map { it.copy(neighborSignature = neighborByRow[it.rowIndex]) }
        val normalizedOcrTargets = ocrTargets.map { it.copy(neighborSignature = neighborByRow[it.rowIndex]) }

        val messages = parser.parse(normalizedCandidates, limit)
        val status = when {
            messages.isEmpty() && unexposedMessageTextViews > 0 ->
                MessageReadStatus.MESSAGE_TEXT_NOT_EXPOSED
            messages.isEmpty() -> MessageReadStatus.MESSAGE_ROWS_UNRECOGNIZED
            messages.all { it.direction == ChatMessageDirection.UNKNOWN } -> MessageReadStatus.DIRECTION_UNKNOWN
            else -> MessageReadStatus.SUCCESS
        }
        val directionDiagnostics = normalizedCandidates.mapNotNull { candidate ->
            if (candidate.isSystemMessage || candidate.typeHint == ChatMessageType.SYSTEM) {
                return@mapNotNull null
            }
            val message = messages.firstOrNull { it.bounds == candidate.rowBounds }
            if (message?.direction != ChatMessageDirection.UNKNOWN) return@mapNotNull null
            createDirectionDiagnostic(candidate, coordinateSpaceReliable)
        }.take(MAX_DIRECTION_DIAGNOSTICS)
        return ReadResult(
            status = status,
            windowSessionKey = windowKey(window, conversationTitle),
            messages = messages,
            scannedRows = scannedRows,
            candidateRows = normalizedCandidates.size,
            truncated = truncated,
            textEvidenceNodeCount = textEvidenceNodes,
            descriptionEvidenceNodeCount = descriptionEvidenceNodes,
            unrecognizedRows = (scannedRows - normalizedCandidates.size).coerceAtLeast(0),
            coordinateSpaceReliable = coordinateSpaceReliable,
            coordinateRootBounds = rootBounds,
            coordinateListBounds = listBounds,
            directionDiagnostics = directionDiagnostics,
            unexposedMessageTextViewCount = unexposedMessageTextViews,
            messageStructureEvidence = messageStructureEvidence.take(MAX_STRUCTURE_DIAGNOSTICS),
            ocrTargets = normalizedOcrTargets.takeLast(MAX_OCR_TARGETS),
            completeTailRowBounds = completeTailRowBounds
        )
    }

    /** Takes ownership of [row] and always recycles it and all descendants. */
    private fun readRow(
        row: AccessibilityNodeInfo,
        rowIndex: Int,
        listBounds: ScreenRect,
        coordinateSpaceReliable: Boolean
    ): RowRead {
        val rowBounds = boundsOf(row)
        if (rowBounds.isEmpty || !safeBoolean { row.isVisibleToUser } ||
            !intersects(rowBounds, listBounds)
        ) {
            recycle(row)
            return RowRead(null, false, 0, 0)
        }

        // 没有正式文本容器的行直接跳过，不扫描其卡片内容、不建立 OCR 目标。
        // 查询限定在当前行和既有正文资源名内，随后仍按原层级规则复核归属与方向。
        val hasFormalContainer = try {
            hasVisibleFormalTextContainer(row, rowBounds, listBounds)
        } catch (failure: Exception) {
            recycle(row)
            throw failure
        }
        if (!hasFormalContainer) {
            recycle(row)
            return RowRead(null, false, 0, 0)
        }

        val queue = ArrayDeque<PendingNode>()
        queue.add(PendingNode(row, 0, emptyList(), emptyList(), emptyList()))
        val textEvidence = ArrayList<MomoChatTextEvidence>()
        val avatarEvidence = ArrayList<AvatarEvidence>()
        val unexposedMessageTextViews = ArrayList<MomoChatTextEvidence>()
        val layoutNodeEvidence = ArrayList<MomoChatMessageLayoutNodeEvidence>()
        val signatures = ArrayList<String>()
        var truncated = false
        var scanned = 0
        var timestampHint: String? = null
        var textEvidenceCount = 0
        var descriptionEvidenceCount = 0

        try {
            while (queue.isNotEmpty() && scanned < maxNodesPerRow) {
                val pending = queue.removeFirst()
                val node = pending.node
                scanned++
                try {
                    if (!safeBoolean { node.isVisibleToUser }) continue
                    val className = safeString { node.className?.toString().orEmpty() }
                    val normalizedClass = className.substringAfterLast('.').lowercase()
                    val viewId = safeString { node.viewIdResourceName.orEmpty() }.lowercase()
                    val contentDescription = safeString { node.contentDescription?.toString().orEmpty() }.trim()
                    val text = safeString { node.text?.toString().orEmpty() }.trim()
                    val bounds = boundsOf(node)
                    val childCount = safeInt { node.childCount }
                    val idTrail = pending.ancestorViewIds + viewId
                    signatures += "$viewId $normalizedClass $contentDescription".lowercase()
                    layoutNodeEvidence += MomoChatMessageLayoutNodeEvidence(
                        viewId = viewId,
                        className = normalizedClass,
                        path = pending.path,
                        viewIdTrail = idTrail
                    )

                    if (!bounds.isEmpty && intersects(bounds, listBounds) &&
                        isAvatarNode(viewId, normalizedClass, bounds, rowBounds, listBounds)
                    ) {
                        avatarEvidence += AvatarEvidence(
                            bounds = bounds,
                            path = pending.path,
                            ancestorBounds = pending.ancestorBounds
                        )
                    }

                    if (isMomoMessageBodyView(viewId) &&
                        text.isBlank() && contentDescription.isBlank() &&
                        !bounds.isEmpty && intersects(bounds, listBounds)
                    ) {
                        unexposedMessageTextViews += MomoChatTextEvidence(
                            value = "",
                            bounds = bounds,
                            depth = pending.depth,
                            childCount = childCount,
                            className = normalizedClass,
                            viewId = viewId,
                            ancestorBounds = pending.ancestorBounds,
                            viewIdTrail = idTrail,
                            path = pending.path
                        )
                    }

                    if (!safeBoolean { node.isEditable } && !bounds.isEmpty && intersects(bounds, listBounds)) {
                        val textValue = when {
                            text.isNotBlank() -> {
                                textEvidenceCount++
                                text
                            }
                            contentDescription.isNotBlank() && MomoChatMessageTextEvidencePolicy.isLikelyMessageDescription(
                                contentDescription,
                                viewId,
                                normalizedClass,
                                safeBoolean { node.isClickable },
                                childCount
                            ) -> {
                                descriptionEvidenceCount++
                                contentDescription
                            }
                            else -> ""
                        }
                        if (textValue.isNotBlank() && MomoChatMessageTextEvidencePolicy.isTimeLabel(textValue) && timestampHint == null) {
                            timestampHint = textValue
                        }
                        if (textValue.isNotBlank() && !MomoChatMessageTextEvidencePolicy.isIgnoredStructuralText(textValue, viewId)) {
                            textEvidence += MomoChatTextEvidence(
                                value = textValue,
                                bounds = bounds,
                                depth = pending.depth,
                                childCount = childCount,
                                className = normalizedClass,
                                viewId = viewId,
                                ancestorBounds = pending.ancestorBounds,
                                viewIdTrail = idTrail,
                                path = pending.path
                            )
                        }
                    }

                    for (childIndex in 0 until childCount) {
                        if (scanned + queue.size >= maxNodesPerRow) {
                            truncated = true
                            break
                        }
                        val child = safeNode { node.getChild(childIndex) } ?: continue
                        queue.add(
                            PendingNode(
                                node = child,
                                depth = pending.depth + 1,
                                ancestorBounds = pending.ancestorBounds + bounds,
                                ancestorViewIds = idTrail,
                                path = pending.path + childIndex
                            )
                        )
                    }
                } finally {
                    recycle(node)
                }
            }
            if (queue.isNotEmpty() || scanned >= maxNodesPerRow) truncated = true
        } finally {
            while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
        }

        val rowViewIds = signatures.map { signature ->
            signature.substringBefore(' ').trim()
        }
        // A resource ID alone is not enough: auxiliary cards and composer controls can expose
        // message-like descendants. Require the actual message_root RelativeLayout as the
        // primary row identity, then use the bubble/avatar/layout evidence as corroboration.
        val hasRelativeMessageRoot = layoutNodeEvidence.any { node ->
            node.viewId.substringAfterLast('/').substringAfterLast(':')
                .equals("message_root", ignoreCase = true) &&
                node.className.substringAfterLast('.')
                    .equals("RelativeLayout", ignoreCase = true)
        }
        val hasMessageRowAnchor = hasRelativeMessageRoot &&
            MomoChatRowCandidatePolicy.hasMessageRowAnchor(rowViewIds)
        val body = if (hasMessageRowAnchor) {
            MomoChatTextContainerPolicy.selectBody(textEvidence, listBounds)
        } else null
        val timeHint = timestampHint
        val bodyText = body?.value
        val messageRoot = layoutNodeEvidence
            .filter { it.viewId == "message_root" || it.viewId.endsWith(":id/message_root") }
            .minByOrNull { it.path.size }
        val structureSignature = messageRoot?.let { root ->
            val rootPath = root.path
            val nodes = layoutNodeEvidence
                .filter { it.path.size >= rootPath.size && it.path.take(rootPath.size) == rootPath }
                .joinToString(",") { node ->
                    val relativePath = node.path.drop(rootPath.size).joinToString(".")
                    "$relativePath:${node.viewId}:${node.className}"
                }
            "root=${root.viewId};nodes=$nodes"
        }
        val structuralBody = unexposedMessageTextViews
            .filter { MomoChatTextContainerPolicy.isBodyText(it.viewId, it.viewIdTrail) }
            .maxByOrNull {
                it.bounds.width.toLong() * it.bounds.height.toLong()
            }
        val structuralBubbleBounds = if (coordinateSpaceReliable && structuralBody != null) {
            MomoChatMessageTextEvidencePolicy.resolveBubbleBounds(structuralBody, listBounds)
        } else null
        val structuralAvatarBounds = if (
            coordinateSpaceReliable && structuralBubbleBounds != null && structuralBody != null
        ) {
            resolveAvatarBounds(
                avatarEvidence, structuralBody, structuralBubbleBounds, rowBounds, listBounds
            )
        } else null
        // Resolve direction independently for the visible text node and the hidden-body node used
        // by OCR. They may be different descendants; sharing one result can silently sever the
        // body-to-message_root evidence chain when the accessibility layout is wrapped differently.
        fun directionFor(evidence: MomoChatTextEvidence?): MomoChatMessageDirectionResolution =
            evidence?.let {
                MomoChatMessageDirectionPolicy.resolveForBody(
                    bodyPath = it.path,
                    bodyViewIdTrail = it.viewIdTrail,
                    rowNodes = layoutNodeEvidence
                )
            } ?: MomoChatMessageDirectionResolution(
                direction = null,
                evidence = DirectionEvidence.AMBIGUOUS,
                reason = "MESSAGE_BODY_NODE_NOT_FOUND"
            )
        val bodyDirection = directionFor(body)
        val structuralBodyDirection = directionFor(structuralBody)
        val identityDirection = structuralBodyDirection.direction ?: bodyDirection.direction
        val structureEvidence = structuralBody?.let {
            ChatMessageStructureEvidence(
                rowIndex = rowIndex,
                rowBounds = rowBounds,
                bodyViewId = it.viewId,
                bodyViewClass = it.className,
                bubbleBounds = structuralBubbleBounds,
                avatarBounds = structuralAvatarBounds
            )
        }
        val ocrTarget = structuralBody?.takeIf {
            hasMessageRowAnchor && coordinateSpaceReliable
        }?.let {
            ChatMessageOcrTarget(
                rowIndex = rowIndex,
                rowBounds = rowBounds,
                bodyBounds = it.bounds,
                bubbleBounds = structuralBubbleBounds,
                avatarBounds = structuralAvatarBounds,
                timeHint = timeHint,
                explicitDirectionHint = structuralBodyDirection.direction,
                explicitDirectionEvidence = structuralBodyDirection.evidence,
                structureSignature = structureSignature,
                identityDirectionHint = identityDirection
            )
        }
        // Momo-specific centered prompts and toolbar labels are UI, not chat messages.
        // Keep this filter at the social-app adapter boundary, not in the generic parser.
        if (!bodyText.isNullOrBlank() && (MomoChatUiTextFilter.isNonMessageUiText(bodyText) || MomoChatMessageTextEvidencePolicy.isNonMessageUiText(bodyText))) {
            return RowRead(
                null, truncated, textEvidenceCount, descriptionEvidenceCount,
                unexposedMessageTextViews.size, structureEvidence, ocrTarget
            )
        }
        val systemMessage = bodyText?.let(MomoChatMessageTextEvidencePolicy::isSystemMessage) == true
        val bubbleBounds = if (coordinateSpaceReliable) {
            body?.let { MomoChatMessageTextEvidencePolicy.resolveBubbleBounds(it, listBounds) }
        } else null
        val avatarBounds = if (coordinateSpaceReliable && bubbleBounds != null && body != null) {
            resolveAvatarBounds(avatarEvidence, body, bubbleBounds, rowBounds, listBounds)
        } else null
        val directionListBounds = if (coordinateSpaceReliable) listBounds else ScreenRect(0, 0, 0, 0)

        val candidate = when {
            systemMessage -> ChatMessageCandidate(
                rowIndex = rowIndex,
                rowBounds = rowBounds,
                bubbleBounds = bubbleBounds,
                listBounds = directionListBounds,
                text = bodyText,
                typeHint = ChatMessageType.SYSTEM,
                explicitDirectionHint = ChatMessageDirection.UNKNOWN,
                explicitDirectionEvidence = DirectionEvidence.AMBIGUOUS,
                timeHint = timeHint,
                isSystemMessage = true,
                avatarBounds = avatarBounds,
                structureSignature = structureSignature,
                identityDirectionHint = identityDirection,
                identityBubbleBounds = structuralBubbleBounds,
                identityAvatarBounds = structuralAvatarBounds
            )
            !bodyText.isNullOrBlank() -> ChatMessageCandidate(
                rowIndex = rowIndex,
                rowBounds = rowBounds,
                bubbleBounds = bubbleBounds,
                listBounds = directionListBounds,
                text = bodyText,
                typeHint = ChatMessageType.TEXT,
                explicitDirectionHint = bodyDirection.direction,
                explicitDirectionEvidence = bodyDirection.evidence,
                timeHint = timeHint,
                isSystemMessage = false,
                avatarBounds = avatarBounds,
                structureSignature = structureSignature,
                identityDirectionHint = identityDirection,
                identityBubbleBounds = structuralBubbleBounds,
                identityAvatarBounds = structuralAvatarBounds
            )
            else -> null
        }
        return RowRead(
            candidate,
            truncated,
            textEvidenceCount,
            descriptionEvidenceCount,
            unexposedMessageTextViews.size,
            structureEvidence,
            ocrTarget
        )
    }

    private fun resolveAvatarBounds(
        avatars: List<AvatarEvidence>,
        body: MomoChatTextEvidence,
        bubbleBounds: ScreenRect,
        rowBounds: ScreenRect,
        listBounds: ScreenRect
    ): ScreenRect? {
        return avatars.asSequence()
            .filter { avatar -> avatar.path != body.path }
            .filter { avatar -> isAvatarNodeGeometry(avatar.bounds, rowBounds, listBounds) }
            .filterNot { avatar -> intersects(avatar.bounds, bubbleBounds) }
            .filter { avatar ->
                val commonAncestor = nearestCommonAncestorBounds(avatar, body, rowBounds)
                val sharesContainingAncestor = commonAncestor != null &&
                    contains(commonAncestor, avatar.bounds) && contains(commonAncestor, bubbleBounds)
                // Every candidate is collected while traversing this one RecyclerView row.
                // A row-local fallback is safe only for a compact edge avatar vertically
                // aligned with the selected bubble; unrelated icons elsewhere in the row fail
                // the geometry and alignment checks below.
                sharesContainingAncestor || contains(rowBounds, avatar.bounds)
            }
            .filter { avatar ->
                val verticalDistance = kotlin.math.abs(
                    ((avatar.bounds.top + avatar.bounds.bottom) / 2) -
                        ((bubbleBounds.top + bubbleBounds.bottom) / 2)
                )
                verticalDistance <= maxOf(avatar.bounds.height, bubbleBounds.height)
            }
            .minByOrNull { avatar ->
                kotlin.math.abs(
                    ((avatar.bounds.top + avatar.bounds.bottom) / 2) -
                        ((bubbleBounds.top + bubbleBounds.bottom) / 2)
                )
            }
            ?.bounds
    }

    /** 在当前可见行查询精确的正文容器，不把卡片的任意文本标签当作正文。 */
    private fun hasVisibleFormalTextContainer(
        row: AccessibilityNodeInfo,
        rowBounds: ScreenRect,
        listBounds: ScreenRect
    ): Boolean = MomoChatRowCandidatePolicy.formalTextContainerIds().any { entry ->
        val viewId = "$targetPackage:id/$entry"
        val nodes = row.findAccessibilityNodeInfosByViewId(viewId).orEmpty()
        try {
            nodes.any { node ->
                val bounds = boundsOf(node)
                safeString { node.viewIdResourceName.orEmpty() } == viewId &&
                    safeBoolean { node.isVisibleToUser } && !bounds.isEmpty &&
                    intersects(bounds, rowBounds) && intersects(bounds, listBounds)
            }
        } finally {
            nodes.forEach(::recycle)
        }
    }

    private fun isMomoMessageBodyView(viewId: String): Boolean =
        MomoChatRowCandidatePolicy.isFormalTextContainer(viewId)

    private fun isAvatarNode(
        viewId: String,
        className: String,
        bounds: ScreenRect,
        rowBounds: ScreenRect,
        listBounds: ScreenRect
    ): Boolean {
        val hasSemanticAvatarId = AVATAR_ID_HINTS.any(viewId::contains)
        // Momo builds may expose avatar images through Fresco/Drawee or AppCompat wrappers
        // instead of android.widget.ImageView. Keep the recognition semantic and geometry
        // bounded so small edge icons are not accepted as avatars.
        val isImageLikeNode = className.contains("imageview") ||
            className.contains("imagebutton") || className.contains("draweeview") ||
            className.contains("simpledrawee") || className.contains("avatar")
        val isEdgeAnchoredImage = isImageLikeNode &&
            (bounds.left - rowBounds.left <= listBounds.width * MAX_AVATAR_EDGE_MARGIN_RATIO ||
                rowBounds.right - bounds.right <= listBounds.width * MAX_AVATAR_EDGE_MARGIN_RATIO)
        if (!hasSemanticAvatarId && !isEdgeAnchoredImage) return false
        if (!hasSemanticAvatarId && !isImageLikeNode) return false
        return isAvatarNodeGeometry(bounds, rowBounds, listBounds)
    }

    private fun isAvatarNodeGeometry(
        bounds: ScreenRect,
        rowBounds: ScreenRect,
        listBounds: ScreenRect
    ): Boolean {
        if (bounds.isEmpty || listBounds.isEmpty || rowBounds.isEmpty) return false
        val aspectRatio = bounds.width.toFloat() / bounds.height.toFloat()
        return bounds.width >= MIN_AVATAR_EDGE_PX && bounds.height >= MIN_AVATAR_EDGE_PX &&
            bounds.width <= listBounds.width * MAX_AVATAR_WIDTH_RATIO &&
            bounds.height <= rowBounds.height * MAX_AVATAR_HEIGHT_RATIO &&
            aspectRatio in MIN_AVATAR_ASPECT_RATIO..MAX_AVATAR_ASPECT_RATIO &&
            bounds.left >= rowBounds.left && bounds.right <= rowBounds.right &&
            bounds.top >= rowBounds.top && bounds.bottom <= rowBounds.bottom
    }

    /** Resolve the nearest shared accessibility ancestor for the avatar and message text. */
    private fun nearestCommonAncestorBounds(
        avatar: AvatarEvidence,
        body: MomoChatTextEvidence,
        rowBounds: ScreenRect
    ): ScreenRect? {
        var commonPrefixLength = 0
        val maxPrefixLength = minOf(avatar.path.size, body.path.size)
        while (commonPrefixLength < maxPrefixLength &&
            avatar.path[commonPrefixLength] == body.path[commonPrefixLength]
        ) {
            commonPrefixLength++
        }
        return body.ancestorBounds.getOrNull(commonPrefixLength)
            ?: avatar.ancestorBounds.getOrNull(commonPrefixLength)
            ?: rowBounds
    }

    private fun createDirectionDiagnostic(
        candidate: ChatMessageCandidate,
        coordinateSpaceReliable: Boolean
    ): ChatMessageDirectionDiagnostic {
        val list = candidate.listBounds
        val bubble = candidate.bubbleBounds
        val avatar = candidate.avatarBounds
        val bubbleRatio = bubble?.takeIf { !list.isEmpty && !it.isEmpty }
            ?.let { ((it.left + it.right) / 2f - list.left) / list.width.toFloat() }
        val avatarRatio = avatar?.takeIf { !list.isEmpty && !it.isEmpty }
            ?.let { ((it.left + it.right) / 2f - list.left) / list.width.toFloat() }
        val reason = when {
            !coordinateSpaceReliable -> MessageDirectionDiagnosticReason.COORDINATE_SPACE_UNRELIABLE
            bubble == null -> MessageDirectionDiagnosticReason.BUBBLE_NOT_FOUND
            list.isEmpty || bubble.isEmpty || bubble.width < list.width * MomoChatMessageTextEvidencePolicy.MIN_BUBBLE_WIDTH_RATIO ||
                bubble.width >= list.width * MomoChatMessageTextEvidencePolicy.MAX_BUBBLE_WIDTH_RATIO ->
                MessageDirectionDiagnosticReason.GEOMETRY_INVALID
            bubbleRatio != null && bubbleRatio > DIRECTION_INCOMING_MAX_RATIO &&
                bubbleRatio < DIRECTION_OUTGOING_MIN_RATIO -> MessageDirectionDiagnosticReason.CENTER_BAND
            avatarRatio != null && ((bubbleRatio ?: 0f) < 0.5f) != (avatarRatio < 0.5f) ->
                MessageDirectionDiagnosticReason.AVATAR_SIDE_CONFLICT
            else -> MessageDirectionDiagnosticReason.GEOMETRY_INVALID
        }
        return ChatMessageDirectionDiagnostic(
            rowIndex = candidate.rowIndex,
            rowBounds = candidate.rowBounds,
            bubbleBounds = bubble,
            avatarBounds = avatar,
            bubbleCenterRatio = bubbleRatio,
            avatarCenterRatio = avatarRatio,
            reason = reason
        )
    }

    private fun isExpectedMessageList(
        node: AccessibilityNodeInfo,
        expectedClassName: String?,
        expectedViewId: String?
    ): Boolean {
        val className = safeString { node.className?.toString().orEmpty() }
        val viewId = safeString { node.viewIdResourceName.orEmpty() }.lowercase()
        if (!expectedClassName.isNullOrBlank() && className != expectedClassName) return false
        if (!expectedViewId.isNullOrBlank() && viewId != expectedViewId.lowercase()) return false
        return className == MomoChatPageContract.MESSAGE_LIST_CLASS_NAME &&
            viewId == MomoChatPageContract.MESSAGE_LIST_VIEW_ID
    }

    /**
     * Find the canonical message RecyclerView in the current window tree. The returned node is
     * owned by the caller; every other traversed node is recycled here. No previous child-index
     * path is trusted because the Momo chat tree can insert/remove wrappers between events.
     */
    private fun resolveCurrentMessageList(
        root: AccessibilityNodeInfo,
        expectedClassName: String?,
        expectedViewId: String?
    ): MessageListResolution {
        val queue = ArrayDeque<PendingListSearch>()
        queue.add(PendingListSearch(AccessibilityNodeInfo.obtain(root)))
        var selected: AccessibilityNodeInfo? = null
        var matches = 0
        var visited = 0
        var truncated = false
        var hiddenOrEmptyMatches = 0

        try {
            while (queue.isNotEmpty() && visited < MAX_LIST_SEARCH_NODES) {
                val pending = queue.removeFirst()
                val node = pending.node
                visited++
                var retained = false
                try {
                    if (isExpectedMessageList(node, expectedClassName, expectedViewId)) {
                        val bounds = boundsOf(node)
                        if (safeBoolean { node.isVisibleToUser } && !bounds.isEmpty) {
                            matches++
                            if (selected == null) {
                                selected = node
                                retained = true
                            }
                        } else {
                            hiddenOrEmptyMatches++
                        }
                        // Do not traverse into the RecyclerView: its descendants are rows, not
                        // alternative list roots. Continue searching sibling branches only.
                    } else {
                        val childCount = safeInt { node.childCount }
                        for (index in 0 until childCount) {
                            if (visited + queue.size >= MAX_LIST_SEARCH_NODES) {
                                truncated = true
                                break
                            }
                            val child = safeNode { node.getChild(index) } ?: continue
                            queue.add(PendingListSearch(child))
                        }
                    }
                } finally {
                    if (!retained) recycle(node)
                }
            }
            if (queue.isNotEmpty()) truncated = true
        } finally {
            while (queue.isNotEmpty()) recycle(queue.removeFirst().node)
        }

        if (truncated) {
            recycle(selected)
            return MessageListResolution(
                node = null,
                scannedNodes = visited,
                matchCount = matches,
                truncated = true,
                failureReason = "当前窗口树消息列表搜索达到节点上限（$MAX_LIST_SEARCH_NODES），结果不完整，拒绝使用不确定定位"
            )
        }
        if (matches > 1) {
            recycle(selected)
            return MessageListResolution(
                node = null,
                scannedNodes = visited,
                matchCount = matches,
                truncated = false,
                failureReason = "当前窗口树发现多个可见的标准消息列表节点，定位存在歧义"
            )
        }
        if (selected != null) {
            return MessageListResolution(selected, visited, matches, false)
        }
        val reason = if (hiddenOrEmptyMatches > 0) {
            "找到标准消息列表节点，但节点不可见或边界为空"
        } else {
            "当前窗口树未找到符合 class/id 契约的消息列表（${expectedClassName.orEmpty()} / ${expectedViewId.orEmpty()}）"
        }
        return MessageListResolution(null, visited, 0, false, reason)
    }

    private fun boundsOf(node: AccessibilityNodeInfo): ScreenRect {
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        return ScreenRect(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun intersects(first: ScreenRect, second: ScreenRect): Boolean =
        first.left < second.right && first.right > second.left &&
            first.top < second.bottom && first.bottom > second.top

    private fun contains(container: ScreenRect, child: ScreenRect): Boolean =
        !container.isEmpty && !child.isEmpty && child.left >= container.left &&
            child.top >= container.top && child.right <= container.right &&
            child.bottom <= container.bottom

    /**
     * Accessibility screen bounds for a descendant should remain within the active root's
     * coordinate space. A small edge tolerance covers clipping/rounding but rejects the 716px
     * offset seen in the supplied run log.
     */
    private fun isListInsideRoot(rootBounds: ScreenRect, listBounds: ScreenRect): Boolean {
        if (rootBounds.isEmpty || listBounds.isEmpty) return false
        return listBounds.left >= rootBounds.left - COORDINATE_EDGE_TOLERANCE_PX &&
            listBounds.top >= rootBounds.top - COORDINATE_EDGE_TOLERANCE_PX &&
            listBounds.right <= rootBounds.right + COORDINATE_EDGE_TOLERANCE_PX &&
            listBounds.bottom <= rootBounds.bottom + COORDINATE_EDGE_TOLERANCE_PX
    }

    private fun sameWindowGeneration(left: WindowRef, right: WindowRef): Boolean =
        left.packageName == right.packageName && left.windowId >= 0 && right.windowId >= 0 &&
            left.windowId == right.windowId && left.generation == right.generation

    private fun windowKey(window: WindowRef, conversationTitle: String?): String {
        val conversationIdentity = conversationTitle?.trim()
            ?.takeIf { it.startsWith("与") && it.endsWith("对话") }
            ?.hashCode()
            ?.toString()
            ?: "unknown"
        return "${window.packageName}|${window.windowId}|${window.generation}|conversation=$conversationIdentity"
    }

    private fun emptyBatch(
        status: MessageReadStatus,
        windowSessionKey: String?,
        capturedAt: Long,
        unavailableReason: String? = null
    ) = ChatMessageBatch(
        status = status,
        windowSessionKey = windowSessionKey,
        messages = emptyList(),
        scannedRowCount = 0,
        candidateRowCount = 0,
        truncated = false,
        capturedAtMillis = capturedAt,
        messageListUnavailableReason = unavailableReason
    )

    private fun recycle(node: AccessibilityNodeInfo?) {
        if (node != null) runCatching { node.recycle() }
    }

    private inline fun safeString(block: () -> String): String = runCatching(block).getOrDefault("")
    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)
    private fun safeNode(block: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        runCatching(block).getOrNull()

    private companion object {
        const val MAX_VISIBLE_ROWS = 48
        const val MAX_NODES_PER_ROW = 64
        const val MAX_LIST_SEARCH_NODES = 512
        const val MIN_AVATAR_EDGE_PX = 10
        const val MAX_AVATAR_WIDTH_RATIO = 0.22f
        const val MAX_AVATAR_HEIGHT_RATIO = 0.85f
        const val MAX_AVATAR_EDGE_MARGIN_RATIO = 0.15f
        const val MIN_AVATAR_ASPECT_RATIO = 0.55f
        const val MAX_AVATAR_ASPECT_RATIO = 1.80f
        const val COORDINATE_EDGE_TOLERANCE_PX = 4
        const val DIRECTION_INCOMING_MAX_RATIO = 0.43f
        const val DIRECTION_OUTGOING_MIN_RATIO = 0.57f
        const val MAX_DIRECTION_DIAGNOSTICS = 8
        const val MAX_STRUCTURE_DIAGNOSTICS = 10
        const val MAX_OCR_TARGETS = 10
        val AVATAR_ID_HINTS = setOf(
            "avatar", "head_icon", "headicon", "user_icon", "usericon", "profile_avatar", "portrait"
        )
    }
}
