package com.freedom.adapters.accessibility.android

import android.view.accessibility.AccessibilityEvent
import com.freedom.core.accessibility.api.UnifiedAccessibilityEntry
import com.freedom.core.accessibility.api.UnifiedAccessibilityEvent
import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventRoute
import com.freedom.core.accessibility.events.AccessibilityEventRoutePolicy
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.events.DefaultAccessibilityEventRoutePolicy
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.PageObservationCoordinator

/**
 * Owns the synchronous Android-event normalization and dispatch pipeline.
 *
 * The service remains responsible for Android lifecycle, run/permission gates and the
 * pre-normalization window-transition notification. AccountBrowse retains its existing
 * pre-deduplication event delivery; AI Reply receives ordinary page-observation events only.
 */
internal class AndroidAccessibilityEventPipeline(
    private val routePolicy: AccessibilityEventRoutePolicy = DefaultAccessibilityEventRoutePolicy(),
    private val targetPackage: () -> String,
    private val freedomPackage: String,
    private val resolveAppLabel: (String) -> String,
    /** Mandatory business dispatch boundary, including in isolated normalized-event tests. */
    private val unifiedAccessibilityEntry: UnifiedAccessibilityEntry,
    private val interactionDiagnostics: (AccessibilityEventEnvelope, Long) -> List<String>,
    private val handleManualInteractionFallback: (AccessibilityEventEnvelope) -> Unit,
    private val updateAppPage: (appLabel: String, pageLabel: String) -> Unit,
    private val appendLog: (String) -> Unit,
    private val currentActivityClass: () -> String,
    private val updateCurrentActivityClass: (String) -> Unit,
    private val onBrowseEventLogged: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val currentWindowGeneration: () -> Long = { 0L },
    private val inputOriginClassifier: InputOriginClassifier = InputOriginClassifier(),
    private val onDuplicateSuppressed: (AccessibilityEventEnvelope) -> Unit = {},
    private val observeWindowStateEvent: (className: String, windowId: Int) -> Unit = { _, _ -> },
    private val pageObservationCoordinator: PageObservationCoordinator? = null,
    private val onActivityWindowEventDiagnostic: (
        className: String, windowId: Int, generation: Long, tracked: Boolean
    ) -> Unit = { _, _, _, _ -> },
    // No secondary business-facing dispatch callbacks: all events use the same entry.
) : AccessibilityEventPipelinePort {

    /** Entry point for Android callbacks. The platform event is normalized synchronously. */
    override fun process(event: AccessibilityEvent, runMode: RunMode) = processEvent(event, runMode)

    override fun processWithoutRuntime(event: AccessibilityEvent) = processEvent(event, null)

    private fun processEvent(event: AccessibilityEvent, runMode: RunMode?) {
        val androidEventType = event.eventType
        if (androidEventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            androidEventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
        ) {
            appendLog(
                "全局触摸链路[PIPELINE_INPUT]：事件=${AccessibilityEventFormatter.rawEventName(androidEventType)}；" +
                    "eventTime=${event.eventTime}；来源包=${event.packageName?.toString().orEmpty().ifBlank { "SYSTEM/NULL" }}"
            )
            // Global touch events are system-level control signals, not target-page events.
            // Do not access event.source, event.text, or contentDescription here.
            val touchBoundaryEvent = AccessibilityEventEnvelope(
                type = AndroidAccessibilityEventNormalizer.mapEventType(androidEventType),
                packageName = "",
                className = "",
                contentChanges = setOf(AccessibilityContentChange.OTHER),
                eventTimeMillis = event.eventTime,
                eventText = emptyList(),
                contentDescription = null,
                source = null,
                signals = emptySet(),
                windowId = runCatching { event.windowId }.getOrDefault(-1).takeIf { it >= 0 } ?: -1,
                pageGeneration = currentWindowGeneration()
            )
            processNormalized(touchBoundaryEvent, androidEventType, runMode)
            return
        }

        val normalizedEvent = AndroidAccessibilityEventNormalizer.normalize(
            event,
            includeText = shouldCaptureText(runMode)
        )
        processNormalized(normalizedEvent, androidEventType, runMode)
    }

    internal fun shouldCaptureText(runMode: RunMode?): Boolean = runMode == RunMode.ACCOUNT_BROWSE

    /** Testable normalized-event seam; production callers should use [process]. */
    internal fun processNormalized(
        normalizedEvent: AccessibilityEventEnvelope,
        androidEventType: Int,
        runMode: RunMode?,
        now: Long? = null
    ) {
        val eventForRouting = if (runMode != RunMode.ACCOUNT_BROWSE) {
            normalizedEvent.copy(pageGeneration = currentWindowGeneration())
        } else normalizedEvent
        val eventRoute = routePolicy.route(
            event = eventForRouting,
            targetPackage = targetPackage(),
            freedomPackage = freedomPackage
        )
        if (eventRoute == AccessibilityEventRoute.IGNORE) return

        // Global touch has no ordinary source package, so it must be routed before target filtering.
        if (eventRoute == AccessibilityEventRoute.GLOBAL_TOUCH) {
            appendLog(
                "全局触摸链路[PIPELINE_ROUTED]：事件=${eventForRouting.type}；" +
                    "路由=GLOBAL_TOUCH；模式=$runMode；eventTime=${eventForRouting.eventTimeMillis}"
            )
            if (runMode == RunMode.ACCOUNT_BROWSE) {
                val originDecision = inputOriginClassifier.classify(
                    event = eventForRouting,
                    currentActivityClass = currentActivityClass(),
                    currentPageGeneration = currentWindowGeneration(),
                    targetPackage = targetPackage()
                )
                val routedTouchEvent = eventForRouting.copy(inputOriginDecision = originDecision)
                runCatching {
                    unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.GlobalTouch(routedTouchEvent))
                }
                    .onSuccess {
                        appendLog("全局触摸链路[PIPELINE_FORWARDED]：事件=${eventForRouting.type}；模式=$runMode")
                    }
                    .onFailure {
                        appendLog(
                            "全局触摸链路[PIPELINE_FORWARD_ERROR]：事件=${eventForRouting.type}；" +
                                "异常=${it.javaClass.simpleName}"
                        )
                    }
            } else if (runMode == RunMode.AI_REPLY) {
                appendLog("全局触摸链路[PIPELINE_DROPPED]：AI Reply 不使用全局触摸事件决定任务或中断调度")
            } else {
                appendLog("全局触摸链路[PIPELINE_OBSERVATION_ONLY]：尚无活动业务运行时")
            }
            return
        }

        val eventPackage = eventForRouting.packageName
        val className = eventForRouting.className
        // Keep the original timestamp point: after route/ignore/global-touch handling and
        // immediately before AccountBrowse's pre-deduplication delivery.
        val eventNow = now ?: nowMillis()

        val routedTargetEvent = eventForRouting

        pageObservationCoordinator?.signal(routedTargetEvent.type)

        if (androidEventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && className.isNotBlank()) {
            runCatching { observeWindowStateEvent(className, eventForRouting.windowId) }
        }

        // AccountBrowse consumes target events before visible-log de-duplication. This updates
        // scroll counters and refresh verification even when the matching log line is suppressed.
        if (runMode == RunMode.ACCOUNT_BROWSE) {
            // AccountBrowse must receive this before the visible-log dedup gate.
            unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(normalizedEvent))
        } else if (androidEventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            classNameEndsWithActivity(className)
        ) {
            updateCurrentActivityClass(className)
        }

        // Report activity clue classification before visible-log deduplication. This callback
        // cannot change readiness, route selection or business behavior.
        if (androidEventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            runCatching {
                onActivityWindowEventDiagnostic(
                    className, eventForRouting.windowId, currentWindowGeneration(),
                    isActivityWindowClassName(className) && currentActivityClass() == className
                )
            }
        }

        val rawEventName = AccessibilityEventFormatter.rawEventName(androidEventType)
        // AI-mode content is redacted at normalization and never enters ordinary event logs.
        val content = if (runMode != RunMode.ACCOUNT_BROWSE) "" else eventForRouting.eventText
            .joinToString(" ")
            .trim()
            .take(MAX_EVENT_CONTENT_CHARS)
        val description = if (runMode != RunMode.ACCOUNT_BROWSE) "" else
            normalizedEvent.contentDescription?.take(180).orEmpty()
        val signature = "$eventPackage|$rawEventName|$className|$content|$description"

        // Duplicate suppression affects only visible logs and post-log actions, not the
        // AccountBrowse preflight event dispatch above.
        if (routePolicy.shouldSuppressDuplicate(signature, eventNow)) {
            // Keep the high-volume source event suppressed, but retain a compact signal so the
            // run diagnostic engine can quantify event bursts instead of treating them as absent.
            onDuplicateSuppressed(routedTargetEvent)
            return
        }

        if (androidEventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && className.isNotBlank()) {
            if (classNameEndsWithActivity(className)) updateCurrentActivityClass(className)
            if (runMode != null) unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.ActivityTransition(className))
        }

        // AccountBrowse click attribution uses the actual platform dispatch ledger,
        // not the time when a gesture was queued. Keep AI observation unchanged.
        val browseInteractionEvent = if (runMode == RunMode.ACCOUNT_BROWSE &&
            normalizedEvent.type == AccessibilityEventType.VIEW_CLICKED
        ) {
            normalizedEvent.copy(
                inputOriginDecision = inputOriginClassifier.classify(
                    event = normalizedEvent,
                    currentActivityClass = currentActivityClass(),
                    currentPageGeneration = currentWindowGeneration(),
                    targetPackage = targetPackage()
                )
            )
        } else normalizedEvent

        val appLabel = resolveAppLabel(eventPackage)
        // Page labels are persisted in SharedPreferences. Use only the control class so
        // transient event text/descriptions (which may include names or message bodies) never
        // become long-lived monitor metadata.
        val pageHint = className.substringAfterLast('.').takeIf(String::isNotBlank).orEmpty()
        updateAppPage(appLabel, pageHint)

        val friendlyEvent = AccessibilityEventFormatter.friendlyEventDescription(
            eventType = androidEventType,
            className = className,
            content = content,
            description = description
        )
        val technicalDetails = buildList {
            add("原始事件名=$rawEventName")
            add("事件类型值=$androidEventType")
            add("来源应用=$eventPackage")
            if (className.isNotBlank()) add("原始控件类名=$className")
            if (content.isNotBlank()) add("事件文本=$content")
            if (description.isNotBlank()) add("内容描述=$description")
            if (isTrackedInteractionEvent(eventForRouting.type)) {
                if (runMode == RunMode.ACCOUNT_BROWSE) {
                    addAll(interactionDiagnostics(browseInteractionEvent, eventNow))
                } else {
                    add("当前 Activity=${currentActivityClass().ifBlank { "未知" }}")
                    add("AI Reply 仅将目标事件用于页面结构观察；UNKNOWN 不参与任务调度")
                    add(AccessibilityEventFormatter.sourceDetails(eventForRouting.source))
                }
            }
        }.joinToString("；")
        appendLog("$friendlyEvent$LOG_DETAIL_SEPARATOR$technicalDetails")

        if (runMode == RunMode.ACCOUNT_BROWSE) {
            handleManualInteractionFallback(browseInteractionEvent)
            onBrowseEventLogged()
        } else if (runMode != null) {
            // AI event delivery intentionally follows visible-event de-duplication and logging.
            unifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent.TargetApplication(routedTargetEvent))
        }
    }

    fun resetDuplicateWindow() = routePolicy.resetDuplicateWindow()

    private fun isTrackedInteractionEvent(type: AccessibilityEventType): Boolean = when (type) {
        AccessibilityEventType.VIEW_CLICKED,
        AccessibilityEventType.VIEW_LONG_CLICKED,
        AccessibilityEventType.VIEW_SCROLLED,
        AccessibilityEventType.VIEW_TEXT_CHANGED,
        AccessibilityEventType.VIEW_TEXT_SELECTION_CHANGED,
        AccessibilityEventType.VIEW_CONTEXT_CLICKED -> true
        else -> false
    }

    private fun classNameEndsWithActivity(className: String): Boolean =
        isActivityWindowClassName(className)

    private companion object {
        const val LOG_DETAIL_SEPARATOR = "\u001F"
        const val MAX_EVENT_CONTENT_CHARS = 4_500
    }
}
/**
 * Android's TYPE_WINDOW_STATE_CHANGED class name is usually an Activity class, but some apps
 * append a single build/variant suffix (for example PersonalProfileActivityK). Accept that
 * documented shape without treating arbitrary widget names such as ActivityToolbar as pages.
 */
internal fun isActivityWindowClassName(className: String): Boolean {
    val simpleName = className.substringAfterLast('.').trim()
    return simpleName.endsWith("Activity") || simpleName.matches(Regex(".+Activity[A-Z]"))
}

