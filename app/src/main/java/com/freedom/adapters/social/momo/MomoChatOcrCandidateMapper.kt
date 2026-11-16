package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageCandidate
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.ChatMessage
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.ChatMessageOcrTarget
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.MessageContentQuality

/** Pure bridge from OCR text + previously verified Momo row geometry into the shared parser. */
internal object MomoChatOcrCandidateMapper {
    fun candidates(
        targets: List<ChatMessageOcrTarget>,
        textByRowIndex: Map<Int, String>,
        listBounds: ScreenRect,
        qualityByRowIndex: Map<Int, MessageContentQuality> = emptyMap()
    ): List<ChatMessageCandidate> = targets.mapNotNull { target ->
        val text = textByRowIndex[target.rowIndex]
            ?.replace('\u00A0', ' ')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        if (MomoChatUiTextFilter.isNonMessageUiText(text)) return@mapNotNull null
        ChatMessageCandidate(
            rowIndex = target.rowIndex,
            rowBounds = target.rowBounds,
            bubbleBounds = target.bubbleBounds,
            listBounds = listBounds,
            text = text,
            typeHint = ChatMessageType.TEXT,
            explicitDirectionHint = target.explicitDirectionHint,
            explicitDirectionEvidence = target.explicitDirectionEvidence,
            timeHint = target.timeHint,
            isSystemMessage = false,
            avatarBounds = target.avatarBounds,
            // A row quality supplied by the OCR processor may downgrade a result, but it may
            // not promote a single-engine transcription to VERIFIED. Promotion requires a
            // separate, explicit verification contract which this Momo OCR path does not yet have.
            contentQuality = conservativeQuality(
                text = text,
                processorQuality = qualityByRowIndex[target.rowIndex]
            )
        )
    }

    private fun conservativeQuality(
        text: String,
        processorQuality: MessageContentQuality?
    ): MessageContentQuality {
        val singleEngineQuality = MomoOcrQualityPolicy.classify(text)
        return when (singleEngineQuality) {
            MessageContentQuality.FAILED,
            MessageContentQuality.AMBIGUOUS -> singleEngineQuality
            MessageContentQuality.UNVERIFIED -> when (processorQuality) {
                MessageContentQuality.FAILED -> MessageContentQuality.FAILED
                MessageContentQuality.AMBIGUOUS -> MessageContentQuality.AMBIGUOUS
                else -> MessageContentQuality.UNVERIFIED
            }
            MessageContentQuality.VERIFIED -> processorQuality ?: singleEngineQuality
        }
    }

    fun parse(
        targets: List<ChatMessageOcrTarget>,
        textByRowIndex: Map<Int, String>,
        listBounds: ScreenRect,
        parser: ChatMessageParser,
        limit: Int,
        qualityByRowIndex: Map<Int, MessageContentQuality> = emptyMap()
    ): List<ChatMessage> = parser.parse(
        candidates(targets, textByRowIndex, listBounds, qualityByRowIndex),
        limit
    )
}
