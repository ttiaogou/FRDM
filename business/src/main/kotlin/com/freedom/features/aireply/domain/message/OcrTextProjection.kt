package com.freedom.features.aireply.domain.message

/** Completeness of an OCR-derived text projection, not a claim of ground-truth OCR accuracy. */
enum class OcrTextProjectionStatus { COMPLETE, PARTIAL }

/**
 * Evidence-backed, in-memory text extracted from independently admissible OCR lines.
 * Original message content/quality/identity are unchanged. Only a later AI admission stage
 * may decide whether to use this projection.
 */
data class OcrTextProjection(
    val text: String,
    val status: OcrTextProjectionStatus,
    val includedLineIndices: List<Int>,
    val excludedLineIndices: List<Int>
) {
    override fun toString(): String =
        "OcrTextProjection(status=$status,includedLines=${includedLineIndices.size}," +
            "excludedLines=${excludedLineIndices.size},text=[REDACTED])"

    companion object {
        const val MIN_ISOLATED_TEXT_CONFIDENCE = 0.80f
        private const val ISOLATION_GAP_PX = 2

        /** Only an edge region is eligible. Middle/overlapping symbols must not split sentences. */
        fun isolatedCropBounds(
            line: OcrContentRect,
            region: OcrContentRect,
            width: Int,
            height: Int
        ): OcrContentRect? {
            val full = OcrContentRect(0, 0, width, height)
            if (!full.valid() || !full.contains(line) || !full.contains(region) ||
                !line.valid() || !region.valid() || !line.overlapsVertically(region)
            ) return null
            val tolerance = maxOf(4, (line.bottom - line.top) / 4)
            val leading = region.left <= line.left + tolerance && region.right > line.left
            val trailing = region.right >= line.right - tolerance && region.left < line.right
            if (leading == trailing) return null
            val candidate = if (leading) OcrContentRect(region.right + ISOLATION_GAP_PX, 0, width, height)
                else OcrContentRect(0, 0, region.left - ISOLATION_GAP_PX, height)
            return candidate.takeIf { it.valid() && full.contains(it) &&
                it.right - it.left >= 8 && it.bottom - it.top >= 8 }
        }

        /**
         * A single edge visual region may also have been rendered as an unverified Unicode emoji
         * by OCR. The extra token is never proof of its identity or of adjacent text. Only a
         * matching isolated read can admit the single contiguous TEXT token.
         */
        fun isolatedTextCandidate(parts: List<OcrContentToken>): OcrContentToken? {
            if (parts.size !in 2..3) return null
            val text = parts.singleOrNull { it.kind == OcrContentKind.TEXT } ?: return null
            val marker = parts.singleOrNull { it.kind == OcrContentKind.PLACEHOLDER } ?: return null
            val line = text.lineBounds ?: return null
            val region = marker.symbolBounds ?: return null
            if (text.status != OcrTokenStatus.AMBIGUOUS || text.source != OcrTokenSource.OCR_LINE ||
                text.symbolBounds != null || text.text.isBlank() || '\n' in text.text ||
                marker.status != OcrTokenStatus.UNVERIFIED || marker.source != OcrTokenSource.VISUAL_GUARD ||
                marker.text != OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY ||
                marker.isolatedTextEvidence != null || !line.valid() || !region.valid() ||
                parts.any { it.lineIndex != text.lineIndex || it.lineBounds != line }
            ) return null
            val tolerance = maxOf(4, (line.bottom - line.top) / 4)
            val leading = region.left <= line.left + tolerance && region.right > line.left
            val trailing = region.right >= line.right - tolerance && region.left < line.right
            if (!line.overlapsVertically(region) || leading == trailing) return null
            if (parts.size == 3) {
                val emoji = parts.singleOrNull { it.kind == OcrContentKind.EMOJI } ?: return null
                if (emoji.status != OcrTokenStatus.UNVERIFIED ||
                    emoji.source != OcrTokenSource.UNICODE_EMOJI ||
                    emoji.symbolBounds != null || emoji.isolatedTextEvidence != null ||
                    emoji.text.isBlank() || '\n' in emoji.text ||
                    parts.indexOf(emoji) != (if (leading) parts.indexOf(text) - 1 else parts.indexOf(text) + 1)
                ) return null
            }
            return text
        }

        private fun hasIndependentTextEvidence(
            parts: List<OcrContentToken>,
            canonicalize: (String) -> String
        ): Boolean {
            val text = isolatedTextCandidate(parts) ?: return false
            val marker = parts.single { it.kind == OcrContentKind.PLACEHOLDER }
            val line = text.lineBounds ?: return false
            val region = marker.symbolBounds ?: return false
            val proof = text.isolatedTextEvidence ?: return false
            if (!proof.cropBounds.valid() || !proof.recognizedBounds.valid() ||
                !proof.confidence.isFinite() || proof.confidence !in MIN_ISOLATED_TEXT_CONFIDENCE..1f ||
                !proof.cropBounds.contains(proof.recognizedBounds) ||
                !line.overlapsVertically(proof.recognizedBounds) ||
                proof.recognizedBounds.left < line.left - 4 || proof.recognizedBounds.right > line.right + 4 ||
                proof.recognizedBounds.top < line.top - 4 || proof.recognizedBounds.bottom > line.bottom + 4 ||
                canonicalize(proof.recognizedText.trim()) != canonicalize(text.text.trim())
            ) return false
            val tolerance = maxOf(4, (line.bottom - line.top) / 4)
            val leading = region.left <= line.left + tolerance && region.right > line.left
            val trailing = region.right >= line.right - tolerance && region.left < line.right
            if (!line.overlapsVertically(region) || leading == trailing) return false
            return if (leading) proof.cropBounds.left >= region.right + ISOLATION_GAP_PX
                else proof.cropBounds.right <= region.left - ISOLATION_GAP_PX
        }

        private fun OcrContentRect.valid() = left >= 0 && top >= 0 && right > left && bottom > top
        private fun OcrContentRect.contains(other: OcrContentRect) =
            left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
        private fun OcrContentRect.overlapsVertically(other: OcrContentRect) =
            maxOf(top, other.top) < minOf(bottom, other.bottom)

        /**
         * Single structural evidence rule for the OCR producer and independent AI consumer.
         * The caller still owns its existing normalization and text-quality policy.
         * This does not upgrade the original message or authorize AI input.
         */
        fun derive(
            rawContent: String?,
            tokens: List<OcrContentToken>,
            canonicalize: (String) -> String,
            verifiedLineText: (String) -> String?
        ): OcrTextProjection? {
            if (rawContent.isNullOrBlank() || tokens.isEmpty() ||
                tokens.any { it.lineIndex < 0 } ||
                tokens.zipWithNext().any { (a, b) -> a.lineIndex > b.lineIndex }
            ) return null
            val groups = tokens.groupBy { it.lineIndex }
            if (groups.keys.toList() != (0 until groups.size).toList()) return null
            val full = groups.values.joinToString("\n") { parts -> parts.joinToString("") { it.text } }
            if (canonicalize(full) != canonicalize(rawContent)) return null

            val included = ArrayList<Int>()
            val excluded = ArrayList<Int>()
            val textLines = ArrayList<String>()
            var omittedEmoji = false
            for ((index, parts) in groups) {
                val bounds = parts.first().lineBounds
                val supported = bounds != null && bounds.right > bounds.left && bounds.bottom > bounds.top &&
                    parts.all { token ->
                        token.lineBounds == bounds && token.isolatedTextEvidence == null && when (token.kind) {
                            OcrContentKind.TEXT ->
                                token.status == OcrTokenStatus.RECOGNIZED &&
                                    token.source == OcrTokenSource.OCR_LINE &&
                                    token.symbolBounds == null && '\n' !in token.text
                            OcrContentKind.EMOJI ->
                                token.status == OcrTokenStatus.UNVERIFIED &&
                                    token.source == OcrTokenSource.UNICODE_EMOJI &&
                                    token.symbolBounds == null
                            OcrContentKind.PLACEHOLDER -> false
                        }
                    }
                val originalText = parts.filter { it.kind == OcrContentKind.TEXT }
                    .joinToString("") { it.text }.trim()
                // A raw ambiguous line remains ambiguous. Only a separate, geometrically
                // isolated matching OCR observation can provide its text projection.
                val isolated = !supported && hasIndependentTextEvidence(parts, canonicalize)
                val clean = if (supported || isolated) verifiedLineText(originalText) else null
                if (clean == null) {
                    excluded += index
                    continue
                }
                included += index
                textLines += clean
                if (isolated || parts.any { it.kind == OcrContentKind.EMOJI }) omittedEmoji = true
            }
            if (included.isEmpty()) return null
            return OcrTextProjection(
                text = canonicalize(textLines.joinToString("\n")),
                status = if (excluded.isNotEmpty() || omittedEmoji) {
                    OcrTextProjectionStatus.PARTIAL
                } else OcrTextProjectionStatus.COMPLETE,
                includedLineIndices = included,
                excludedLineIndices = excluded
            )
        }
    }
}
