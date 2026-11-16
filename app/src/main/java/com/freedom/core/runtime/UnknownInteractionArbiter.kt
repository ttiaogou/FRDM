package com.freedom.core.runtime

import com.freedom.core.accessibility.events.InputOrigin

/**
 * Counts independent, unattributed interaction behaviours rather than raw accessibility callbacks.
 *
 * Repeated callbacks from the same source/window/page are coalesced for a bounded interval. A
 * global touch boundary has no source node, so it can join the adjacent target feedback belonging
 * to the same window/page. This is correlation only; it does not claim that UNKNOWN is physical
 * human input.
 */
class UnknownInteractionArbiter(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val coalescingWindowMs: Long = DEFAULT_COALESCING_WINDOW_MS
) {
    enum class Disposition {
        SELF_ACTION_IGNORED,
        APPLICATION_EVENT_IGNORED,
        COALESCED_WITH_CURRENT_BEHAVIOUR,
        COUNTED_AS_NEW_BEHAVIOUR,
        THRESHOLD_REACHED,
        HUMAN_CONFIRMED
    }

    data class Observation(
        val disposition: Disposition,
        val count: Int,
        val behaviourId: Long,
        val thresholdReached: Boolean,
        val reason: String
    )

    private var count = 0
    private var nextBehaviourId = 1L
    private var currentBehaviourId = 0L
    private var lastBehaviourAtMs = Long.MIN_VALUE
    private var lastWindowId = -1
    private var lastPageGeneration = Long.MIN_VALUE
    private var lastFamily = ""
    private var lastSourceKey = ""
    private var thresholdLatched = false

    val currentCount: Int
        get() = count

    val isThresholdLatched: Boolean
        get() = thresholdLatched

    fun record(
        origin: InputOrigin,
        windowId: Int,
        pageGeneration: Long,
        family: String,
        sourceKey: String,
        nowMillis: Long
    ): Observation {
        if (origin == InputOrigin.SELF_INJECTED) {
            return Observation(
                disposition = Disposition.SELF_ACTION_IGNORED,
                count = count,
                behaviourId = currentBehaviourId,
                thresholdReached = false,
                reason = "与Freedom自主动作关联；不计入未知行为"
            )
        }

        if (origin == InputOrigin.APPLICATION_INTERNAL) {
            return Observation(
                disposition = Disposition.APPLICATION_EVENT_IGNORED,
                count = count,
                behaviourId = currentBehaviourId,
                thresholdReached = false,
                reason = "目标应用控件反馈已归属应用内部变化；不计入UNKNOWN行为"
            )
        }

        if (origin == InputOrigin.PHYSICAL_USER) {
            thresholdLatched = true
            count = threshold
            currentBehaviourId = nextBehaviourId++
            currentBehaviourAt(nowMillis, windowId, pageGeneration, family, sourceKey)
            return Observation(
                disposition = Disposition.HUMAN_CONFIRMED,
                count = count,
                behaviourId = currentBehaviourId,
                thresholdReached = true,
                reason = "来源分类器提供了明确物理用户输入证据；立即进入保护"
            )
        }

        if (thresholdLatched) {
            // Keep the last-input timestamp fresh; the recovery owner will restart its quiet clock.
            lastBehaviourAtMs = nowMillis
            return Observation(
                disposition = Disposition.COALESCED_WITH_CURRENT_BEHAVIOUR,
                count = count,
                behaviourId = currentBehaviourId,
                thresholdReached = false,
                reason = "三次阈值已触发；当前保护周期内不重复触发"
            )
        }

        // Global touch events can arrive without a reliable window/page attribution. Treat a
        // missing identifier as a wildcard only for correlation with the immediately adjacent
        // interaction burst; concrete conflicting identifiers still split behaviours.
        val sameWindow = windowId == lastWindowId || windowId < 0 || lastWindowId < 0
        val samePage = pageGeneration == lastPageGeneration || pageGeneration <= 0L || lastPageGeneration <= 0L
        val sameWindowAndPage = sameWindow && samePage
        val withinBurst = lastBehaviourAtMs != Long.MIN_VALUE &&
            nowMillis >= lastBehaviourAtMs && nowMillis - lastBehaviourAtMs <= coalescingWindowMs
        val sameFamily = family == lastFamily || family == GLOBAL_TOUCH_FAMILY || lastFamily == GLOBAL_TOUCH_FAMILY
        val sourceCompatible = sourceKey.isBlank() || lastSourceKey.isBlank() || sourceKey == lastSourceKey
        val sameBehaviour = sameWindowAndPage && withinBurst && sameFamily && sourceCompatible

        if (sameBehaviour) {
            lastBehaviourAtMs = nowMillis
            if (lastSourceKey.isBlank() && sourceKey.isNotBlank()) lastSourceKey = sourceKey
            // Once a global boundary with missing attribution joins a concrete target event,
            // retain the newly available identifiers so the next event cannot bridge into a
            // different page/window through the original wildcard values.
            if (lastWindowId < 0 && windowId >= 0) lastWindowId = windowId
            if (lastPageGeneration <= 0L && pageGeneration > 0L) lastPageGeneration = pageGeneration
            return Observation(
                disposition = Disposition.COALESCED_WITH_CURRENT_BEHAVIOUR,
                count = count,
                behaviourId = currentBehaviourId,
                thresholdReached = false,
                reason = "同一窗口、页面代次和交互来源的连续反馈已合并"
            )
        }

        count = (count + 1).coerceAtMost(threshold)
        currentBehaviourId = nextBehaviourId++
        currentBehaviourAt(nowMillis, windowId, pageGeneration, family, sourceKey)
        val reached = count >= threshold
        if (reached) thresholdLatched = true
        return Observation(
            disposition = if (reached) Disposition.THRESHOLD_REACHED
                else Disposition.COUNTED_AS_NEW_BEHAVIOUR,
            count = count,
            behaviourId = currentBehaviourId,
            thresholdReached = reached,
            reason = if (reached) "累计达到独立未知交互行为阈值=$threshold"
            else "识别到新的独立未知交互行为"
        )
    }

    /** Only clear a sub-threshold count after the caller has verified page stability. */
    fun clearAfterStableQuiet(nowMillis: Long, quietPeriodMs: Long = DEFAULT_RESET_QUIET_PERIOD_MS): Boolean {
        if (thresholdLatched || count == 0 || lastBehaviourAtMs == Long.MIN_VALUE) return false
        if (nowMillis < lastBehaviourAtMs || nowMillis - lastBehaviourAtMs < quietPeriodMs) return false
        reset()
        return true
    }

    fun reset() {
        count = 0
        currentBehaviourId = 0L
        lastBehaviourAtMs = Long.MIN_VALUE
        lastWindowId = -1
        lastPageGeneration = Long.MIN_VALUE
        lastFamily = ""
        lastSourceKey = ""
        thresholdLatched = false
    }

    private fun currentBehaviourAt(
        nowMillis: Long,
        windowId: Int,
        pageGeneration: Long,
        family: String,
        sourceKey: String
    ) {
        lastBehaviourAtMs = nowMillis
        lastWindowId = windowId
        lastPageGeneration = pageGeneration
        lastFamily = family
        lastSourceKey = sourceKey
    }

    companion object {
        const val DEFAULT_THRESHOLD = 3
        const val DEFAULT_COALESCING_WINDOW_MS = 1_200L
        const val DEFAULT_RESET_QUIET_PERIOD_MS = 10_000L
        const val GLOBAL_TOUCH_FAMILY = "GLOBAL_TOUCH"
    }
}
