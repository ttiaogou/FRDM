package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality

/** Conservative trust policy for OCR-derived message bodies. */
internal object MomoOcrQualityPolicy {
    fun classify(text: String): MessageContentQuality {
        if (text.isBlank()) return MessageContentQuality.FAILED
        if (text.contains(MomoOcrVisualSymbolGuard.UNKNOWN_VISUAL_SYMBOL) ||
            text.contains(MomoOcrVisualSymbolGuard.UNCERTAIN_TEXT_SYMBOL) ||
            text.contains(MomoOcrVisualSymbolGuard.UNCERTAIN_SYMBOL_ONLY)
        ) {
            return MessageContentQuality.AMBIGUOUS
        }
        if (isSymbolOnly(text)) return MessageContentQuality.AMBIGUOUS
        // Confidence scores from this same OCR engine are not independent evidence. Keep every
        // ordinary one-engine transcription UNVERIFIED; do not promote it for AI context.
        return MessageContentQuality.UNVERIFIED
    }

    private fun isSymbolOnly(value: String): Boolean {
        var hasVisibleSymbol = false
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            if (Character.isWhitespace(codePoint)) {
                offset += Character.charCount(codePoint)
                continue
            }
            val type = Character.getType(codePoint)
            val symbolOrPunctuation = type in SYMBOL_OR_PUNCTUATION_TYPES ||
                codePoint in 0x1F000..0x1FAFF ||
                codePoint in 0x2600..0x27BF
            if (!symbolOrPunctuation) return false
            hasVisibleSymbol = true
            offset += Character.charCount(codePoint)
        }
        return hasVisibleSymbol
    }

    private val SYMBOL_OR_PUNCTUATION_TYPES = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt()
    )
}
