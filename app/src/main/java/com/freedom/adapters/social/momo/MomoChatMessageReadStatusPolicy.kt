package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageReadStatus

/** Keeps structural message-read success distinct from OCR engine execution success. */
internal object MomoChatMessageReadStatusPolicy {
    fun afterOcr(
        fallbackStatus: MessageReadStatus,
        hasMessages: Boolean,
        allMessageDirectionsUnknown: Boolean,
        hasOcrTargets: Boolean,
        attemptedTargets: Int,
        recognizedTargets: Int,
        failedTargets: Int,
    ): MessageReadStatus {
        val attempted = attemptedTargets.coerceAtLeast(0)
        val recognized = recognizedTargets.coerceAtLeast(0)
        val failed = failedTargets.coerceAtLeast(0)
        if (attempted > 0 && failed > 0) {
            return if (recognized == 0) MessageReadStatus.OCR_FAILED else MessageReadStatus.OCR_PARTIAL
        }
        return when {
            !hasMessages && hasOcrTargets -> MessageReadStatus.MESSAGE_TEXT_NOT_EXPOSED
            !hasMessages -> fallbackStatus
            allMessageDirectionsUnknown -> MessageReadStatus.DIRECTION_UNKNOWN
            else -> MessageReadStatus.SUCCESS
        }
    }

    fun afterOcrException(): MessageReadStatus = MessageReadStatus.OCR_FAILED
}
