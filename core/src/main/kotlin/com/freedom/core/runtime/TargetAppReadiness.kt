package com.freedom.core.runtime

import com.freedom.core.accessibility.api.WindowRef

/**
 * Formal readiness of the target application for autonomous screen interaction.
 * This is deliberately separate from AccessibilityService connection/initialization state.
 */
enum class TargetAppReadinessStatus {
    UNKNOWN,
    STARTING,
    BLOCKED,
    READY
}

data class TargetAppPageReadiness(
    val ready: Boolean,
    val reason: String,
    val blockingReason: String? = null,
    /** A fresh inspection may be retried; this does not authorize a gesture. */
    val retryable: Boolean = false
)

data class TargetAppReadinessEvidence(
    val targetPackage: String,
    val foregroundPackage: String,
    val activityClass: String,
    val runMode: RunMode?,
    val windowId: Int,
    val pageGeneration: Long,
    val activeWindowAvailable: Boolean,
    val blockingReason: String? = null,
    val businessPageReady: Boolean = false,
    val businessPageReason: String = ""
)

data class TargetAppReadinessSnapshot(
    val status: TargetAppReadinessStatus = TargetAppReadinessStatus.UNKNOWN,
    val targetPackage: String = "",
    val foregroundPackage: String = "",
    val activityClass: String = "",
    val runMode: RunMode? = null,
    val windowId: Int = -1,
    val pageGeneration: Long = -1L,
    val observedAtUptimeMs: Long = 0L,
    val reason: String = "尚未执行目标应用就绪检查"
) {
    /** Match a freshly evaluated observation to an exact action window; never a reusable write permit. */
    fun isReadyForWindow(window: WindowRef, mode: RunMode): Boolean =
        status == TargetAppReadinessStatus.READY && runMode == mode &&
            targetPackage.isNotBlank() && targetPackage == foregroundPackage &&
            targetPackage == window.packageName &&
            activityClass.isNotBlank() && activityClass == window.activityClass &&
            windowId >= 0 && windowId == window.windowId &&
            pageGeneration >= 0L && pageGeneration == window.generation
}

/**
 * Owns the last observed readiness state. A fresh evidence evaluation is required for every
 * autonomous dispatch; this snapshot is diagnostic state, not a reusable permission token.
 */
