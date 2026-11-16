package com.freedom.adapters.accessibility.android

import android.view.accessibility.AccessibilityEvent
import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventRoute
import com.freedom.core.accessibility.events.AccessibilityEventRoutePolicy
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.events.DefaultAccessibilityEventRoutePolicy
import com.freedom.core.accessibility.events.InputOrigin
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.accessibility.events.InputOriginDecision
import com.freedom.core.accessibility.events.InputOriginEvidence
import com.freedom.core.runtime.RunMode

/**
 * Owns the synchronous Android-event normalization and dispatch pipeline.
 *
 * The service remains responsible for Android lifecycle, run/permission gates and the
 * pre-normalization window-transition notification. This adapter preserves the established
 * event ordering for the two latched runtimes, including AccountBrowse's pre-deduplication
 * event delivery, AI Reply's pre-deduplication human-interaction arbitration, and AI Reply's
 * ordinary post-log event delivery.
 */
internal class AndroidAccessibilityEventPipeline(
    private val routePolicy: AccessibilityEventRoutePolicy = DefaultAccessibilityEventRoutePolicy(),
    private val targetPackage: () -> String,
    private val freedomPackage: String,
    private val resolveAppLabel: (String) -> String,
    private val dispatchTargetApplicationEvent: (AccessibilityEventEnvelope) -> Unit,
    private val dispatchGlobalTouchEvent: (AccessibilityEventEnvelope) -> Unit,
    private val dispatchActivityTransition: (String) -> Unit,
    private val interactionDiagnostics: (AccessibilityEventEnvelope, Long) -> List<String>,
    private val handleManualInteractionFallback: (AccessibilityEventEnvelope) -> Unit,
    private val updateAppPage: (appLabel: String, pageLabel: String) -> Unit,
    private val appendLog: (String) -> Unit,
    private val currentActivityClass: () -> String,
    private val updateCurrentActivityClass: (String) -> Unit,
    private val onBrowseEventLogged: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val dispatchPreemptiveTargetInteraction: (AccessibilityEventEnvelope) -> Unit = {},
    private val inputOriginClassifier: InputOriginClassifier? = null,
    private val currentWindowGeneration: () -> Long = { 0L },
    private val onDuplicateSuppressed: (AccessibilityEventEnvelope) -> Unit = {},
    private val observeWindowStateEvent: (className: String, windowId: Int) -> Unit = { _, _ -> }
) {

    /** Entry point for Android callbacks. The platform event is normalized synchronously. */
    fun process(event: AccessibilityEvent, runMode: RunMode) {
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

    internal fun shouldCaptureText(runMode: RunMode): Boolean = runMode == RunMode.ACCOUNT_BROWSE

    /** Testable normalized-event seam; production callers should use [process]. */
    internal fun processNormalized(
        normalizedEvent: AccessibilityEventEnvelope,
        androidEventType: Int,
        runMode: RunMode,
        now: Long? = null
    ) {
        val eventForRouting = if (runMode == RunMode.AI_REPLY) {
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
            val originDecision = if (runMode == RunMode.AI_REPLY) {
                classifyInputOrigin(eventForRouting)
            } else null
            if (originDecision != null) logInputOriginDecision(eventForRouting, originDecision)
            val routedTouchEvent = if (originDecision != null) {
                eventForRouting.copy(inputOriginDecision = originDecision)
            } else eventForRouting
            if (originDecision?.shouldYieldAutomation == false) {
                appendLog(
                    "全局触摸链路[NON_EXTERNAL_SUPPRESSED]：事件=${eventForRouting.type}；" +
                        "origin=${originDecision.origin}；actionId=${originDecision.actionId ?: "NONE"}；不作为外部交互抢占"
                )
            } else if (runMode == RunMode.ACCOUNT_BROWSE || runMode == RunMode.AI_REPLY) {
                runCatching { dispatchGlobalTouchEvent(routedTouchEvent) }
                    .onSuccess {
                        appendLog("全局触摸链路[PIPELINE_FORWARDED]：事件=${eventForRouting.type}；模式=$runMode")
                    }
                    .onFailure {
                        appendLog(
                            "全局触摸链路[PIPELINE_FORWARD_ERROR]：事件=${eventForRouting.type}；" +
                                "异常=${it.javaClass.simpleName}"
                        )
                    }
            } else {
                appendLog("全局触摸链路[PIPELINE_DROPPED]：当前模式不消费全局触摸；模式=$runMode")
            }
            return
        }

        val eventPackage = eventForRouting.packageName
        val className = eventForRouting.className
        // Keep the original timestamp point: after route/ignore/global-touch handling and
        // immediately before AccountBrowse's pre-deduplication delivery.
        val eventNow = now ?: nowMillis()

        val inputOriginDecision = if (runMode == RunMode.AI_REPLY && isInputOriginRelevant(eventForRouting.type)) {
            classifyInputOrigin(eventForRouting).also { logInputOriginDecision(eventForRouting, it) }
        } else null
        val routedTargetEvent = if (inputOriginDecision != null) {
            eventForRouting.copy(inputOriginDecision = inputOriginDecision)
        } else eventForRouting

        if (androidEventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && className.isNotBlank()) {
            runCatching { observeWindowStateEvent(className, eventForRouting.windowId) }
        }

        // AI interaction arbitration runs before log de-duplication. SELF_INJECTED and
        // target-app control feedback are filtered; only remaining UNKNOWN/external evidence
        // reaches the independent-behaviour counter downstream.
        if (runMode == RunMode.AI_REPLY && isPreemptiveInteractionEvent(eventForRouting.type)) {
            if (inputOriginDecision?.shouldYieldAutomation == false) {
                val filterTag = if (inputOriginDecision.origin == InputOrigin.SELF_INJECTED) {
                    "SELF_ACTION_FEEDBACK_CONSUMED"
                } else {
                    "APPLICATION_CONTROL_FILTERED"
                }
                appendLog(
                    "人工交互抢占链路[$filterTag]：事件=${eventForRouting.type}；" +
                        "origin=${inputOriginDecision.origin}；actionId=${inputOriginDecision.actionId ?: "NONE"}；不进入UNKNOWN仲裁"
                )
            } else {
                appendLog(
                    "人工交互抢占链路[PIPELINE_PRE_DEDUP]：事件=${eventForRouting.type}；" +
                        "来源包=$eventPackage；eventTime=${eventForRouting.eventTimeMillis}；" +
                        "ViewId=${eventForRouting.source?.viewId.orEmpty().ifBlank { "NONE" }}；" +
                        "inputOrigin=${inputOriginDecision?.origin ?: InputOrigin.UNKNOWN}"
                )
                runCatching { dispatchPreemptiveTargetInteraction(routedTargetEvent) }
                    .onSuccess {
                        appendLog("人工交互抢占链路[PIPELINE_FORWARDED]：事件=${eventForRouting.type}；执行时点=去重前")
                    }
                    .onFailure {
                        appendLog(
                            "人工交互抢占链路[PIPELINE_FORWARD_ERROR]：事件=${eventForRouting.type}；" +
                                "异常=${it.javaClass.simpleName}"
                        )
                    }
            }
        }

        // AccountBrowse consumes target events before visible-log de-duplication. This updates
        // scroll counters and refresh verification even when the matching log line is suppressed.
        if (runMode == RunMode.ACCOUNT_BROWSE) {
            dispatchTargetApplicationEvent(normalizedEvent)
        } else if (androidEventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            classNameEndsWithActivity(className)
        ) {
            updateCurrentActivityClass(className)
        }

        val rawEventName = AccessibilityEventFormatter.rawEventName(androidEventType)
        // AI-mode content is redacted at normalization and never enters ordinary event logs.
        val content = if (runMode == RunMode.AI_REPLY) "" else eventForRouting.eventText
            .joinToString(" ")
            .trim()
            .take(MAX_EVENT_CONTENT_CHARS)
        val description = if (runMode == RunMode.AI_REPLY) "" else
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
            dispatchActivityTransition(className)
        }

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
                    addAll(interactionDiagnostics(normalizedEvent, eventNow))
                } else {
                    add("当前 Activity=${currentActivityClass().ifBlank { "未知" }}")
                    add("AI 模式不调用自动浏览人工接管逻辑；目标事件仍投递到 AI 被动观察链路")
                    add(AccessibilityEventFormatter.sourceDetails(eventForRouting.source))
                }
            }
        }.joinToString("；")
        appendLog("$friendlyEvent$LOG_DETAIL_SEPARATOR$technicalDetails")

        if (runMode == RunMode.ACCOUNT_BROWSE) {
            handleManualInteractionFallback(normalizedEvent)
            onBrowseEventLogged()
        } else {
            // AI event delivery intentionally follows visible-event de-duplication and logging.
            dispatchTargetApplicationEvent(routedTargetEvent)
        }
    }

    fun resetDuplicateWindow() = routePolicy.resetDuplicateWindow()

    private fun classifyInputOrigin(event: AccessibilityEventEnvelope): InputOriginDecision =
        inputOriginClassifier?.classify(
            event = event,
            currentActivityClass = currentActivityClass(),
            currentPageGeneration = currentWindowGeneration(),
            targetPackage = targetPackage()
        ) ?: InputOriginDecision(
            origin = InputOrigin.UNKNOWN,
            evidence = InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
            reason = "来源分类器未配置，不能证明事件来源"
        )

    private fun logInputOriginDecision(event: AccessibilityEventEnvelope, decision: InputOriginDecision) {
        appendLog(
            "输入来源分类[DECISION]：origin=${decision.origin}；evidence=${decision.evidence}；" +
                "actionId=${decision.actionId ?: "NONE"}；event=${event.type}；" +
                "windowId=${event.windowId}；pageGeneration=${currentWindowGeneration()}；" +
                "reason=${decision.reason}"
        )
    }

    private fun isInputOriginRelevant(type: AccessibilityEventType): Boolean =
        isPreemptiveInteractionEvent(type) || type == AccessibilityEventType.VIEW_SCROLLED

    private fun isPreemptiveInteractionEvent(type: AccessibilityEventType): Boolean = when (type) {
        AccessibilityEventType.VIEW_CLICKED,
        AccessibilityEventType.VIEW_LONG_CLICKED,
        AccessibilityEventType.VIEW_CONTEXT_CLICKED,
        AccessibilityEventType.VIEW_SELECTED,
        AccessibilityEventType.VIEW_SCROLLED -> true
        else -> false
    }

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

