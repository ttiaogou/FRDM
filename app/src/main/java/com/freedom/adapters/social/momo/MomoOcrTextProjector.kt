package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.OcrContentToken
import com.freedom.features.aireply.domain.message.OcrTextProjection

/**
 * P2: Extract text ONLY from independently supported OCR lines.
 *
 * The existing PaddleOCR result supplies line boxes, not character boxes. An unknown visual
 * marker or a low-confidence symbol therefore excludes its whole line; it cannot make nearby
 * characters independently admissible. Identified Unicode emoji can be omitted from an otherwise
 * unambiguous OCR line without changing the original message. A same-line visual placeholder
 * remains inadmissible unless the domain verifies separate, matching edge-region OCR evidence.
 *
 * This produces metadata for the later AI-context stage; it does not change P0 message admission.
 */
internal object MomoOcrTextProjector {
    fun extract(renderedText: String, tokens: List<OcrContentToken>): OcrTextProjection? =
        OcrTextProjection.derive(
            renderedText,
            tokens,
            canonicalize = { it },
            verifiedLineText = { text ->
                text.takeIf { MomoOcrQualityPolicy.classify(it) == MessageContentQuality.VERIFIED }
            }
        )
}
