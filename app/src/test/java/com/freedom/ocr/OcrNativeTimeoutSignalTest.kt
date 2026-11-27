package com.freedom.ocr

import org.autojs.plugin.paddle.ocr.api.OcrPluginContract
import org.junit.Assert.*
import org.junit.Test

class OcrNativeTimeoutSignalTest {
    @Test fun exactTypedSignalIsNonRetryableTimeout() {
        assertTrue(isNativeInferenceTimeoutSignal(IllegalStateException(OcrPluginContract.INFERENCE_TIMEOUT_SIGNAL)))
        assertFalse(OcrPluginFailureKind.OCR_TIMEOUT.isRetryable())
        assertEquals(OcrLifecycleState.OCR_TIMEOUT, OcrPluginFailureKind.OCR_TIMEOUT.toLifecycleState())
    }

    @Test fun unrelatedErrorsCannotMasqueradeAsTimeout() {
        assertFalse(isNativeInferenceTimeoutSignal(IllegalStateException("other error")))
        assertFalse(isNativeInferenceTimeoutSignal(IllegalStateException("prefix " + OcrPluginContract.INFERENCE_TIMEOUT_SIGNAL)))
        assertFalse(isNativeInferenceTimeoutSignal(IllegalArgumentException(OcrPluginContract.INFERENCE_TIMEOUT_SIGNAL)))
    }
}
