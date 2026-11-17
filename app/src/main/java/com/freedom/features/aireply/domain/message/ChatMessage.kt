package com.freedom.features.aireply.domain.message

import com.freedom.core.accessibility.api.ScreenRect

/** Direction is UNKNOWN whenever the adapter cannot distinguish a sender reliably. */
enum class ChatMessageDirection {
    INCOMING,
    OUTGOING,
    UNKNOWN
}

enum class ChatMessageType {
    TEXT,
    IMAGE,
    EMOJI,
    VOICE,
    VIDEO,
    DYNAMIC_CARD,
    INTERACTIVE_CARD,
    /** Legacy generic card value retained for older callers and stored observations. */
    CARD,
    SYSTEM,
    UNKNOWN
}


/** Content trust is independent from whether a message row was structurally parsed. */
enum class MessageContentQuality {
    /** Text admitted for model input after the message-read structural checks. */
    VERIFIED,
    /** Legacy state for callers that have not admitted text for model input. */
    UNVERIFIED,
    /** Competing OCR evidence or unresolved visual symbols make the meaning ambiguous. */
    AMBIGUOUS,
    /** No usable text could be recovered. */
    FAILED
}

enum class DirectionEvidence {
    EXPLICIT_LAYOUT_HINT,
    LAYOUT_ID_AND_HIERARCHY,
    BUBBLE_GEOMETRY,
    BUBBLE_AND_AVATAR_GEOMETRY,
    AMBIGUOUS
}

enum class MessageDirectionDiagnosticReason {
    COORDINATE_SPACE_UNRELIABLE,
    BUBBLE_NOT_FOUND,
    CENTER_BAND,
    AVATAR_SIDE_CONFLICT,
    GEOMETRY_INVALID
}

/** Geometry-only diagnostics; deliberately contains no message or contact text. */
data class ChatMessageDirectionDiagnostic(
    val rowIndex: Int,
    val rowBounds: ScreenRect,
    val bubbleBounds: ScreenRect?,
    val avatarBounds: ScreenRect?,
    val bubbleCenterRatio: Float?,
    val avatarCenterRatio: Float?,
    val reason: MessageDirectionDiagnosticReason
) {
    override fun toString(): String =
        "row=$rowIndex,rowBounds=$rowBounds,bubbleBounds=$bubbleBounds," +
            "avatarBounds=$avatarBounds,bubbleCenterRatio=$bubbleCenterRatio," +
            "avatarCenterRatio=$avatarCenterRatio,reason=$reason"
}

enum class MessageReadStatus {
    /** At least one message was extracted and at least one sender direction was confirmed. */
    SUCCESS,
    /** Messages were extracted, but every sender direction remains ambiguous. */
    DIRECTION_UNKNOWN,
    /** OCR was attempted for message rows, but every attempted target failed. */
    OCR_FAILED,
    /** OCR returned some usable text but at least one attempted target failed. */
    OCR_PARTIAL,
    /** The canonical message list is valid and exposes no visible rows. */
    EMPTY_CONFIRMED,
    /** Visible row roots exist, but none could be interpreted as a message. */
    MESSAGE_ROWS_UNRECOGNIZED,
    /** A Momo message body View was located, but it exposes neither text nor a description. */
    MESSAGE_TEXT_NOT_EXPOSED,
    PAGE_NOT_CONFIRMED,
    WINDOW_STALE,
    MESSAGE_LIST_UNAVAILABLE,
    READ_FAILED
}



/** Structural-only evidence for a visible message row; never contains message text or identity. */
data class ChatMessageStructureEvidence(
    val rowIndex: Int,
    val rowBounds: ScreenRect,
    val bodyViewId: String,
    val bodyViewClass: String,
    val bubbleBounds: ScreenRect?,
    val avatarBounds: ScreenRect?
) {
    override fun toString(): String =
        "row=$rowIndex,rowBounds=$rowBounds,bodyViewId=$bodyViewId," +
            "bodyViewClass=$bodyViewClass,bubbleBounds=$bubbleBounds,avatarBounds=$avatarBounds"
}

/** Geometry-only target used to read a custom-drawn message body with local on-device OCR. */
data class ChatMessageOcrTarget(
    val rowIndex: Int,
    val rowBounds: ScreenRect,
    val bodyBounds: ScreenRect,
    val bubbleBounds: ScreenRect?,
    val avatarBounds: ScreenRect?,
    val timeHint: String? = null,
    /** Direction resolved from Momo avatar-container IDs after same-message-root validation. */
    val explicitDirectionHint: ChatMessageDirection? = null,
    val explicitDirectionEvidence: DirectionEvidence = DirectionEvidence.EXPLICIT_LAYOUT_HINT,
    /** Row-level structure captured from the same message_root as the OCR body. */
    val structureSignature: String? = null,
    /** Neighbor signature is assigned after the complete visible batch has been collected. */
    val neighborSignature: String? = null,
    /** Identity-only direction evidence shared by structural and OCR observations. */
    val identityDirectionHint: ChatMessageDirection? = null
) {
    override fun toString(): String =
        "ChatMessageOcrTarget(rowIndex=$rowIndex,rowBounds=$rowBounds," +
            "bodyBounds=$bodyBounds,bubbleBounds=$bubbleBounds,avatarBounds=$avatarBounds)"
}

