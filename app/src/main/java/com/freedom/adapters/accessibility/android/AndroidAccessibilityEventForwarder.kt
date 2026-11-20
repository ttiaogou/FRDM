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
    private val process: (AccessibilityEvent, RunMode) -> Unit
) {
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
            runMode = mode
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
        if (!accepted) {
            if (isGlobalTouch) onLog("全局触摸链路[SERVICE_DROPPED]：回调入口过滤拒绝；事件=$eventTypeName")
            return
        }
        if (isGlobalTouch) onLog("全局触摸链路[SERVICE_ACCEPTED]：已放行至事件管线；事件=$eventTypeName")

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            onWindowTransition(event, mode)
        }
        if (!isRunRequested()) return
        val runMode = mode ?: return
        process(event, runMode)
    }
}
