package com.freedom.features.aireply.domain.message

/** Shared OCR uncertainty markers. Legacy persisted strings must never treat these as verified text. */
object OcrUncertaintyMarkers {
    const val UNKNOWN_VISUAL_SYMBOL = "[表情/图形未识别]"
    const val UNCERTAIN_TEXT_SYMBOL = "[标点/符号待确认]"
    const val UNCERTAIN_SYMBOL_ONLY = "[图形/符号内容待确认]"

    val all: List<String> = listOf(
        UNKNOWN_VISUAL_SYMBOL, UNCERTAIN_TEXT_SYMBOL, UNCERTAIN_SYMBOL_ONLY
    )
}
