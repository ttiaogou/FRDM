package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.OcrContentKind
import com.freedom.features.aireply.domain.message.OcrContentToken
import com.freedom.features.aireply.domain.message.OcrTextProjection
import com.freedom.features.aireply.domain.message.OcrTextProjectionStatus
import com.freedom.features.aireply.domain.message.OcrTokenSource
import com.freedom.features.aireply.domain.message.OcrTokenStatus

/**
 * P2: Extract text ONLY from independently supported OCR lines.
 *
 * The existing PaddleOCR result supplies line boxes, not character boxes. An unknown visual
 * marker or a low-confidence symbol therefore excludes its whole line; it cannot make nearby
 * characters independently admissible. Identified Unicode emoji can be omitted from an otherwise
 * unambiguous OCR line without changing the original message.
 *
 * This produces metadata for the later AI-context stage; it does not change P0 message admission.
 */
internal object MomoOcrTextProjector {
    fun extract(renderedText: String, tokens: List<OcrContentToken>): OcrTextProjection? {
        if (renderedText.isBlank() || tokens.isEmpty()) return null

        // Reject stale/mismatched/partial evidence rather than guessing how it maps to text.
        if (tokens.any { it.lineIndex < 0 } ||
            tokens.zipWithNext().any { (a, b) -> a.lineIndex > b.lineIndex }
        ) return null
        val groups = tokens.groupBy { it.lineIndex }
        if (groups.keys.toList() != (0 until groups.size).toList()) return null
        if (groups.values.joinToString("\n") { line ->
                line.joinToString("") { it.text }
            } != renderedText
        ) return null

        val included = ArrayList<Pair<Int, String>>()
        val excluded = ArrayList<Int>()
        var omittedEmoji = false
        for ((index, line) in groups) {
            val bounds = line.first().lineBounds
            val hasStableLineBounds = bounds != null &&
                bounds.right > bounds.left && bounds.bottom > bounds.top &&
                line.all { it.lineBounds == bounds }
            val supported = hasStableLineBounds && line.all { token ->
                when (token.kind) {
                    OcrContentKind.TEXT ->
                        token.status == OcrTokenStatus.RECOGNIZED &&
                            token.source == OcrTokenSource.OCR_LINE &&
                            token.symbolBounds == null &&
                            '\n' !in token.text
                    OcrContentKind.EMOJI ->
                        token.status == OcrTokenStatus.UNVERIFIED &&
                            token.source == OcrTokenSource.UNICODE_EMOJI &&
                            token.symbolBounds == null
                    OcrContentKind.PLACEHOLDER -> false
                }
            }
            if (!supported) {
                excluded += index
                continue
            }

            val text = line.filter { it.kind == OcrContentKind.TEXT }
                .joinToString("") { it.text }.trim()
            if (MomoOcrQualityPolicy.classify(text) != MessageContentQuality.VERIFIED) {
                excluded += index
                continue
            }
            included += index to text
            if (line.any { it.kind == OcrContentKind.EMOJI }) omittedEmoji = true
        }

        if (included.isEmpty()) return null
        return OcrTextProjection(
            text = included.joinToString("\n") { it.second },
            status = if (excluded.isNotEmpty() || omittedEmoji) {
                OcrTextProjectionStatus.PARTIAL
            } else {
                OcrTextProjectionStatus.COMPLETE
            },
            includedLineIndices = included.map { it.first },
            excludedLineIndices = excluded
        )
    }
}
