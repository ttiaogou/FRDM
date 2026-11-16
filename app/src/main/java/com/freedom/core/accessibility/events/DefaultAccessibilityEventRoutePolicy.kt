package com.freedom.core.accessibility.events

/** Pure routing policy; the Android service adapter only normalizes the platform event. */
class DefaultAccessibilityEventRoutePolicy : AccessibilityEventRoutePolicy {
    private var lastEventSignature: String = ""
    private var lastEventAt: Long = 0L

    override fun route(
        event: AccessibilityEventEnvelope,
        targetPackage: String,
        freedomPackage: String
    ): AccessibilityEventRoute = when (event.type) {
        AccessibilityEventType.TOUCH_INTERACTION_START,
        AccessibilityEventType.TOUCH_INTERACTION_END -> AccessibilityEventRoute.GLOBAL_TOUCH
        else -> if (event.packageName != targetPackage || event.packageName == freedomPackage) {
            AccessibilityEventRoute.IGNORE
        } else {
            AccessibilityEventRoute.TARGET_APPLICATION
        }
    }

    override fun shouldSuppressDuplicate(
        signature: String,
        nowMillis: Long,
        windowMs: Long
    ): Boolean {
        if (signature == lastEventSignature && nowMillis - lastEventAt < windowMs) return true
        lastEventSignature = signature
        lastEventAt = nowMillis
        return false
    }

    override fun resetDuplicateWindow() {
        lastEventSignature = ""
        lastEventAt = 0L
    }
}
