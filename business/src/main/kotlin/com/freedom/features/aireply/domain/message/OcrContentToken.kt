package com.freedom.features.aireply.domain.message

/** Platform-neutral, crop-local OCR evidence; tokens never authorize model input themselves. */
enum class OcrContentKind { TEXT, EMOJI, PLACEHOLDER }
enum class OcrTokenStatus { RECOGNIZED, UNVERIFIED, AMBIGUOUS }
enum class OcrTokenSource { OCR_LINE, UNICODE_EMOJI, VISUAL_GUARD, OCR_SYMBOL }

data class OcrContentRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Ordered OCR content piece. lineBounds is the whole OCR-line rectangle, NOT the piece bounds.
 * symbolBounds is null when no character/visual-region coordinates exist.
 * RECOGNIZED means text was observed, not independently proved correct.
 * Raw content stays local; never print it through toString().
 */
data class OcrContentToken(
    val kind: OcrContentKind,
    val status: OcrTokenStatus,
    val text: String,
    val source: OcrTokenSource,
    val lineIndex: Int,
    val lineBounds: OcrContentRect? = null,
    val symbolBounds: OcrContentRect? = null
) {
    override fun toString(): String =
        "OcrContentToken(kind=$kind,status=$status,source=$source,lineIndex=$lineIndex," +
            "lineBounds=$lineBounds,symbolBounds=$symbolBounds,text=[REDACTED])"
}
