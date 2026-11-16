package com.freedom.adapters.social.momo

/** Objective sample metrics. Recognition yield/confidence are not ground-truth accuracy. */
internal data class MomoOcrSampleMetrics(
    val attemptedTargetCount: Int,
    val recognizedTargetCount: Int,
    val failedTargetCount: Int,
    val recognitionYieldPercent: Double?,
    val confidenceSampleCount: Int,
    val meanConfidence: Double?
) {
    companion object {
        fun calculate(
            attemptedTargetCount: Int,
            textByRowIndex: Map<Int, String>,
            failedTargetCount: Int,
            confidenceByRowIndex: Map<Int, Float>
        ): MomoOcrSampleMetrics {
            val attempted = attemptedTargetCount.coerceAtLeast(0)
            val recognized = textByRowIndex.values.count(String::isNotBlank)
            val confidences = confidenceByRowIndex.values.filter { it.isFinite() && it in 0f..1f }
            return MomoOcrSampleMetrics(
                attemptedTargetCount = attempted,
                recognizedTargetCount = recognized,
                failedTargetCount = failedTargetCount.coerceAtLeast(0),
                recognitionYieldPercent = if (attempted == 0) null else recognized * 100.0 / attempted,
                confidenceSampleCount = confidences.size,
                meanConfidence = confidences.takeIf { it.isNotEmpty() }?.average()
            )
        }
    }
}
