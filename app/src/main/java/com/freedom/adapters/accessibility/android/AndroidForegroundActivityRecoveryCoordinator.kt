package com.freedom.adapters.accessibility.android

import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler

/**
 * Re-acquires Activity evidence without touching UI, navigating, or granting any gesture lease.
 * Two independent reads must agree on Activity, current window ID and page generation.
 */
internal class AndroidForegroundActivityRecoveryCoordinator(
    private val scheduler: TaskScheduler,
    private val isRunRequested: () -> Boolean,
    private val currentActivityClass: () -> String,
    private val readEvidence: () -> ActivityIdentityProbeResult,
    private val acceptEvidence: (ActivityReadOnlyEvidence) -> Boolean,
    private val onLog: (String) -> Unit = {}
) {
    private var active = false
    private var epoch = 0L
    private var pending: ScheduledTask? = null
    private var firstSample: ActivityReadOnlyEvidence? = null
    private var lastPendingReason: String? = null

    fun ensureStarted() {
        if (active || !isRunRequested() || currentActivityClass().isNotBlank()) return
        active = true
        epoch++
        onLog("ACTIVITY_RECOVERY_REQUESTED source=SERVICE_OR_RUN_RECHECK mode=READ_ONLY")
        schedule(0L)
    }

    fun restart() {
        stop()
        ensureStarted()
    }

    fun onWindowTransition() {
        firstSample = null
        if (active) schedule(WINDOW_SETTLE_MS)
        else ensureStarted()
    }

    fun stop() {
        epoch++
        pending?.cancel()
        pending = null
        firstSample = null
        lastPendingReason = null
        active = false
    }

    private fun schedule(delayMs: Long) {
        pending?.cancel()
        val expectedEpoch = epoch
        pending = scheduler.schedule(delayMs) {
            pending = null
            if (active && expectedEpoch == epoch) inspect(expectedEpoch)
        }
    }

    private fun inspect(expectedEpoch: Long) {
        if (expectedEpoch != epoch) return
        if (!isRunRequested() || currentActivityClass().isNotBlank()) {
            stop()
            return
        }
        when (val result = runCatching { readEvidence() }.getOrElse {
            ActivityIdentityProbeResult.Pending("READ_ONLY_PROBE_ERROR:" + it.javaClass.simpleName)
        }) {
            is ActivityIdentityProbeResult.Pending -> {
                firstSample = null
                if (lastPendingReason != result.reason) {
                    lastPendingReason = result.reason
                    onLog("ACTIVITY_EVIDENCE_PENDING reason=" + result.reason +
                        "; activityEvidenceSource=USAGE_ACTIVITY_RESUMED; rejectReason=" + result.reason +
                        "; no Activity inferred from a node tree; gestures remain gated")
                }
                schedule(RETRY_MS)
            }
            is ActivityIdentityProbeResult.Proven -> {
                lastPendingReason = null
                val proof = result.evidence
                if (proof.windowId < 0 || proof.activityClass.isBlank()) {
                    firstSample = null
                    schedule(RETRY_MS)
                    return
                }
                if (firstSample != proof) {
                    firstSample = proof
                    onLog("ACTIVITY_RECOVERY_SAMPLE sample=1/2; windowId=" + proof.windowId +
                        "; generation=" + proof.windowGeneration)
                    schedule(STABLE_SAMPLE_MS)
                    return
                }
                if (acceptEvidence(proof)) {
                    onLog("ACTIVITY_EVIDENCE_VERIFIED source=USAGE_ACTIVITY_RESUMED; windowId=" +
                        proof.windowId + "; generation=" + proof.windowGeneration +
                        "; Activity=" + proof.activityClass +
                        "; activityEvidenceSource=USAGE_ACTIVITY_RESUMED; result=VERIFIED")
                    stop()
                } else {
                    firstSample = null
                    schedule(RETRY_MS)
                }
            }
        }
    }

    companion object {
        const val STABLE_SAMPLE_MS = 350L
        const val WINDOW_SETTLE_MS = 350L
        const val RETRY_MS = 5_000L
    }
}
