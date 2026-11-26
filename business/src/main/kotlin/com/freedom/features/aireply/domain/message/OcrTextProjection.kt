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
                        token.lineBounds == bounds && when (token.kind) {
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
                val clean = if (supported) verifiedLineText(originalText) else null
                if (clean == null) {
                    excluded += index
                    continue
                }
                included += index
                textLines += clean
                if (parts.any { it.kind == OcrContentKind.EMOJI }) omittedEmoji = true
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
