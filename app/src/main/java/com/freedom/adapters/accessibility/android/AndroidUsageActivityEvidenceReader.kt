package com.freedom.adapters.accessibility.android

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityWindowInfo

/** Only lifecycle metadata is observed; no message text, nodes, or stale navigation data are persisted. */
internal enum class ActivityUsageTransition { RESUMED, PAUSED, STOPPED }

internal data class ActivityUsageTransitionRecord(
    val packageName: String,
    val activityClass: String,
    val transition: ActivityUsageTransition,
    val atEpochMs: Long
)

internal data class ActiveActivityUsageEvidence(val activityClass: String, val atEpochMs: Long)

internal object ActivityUsageEvidenceResolver {
    /** Fail closed when the last observed lifecycle transition no longer proves target foreground. */
    fun resolve(
        records: List<ActivityUsageTransitionRecord>,
        targetPackage: String,
        nowEpochMs: Long
    ): ActiveActivityUsageEvidence? {
        val earliest = (nowEpochMs - AndroidUsageActivityEvidenceReader.LOOKBACK_MS).coerceAtLeast(0L)
        var candidate: ActiveActivityUsageEvidence? = null
        for (record in records.sortedBy(ActivityUsageTransitionRecord::atEpochMs)) {
            if (record.atEpochMs !in earliest..nowEpochMs) continue
            when (record.transition) {
                ActivityUsageTransition.RESUMED -> {
                    candidate = if (record.packageName == targetPackage &&
                        isActivityWindowClassName(record.activityClass)
                    ) {
                        ActiveActivityUsageEvidence(record.activityClass, record.atEpochMs)
                    } else null
                }
                ActivityUsageTransition.PAUSED, ActivityUsageTransition.STOPPED -> {
                    if (record.packageName == targetPackage &&
                        record.activityClass == candidate?.activityClass
                    ) candidate = null
                }
            }
        }
        return candidate
    }
}

internal data class ActivityReadOnlyEvidence(
    val activityClass: String,
    val windowId: Int,
    val windowGeneration: Long,
    val activityResumedAtEpochMs: Long
)

internal sealed interface ActivityIdentityProbeResult {
    data class Proven(val evidence: ActivityReadOnlyEvidence) : ActivityIdentityProbeResult
    data class Pending(val reason: String) : ActivityIdentityProbeResult
}

/**
 * Android does not expose the hosting Activity through AccessibilityNodeInfo.
 * Read-only usage events can provide a separate Activity clue when the user has explicitly
 * enabled Usage Access. It is accepted only with the same live, active APPLICATION window
 * before and after the query and with an unchanged service-local window generation.
 */
internal class AndroidUsageActivityEvidenceReader(
    private val service: AccessibilityService,
    private val targetPackage: () -> String,
    private val currentWindowGeneration: () -> Long,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    fun probe(): ActivityIdentityProbeResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ActivityIdentityProbeResult.Pending("ACTIVITY_USAGE_EVENTS_UNAVAILABLE")
        }
        val target = targetPackage().trim()
        if (target.isBlank()) return ActivityIdentityProbeResult.Pending("TARGET_PACKAGE_EMPTY")
        val generation = currentWindowGeneration()
        val beforeWindowId = activeTargetApplicationWindowId(target)
            ?: return ActivityIdentityProbeResult.Pending("ACTIVE_TARGET_WINDOW_UNCONFIRMED")

        val appOps = service.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return ActivityIdentityProbeResult.Pending("APP_OPS_UNAVAILABLE")
        val accessMode = runCatching {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                service.applicationInfo.uid,
                service.packageName
            )
        }.getOrNull()
        if (accessMode != AppOpsManager.MODE_ALLOWED) {
            return ActivityIdentityProbeResult.Pending("USAGE_ACCESS_NOT_GRANTED")
        }
        val usage = service.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return ActivityIdentityProbeResult.Pending("USAGE_EVENTS_UNAVAILABLE")
        val now = nowEpochMs()
        val events = runCatching {
            usage.queryEvents((now - LOOKBACK_MS).coerceAtLeast(0L), now + 1L)
        }.getOrNull() ?: return ActivityIdentityProbeResult.Pending("USAGE_EVENTS_UNAVAILABLE")
        val records = ArrayList<ActivityUsageTransitionRecord>()
        val event = UsageEvents.Event()
        var eventCount = 0
        while (events.hasNextEvent()) {
            if (++eventCount > MAX_USAGE_EVENTS) {
                return ActivityIdentityProbeResult.Pending("USAGE_EVENTS_BUDGET_EXCEEDED")
            }
            if (!events.getNextEvent(event)) break
            val transition = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> ActivityUsageTransition.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> ActivityUsageTransition.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> ActivityUsageTransition.STOPPED
                else -> null
            } ?: continue
            records += ActivityUsageTransitionRecord(
                packageName = event.packageName.orEmpty(),
                activityClass = event.className.orEmpty(),
                transition = transition,
                atEpochMs = event.timeStamp
            )
        }
        val activity = ActivityUsageEvidenceResolver.resolve(records, target, now)
            ?: return ActivityIdentityProbeResult.Pending("CURRENT_ACTIVITY_USAGE_EVIDENCE_MISSING")
        val afterWindowId = activeTargetApplicationWindowId(target)
        if (afterWindowId != beforeWindowId || currentWindowGeneration() != generation) {
            return ActivityIdentityProbeResult.Pending("WINDOW_CHANGED_DURING_READ")
        }
        return ActivityIdentityProbeResult.Proven(
            ActivityReadOnlyEvidence(
                activityClass = activity.activityClass,
                windowId = beforeWindowId,
                windowGeneration = generation,
                activityResumedAtEpochMs = activity.atEpochMs
            )
        )
    }

    private fun activeTargetApplicationWindowId(target: String): Int? {
        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        val id = try {
            val rootPackage = runCatching { activeRoot.packageName?.toString().orEmpty() }.getOrDefault("")
            val rootId = runCatching { activeRoot.windowId }.getOrDefault(-1)
            if (rootPackage == target && rootId >= 0) rootId else -1
        } finally {
            runCatching { activeRoot.recycle() }
        }
        if (id < 0) return null
        val windows = runCatching { service.windows.toList() }.getOrNull() ?: return null
        return try {
            val matched = windows.any { window ->
                val isTargetWindow = runCatching {
                    window.id == id &&
                        window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (window.isActive || window.isFocused)
                }.getOrDefault(false)
                if (!isTargetWindow) false else {
                    val windowRoot = runCatching { window.root }.getOrNull()
                    try {
                        windowRoot != null &&
                            runCatching { windowRoot.packageName?.toString() == target }.getOrDefault(false)
                    } finally {
                        if (windowRoot != null) runCatching { windowRoot.recycle() }
                    }
                }
            }
            id.takeIf { matched }
        } finally {
            windows.forEach { window -> runCatching { window.recycle() } }
        }
    }

    companion object {
        const val LOOKBACK_MS = 30L * 60L * 1_000L
        private const val MAX_USAGE_EVENTS = 6_000
    }
}
