package org.autojs.plugin.paddle.ocr.api

/** Optional, bounded performance evidence; never controls recognition or retry admission. */
object OcrPerformanceDiagnostics {
    const val SLOW_REQUEST_MS = 1_000L
    private const val MAX_SAMPLES = 16
    private const val MAX_SHAPE_DIMENSIONS = 8

    val numericFields: List<Pair<String, String>> = listOf(
        "decodeMs" to OcrPluginContract.EXTRA_IMAGE_DECODE_TIME_MS,
        "engineLockWaitMs" to OcrPluginContract.EXTRA_ENGINE_LOCK_WAIT_MS,
        "modelPrepareMs" to OcrPluginContract.EXTRA_MODEL_PREPARE_TIME_MS,
        "engineAcquireMs" to OcrPluginContract.EXTRA_ENGINE_ACQUIRE_TIME_MS,
        "engineCacheHit" to OcrPluginContract.EXTRA_ENGINE_CACHE_HIT,
        "engineCreateCount" to OcrPluginContract.EXTRA_ENGINE_CREATE_COUNT,
        "detMaxSideLimit" to OcrPluginContract.EXTRA_DET_MAX_SIDE_LIMIT,
        "inferenceWallMs" to OcrPluginContract.EXTRA_INFERENCE_WALL_TIME_MS,
        "detectionMs" to OcrPluginContract.EXTRA_DETECTION_TIME_MS,
        "recognitionMs" to OcrPluginContract.EXTRA_RECOGNITION_TIME_MS,
        "coldLoadMs" to OcrPluginContract.EXTRA_COLD_LOAD_TIME_MS,
        "engineTotalMs" to OcrPluginContract.EXTRA_TOTAL_TIME_MS,
        "detPreprocessMs" to OcrPluginContract.EXTRA_DET_PREPROCESS_MS,
        "detInferenceMs" to OcrPluginContract.EXTRA_DET_INFERENCE_MS,
        "detPostprocessMs" to OcrPluginContract.EXTRA_DET_POSTPROCESS_MS,
        "recPreprocessMs" to OcrPluginContract.EXTRA_REC_PREPROCESS_MS,
        "recInferenceMs" to OcrPluginContract.EXTRA_REC_INFERENCE_MS,
        "recPostprocessMs" to OcrPluginContract.EXTRA_REC_POSTPROCESS_MS,
        "pipelineOverheadMs" to OcrPluginContract.EXTRA_PIPELINE_OVERHEAD_MS,
        "lineCount" to OcrPluginContract.EXTRA_LINE_COUNT
    )
    val seriesFields: List<Pair<String, String>> = listOf(
        "detInputShape" to OcrPluginContract.EXTRA_DET_INPUT_SHAPE,
        "recInputShapes" to OcrPluginContract.EXTRA_REC_INPUT_SHAPES,
        "perLineRecMs" to OcrPluginContract.EXTRA_PER_LINE_REC_MS
    )

    fun formatShape(shape: List<Int>): String =
        if (shape.isEmpty()) "UNAVAILABLE" else bounded(shape, MAX_SHAPE_DIMENSIONS) { it.toString() }

    fun formatShapes(shapes: List<List<Int>>): String =
        if (shapes.isEmpty()) "UNAVAILABLE" else bounded(shapes, MAX_SAMPLES, ::formatShape)

    fun formatLineTimes(times: List<Long>): String =
        if (times.isEmpty()) "UNAVAILABLE" else bounded(times, MAX_SAMPLES) { it.toString() }

    private fun <T> bounded(values: List<T>, limit: Int, render: (T) -> String): String {
        val shown = values.take(limit).joinToString(",", "[", "]", transform = render)
        return if (values.size > limit) "$shown(+${values.size - limit})" else shown
    }

    /** Older plugins and empty-result AIDL responses must not be interpreted as zero. */
    fun formatFields(number: (String) -> Long?, series: (String) -> String?): String =
        (numericFields.map { (label, key) -> "$label=${number(key) ?: "UNAVAILABLE"}" } +
            seriesFields.map { (label, key) ->
                // Accept only bounded numeric diagnostics, not arbitrary strings from plugins.
                val value = series(key)?.takeIf { it.length <= 2048 && it.all { c ->
                    c in '0'..'9' || c in "[],()+-"
                } && it.isNotEmpty() }
                "$label=${value ?: "UNAVAILABLE"}"
            }).joinToString(";")
}

/** A per-attempt observation token. Late callbacks cannot report a completed call as running. */
class OcrSlowCallObservation(private val startedAtMs: Long) {
    private var finished = false
    private var reported = false

    @Synchronized
    fun claimSlowWarning(nowMs: Long): Long? {
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(0L)
        if (finished || reported || elapsed < OcrPerformanceDiagnostics.SLOW_REQUEST_MS) return null
        reported = true
        return elapsed
    }

    @Synchronized
    fun finish() {
        finished = true
    }
}
