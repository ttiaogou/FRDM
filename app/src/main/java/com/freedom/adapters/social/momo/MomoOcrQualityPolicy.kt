package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageContentQuality

/**
 * Admission policy for OCR-derived message bodies.
 *
 * OCR correctness is not something Freedom can prove locally. The caller is responsible for
 * validating the window, row geometry, message order and sender direction; once a non-empty OCR
 * result passes that structural admission path, it is usable model input. The model may perform
 * its own reasoning, but Freedom must not pretend that a second local gate can establish textual
 * ground truth.
 */
internal object MomoOcrQualityPolicy {
    fun classify(text: String): MessageContentQuality {
        if (text.isBlank()) return MessageContentQuality.FAILED
        return MessageContentQuality.VERIFIED
    }
}