/**
 * A short-lived, in-memory message observation. [content] must never be logged or persisted by
 * default. The ID is a one-way content fingerprint, not a server-provided message identifier.
 */
data class ChatMessage(
    val id: String,
    val direction: ChatMessageDirection,
    val type: ChatMessageType,
    val content: String?,
    val bounds: ScreenRect,
    val directionEvidence: DirectionEvidence,
    val contentQuality: MessageContentQuality = MessageContentQuality.VERIFIED,
    val logicalMessageId: String? = null,
    val contentRevision: String? = null,
    val observationRowIndex: Int? = null
) {
    val stableLogicalMessageId: String
        get() = logicalMessageId ?: id

    val effectiveContentRevision: String
        get() = contentRevision ?: ChatMessageIdentity.contentRevision(content)
    val isTextMessage: Boolean
        get() = type == ChatMessageType.TEXT && !content.isNullOrBlank()

    /** Only verified text may be used as automatic-reply context. */
    val isAiInputEligible: Boolean
        get() = isTextMessage && contentQuality == MessageContentQuality.VERIFIED &&
            direction != ChatMessageDirection.UNKNOWN

    override fun toString(): String =
        "ChatMessage(id=$id,direction=$direction,type=$type,content=[REDACTED]," +
            "bounds=$bounds,directionEvidence=$directionEvidence)"
}

/**
 * A bounded snapshot of currently visible messages only. It is not a complete conversation
 * history and does not imply that older messages have been loaded from the target app.
 */
