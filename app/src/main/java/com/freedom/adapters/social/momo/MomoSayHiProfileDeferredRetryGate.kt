package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.runtime.TargetAppReadinessController

/**
 * Run-volatile gate for a deferred received-greetings profile ID swipe.
 *
 * A DEFERRED result is not a swipe. Wait for startup readiness to settle before at
 * most one same-window probe. After two distinct deferrals of identical window evidence,
 * keep observing without repeating the gesture until the verified window/Activity changes.
 * No durable cursor, identity cache or gesture trajectory is altered.
 */
internal class MomoSayHiProfileDeferredRetryGate(
    private val sameWindowProbeDelayMs: Long =
        TargetAppReadinessController.DEFAULT_FOREGROUND_QUIET_PERIOD_MS
) {
    private var deferredWindowKey: String? = null
    private var deferredAtUptimeMs = 0L
    private var sameWindowProbeExhausted = false
    private var lastDeferredRequestId: String? = null

    init { require(sameWindowProbeDelayMs >= 0L) }

    @Synchronized
    fun onDeferred(requestId: String, window: WindowRef, nowUptimeMs: Long) {
        if (requestId == lastDeferredRequestId) return
        val key = windowIdentity(window)
        if (key != deferredWindowKey) {
            deferredWindowKey = key
            deferredAtUptimeMs = nowUptimeMs
            sameWindowProbeExhausted = false
        } else {
            sameWindowProbeExhausted = true
        }
        lastDeferredRequestId = requestId
    }

    /** Call only after a new complete profile snapshot and current-window verification. */
    @Synchronized
    fun mayAttempt(window: WindowRef, nowUptimeMs: Long): Boolean {
        val blockedKey = deferredWindowKey ?: return true
        if (windowIdentity(window) != blockedKey) {
            reset()
            return true
        }
        return !sameWindowProbeExhausted &&
            nowUptimeMs >= deferredAtUptimeMs &&
            nowUptimeMs - deferredAtUptimeMs >= sameWindowProbeDelayMs
    }

    @Synchronized
    fun reset() {
        deferredWindowKey = null
        deferredAtUptimeMs = 0L
        sameWindowProbeExhausted = false
        lastDeferredRequestId = null
    }

    private fun windowIdentity(window: WindowRef): String =
        window.packageName + "|" + window.windowId + "|" +
            window.generation + "|" + window.activityClass
}
