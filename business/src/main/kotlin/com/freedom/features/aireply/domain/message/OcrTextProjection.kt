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
         * Group only adjacent components on a common horizontal band at exactly one line edge.
         * Keep individual rectangles so consumers can recompute the union, rather than trusting
         * an arbitrary producer-supplied bounding box. Independent text proof is still required.
         */
        fun groupedEdgeRegion(line: OcrContentRect, regions: List<OcrContentRect>): OcrContentRect? {
            if (!line.valid() || regions.size < 2 || regions.distinct().size != regions.size ||
                regions.any { !it.valid() || !line.overlapsVertically(it) }
            ) return null
            val ordered = regions.sortedBy { it.left }
            val minHeight = minOf(line.bottom - line.top, ordered.minOf { it.bottom - it.top })
            val maxGap = maxOf(4, minHeight / 4)
            val bandTop = maxOf(line.top, ordered.maxOf { it.top })
            val bandBottom = minOf(line.bottom, ordered.minOf { it.bottom })
            if (bandBottom - bandTop < maxOf(1, minHeight / 2)) return null
            var right = ordered.first().right
            for (region in ordered.drop(1)) {
                if (region.left - right > maxGap) return null
                right = maxOf(right, region.right)
            }
            val union = OcrContentRect(ordered.first().left, ordered.minOf { it.top },
                right, ordered.maxOf { it.bottom })
            val tolerance = maxOf(4, (line.bottom - line.top) / 4)
            val leading = union.left <= line.left + tolerance && union.right > line.left
            val trailing = union.right >= line.right - tolerance && union.left < line.right
            return union.takeIf { leading != trailing }
        }

        /**
         * An edge visual region (single or grouped) may also have been rendered as an unverified
         * Unicode emoji span by OCR. Only a matching isolated read can admit contiguous text.
         */
        fun isolatedTextCandidate(parts: List<OcrContentToken>): OcrContentToken? {
            val marker = parts.singleOrNull { it.kind == OcrContentKind.PLACEHOLDER } ?: return null
            val grouped = marker.visualRegions.isNotEmpty()
            // The producer inserts a literal separator after the marker. When OCR also
            // renders leading emoji, that separator is its own TEXT token, not message text.
            val separator = if (grouped && parts.size == 4 && parts.first() === marker) {
                parts[1].takeIf { it.kind == OcrContentKind.TEXT && it.text == " " }
            } else null
            val content = parts.filterNot { it === separator }
            if (content.size !in 2..3) return null
            val text = content.singleOrNull { it.kind == OcrContentKind.TEXT } ?: return null
            val line = text.lineBounds ?: return null
            val region = marker.symbolBounds ?: return null
            if (text.status != OcrTokenStatus.AMBIGUOUS || text.source != OcrTokenSource.OCR_LINE ||
                text.symbolBounds != null || text.text.isBlank() || '\n' in text.text ||
                marker.status != OcrTokenStatus.UNVERIFIED || marker.source != OcrTokenSource.VISUAL_GUARD ||
                marker.text != OcrUncertaintyMarkers.UNCERTAIN_SYMBOL_ONLY ||
                marker.isolatedTextEvidence != null || !line.valid() || !region.valid() ||
                parts.any { it.lineIndex != text.lineIndex || it.lineBounds != line ||
                    (it !== marker && it.visualRegions.isNotEmpty()) }
            ) return null
            if (grouped && (parts.first() !== marker || groupedEdgeRegion(line, marker.visualRegions) != region))
                return null
            if (separator != null && (separator.status != OcrTokenStatus.AMBIGUOUS ||
                separator.source != OcrTokenSource.OCR_LINE || separator.symbolBounds != null ||
                separator.isolatedTextEvidence != null)
            ) return null
            val tolerance = maxOf(4, (line.bottom - line.top) / 4)
            val leading = region.left <= line.left + tolerance && region.right > line.left
            val trailing = region.right >= line.right - tolerance && region.left < line.right
            if (!line.overlapsVertically(region) || leading == trailing) return null
            if (separator != null && !leading) return null
            if (content.size == 3) {
                val emoji = content.singleOrNull { it.kind == OcrContentKind.EMOJI } ?: return null
                if (emoji.status != OcrTokenStatus.UNVERIFIED ||
                    emoji.source != OcrTokenSource.UNICODE_EMOJI ||
                    emoji.symbolBounds != null || emoji.isolatedTextEvidence != null ||
                    emoji.text.isBlank() || '\n' in emoji.text ||
                    content.indexOf(emoji) != (if (leading) content.indexOf(text) - 1 else content.indexOf(text) + 1)
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
                        token.lineBounds == bounds && token.isolatedTextEvidence == null &&
                            token.visualRegions.isEmpty() && when (token.kind) {
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
