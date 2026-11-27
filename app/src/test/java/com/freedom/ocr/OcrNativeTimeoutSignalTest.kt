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

    @Test fun coldTimeoutIsExactAndUsesExistingFreshObservationPolicy() {
        val marker = OcrPluginContract.COLD_LOAD_TIMEOUT_SIGNAL
        assertTrue(isColdLoadTimeoutSignal(IllegalStateException(marker)))
        assertFalse(isNativeInferenceTimeoutSignal(IllegalStateException(marker)))
        assertFalse(isColdLoadTimeoutSignal(IllegalStateException("prefix $marker")))
        assertFalse(isColdLoadTimeoutSignal(IllegalArgumentException(marker)))
        assertFalse(isColdLoadTimeoutSignal(IllegalStateException(OcrPluginContract.INFERENCE_TIMEOUT_SIGNAL)))
        assertTrue(OcrPluginFailureKind.OCR_TIMEOUT.requiresFreshOcrObservation())
        assertFalse(OcrPluginFailureKind.OCR_TIMEOUT.isRetryable())
    }

    @Test fun unrelatedErrorsCannotMasqueradeAsTimeout() {
        assertFalse(isNativeInferenceTimeoutSignal(IllegalStateException("other error")))
        assertFalse(isNativeInferenceTimeoutSignal(IllegalStateException("prefix " + OcrPluginContract.INFERENCE_TIMEOUT_SIGNAL)))
        assertFalse(isNativeInferenceTimeoutSignal(IllegalArgumentException(OcrPluginContract.INFERENCE_TIMEOUT_SIGNAL)))
    }
}
