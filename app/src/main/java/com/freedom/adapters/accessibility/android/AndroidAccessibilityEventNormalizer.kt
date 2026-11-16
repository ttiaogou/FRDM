package com.freedom.adapters.accessibility.android

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.events.AccessibilitySourceSnapshot
import com.freedom.core.accessibility.events.AccessibilityUiSignal

/** Converts Android events to short-lived immutable events before Feature routing. */
internal object AndroidAccessibilityEventNormalizer {

    fun normalize(event: AccessibilityEvent, includeText: Boolean): AccessibilityEventEnvelope {
        val source = runCatching { event.source }.getOrNull()
        val sourceClass = safeString { source?.className?.toString().orEmpty() }
        val sourceViewId = safeString { source?.viewIdResourceName.orEmpty() }
        val sourceText = safeString { source?.text?.toString().orEmpty() }
        val sourceDescription = safeString { source?.contentDescription?.toString().orEmpty() }
        val sourceBounds = source?.let {
            val rect = Rect()
            runCatching { it.getBoundsInScreen(rect) }
            ScreenRect(rect.left, rect.top, rect.right, rect.bottom)
        }
        val sourceClickable = safeBoolean { source?.isClickable == true }
        val sourceLongClickable = safeBoolean { source?.isLongClickable == true }
        val sourceScrollable = safeBoolean { source?.isScrollable == true }
        val sourceEditable = safeBoolean { source?.isEditable == true }
        runCatching { source?.recycle() }

        val eventTextValues = runCatching {
            event.text?.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.orEmpty()
        }.getOrDefault(emptyList())
        val eventContentDescription = safeString { event.contentDescription?.toString().orEmpty() }
        val sourceSignalText = (sourceText + " " + sourceDescription).lowercase()
        val eventSignalText = eventTextValues.joinToString(" ").lowercase()
        val viewId = sourceViewId.lowercase()
        val hasNavigationViewId = listOf(
            "maintab_layout_chat", "chat", "message", "msg_", "conversation", "active_rv"
        ).any(viewId::contains)
        val hasSourceNavigationText = sourceSignalText.contains("消息") || sourceSignalText.contains("发送")
        val hasEventNavigationText = eventSignalText.contains("消息")
        val signals = buildSet {
            if (hasNavigationViewId || hasSourceNavigationText || hasEventNavigationText) {
                add(AccessibilityUiSignal.CHAT_NAVIGATION)
            }
            if (viewId.endsWith("maintab_layout_chat") ||
                sourceSignalText.contains("消息") || eventSignalText.contains("消息")
            ) {
                add(AccessibilityUiSignal.MESSAGE_TAB)
            }
            if (viewId.contains("send") || sourceSignalText.contains("发送") ||
                eventSignalText.contains("发送")
            ) {
                add(AccessibilityUiSignal.SEND_ACTION)
            }
            if (sourceEditable || viewId.contains("input") || viewId.contains("composer") ||
                viewId.contains("message_box") || viewId.contains("msg_box")
            ) add(AccessibilityUiSignal.CHAT_INPUT)
            if (viewId.contains("active_rv") || viewId.contains("message_list") ||
                viewId.contains("chat_list") || sourceClass.endsWith("RecyclerView") ||
                sourceClass.endsWith("ListView")
            ) add(AccessibilityUiSignal.LIST_CONTAINER)
        }

        val contentChanges = buildSet {
            val changes = event.contentChangeTypes
            if ((changes and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0) {
                add(AccessibilityContentChange.SUBTREE)
            }
            if ((changes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) != 0) {
                add(AccessibilityContentChange.PANE_APPEARED)
            }
            if ((changes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) != 0) {
                add(AccessibilityContentChange.PANE_DISAPPEARED)
            }
            if (isEmpty()) add(AccessibilityContentChange.OTHER)
        }

        return AccessibilityEventEnvelope(
            type = mapEventType(event.eventType),
            packageName = safeString { event.packageName?.toString().orEmpty() },
            className = safeString { event.className?.toString().orEmpty() },
            contentChanges = contentChanges,
            eventTimeMillis = event.eventTime,
            eventText = if (includeText) eventTextValues else emptyList(),
            contentDescription = if (includeText) eventContentDescription.takeIf(String::isNotBlank) else null,
            source = if (source == null) null else AccessibilitySourceSnapshot(
                className = sourceClass,
                viewId = sourceViewId,
                text = if (includeText) sourceText.takeIf(String::isNotBlank) else null,
                contentDescription = if (includeText) sourceDescription.takeIf(String::isNotBlank) else null,
                bounds = sourceBounds,
                clickable = sourceClickable,
                longClickable = sourceLongClickable,
                scrollable = sourceScrollable,
                editable = sourceEditable
            ),
            signals = signals,
            windowId = safeInt { event.windowId }.takeIf { it >= 0 } ?: -1
        )
    }

    internal fun mapEventType(type: Int): AccessibilityEventType = when (type) {
        AccessibilityEvent.TYPE_ANNOUNCEMENT -> AccessibilityEventType.ANNOUNCEMENT
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> AccessibilityEventType.NOTIFICATION_STATE_CHANGED
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> AccessibilityEventType.WINDOW_STATE_CHANGED
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> AccessibilityEventType.WINDOWS_CHANGED
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> AccessibilityEventType.WINDOW_CONTENT_CHANGED
        AccessibilityEvent.TYPE_VIEW_CLICKED -> AccessibilityEventType.VIEW_CLICKED
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> AccessibilityEventType.VIEW_LONG_CLICKED
        AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> AccessibilityEventType.VIEW_CONTEXT_CLICKED
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> AccessibilityEventType.VIEW_SCROLLED
        AccessibilityEvent.TYPE_VIEW_SELECTED -> AccessibilityEventType.VIEW_SELECTED
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> AccessibilityEventType.VIEW_FOCUSED
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> AccessibilityEventType.VIEW_TEXT_CHANGED
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> AccessibilityEventType.VIEW_TEXT_SELECTION_CHANGED
        AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> AccessibilityEventType.VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> AccessibilityEventType.VIEW_ACCESSIBILITY_FOCUSED
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> AccessibilityEventType.VIEW_ACCESSIBILITY_FOCUS_CLEARED
        AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> AccessibilityEventType.VIEW_HOVER_ENTER
        AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> AccessibilityEventType.VIEW_HOVER_EXIT
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> AccessibilityEventType.TOUCH_INTERACTION_START
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> AccessibilityEventType.TOUCH_INTERACTION_END
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> AccessibilityEventType.TOUCH_EXPLORATION_GESTURE_START
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> AccessibilityEventType.TOUCH_EXPLORATION_GESTURE_END
        AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> AccessibilityEventType.GESTURE_DETECTION_START
        AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> AccessibilityEventType.GESTURE_DETECTION_END
        AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> AccessibilityEventType.ASSIST_READING_CONTEXT
        else -> AccessibilityEventType.OTHER
    }


    internal fun toAndroidEventType(type: AccessibilityEventType): Int = when (type) {
        AccessibilityEventType.ANNOUNCEMENT -> AccessibilityEvent.TYPE_ANNOUNCEMENT
        AccessibilityEventType.NOTIFICATION_STATE_CHANGED -> AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        AccessibilityEventType.WINDOW_STATE_CHANGED -> AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        AccessibilityEventType.WINDOWS_CHANGED -> AccessibilityEvent.TYPE_WINDOWS_CHANGED
        AccessibilityEventType.WINDOW_CONTENT_CHANGED -> AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        AccessibilityEventType.VIEW_CLICKED -> AccessibilityEvent.TYPE_VIEW_CLICKED
        AccessibilityEventType.VIEW_LONG_CLICKED -> AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        AccessibilityEventType.VIEW_CONTEXT_CLICKED -> AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
        AccessibilityEventType.VIEW_SCROLLED -> AccessibilityEvent.TYPE_VIEW_SCROLLED
        AccessibilityEventType.VIEW_SELECTED -> AccessibilityEvent.TYPE_VIEW_SELECTED
        AccessibilityEventType.VIEW_FOCUSED -> AccessibilityEvent.TYPE_VIEW_FOCUSED
        AccessibilityEventType.VIEW_TEXT_CHANGED -> AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        AccessibilityEventType.VIEW_TEXT_SELECTION_CHANGED -> AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
        AccessibilityEventType.VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY -> AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
        AccessibilityEventType.VIEW_ACCESSIBILITY_FOCUSED -> AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        AccessibilityEventType.VIEW_ACCESSIBILITY_FOCUS_CLEARED -> AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
        AccessibilityEventType.VIEW_HOVER_ENTER -> AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
        AccessibilityEventType.VIEW_HOVER_EXIT -> AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
        AccessibilityEventType.TOUCH_INTERACTION_START -> AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
        AccessibilityEventType.TOUCH_INTERACTION_END -> AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
        AccessibilityEventType.TOUCH_EXPLORATION_GESTURE_START -> AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START
        AccessibilityEventType.TOUCH_EXPLORATION_GESTURE_END -> AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END
        AccessibilityEventType.GESTURE_DETECTION_START -> AccessibilityEvent.TYPE_GESTURE_DETECTION_START
        AccessibilityEventType.GESTURE_DETECTION_END -> AccessibilityEvent.TYPE_GESTURE_DETECTION_END
        AccessibilityEventType.ASSIST_READING_CONTEXT -> AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT
        AccessibilityEventType.OTHER -> 0
    }

    private inline fun safeString(block: () -> String?): String = runCatching { block().orEmpty() }.getOrDefault("")
    private fun safeBoolean(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(-1)
}
