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

/** Pure bridge from OCR text + previously verified Momo row geometry into the shared parser. */
internal object MomoChatOcrCandidateMapper {
    fun candidates(
        targets: List<ChatMessageOcrTarget>,
        textByRowIndex: Map<Int, String>,
        listBounds: ScreenRect
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
            // Textual correctness is not locally provable. Structural admission (window, row,
            // order and direction) is handled by the surrounding read/draft pipeline; a non-empty
            // OCR result is therefore allowed to become model input here.
            contentQuality = conservativeQuality(text),
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
        limit: Int
    ): List<ChatMessage> = parser.parse(
        candidates(targets, textByRowIndex, listBounds),
        limit
    )
}
