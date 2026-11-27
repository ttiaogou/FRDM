package com.freedom.ocr

import org.autojs.plugin.paddle.ocr.api.OcrPerformanceDiagnostics
import org.autojs.plugin.paddle.ocr.api.OcrPluginContract
import org.autojs.plugin.paddle.ocr.api.OcrSlowCallObservation
import org.junit.Assert.*
import org.junit.Test

class OcrPerformanceDiagnosticsTest {
    @Test fun missingMetricsAreNotZeroAndOldPluginStillWorks() {
        val text = OcrPerformanceDiagnostics.formatFields(
            { key -> if (key == OcrPluginContract.EXTRA_TOTAL_TIME_MS) 172L else null },
            { null }
        )
        assertTrue(text.contains("engineTotalMs=172"))
        assertTrue(text.contains("recInferenceMs=UNAVAILABLE"))
        assertTrue(text.contains("recInputShapes=UNAVAILABLE"))
    }

    @Test fun metricsPreserveZeroAndSeparateRecognitionStages() {
        val values = mapOf(
            OcrPluginContract.EXTRA_REC_PREPROCESS_MS to 0L,
            OcrPluginContract.EXTRA_REC_INFERENCE_MS to 17095L,
            OcrPluginContract.EXTRA_REC_POSTPROCESS_MS to 3L
        )
        val text = OcrPerformanceDiagnostics.formatFields(values::get) { "[1,3,48,320]" }
        assertTrue(text.contains("recPreprocessMs=0"))
        assertTrue(text.contains("recInferenceMs=17095"))
        assertTrue(text.contains("recPostprocessMs=3"))
        assertTrue(text.contains("recInputShapes=[1,3,48,320]"))
    }

    @Test fun seriesAreBoundedAndArbitraryPluginTextIsNotLogged() {
        assertEquals("[1,3,48,320]", OcrPerformanceDiagnostics.formatShape(listOf(1,3,48,320)))
        assertEquals("UNAVAILABLE", OcrPerformanceDiagnostics.formatLineTimes(emptyList()))
        assertTrue(OcrPerformanceDiagnostics.formatLineTimes(List(100) { 1L }).endsWith("(+84)"))
        val text = OcrPerformanceDiagnostics.formatFields({ null }, { "private message" })
        assertFalse(text.contains("private message"))
        assertTrue(text.contains("detInputShape=UNAVAILABLE"))
    }

    @Test fun slowWarningIsSingleShotAndNeverReopensAfterFinish() {
        val call = OcrSlowCallObservation(100L)
        assertNull(call.claimSlowWarning(1099L))
        assertEquals(1000L, call.claimSlowWarning(1100L))
        assertNull(call.claimSlowWarning(5000L))
        call.finish()
        assertNull(call.claimSlowWarning(6000L))
    }

    @Test fun fastCompletionAndOldAttemptCannotWarnForNextAttempt() {
        val old = OcrSlowCallObservation(0L)
        old.finish()
        val next = OcrSlowCallObservation(2000L)
        assertNull(old.claimSlowWarning(3000L))
        assertEquals(1000L, next.claimSlowWarning(3000L))
    }
}
