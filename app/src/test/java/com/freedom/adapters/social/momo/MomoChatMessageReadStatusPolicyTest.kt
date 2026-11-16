package com.freedom.adapters.social.momo

import com.freedom.features.aireply.domain.message.MessageReadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoChatMessageReadStatusPolicyTest {
    @Test
    fun allFailedOcrTargetsDoNotLeaveBatchMarkedSuccessful() {
        assertEquals(
            MessageReadStatus.OCR_FAILED,
            MomoChatMessageReadStatusPolicy.afterOcr(
                fallbackStatus = MessageReadStatus.SUCCESS,
                hasMessages = true,
                allMessageDirectionsUnknown = false,
                hasOcrTargets = true,
                attemptedTargets = 5,
                recognizedTargets = 0,
                failedTargets = 5,
            ),
        )
    }

    @Test
    fun mixedOcrSuccessAndFailureIsReportedAsPartial() {
        assertEquals(
            MessageReadStatus.OCR_PARTIAL,
            MomoChatMessageReadStatusPolicy.afterOcr(
                fallbackStatus = MessageReadStatus.SUCCESS,
                hasMessages = true,
                allMessageDirectionsUnknown = false,
                hasOcrTargets = true,
                attemptedTargets = 5,
                recognizedTargets = 2,
                failedTargets = 3,
            ),
        )
    }

    @Test
    fun successfulOcrPreservesDirectionDiagnostics() {
        assertEquals(
            MessageReadStatus.DIRECTION_UNKNOWN,
            MomoChatMessageReadStatusPolicy.afterOcr(
                fallbackStatus = MessageReadStatus.SUCCESS,
                hasMessages = true,
                allMessageDirectionsUnknown = true,
                hasOcrTargets = true,
                attemptedTargets = 5,
                recognizedTargets = 5,
                failedTargets = 0,
            ),
        )
    }

    @Test
    fun ocrExceptionIsExplicitFailure() {
        assertEquals(MessageReadStatus.OCR_FAILED, MomoChatMessageReadStatusPolicy.afterOcrException())
    }
}
