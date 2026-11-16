package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomoOcrSampleMetricsTest {
    @Test fun reportsRecognitionYieldAndMeanConfidenceSeparatelyFromAccuracy() {
        val metrics = MomoOcrSampleMetrics.calculate(
            attemptedTargetCount = 4,
            textByRowIndex = mapOf(1 to "你好", 2 to "", 3 to "早上好"),
            failedTargetCount = 2,
            confidenceByRowIndex = mapOf(1 to 0.9f, 2 to 0.7f, 3 to Float.NaN, 4 to 1.2f)
        )
        assertEquals(2, metrics.recognizedTargetCount)
        assertEquals(2, metrics.failedTargetCount)
        assertEquals(50.0, metrics.recognitionYieldPercent!!, 0.0001)
        assertEquals(2, metrics.confidenceSampleCount)
        assertEquals(0.8, metrics.meanConfidence!!, 0.0001)
    }

    @Test fun zeroTargetsHasNoYieldOrConfidenceEstimate() {
        val metrics = MomoOcrSampleMetrics.calculate(0, emptyMap(), 0, emptyMap())
        assertNull(metrics.recognitionYieldPercent)
        assertNull(metrics.meanConfidence)
        assertEquals(0, metrics.confidenceSampleCount)
    }
}
