package com.freedom.adapters.accessibility.android

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.runtime.RunMode

/**
 * Android callback boundary. The AccessibilityService only forwards the platform callback here;
 * filtering, global-touch observation, transition notification and pipeline delivery stay in this
 * adapter so feature code cannot be reached directly from the Service callback.
 */
internal class AndroidAccessibilityEventForwarder(
    private val activeMode: () -> RunMode?,
    private val targetPackage: () -> String,
    private val isRunRequested: () -> Boolean,
    private val onLog: (String) -> Unit,
    private val onGlobalTouch: (AccessibilityEventType, Long, Long, String?, Int) -> Unit,
    private val onWindowTransition: (AccessibilityEvent, RunMode?) -> Unit,
    private val pipeline: AccessibilityEventPipelinePort,
    private val currentRunGeneration: () -> Long = { -1L },
    private val currentWindowGeneration: () -> Long = { -1L }
) {
    private var windowCallbackSequence = 0L
    fun accept(event: AccessibilityEvent?) {
        if (event == null) return
        val mode = activeMode()
        val eventPackage = event.packageName?.toString()
        val eventTypeName = AccessibilityEventFormatter.rawEventName(event.eventType)
        val isGlobalTouch = event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
        val accepted = AndroidAccessibilitySubscriptionPolicy.acceptsCallbackEvent(
            eventPackage = eventPackage,
            eventType = event.eventType,
            targetPackage = targetPackage(),
            runMode = mode,
            runRequested = isRunRequested()
        )
        if (isGlobalTouch) {
            onLog(
                "全局触摸链路[SERVICE_CALLBACK]：收到=$eventTypeName；" +
                    "eventTimeUptimeMs=${event.eventTime}；callbackUptimeMs=${SystemClock.uptimeMillis()}；" +
                    "eventPackage=${eventPackage ?: "NULL"}；windowId=${runCatching { event.windowId }.getOrDefault(-1)}；" +
                    "targetPackage=${targetPackage()}；mode=${mode ?: "NONE"}；" +
                    "callbackAccepted=$accepted；actorProvenance=UNAVAILABLE；origin=UNKNOWN"
            )
            onGlobalTouch(
                if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
                    AccessibilityEventType.TOUCH_INTERACTION_START
                } else AccessibilityEventType.TOUCH_INTERACTION_END,
                event.eventTime,
                SystemClock.uptimeMillis(),
                eventPackage,
                runCatching { event.windowId }.getOrDefault(-1)
            )
        }
        val isWindowEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        // Only metadata is observed. Filtering and transition callbacks retain their order.
        fun logWindowEvent(runRequested: Boolean) {
            if (!isWindowEvent) return
            runCatching {
                windowCallbackSequence++
                onLog(formatActivityWindowCallbackDiagnostic(
                    eventTypeName = eventTypeName,
                    eventPackage = eventPackage,
                    targetPackage = targetPackage(),
                    eventClassName = if (eventPackage == targetPackage()) {
                        event.className?.toString()
                    } else null,
                    windowId = event.windowId,
                    callbackAccepted = accepted,
                    runRequested = runRequested,
                    mode = mode,
                    runGeneration = currentRunGeneration(),
                    windowGeneration = currentWindowGeneration(),
                    sequence = windowCallbackSequence,
                    observeWithoutRuntime = true
                ))
            }
        }
        if (!accepted) {
            logWindowEvent(false)
            if (isGlobalTouch) onLog("全局触摸链路[SERVICE_DROPPED]：回调入口过滤拒绝；事件=$eventTypeName")
            return
        }
        if (isGlobalTouch) onLog("全局触摸链路[SERVICE_ACCEPTED]：已放行至事件管线；事件=$eventTypeName")

        if (isWindowEvent) {
            onWindowTransition(event, mode)
        }
        val runRequested = isRunRequested()
        logWindowEvent(runRequested)
        if (!runRequested) return
        val runMode = mode
        if (runMode == null) pipeline.processWithoutRuntime(event)
        else pipeline.process(event, runMode)
    }
}

/** Only plain Android class identifiers from the target package may appear in logs. */
internal fun safeActivityEventClassName(value: String?): String {
    val className = value.orEmpty()
    return className.takeIf {
        it.length in 1..120 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_.$]*"))
    } ?: "UNAVAILABLE_OR_REDACTED"
}

/** Pure diagnostic formatter; never reads nodes, chat content or foreign application names. */
internal fun formatActivityWindowCallbackDiagnostic(
    eventTypeName: String,
    eventPackage: String?,
    targetPackage: String,
    eventClassName: String?,
    windowId: Int,
    callbackAccepted: Boolean,
    runRequested: Boolean,
    mode: RunMode?,
    runGeneration: Long,
    windowGeneration: Long,
    sequence: Long,
    observeWithoutRuntime: Boolean = false
): String {
    val source = when {
        targetPackage.isBlank() -> "TARGET_UNCONFIGURED"
        eventPackage == targetPackage -> "TARGET"
        eventPackage == null -> "NULL"
        else -> "OTHER"
    }
    val pipelineEligible = callbackAccepted && runRequested && (mode != null || observeWithoutRuntime)
    val reason = when {
        !callbackAccepted -> "CALLBACK_FILTER_REJECTED"
        !runRequested -> "RUN_NOT_REQUESTED"
        mode == null && !observeWithoutRuntime -> "MODE_UNAVAILABLE"
        eventTypeName != "TYPE_WINDOW_STATE_CHANGED" -> "WINDOW_CHANGE_ONLY"
        !isActivityWindowClassName(eventClassName.orEmpty()) -> "NO_ACTIVITY_CLASS_CANDIDATE"
        else -> "FORWARDED_ACTIVITY_CANDIDATE"
    }
    return "ACTIVITY_EVENT_DIAGNOSTIC stage=SERVICE_CALLBACK; sequence=$sequence;" +
        " eventType=$eventTypeName; sourcePackage=$source;" +
        " eventClass=${safeActivityEventClassName(eventClassName)}; windowId=$windowId;" +
        " runGeneration=$runGeneration; pageGeneration=$windowGeneration;" +
        " mode=${mode?.name ?: "NONE"}; callbackAccepted=$callbackAccepted;" +
        " pipelineEligible=$pipelineEligible; rejectReason=$reason"
}
