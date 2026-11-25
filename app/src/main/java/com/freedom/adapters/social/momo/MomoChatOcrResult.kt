package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.OcrContentToken
import com.freedom.features.aireply.domain.message.OcrTextProjection

/**
 * Short-lived local OCR outputs. The legacy chineseTextByRowIndex archive field now stores the
 * complete PP-OCRv5 multilingual output for backward-compatible sample serialization;
 * latinTextByRowIndex remains empty because the new engine uses one multilingual model.
 */
internal data class MomoChatOcrResult(
    val textByRowIndex: Map<Int, String>,
    val attemptedTargetCount: Int,
    val failedTargetCount: Int,
    val failureReason: String? = null,
    val chineseTextByRowIndex: Map<Int, String> = emptyMap(),
    val latinTextByRowIndex: Map<Int, String> = emptyMap(),
    val qualityByRowIndex: Map<Int, MessageContentQuality> = emptyMap(),
    val captureDescription: String? = null,
    val sampleScreenshotPath: String? = null,
    val sampleCropPathsByRowIndex: Map<Int, String> = emptyMap(),
    /** Mean plugin confidence per row; this is not a measure of ground-truth accuracy. */
    val confidenceByRowIndex: Map<Int, Float> = emptyMap(),
    /** Number of valid OCR text/box lines returned for each cropped row. */
    val lineCountByRowIndex: Map<Int, Int> = emptyMap(),
    /** Captured batch identity; blank is accepted only for legacy/unit-test callers. */
    val observationBatchId: String = "",
    /** Row-indexed in-memory OCR tokens; legacy text and P0 quality remain unchanged. */
    val tokensByRowIndex: Map<Int, List<OcrContentToken>> = emptyMap(),
    /** P2 evidence-backed text; kept separate from full OCR text and P0 content quality. */
    val textProjectionsByRowIndex: Map<Int, OcrTextProjection> = emptyMap(),
    val admissionDeferred: Boolean = false
) {
    override fun toString(): String =
        "MomoChatOcrResult(recognizedRows=${textByRowIndex.values.count(String::isNotBlank)}," +
            "attemptedTargetCount=$attemptedTargetCount,failedTargetCount=$failedTargetCount," +
            "captureDescription=$captureDescription," +
            "failureReason=$failureReason,chinese=[REDACTED],latin=[REDACTED],text=[REDACTED])"
}
