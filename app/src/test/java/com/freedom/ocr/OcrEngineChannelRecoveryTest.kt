package com.freedom.ocr

import org.junit.Assert.*
import org.junit.Test

class OcrEngineChannelRecoveryTest {
    @Test fun engineChannelFaultsRequireFreshFrameButOrdinaryFailuresKeepExistingPolicy() {
        assertTrue(OcrPluginFailureKind.OCR_TIMEOUT.requiresFreshOcrObservation())
        assertTrue(OcrPluginFailureKind.BINDER_DIED.requiresFreshOcrObservation())
        assertTrue(OcrPluginFailureKind.SERVICE_DISCONNECTED.requiresFreshOcrObservation())
        assertFalse(OcrPluginFailureKind.UNKNOWN.requiresFreshOcrObservation())
        assertFalse(OcrPluginFailureKind.IMAGE_TRANSFER_FAILED.requiresFreshOcrObservation())
        assertFalse(OcrPluginFailureKind.BIND_TIMEOUT.requiresFreshOcrObservation())
        assertFalse(OcrPluginFailureKind.REMOTE_CALL_FAILED.requiresFreshOcrObservation())
    }
}
