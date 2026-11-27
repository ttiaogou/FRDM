package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageCandidate
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.ChatMessage
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.ChatMessageIdentity
import com.freedom.features.aireply.domain.message.ChatMessageOcrTarget
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.OcrContentToken

/** Pure bridge from OCR text + previously verified Momo row geometry into the shared parser. */
internal object MomoChatOcrCandidateMapper {
    fun candidates(
        targets: List<ChatMessageOcrTarget>,
        textByRowIndex: Map<Int, String>,
        listBounds: ScreenRect,
        tokensByRowIndex: Map<Int, List<OcrContentToken>> = emptyMap()
    ): List<ChatMessageCandidate> = targets.mapNotNull { target ->
        // 分类探针不是消息正文，绝不把资料字段当作对方消息提交模型。
        if (!target.isMessageTextTarget) return@mapNotNull null
        val text = textByRowIndex[target.rowIndex]
            ?.replace('\u00A0', ' ')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        if (MomoChatUiTextFilter.isNonMessageUiText(text)) return@mapNotNull null
        val type = MomoChatMessageTextEvidencePolicy.classifyOcrBody(text, tokensByRowIndex[target.rowIndex].orEmpty())
        ChatMessageCandidate(
            rowIndex = target.rowIndex,
            rowBounds = target.rowBounds,
            bubbleBounds = target.bubbleBounds,
            listBounds = listBounds,
            text = text.takeIf { type == ChatMessageType.TEXT },
            typeHint = type,
            retainUnknownRow = type == ChatMessageType.UNKNOWN,
            explicitDirectionHint = target.explicitDirectionHint,
            explicitDirectionEvidence = target.explicitDirectionEvidence,
            timeHint = target.timeHint,
            isSystemMessage = false,
            avatarBounds = target.avatarBounds,
            // A non-text row is never verified conversational text.
            contentQuality = if (type == ChatMessageType.TEXT) conservativeQuality(text)
                else MessageContentQuality.AMBIGUOUS,
            neighborSignature = target.neighborSignature,
            structureSignature = target.structureSignature,
            identityDirectionHint = target.identityDirectionHint
        )
    }

    private fun conservativeQuality(text: String): MessageContentQuality =
        MomoOcrQualityPolicy.classify(text)

    fun parse(
        targets: List<ChatMessageOcrTarget>,
        textByRowIndex: Map<Int, String>,
        listBounds: ScreenRect,
        parser: ChatMessageParser,
        limit: Int,
        tokensByRowIndex: Map<Int, List<OcrContentToken>> = emptyMap()
    ): List<ChatMessage> = parser.parse(
        candidates(targets, textByRowIndex, listBounds, tokensByRowIndex),
        limit
    )
}
