package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality

/**
 * Admission policy for OCR-derived message bodies after structural validation.
 * Unknown visual markers and standalone symbol output cannot be treated as verified text.
 * Preserve the original OCR string; token-level recovery belongs to a later stage.
 */
internal object MomoOcrQualityPolicy {
    private val uncertaintyMarkers = listOf(
        MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL,
        MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL,
        MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY
    )

    fun classify(text: String): MessageContentQuality {
        val value = text.trim()
        if (value.isEmpty()) return MessageContentQuality.FAILED
        // The OCR plugin supplies line-level boxes, not character-level visual evidence.
        // Do not guess text boundaries around an explicit unknown visual marker.
        if (uncertaintyMarkers.any(value::contains)) return MessageContentQuality.AMBIGUOUS
        // Standalone symbol output (including emoji) is not verified conversational text.
        // Keycap emoji include digit code points, so treat standalone keycap as ambiguous too.
        if (value.codePoints().noneMatch { Character.isLetterOrDigit(it) } ||
            ('\u20E3' in value && value.codePoints().noneMatch { Character.isLetter(it) })
        ) return MessageContentQuality.AMBIGUOUS
        return MessageContentQuality.VERIFIED
    }
}
