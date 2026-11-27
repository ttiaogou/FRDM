package com.freedom.ocr.plugin.runtime

import com.paddle.ocr.engine.OcrInferenceDeadline

/**
 * Independent of the engine and native cancellation locks. A token remains live until inference
 * AND timeout cleanup have actually returned. Escalation is irreversible for that token.
 */
internal class OcrEngineRecoveryGuard(
    private val startedAtMs: Long,
    private val nowMs: () -> Long,
    private val escalate: (String) -> Unit,
) {
    private var finished = false
    private var escalated = false

    @Synchronized
    fun escalateIfDue(): Boolean {
        if (nowMs() - startedAtMs < HARD_EXIT_BUDGET_MS) return false
        return escalateOnce("EXIT_GRACE_EXPIRED")
    }

    @Synchronized
    fun escalateReleaseFailure(): Boolean = escalateOnce("ENGINE_RELEASE_FAILED")

    private fun escalateOnce(reason: String): Boolean {
        if (finished || escalated) return false
        escalated = true
        escalate(reason)
        return true
    }

    /** Only the actual service worker may finish this guard, after cleanup. */
    @Synchronized
    fun finish(): Boolean {
        finished = true
        return !escalated
    }

    companion object {
        const val EXIT_GRACE_MS = 2_000L
        const val HARD_EXIT_BUDGET_MS = OcrInferenceDeadline.HOT_INFERENCE_BUDGET_MS + EXIT_GRACE_MS
    }
}