class TargetAppReadinessController(
    private val foregroundQuietPeriodMs: Long = DEFAULT_FOREGROUND_QUIET_PERIOD_MS,
    private val onTransition: (TargetAppReadinessSnapshot, TargetAppReadinessSnapshot) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private var targetForegroundSinceUptimeMs: Long? = null

    init {
        require(foregroundQuietPeriodMs >= 0L) { "foregroundQuietPeriodMs cannot be negative" }
    }

    /** Records the first confirmed active-window observation after the target enters foreground. */
    fun observeForegroundPackage(targetPackage: String, foregroundPackage: String, nowUptimeMs: Long) {
        synchronized(lock) {
            if (targetPackage.isBlank() || foregroundPackage != targetPackage) {
                targetForegroundSinceUptimeMs = null
            } else if (targetForegroundSinceUptimeMs == null) {
                targetForegroundSinceUptimeMs = nowUptimeMs
            }
        }
    }

    @Volatile
    private var snapshot = TargetAppReadinessSnapshot()

    fun current(): TargetAppReadinessSnapshot = snapshot

    /** Read-only wait hint, never an authorization to dispatch. */
    fun foregroundQuietRemainingMs(nowUptimeMs: Long): Long? = synchronized(lock) {
        targetForegroundSinceUptimeMs?.let {
            (foregroundQuietPeriodMs - (nowUptimeMs - it)).coerceAtLeast(0L)
        }
    }

    fun evaluate(evidence: TargetAppReadinessEvidence, nowUptimeMs: Long): TargetAppReadinessSnapshot {
        observeForegroundPackage(evidence.targetPackage, evidence.foregroundPackage.takeIf {
            evidence.activeWindowAvailable
        }.orEmpty(), nowUptimeMs)
        val foregroundQuietRemainingMs = if (evidence.runMode == RunMode.AI_REPLY) {
            foregroundQuietRemainingMs(nowUptimeMs)
        } else {
            0L
        }
        val (status, reason) = when {
            evidence.runMode == null ->
                TargetAppReadinessStatus.UNKNOWN to "当前没有有效运行模式；禁止自主手势"
            !evidence.activeWindowAvailable || evidence.foregroundPackage.isBlank() ->
                TargetAppReadinessStatus.STARTING to "尚未取得当前活动窗口；等待目标应用完成前台启动"
            evidence.targetPackage.isBlank() ->
                TargetAppReadinessStatus.BLOCKED to "目标应用包名尚未配置；禁止自主手势"
            evidence.foregroundPackage != evidence.targetPackage ->
                TargetAppReadinessStatus.BLOCKED to
                    "目标应用不在最前台：foreground=${evidence.foregroundPackage}；target=${evidence.targetPackage}"
            evidence.windowId < 0 ->
                TargetAppReadinessStatus.STARTING to "当前活动窗口身份尚未确认"
            !evidence.blockingReason.isNullOrBlank() ->
                TargetAppReadinessStatus.BLOCKED to evidence.blockingReason
            evidence.activityClass.isBlank() ->
                TargetAppReadinessStatus.STARTING to "当前 Activity 身份尚未确认"
            !evidence.businessPageReady ->
                TargetAppReadinessStatus.STARTING to evidence.businessPageReason.ifBlank {
                    "当前页面尚未达到该运行模式的可操作条件"
                }
            foregroundQuietRemainingMs == null || foregroundQuietRemainingMs > 0L ->
                TargetAppReadinessStatus.STARTING to
                    "目标应用已进入前台；启动保护静默观察中；剩余=${foregroundQuietRemainingMs ?: foregroundQuietPeriodMs}ms；禁止自主手势"
            else -> TargetAppReadinessStatus.READY to
                "目标应用前台静默${foregroundQuietPeriodMs}ms已完成，Activity、窗口及业务页面条件均已确认"
        }

        val next = TargetAppReadinessSnapshot(
            status = status,
            targetPackage = evidence.targetPackage,
            foregroundPackage = evidence.foregroundPackage,
            activityClass = evidence.activityClass,
            runMode = evidence.runMode,
            windowId = evidence.windowId,
            pageGeneration = evidence.pageGeneration,
            observedAtUptimeMs = nowUptimeMs,
            reason = reason
        )
        val previous = synchronized(lock) {
            val old = snapshot
            snapshot = next
            old
        }
        if (previous.status != next.status || previous.reason != next.reason ||
            previous.activityClass != next.activityClass || previous.windowId != next.windowId ||
            previous.pageGeneration != next.pageGeneration || previous.runMode != next.runMode
        ) {
            runCatching { onTransition(previous, next) }
        }
        return next
    }

    fun markStarting(runMode: RunMode, reason: String, nowUptimeMs: Long) {
        synchronized(lock) { targetForegroundSinceUptimeMs = null }
        updateWithoutEvidence(
            TargetAppReadinessSnapshot(
                status = TargetAppReadinessStatus.STARTING,
                runMode = runMode,
                observedAtUptimeMs = nowUptimeMs,
                reason = reason
            )
        )
    }

    fun invalidate(reason: String, nowUptimeMs: Long) {
        updateWithoutEvidence(
            TargetAppReadinessSnapshot(
                status = TargetAppReadinessStatus.UNKNOWN,
                observedAtUptimeMs = nowUptimeMs,
                reason = reason
            )
        )
    }

    companion object {
        const val DEFAULT_FOREGROUND_QUIET_PERIOD_MS = 5_000L
    }

    private fun updateWithoutEvidence(next: TargetAppReadinessSnapshot) {
        val previous = synchronized(lock) {
            val old = snapshot
            snapshot = next
            old
        }
        if (previous.status != next.status || previous.reason != next.reason ||
            previous.runMode != next.runMode
        ) {
            runCatching { onTransition(previous, next) }
        }
    }
}