data class ChatMessageBatch(
    val status: MessageReadStatus,
    val windowSessionKey: String?,
    val messages: List<ChatMessage>,
    val scannedRowCount: Int,
    val candidateRowCount: Int,
    val truncated: Boolean,
    val capturedAtMillis: Long,
    val textEvidenceNodeCount: Int = 0,
    val descriptionEvidenceNodeCount: Int = 0,
    val unrecognizedRowCount: Int = 0,
    val coordinateSpaceReliable: Boolean = true,
    val coordinateRootBounds: ScreenRect? = null,
    val coordinateListBounds: ScreenRect? = null,
    val directionDiagnostics: List<ChatMessageDirectionDiagnostic> = emptyList(),
    val unexposedMessageTextViewCount: Int = 0,
    val messageStructureEvidence: List<ChatMessageStructureEvidence> = emptyList(),
    val messageListUnavailableReason: String? = null,
    /** Geometry-only OCR targets; no screenshot or recognized text is retained in this field. */
    val ocrTargets: List<ChatMessageOcrTarget> = emptyList(),
    val ocrAttempted: Boolean = false,
    val ocrAttemptedTargetCount: Int = 0,
    val ocrRecognizedTargetCount: Int = 0,
    val ocrFailedTargetCount: Int = 0,
    val ocrCaptureDescription: String? = null,
    val ocrFailureReason: String? = null,
    /**
     * Temporary validation payload. Populated only by explicitly enabled debug builds,
     * consumed after active-window revalidation, and intentionally omitted from toString/summary.
     */
    /** Null means debug OCR logging is disabled; an empty map means enabled with no recognized text. */
    val ocrDebugTextByRowIndex: Map<Int, String>? = null,
    /** Independent engine outputs are retained only for temporary local Debug validation. */
    val ocrDebugChineseTextByRowIndex: Map<Int, String>? = null,
    val ocrDebugLatinTextByRowIndex: Map<Int, String>? = null,
    /** Local-only file references for explicit session sample collection. */
    val sampleScreenshotPath: String? = null,
    val sampleCropPathsByRowIndex: Map<Int, String> = emptyMap(),
    /** Raw recognition output is retained only in the explicit local OCR sample archive. */
    val ocrSampleTextByRowIndex: Map<Int, String> = emptyMap(),
    /** Plugin confidence values are diagnostics, not verified recognition accuracy. */
    val ocrConfidenceByRowIndex: Map<Int, Float> = emptyMap(),
    val ocrLineCountByRowIndex: Map<Int, Int> = emptyMap(),
    /** 适配器确认已读取列表末行且行内完整；null 表示末尾仍存在缺口。 */
    val completeTailRowBounds: ScreenRect? = null,
    val observationBatchId: String = "",
    val windowSnapshotSignature: String = ""
) {
    val effectiveObservationBatchId: String
        get() = observationBatchId.ifBlank { "${windowSessionKey.orEmpty()}@${capturedAtMillis}" }
    /** Backward-compatible name, but still enforces the automatic-input quality gate. */
    val latestIncomingMessage: ChatMessage?
        get() = aiInputMessages.asReversed().firstOrNull {
            it.direction == ChatMessageDirection.INCOMING
        }

    /** The only message collection permitted as automatic AI context. */
    val aiInputMessages: List<ChatMessage>
        get() = messages.filter(ChatMessage::isAiInputEligible)

    /**
     * Visible messages ordered from the bottom of the chat viewport to the top.
     * MomoChatMessageReader and ChatMessageParser currently emit top-to-bottom order;
     * calculating this view explicitly prevents downstream reply schedulers from silently
     * processing only the last incoming item.
     */
    val messagesBottomToTop: List<ChatMessage>
        get() = messages.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<ChatMessage>> { it.value.bounds.top }
                    .thenByDescending { it.index }
            )
            .map { it.value }

    /** Eligible incoming text in the requested bottom-to-top processing order. */
    val aiInputMessagesBottomToTop: List<ChatMessage>
        get() = messagesBottomToTop.filter(ChatMessage::isAiInputEligible)

    /** Incoming, verified text only; this is the ordered work queue for any future reply sender. */
    val incomingMessagesBottomToTop: List<ChatMessage>
        get() = aiInputMessagesBottomToTop.filter {
            it.direction == ChatMessageDirection.INCOMING
        }

    val latestIncomingTextMessage: ChatMessage?
        get() = aiInputMessages.asReversed().firstOrNull {
            it.direction == ChatMessageDirection.INCOMING
        }

    fun summary(): String {
        val incoming = messages.count { it.direction == ChatMessageDirection.INCOMING }
        val outgoing = messages.count { it.direction == ChatMessageDirection.OUTGOING }
        val unknownDirection = messages.count { it.direction == ChatMessageDirection.UNKNOWN }
        val text = messages.count { it.type == ChatMessageType.TEXT }
        val unsupported = messages.size - text
        val verified = messages.count { it.isTextMessage && it.contentQuality == MessageContentQuality.VERIFIED }
        val unverified = messages.count { it.isTextMessage && it.contentQuality == MessageContentQuality.UNVERIFIED }
        val ambiguous = messages.count { it.isTextMessage && it.contentQuality == MessageContentQuality.AMBIGUOUS }
        return "状态=$status；可见消息=${messages.size}；对方=$incoming；自己=$outgoing；" +
            "方向不明=$unknownDirection；文字=$text；可信文字=$verified；单引擎未验证=$unverified；内容歧义=$ambiguous；非文字/系统=$unsupported；" +
            "扫描行=$scannedRowCount；候选行=$candidateRowCount；未解析行=$unrecognizedRowCount；" +
            "文本证据节点=$textEvidenceNodeCount；描述证据节点=$descriptionEvidenceNodeCount" +
            "；未暴露正文的消息 View=$unexposedMessageTextViewCount" +
            (messageListUnavailableReason?.takeIf { it.isNotBlank() }?.let { "；列表不可用原因=$it" } ?: "") +
            (if (messageStructureEvidence.isNotEmpty()) {
                "；结构消息行=${messageStructureEvidence.size}"
            } else "") +
            (if (ocrTargets.isNotEmpty()) "；OCR候选区域=${ocrTargets.size}" else "") +
            (if (ocrAttempted) {
                val attempted = ocrAttemptedTargetCount.coerceAtLeast(0)
                val yield = if (attempted == 0) null else
                    ocrRecognizedTargetCount.coerceAtLeast(0) * 100.0 / attempted
                val confidenceValues = ocrConfidenceByRowIndex.values.filter { it.isFinite() && it in 0f..1f }
                val meanConfidence = confidenceValues.takeIf { it.isNotEmpty() }?.average()
                "；OCR已识别区域=$ocrRecognizedTargetCount；OCR失败区域=$ocrFailedTargetCount" +
                    "；OCR非空输出率=${yield?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "N/A"}" +
                    "；OCR平均置信度=${meanConfidence?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "N/A"}"
            } else "") +
            (ocrCaptureDescription?.takeIf { it.isNotBlank() }?.let { "；OCR截图路径=$it" } ?: "") +
            (ocrFailureReason?.takeIf { it.isNotBlank() }?.let { "；OCR状态=$it" } ?: "") +
            (if (truncated) "；扫描达到上限" else "") +
            (if (coordinateRootBounds != null || coordinateListBounds != null) {
                "；坐标空间=${if (coordinateSpaceReliable) "一致" else "异常/方向已降级"}" +
                    "；Root=$coordinateRootBounds；RecyclerView=$coordinateListBounds"
            } else "") +
            (if (directionDiagnostics.isNotEmpty()) {
                "；方向诊断=${directionDiagnostics.take(4).joinToString(" || ")}"
            } else "")
    }

    override fun toString(): String =
        "ChatMessageBatch(status=$status,windowSessionKey=$windowSessionKey," +
            "messages=${messages.size},summary=${summary()},capturedAtMillis=$capturedAtMillis)"
}
