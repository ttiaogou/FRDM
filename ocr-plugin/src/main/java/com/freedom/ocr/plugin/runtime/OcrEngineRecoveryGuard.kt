package com.freedom.ocr.plugin.runtime

import com.paddle.ocr.engine.OcrInferenceDeadline

/**
 * Independent of the engine and native cancellation locks. A phase token remains live until
 * the actual acquisition or inference AND its cleanup have returned. Escalation is irreversible.
 */
internal class OcrEngineRecoveryGuard(
    private val startedAtMs: Long,
    private val nowMs: () -> Long,
    private val escalate: (String) -> Unit,
    private val phase: Phase = Phase.INFERENCE,
) {
    enum class Phase { INFERENCE, COLD_LOAD }

    private var finished = false
    private var escalated = false

    @Synchronized
    fun escalateIfDue(): Boolean {
        val budgetMs = if (phase == Phase.COLD_LOAD) COLD_LOAD_BUDGET_MS else HARD_EXIT_BUDGET_MS
        if (nowMs() - startedAtMs < budgetMs) return false
        return escalateOnce(if (phase == Phase.COLD_LOAD) "COLD_LOAD_DEADLINE_EXPIRED" else "EXIT_GRACE_EXPIRED")
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
        // The scheduled callback may be delayed by the OS. Do not publish a
        // late successful acquisition or inference before that callback runs.
        if (!finished && !escalated) escalateIfDue()
        finished = true
        return !escalated
    }

    companion object {
        // Model preparation (including bundled asset install/hash) and native engine
        // acquisition share this separate hard limit; they have no RunOptions to cancel.
        const val COLD_LOAD_BUDGET_MS = 30_000L
        const val EXIT_GRACE_MS = 2_000L
        const val HARD_EXIT_BUDGET_MS = OcrInferenceDeadline.HOT_INFERENCE_BUDGET_MS + EXIT_GRACE_MS
    }
}
