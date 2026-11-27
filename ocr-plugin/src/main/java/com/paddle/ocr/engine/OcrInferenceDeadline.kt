package com.paddle.ocr.engine

import org.autojs.plugin.paddle.ocr.api.OcrPluginContract

internal class OcrInferenceTimeout : IllegalStateException(OcrPluginContract.INFERENCE_TIMEOUT_SIGNAL)

/**
 * One image, one monotonic deadline. Only the owner may retire this token, after all native
 * work returns. The watchdog may request termination but must never close native resources.
 */
internal class OcrInferenceDeadline(
    private val nowMs: () -> Long,
    private val terminate: () -> Unit,
    private val budgetMs: Long = HOT_INFERENCE_BUDGET_MS,
) {
    init { require(budgetMs > 0L) }
    private val startedAtMs = nowMs()
    private var expired = false
    private var retired = false

    @Synchronized
    fun requestTerminationIfDue(): Boolean {
        if (retired || expired || nowMs() - startedAtMs < budgetMs) return false
        expired = true
        // The production callback logs failures without releasing native resources.
        terminate()
        return true
    }

    @Synchronized
    fun checkActive() {
        requestTerminationIfDue()
        if (expired) throw OcrInferenceTimeout()
        check(!retired) { "OCR inference deadline already retired" }
    }

    @Synchronized
    fun complete() {
        checkActive()
        retired = true
    }

    @Synchronized
    fun retire() {
        retired = true
    }

    companion object {
        const val HOT_INFERENCE_BUDGET_MS = 5_000L
    }
}
