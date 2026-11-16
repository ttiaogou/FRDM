package com.freedom.adapters.social.momo

/**
 * Selects a single recognizer output. Different language recognizers must never be token-merged:
 * OCR boxes are independent and no verified character-to-character alignment exists here.
 */
internal object MomoOcrTextSelector {
    /** Prefer the Chinese recognizer whenever it returned any text; use Latin only as a fallback. */
    fun selectPrimary(chineseText: String, latinText: String): String =
        chineseText.takeIf(String::isNotBlank) ?: latinText
}
