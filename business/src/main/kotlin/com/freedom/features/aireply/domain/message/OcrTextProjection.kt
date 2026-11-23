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
}
