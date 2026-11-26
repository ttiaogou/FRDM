package com.freedom.features.aireply.ai

import com.freedom.features.aireply.domain.message.*

/**
 * A single fail-closed AI text boundary for visible OCR projections, legacy history and egress.
 * Message IDs, directions, OCR trust classifications and stored evidence remain unchanged.
 */
internal object AiSafeConversationText {
    private val whitespace = Regex("[\\t ]+")
    private val newlineSpacing = Regex(" *\\n *")

    /** Old history has no OCR geometry: reject any unresolved visual marker, never strip it. */
    fun legacy(content: String?): String? {
        if (content.isNullOrBlank()) return null
        if (OcrUncertaintyMarkers.all.any(content::contains)) return null
        val clean = StringBuilder()
        var offset = 0
        while (offset < content.length) {
            val cp = content.codePointAt(offset)
            val next = offset + Character.charCount(cp)
            val keycapEnd = keycapEnd(content, cp, next)
            if (keycapEnd != null) {
                offset = keycapEnd
                continue
            }
            if (isEmojiBase(cp)) {
                offset = consumeEmoji(content, next) ?: return null
                continue
            }
            // Stray emoji joiners/variants cannot be explained without source evidence.
            if (cp == 0x200D || cp == 0x20E3 || cp == 0xFE0F || cp == 0xFE0E ||
                cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F
            ) return null
            if (Character.isISOControl(cp) && cp != '\n'.code && cp != '\t'.code) return null
            clean.appendCodePoint(cp)
            offset = next
        }
        val result = normalize(clean.toString()).takeIf(String::isNotBlank) ?: return null
        if (OcrUncertaintyMarkers.all.any(result::contains)) return null
        if (result.codePoints().noneMatch { Character.isLetterOrDigit(it) }) return null
        return result
    }

    /** Only genuine OCR row evidence may upgrade AMBIGUOUS content to a safe partial projection. */
    fun visible(batch: ChatMessageBatch, message: ChatMessage): String? {
        if (message.type != ChatMessageType.TEXT ||
            message.direction == ChatMessageDirection.UNKNOWN ||
            message.directionEvidence == DirectionEvidence.AMBIGUOUS ||
            message.contentQuality !in setOf(MessageContentQuality.VERIFIED, MessageContentQuality.AMBIGUOUS)
        ) return null

        val row = message.observationRowIndex
        val hasOcrEvidence = batch.ocrTokensByRowIndex.isNotEmpty() ||
            batch.ocrTextProjectionsByRowIndex.isNotEmpty()
        if (hasOcrEvidence) {
            // A row index not tied to this batch's OCR evidence must never silently fall back
            // to the raw VERIFIED string. This also rejects missing/stale projections.
            if (row == null) return null
            val tokens = batch.ocrTokensByRowIndex[row] ?: return null
            val projection = batch.ocrTextProjectionsByRowIndex[row] ?: return null
            return validateProjection(message.content, tokens, projection)
        }
        return if (message.contentQuality == MessageContentQuality.VERIFIED) legacy(message.content) else null
    }

    /** Last defense before any remote model request, including manually assembled contexts. */
    fun outbound(messages: List<AiModelMessage>): List<AiModelMessage> =
        messages.mapNotNull { message ->
            legacy(message.content)?.let { message.copy(content = it) }
        }

    private fun validateProjection(
        rawContent: String?,
        tokens: List<OcrContentToken>,
        projection: OcrTextProjection
    ): String? {
        val expected = OcrTextProjection.derive(
            rawContent,
            tokens,
            canonicalize = ::normalize,
            verifiedLineText = { originalText ->
                // Retain the stricter model boundary: a hidden emoji in TEXT is not a valid token.
                legacy(originalText)?.takeIf { it == normalize(originalText) }
            }
        ) ?: return null
        if (projection.includedLineIndices != expected.includedLineIndices ||
            projection.excludedLineIndices != expected.excludedLineIndices ||
            projection.status != expected.status ||
            normalize(projection.text) != expected.text
        ) return null
        return expected.text
    }

    private fun normalize(value: String): String = value.replace('\u00A0', ' ')
        .replace(whitespace, " ").replace(newlineSpacing, "\n").trim()

    private fun keycapEnd(text: String, cp: Int, next: Int): Int? {
        if (cp !in '0'.code..'9'.code && cp != '#'.code && cp != '*'.code) return null
        var end = next
        if (end < text.length && text.codePointAt(end) == 0xFE0F) end++
        return if (end < text.length && text.codePointAt(end) == 0x20E3) end + 1 else null
    }

    private fun consumeEmoji(text: String, initial: Int): Int? {
        var end = initial
        while (end < text.length) {
            val cp = text.codePointAt(end)
            if (cp == 0xFE0F || cp == 0xFE0E || cp in 0x1F3FB..0x1F3FF ||
                cp in 0xE0020..0xE007F
            ) {
                end += Character.charCount(cp)
                continue
            }
            if (cp == 0x200D) {
                val joinedStart = end + 1
                if (joinedStart >= text.length) return null
                val joined = text.codePointAt(joinedStart)
                if (!isEmojiBase(joined)) return null
                end = joinedStart + Character.charCount(joined)
                continue
            }
            break
        }
        return end
    }

    private fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2300..0x23FF ||
            cp in 0x2600..0x27BF || cp == 0x00A9 || cp == 0x00AE ||
            cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 ||
            cp in 0x2934..0x2935 || cp in 0x2B05..0x2B07 ||
            cp == 0x2B1B || cp == 0x2B1C || cp == 0x2B50 || cp == 0x2B55
}
