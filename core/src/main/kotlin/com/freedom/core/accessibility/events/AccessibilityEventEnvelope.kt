package com.freedom.core.accessibility.events

import com.freedom.core.accessibility.api.ScreenRect

/** Android event types normalized at the service boundary. */
enum class AccessibilityEventType {
    ANNOUNCEMENT,
    NOTIFICATION_STATE_CHANGED,
    WINDOW_STATE_CHANGED,
    WINDOWS_CHANGED,
    WINDOW_CONTENT_CHANGED,
    VIEW_CLICKED,
    VIEW_LONG_CLICKED,
    VIEW_CONTEXT_CLICKED,
    VIEW_SCROLLED,
    VIEW_SELECTED,
    VIEW_FOCUSED,
    VIEW_TEXT_CHANGED,
    VIEW_TEXT_SELECTION_CHANGED,
    VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
    VIEW_ACCESSIBILITY_FOCUSED,
    VIEW_ACCESSIBILITY_FOCUS_CLEARED,
    VIEW_HOVER_ENTER,
    VIEW_HOVER_EXIT,
    TOUCH_INTERACTION_START,
    TOUCH_INTERACTION_END,
    TOUCH_EXPLORATION_GESTURE_START,
    TOUCH_EXPLORATION_GESTURE_END,
    GESTURE_DETECTION_START,
    GESTURE_DETECTION_END,
    ASSIST_READING_CONTEXT,
    OTHER
}

/** Whitelisted UI semantics extracted at the Android boundary without retaining private message text. */
enum class AccessibilityUiSignal {
    CHAT_NAVIGATION,
    MESSAGE_TAB,
    SEND_ACTION,
    CHAT_INPUT,
    LIST_CONTAINER
}

enum class AccessibilityContentChange {
    SUBTREE,
    PANE_APPEARED,
    PANE_DISAPPEARED,
    OTHER
}

/** Source metadata is copied from the event and must not retain a platform node. */
data class AccessibilitySourceSnapshot(
    val className: String,
    val viewId: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: ScreenRect?,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean
)

/**
 * Immutable, short-lived event data. Raw text may be present for existing AccountBrowse
 * attribution, but must never be written to ordinary logs or persisted for AIReply events.
 */
data class AccessibilityEventEnvelope(
    val type: AccessibilityEventType,
    val packageName: String,
    val className: String,
    val contentChanges: Set<AccessibilityContentChange>,
    val eventTimeMillis: Long,
    val eventText: List<String>,
    val contentDescription: String?,
    val source: AccessibilitySourceSnapshot?,
    val signals: Set<AccessibilityUiSignal> = emptySet(),
    /** Android window identity when supplied; -1 means unavailable. */
    val windowId: Int = -1,
    /** Source decision added by the Android event pipeline; null means not classified. */
    val inputOriginDecision: InputOriginDecision? = null,
    /** Active-window generation captured at event receipt; prevents cross-page grouping. */
    val pageGeneration: Long = 0L
)

enum class AccessibilityEventRoute {
    IGNORE,
    GLOBAL_TOUCH,
    TARGET_APPLICATION
}

interface AccessibilityEventRoutePolicy {
    /** Global touch events must be recognized before target-package filtering. */
    fun route(
        event: AccessibilityEventEnvelope,
        targetPackage: String,
        freedomPackage: String
    ): AccessibilityEventRoute

    /** Duplicate suppression is for event-log burst reduction, not workflow event dropping. */
    fun shouldSuppressDuplicate(signature: String, nowMillis: Long, windowMs: Long = 250L): Boolean

    fun resetDuplicateWindow()
}
