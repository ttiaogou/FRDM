package com.freedom.core.accessibility.events

import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureSpec
import com.freedom.core.accessibility.api.GestureStrokeSpec
import com.freedom.core.accessibility.api.ScreenRect
import kotlin.math.hypot

/**
 * Provenance categories used by the control arbiter.
 *
 * PHYSICAL_USER and EXTERNAL_INJECTED are intentionally not inferred from ordinary
 * AccessibilityEvent/MotionEvent metadata. A privileged input-monitor integration may provide
 * those categories in a future build, but the public AccessibilityService callbacks used here do
 * not expose a trustworthy actor/injection-origin bit.
 */
enum class InputOrigin {
    PHYSICAL_USER,
    SELF_INJECTED,
    /** Feedback emitted by a control owned by the target application; not an external actor signal. */
    APPLICATION_INTERNAL,
    EXTERNAL_INJECTED,
    UNKNOWN
}

enum class InputOriginEvidence {
    FREEDOM_DISPATCH_GESTURE_LEDGER_MATCH,
    GLOBAL_TOUCH_EVENT_WITHOUT_ACTOR_PROVENANCE,
    TARGET_APPLICATION_CONTROL_FEEDBACK,
    NO_MATCHING_FREEDOM_ACTION,
    MULTIPLE_POSSIBLE_FREEDOM_ACTIONS,
    AUTONOMOUS_NAVIGATION_TRANSITION_MATCH,
    CANCELLED_FREEDOM_ACTION,
    POSSIBLE_EXTERNAL_TOUCH_OVERLAP,
    PRIVILEGED_PHYSICAL_INPUT_PROVENANCE
}

data class InputOriginDecision(
    val origin: InputOrigin,
    val evidence: InputOriginEvidence,
    val actionId: String? = null,
    val reason: String = ""
) {
    val shouldYieldAutomation: Boolean
        get() = origin != InputOrigin.SELF_INJECTED && origin != InputOrigin.APPLICATION_INTERNAL
}

/**
 * Correlates Accessibility feedback with gestures submitted by Freedom through
 * AccessibilityService.dispatchGesture().
 *
 * Important limitation: this is an action-correlation ledger, not a system-wide input monitor.
 * It can strongly correlate a view click/scroll with a recorded Freedom dispatch when the
 * event's package, page generation, window, time and geometry all agree. This is application-level
 * correlation, not a platform-signed actor bit. A coincident external input that produces the
 * same feedback without a separately observable touch boundary cannot be distinguished by the
 * public Accessibility API. A uniquely matched Freedom dispatch remains SELF_INJECTED; other
 * target-app click/selection/scroll feedback is APPLICATION_INTERNAL by policy, even when a
 * cancelled or overlapping action prevents exact gesture correlation. Global TOUCH_INTERACTION
 * events remain UNKNOWN because they have no target geometry or actor identity. Such an event
 * also taints overlapping action records so later feedback cannot be falsely marked SELF_INJECTED;
 * the target-app control callback itself is still classified as APPLICATION_INTERNAL.
 */
class InputOriginClassifier(
    private val retentionAfterCompletionMs: Long = DEFAULT_RETENTION_AFTER_COMPLETION_MS,
    private val maximumActions: Int = DEFAULT_MAXIMUM_ACTIONS,
    private val tapMovementThresholdPx: Float = DEFAULT_TAP_MOVEMENT_THRESHOLD_PX,
    private val clickBoundsTolerancePx: Int = DEFAULT_CLICK_BOUNDS_TOLERANCE_PX,
    private val feedbackEventGraceMs: Long = DEFAULT_FEEDBACK_EVENT_GRACE_MS,
    private val movementFeedbackGraceAfterCompletionMs: Long = DEFAULT_MOVEMENT_FEEDBACK_GRACE_AFTER_COMPLETION_MS,
    private val onDiagnostic: (String) -> Unit = {}
) {
    private enum class DispatchState { ACTIVE, FEEDBACK_PENDING, RETAINED, CANCELLED }

    private data class NavigationTransitionTicket(
        val sourcePageGeneration: Long,
        val targetActivityClass: String,
        val targetPageGeneration: Long,
        val targetWindowId: Int,
        val transitionAtUptimeMs: Long,
        val expiresAtUptimeMs: Long,
        var consumed: Boolean = false,
        var possibleExternalTouch: Boolean = false
    )

    private data class StrokeShape(
        val start: GesturePoint,
        val end: GesturePoint,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val maxDistanceFromStart: Float,
        val tapMovementThresholdPx: Float,
        val durationMs: Long,
        val startDelayMs: Long
    ) {
        val isTapLike: Boolean get() = maxDistanceFromStart <= tapMovementThresholdPx
        val endAtOffsetMs: Long get() = startDelayMs + durationMs

        companion object {
            fun from(
                commands: List<GesturePathCommand>,
                durationMs: Long,
                startDelayMs: Long,
                tapMovementThresholdPx: Float
            ): StrokeShape {
                val points = buildList {
                    commands.forEach { command ->
                        when (command) {
                            is GesturePathCommand.MoveTo -> add(command.point)
                            is GesturePathCommand.LineTo -> add(command.point)
                            is GesturePathCommand.CubicTo -> {
                                add(command.control1)
                                add(command.control2)
                                add(command.end)
                            }
                        }
                    }
                }
                val start = points.first()
                val end = points.last()
                val maxDistance = points.maxOf { point -> distance(start, point) }
                return StrokeShape(
                    start = start,
                    end = end,
                    minX = points.minOf(GesturePoint::x),
                    maxX = points.maxOf(GesturePoint::x),
                    minY = points.minOf(GesturePoint::y),
                    maxY = points.maxOf(GesturePoint::y),
                    maxDistanceFromStart = maxDistance,
                    tapMovementThresholdPx = tapMovementThresholdPx,
                    durationMs = durationMs,
                    startDelayMs = startDelayMs
                )
            }

            fun from(stroke: GestureStrokeSpec, tapMovementThresholdPx: Float): StrokeShape =
                from(stroke.commands, stroke.durationMs, stroke.startDelayMs, tapMovementThresholdPx)

            private fun distance(left: GesturePoint, right: GesturePoint): Float =
                hypot(left.x - right.x, left.y - right.y)
        }
    }

    private data class ActionRecord(
        val id: String,
        val packageName: String,
        val activityClass: String,
        val pageGeneration: Long,
        val windowId: Int,
        val submittedAtUptimeMs: Long,
        var feedbackExpiresAtUptimeMs: Long,
        var expiresAtUptimeMs: Long,
        val strokes: List<StrokeShape>,
        val feedbackIntent: GestureFeedbackIntent,
        var state: DispatchState,
        var settledAtUptimeMs: Long? = null,
        var navigationTransitionTicket: NavigationTransitionTicket? = null,
        val consumedClickFeedbackTypes: MutableSet<AccessibilityEventType> = mutableSetOf(),
        var possibleExternalTouchOverlap: Boolean = false
    ) {
        val isTapLike: Boolean get() = strokes.isNotEmpty() && strokes.all(StrokeShape::isTapLike)
        val isMovement: Boolean get() = strokes.any { !it.isTapLike }
    }

    private val lock = Any()
    private val actions = LinkedHashMap<String, ActionRecord>()
    private var manualReviewRequired: Boolean = false
    private var manualReviewReason: String = ""

    /** Latches a fail-safe hold. Clearing action correlations never acknowledges this hold. */
    fun requireManualReview(reason: String) {
        val changed = synchronized(lock) {
            if (manualReviewRequired) false else {
                manualReviewRequired = true
                manualReviewReason = reason
                true
            }
        }
        if (changed) emitDiagnostic("输入仲裁[MANUAL_REVIEW_REQUIRED]：reason=$reason；必须显式重新启动运行后才可清除")
    }

    fun isManualReviewRequired(): Boolean = synchronized(lock) { manualReviewRequired }

    /** Only an explicit new AI run may acknowledge a previous UNKNOWN/EXTERNAL_INJECTED hold. */
    fun acknowledgeManualReview(reason: String) {
        val changed = synchronized(lock) {
            val hadReview = manualReviewRequired
            manualReviewRequired = false
            manualReviewReason = ""
            hadReview
        }
        if (changed) emitDiagnostic("输入仲裁[MANUAL_REVIEW_ACKNOWLEDGED]：reason=$reason；通过显式新运行确认解除暂停")
    }

    fun manualReviewReason(): String = synchronized(lock) { manualReviewReason }

    init {
        require(retentionAfterCompletionMs >= 0L) { "retentionAfterCompletionMs cannot be negative" }
        require(feedbackEventGraceMs >= 0L) { "feedbackEventGraceMs cannot be negative" }
        require(movementFeedbackGraceAfterCompletionMs >= 0L) {
            "movementFeedbackGraceAfterCompletionMs cannot be negative"
        }
        require(retentionAfterCompletionMs >= maxOf(
            feedbackEventGraceMs,
            movementFeedbackGraceAfterCompletionMs
        )) {
            "retentionAfterCompletionMs must cover the longest post-completion feedback grace period"
        }
        require(maximumActions > 0) { "maximumActions must be positive" }
        require(tapMovementThresholdPx >= 0f) { "tapMovementThresholdPx cannot be negative" }
    }

    /** Register before calling AccessibilityService.dispatchGesture(), not after it returns. */
    fun registerDispatchGesture(
        actionId: String,
        packageName: String,
        activityClass: String,
        pageGeneration: Long,
        windowId: Int,
        gesture: GestureSpec,
        submittedAtUptimeMs: Long
    ) {
        require(actionId.isNotBlank()) { "actionId cannot be blank" }
        val strokes = buildList {
            add(StrokeShape.from(
                gesture.commands,
                gesture.durationMs,
                gesture.startDelayMs,
                tapMovementThresholdPx
            ))
            gesture.additionalStrokes.forEach { add(StrokeShape.from(it, tapMovementThresholdPx)) }
        }
        val maxEndOffset = strokes.maxOfOrNull(StrokeShape::endAtOffsetMs) ?: gesture.durationMs
        val record = ActionRecord(
            id = actionId,
            packageName = packageName,
            activityClass = activityClass,
            pageGeneration = pageGeneration,
            windowId = windowId,
            submittedAtUptimeMs = submittedAtUptimeMs,
            feedbackExpiresAtUptimeMs = submittedAtUptimeMs + maxEndOffset + feedbackEventGraceMs,
            expiresAtUptimeMs = submittedAtUptimeMs + maxEndOffset + retentionAfterCompletionMs,
            strokes = strokes,
            feedbackIntent = gesture.feedbackIntent,
            state = DispatchState.ACTIVE
        )
        synchronized(lock) {
            pruneLocked(submittedAtUptimeMs)
            actions.remove(actionId)
            actions[actionId] = record
            while (actions.size > maximumActions.coerceAtLeast(1)) {
                actions.remove(actions.keys.first())
            }
        }
        emitDiagnostic(
            "输入来源账本[REGISTERED]：actionId=$actionId；接口=AccessibilityService.dispatchGesture；" +
                "package=$packageName；activity=${activityClass.ifBlank { "UNKNOWN" }}；" +
                "pageGeneration=$pageGeneration；windowId=$windowId；" +
                "submittedAtUptimeMs=$submittedAtUptimeMs；" +
                "feedbackExpiresAtUptimeMs=${record.feedbackExpiresAtUptimeMs}；" +
                "gestureShape=${if (record.isTapLike) "TAP_LIKE" else "MOVEMENT"}；" +
                "feedbackIntent=${record.feedbackIntent.name}；" +
                "pathBounds=[${record.strokes.minOf { it.minX }},${record.strokes.minOf { it.minY }}]-" +
                "[${record.strokes.maxOf { it.maxX }},${record.strokes.maxOf { it.maxY }}]；" +
                "durationMs=$maxEndOffset"
        )
    }

    /**
     * Mark the Android GestureResultCallback result. A completed swipe can continue producing
     * delayed VIEW_SCROLLED feedback after this callback, so keep its correlation window open
     * relative to the actual settlement timestamp instead of ending it at the predicted stroke
     * duration. Clicks retain the shorter grace; cancelled actions are never self-matched.
     */
    fun settleDispatchGesture(actionId: String, completed: Boolean, settledAtUptimeMs: Long) {
        val state = if (completed) DispatchState.FEEDBACK_PENDING else DispatchState.CANCELLED
        val settledRecord = synchronized(lock) {
            val record = actions[actionId] ?: return@synchronized null
            record.state = state
            record.settledAtUptimeMs = settledAtUptimeMs
            record.expiresAtUptimeMs = maxOf(
                record.expiresAtUptimeMs,
                settledAtUptimeMs + retentionAfterCompletionMs
            )
            if (completed) {
                val postCompletionGraceMs = when {
                    record.isMovement -> movementFeedbackGraceAfterCompletionMs
                    else -> feedbackEventGraceMs
                }
                record.feedbackExpiresAtUptimeMs = maxOf(
                    record.feedbackExpiresAtUptimeMs,
                    settledAtUptimeMs + postCompletionGraceMs
                )
                if (record.feedbackIntent == GestureFeedbackIntent.NAVIGATION_ENTRY) {
                    record.feedbackExpiresAtUptimeMs = maxOf(
                        record.feedbackExpiresAtUptimeMs,
                        settledAtUptimeMs + NAVIGATION_ENTRY_TRANSITION_GRACE_MS
                    )
                    record.expiresAtUptimeMs = maxOf(
                        record.expiresAtUptimeMs,
                        settledAtUptimeMs + NAVIGATION_ENTRY_TRANSITION_GRACE_MS + retentionAfterCompletionMs
                    )
                }
            }
            record.feedbackExpiresAtUptimeMs to record.expiresAtUptimeMs
        }
        if (settledRecord != null) {
            emitDiagnostic(
                "输入来源账本[SETTLED]：actionId=$actionId；" +
                    "platformResult=${if (completed) "COMPLETED" else "CANCELLED"}；" +
                    "ledgerState=${if (completed) "FEEDBACK_PENDING" else "CANCELLED"}；" +
                    "settledAtUptimeMs=$settledAtUptimeMs；" +
                    "feedbackExpiresAtUptimeMs=${settledRecord.first}；" +
                    "retainedUntilUptimeMs=${settledRecord.second}"
            )
        }
    }

    /**
     * Attach one short-lived navigation ticket to a unique NAVIGATION_ENTRY tap when Android
     * reports the resulting target Activity/window transition. A transition is not enough to
     * attribute arbitrary future events: only one scroll feedback event on this exact target
     * page/window, inside the bounded ticket window, may be claimed.
     */
    fun noteActivityTransition(
        packageName: String,
        targetActivityClass: String,
        sourcePageGeneration: Long,
        targetPageGeneration: Long,
        targetWindowId: Int,
        transitionAtUptimeMs: Long
    ) {
        if (packageName.isBlank() || targetActivityClass.isBlank()) return
        val diagnostic = synchronized(lock) {
            pruneLocked(transitionAtUptimeMs)
            val eligible = actions.values.filter { record ->
                record.feedbackIntent == GestureFeedbackIntent.NAVIGATION_ENTRY &&
                    record.state in setOf(DispatchState.ACTIVE, DispatchState.FEEDBACK_PENDING) &&
                    record.packageName == packageName &&
                    record.pageGeneration == sourcePageGeneration &&
                    record.isTapLike && !record.possibleExternalTouchOverlap &&
                    transitionAtUptimeMs >= record.submittedAtUptimeMs - EVENT_EARLY_TOLERANCE_MS &&
                    transitionAtUptimeMs <= record.submittedAtUptimeMs + NAVIGATION_ENTRY_TRANSITION_CAUSALITY_MS
            }
            when (eligible.size) {
                1 -> {
                    val record = eligible.single()
                    record.navigationTransitionTicket = NavigationTransitionTicket(
                        sourcePageGeneration = sourcePageGeneration,
                        targetActivityClass = targetActivityClass,
                        targetPageGeneration = targetPageGeneration,
                        targetWindowId = targetWindowId,
                        transitionAtUptimeMs = transitionAtUptimeMs,
                        expiresAtUptimeMs = transitionAtUptimeMs + NAVIGATION_ENTRY_TRANSITION_GRACE_MS
                    )
                    "导航事务[ATTACHED]：sourceActionId=${record.id}；sourceActivity=${record.activityClass}；" +
                        "sourcePageGeneration=$sourcePageGeneration；targetActivity=$targetActivityClass；" +
                        "targetPageGeneration=$targetPageGeneration；targetWindowId=$targetWindowId；" +
                        "transitionAtUptimeMs=$transitionAtUptimeMs；ticketExpiresAt=${record.navigationTransitionTicket?.expiresAtUptimeMs}"
                }
                0 -> "导航事务[UNMATCHED]：未找到唯一来源点击；package=$packageName；" +
                    "targetActivity=$targetActivityClass；sourcePageGeneration=$sourcePageGeneration；" +
                    "targetPageGeneration=$targetPageGeneration；targetWindowId=$targetWindowId；" +
                    "transitionAtUptimeMs=$transitionAtUptimeMs"
                else -> "导航事务[AMBIGUOUS]：多个来源点击均可能触发页面迁移；" +
                    eligible.joinToString(prefix = "candidates=[", postfix = "]") { actionSummary(it) }
            }
        }
        emitDiagnostic(diagnostic)
    }

    fun rejectDispatchGesture(actionId: String, reason: String) {
        val removed = synchronized(lock) { actions.remove(actionId) != null }
        if (removed) emitDiagnostic("输入来源账本[REJECTED]：actionId=$actionId；reason=$reason")
    }

    /**
     * Use the dispatch ledger for positive SELF_INJECTED matches. Target-app control callbacks
     * without a unique match are APPLICATION_INTERNAL because the public Accessibility API does
     * not identify the actor; only independent unattributed signals such as global touch boundaries
     * remain UNKNOWN.
     */
    fun classify(
        event: AccessibilityEventEnvelope,
        currentActivityClass: String,
        currentPageGeneration: Long,
        targetPackage: String = event.packageName
    ): InputOriginDecision {
        if (event.type == AccessibilityEventType.TOUCH_INTERACTION_START ||
            event.type == AccessibilityEventType.TOUCH_INTERACTION_END
        ) {
            markPossibleExternalTouch(event.eventTimeMillis, currentPageGeneration)
            return InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.GLOBAL_TOUCH_EVENT_WITHOUT_ACTOR_PROVENANCE,
                reason = "系统触摸边界事件没有提供可验证的输入发起者；不能据此断言是物理触摸或程序注入"
            )
        }

        val isClick = event.type in CLICK_FEEDBACK_TYPES
        val isScroll = event.type == AccessibilityEventType.VIEW_SCROLLED
        if (!isClick && !isScroll) {
            return InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "该事件类型不参与自主手势反馈关联"
            )
        }

        // Android AccessibilityEvent.eventTime is based on SystemClock.uptimeMillis(). Use the
        // event timestamp (not callback arrival time) so delayed main-thread delivery does not
        // move an otherwise valid feedback event outside its originating gesture's time window.
        val eventTime = event.eventTimeMillis
        return synchronized(lock) {
            pruneLocked(eventTime)

            if (isScroll) {
                val navigationTickets = actions.values.mapNotNull { record ->
                    val ticket = record.navigationTransitionTicket ?: return@mapNotNull null
                    if (record.state !in setOf(DispatchState.ACTIVE, DispatchState.FEEDBACK_PENDING) ||
                        ticket.consumed || ticket.possibleExternalTouch ||
                        ticket.targetPageGeneration != currentPageGeneration ||
                        ticket.targetActivityClass != currentActivityClass ||
                        ticket.targetWindowId >= 0 && event.windowId != ticket.targetWindowId ||
                        eventTime < ticket.transitionAtUptimeMs || eventTime > ticket.expiresAtUptimeMs ||
                        record.packageName != event.packageName || !isScrollableSource(event.source)
                    ) return@mapNotNull null
                    record to ticket
                }
                if (navigationTickets.size == 1) {
                    val (record, ticket) = navigationTickets.single()
                    ticket.consumed = true
                    return@synchronized InputOriginDecision(
                        origin = InputOrigin.SELF_INJECTED,
                        evidence = InputOriginEvidence.AUTONOMOUS_NAVIGATION_TRANSITION_MATCH,
                        actionId = record.id,
                        reason = "新Activity首次可滚动视口反馈与唯一自主导航事务匹配；" +
                            "sourcePageGeneration=${ticket.sourcePageGeneration}；" +
                            "targetPageGeneration=${ticket.targetPageGeneration}；" +
                            "targetActivity=${ticket.targetActivityClass}；targetWindowId=${ticket.targetWindowId}；" +
                            "transitionAtUptimeMs=${ticket.transitionAtUptimeMs}；ticketConsumed=true"
                    )
                }
                if (navigationTickets.size > 1) {
                    if (isTargetApplicationControlFeedback(event, targetPackage)) {
                        return@synchronized InputOriginDecision(
                            origin = InputOrigin.APPLICATION_INTERNAL,
                            evidence = InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK,
                            reason = "目标应用滚动控件反馈虽存在多个导航事务候选，但无法据此判断外部输入；不进入UNKNOWN累计"
                        )
                    }
                    return@synchronized InputOriginDecision(
                        origin = InputOrigin.UNKNOWN,
                        evidence = InputOriginEvidence.MULTIPLE_POSSIBLE_FREEDOM_ACTIONS,
                        reason = "非目标应用控件反馈存在多个有效导航事务，不能唯一归因；" +
                            navigationTickets.joinToString(prefix = "candidates=[", postfix = "]") {
                                "${actionSummary(it.first)},targetActivity=${it.second.targetActivityClass}," +
                                    "targetPageGeneration=${it.second.targetPageGeneration}," +
                                    "targetWindowId=${it.second.targetWindowId}," +
                                    "transitionAt=${it.second.transitionAtUptimeMs}," +
                                    "ticketExpiresAt=${it.second.expiresAtUptimeMs}"
                            }
                    )
                }
            }

            val candidates = actions.values.filter { record ->
                // The action is registered before dispatchGesture() is called. Target-view
                // feedback can arrive before Android invokes GestureResultCallback, so ACTIVE
                // is matchable; CANCELLED and RETAINED are never matchable. A preceding global touch
                // boundary prevents a SELF_INJECTED match; the boundary remains UNKNOWN on its own.
                if (record.state !in setOf(DispatchState.ACTIVE, DispatchState.FEEDBACK_PENDING)) return@filter false
                if (isClick && event.type in record.consumedClickFeedbackTypes) return@filter false
                if (!isWithinFeedbackWindow(record, eventTime)) return@filter false
                if (record.possibleExternalTouchOverlap) return@filter false
                if (record.pageGeneration != currentPageGeneration) return@filter false
                if (record.activityClass.isBlank() || currentActivityClass.isBlank() ||
                    record.activityClass != currentActivityClass
                ) return@filter false
                if (record.packageName.isBlank() || record.packageName != event.packageName) return@filter false
                if (record.windowId >= 0 && event.windowId != record.windowId) return@filter false
                if (isClick) {
                    record.isTapLike && matchesClickGeometry(record, event.source?.bounds)
                } else {
                    matchesScrollFeedback(record, event)
                }
            }

            when {
                candidates.size == 1 -> {
                    val match = candidates.single()
                    if (isClick) match.consumedClickFeedbackTypes.add(event.type)
                    InputOriginDecision(
                        origin = InputOrigin.SELF_INJECTED,
                        evidence = InputOriginEvidence.FREEDOM_DISPATCH_GESTURE_LEDGER_MATCH,
                        actionId = match.id,
                        reason = if (isClick) {
                            "点击反馈与 Freedom 已登记的 dispatchGesture 目标坐标、页面和时间匹配"
                        } else {
                            "滚动反馈与 Freedom 已登记的 dispatchGesture 移动轨迹、页面和时间匹配"
                        }
                    )
                }
                // The public Accessibility callback identifies the target app's control stream,
                // not the actor that caused the control to change. Keep unmatched or ambiguous
                // target controls out of UNKNOWN even if an old/cancelled Freedom action or an
                // unattributed global touch overlaps. The global touch boundary itself remains
                // UNKNOWN and is arbitrated independently.
                isTargetApplicationControlFeedback(event, targetPackage) -> InputOriginDecision(
                    origin = InputOrigin.APPLICATION_INTERNAL,
                    evidence = InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK,
                    reason = "事件来自目标应用自身控件反馈；不推断物理触摸/外部输入，不进入UNKNOWN累计"
                )
                candidates.size > 1 -> InputOriginDecision(
                    InputOrigin.UNKNOWN,
                    InputOriginEvidence.MULTIPLE_POSSIBLE_FREEDOM_ACTIONS,
                    reason = "存在多个可能匹配的自主动作，且事件不属于已确认的目标应用控件反馈；不能唯一归因；" +
                        candidates.joinToString(prefix = "candidates=[", postfix = "]") { actionSummary(it) }
                )
                actions.values.any { record ->
                    record.state == DispatchState.CANCELLED &&
                        record.packageName == event.packageName &&
                        record.pageGeneration == currentPageGeneration &&
                        record.activityClass.isNotBlank() && currentActivityClass.isNotBlank() &&
                        record.activityClass == currentActivityClass &&
                        isWithinFeedbackWindow(record, eventTime) &&
                        (record.windowId < 0 || event.windowId == record.windowId) &&
                        if (isClick) record.isTapLike && matchesClickGeometry(record, event.source?.bounds)
                        else matchesScrollFeedback(record, event)
                } -> InputOriginDecision(
                    InputOrigin.UNKNOWN,
                    InputOriginEvidence.CANCELLED_FREEDOM_ACTION,
                    reason = "事件不属于已确认的目标应用控件反馈，且与已取消的 Freedom 动作匹配；保留UNKNOWN"
                )
                actions.values.any { record ->
                    record.possibleExternalTouchOverlap &&
                        record.packageName == event.packageName &&
                        record.pageGeneration == currentPageGeneration &&
                        isWithinFeedbackWindow(record, eventTime) &&
                        (record.windowId < 0 || event.windowId == record.windowId) &&
                        if (isClick) record.isTapLike && matchesClickGeometry(record, event.source?.bounds)
                        else matchesScrollFeedback(record, event)
                } -> InputOriginDecision(
                    InputOrigin.UNKNOWN,
                    InputOriginEvidence.POSSIBLE_EXTERNAL_TOUCH_OVERLAP,
                    reason = "事件不属于已确认的目标应用控件反馈，且与存在未知触摸重叠的自主动作匹配；保留UNKNOWN"
                )
                else -> InputOriginDecision(
                    InputOrigin.UNKNOWN,
                    InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                    reason = if (event.source?.bounds == null && isClick) {
                        "点击反馈缺少控件边界，且无法确认目标应用控件归属"
                    } else if (isClick) {
                        clickMismatchReason(event, currentActivityClass, currentPageGeneration, eventTime)
                    } else {
                        scrollMismatchReason(event, currentActivityClass, currentPageGeneration, eventTime)
                    }
                )
            }
        }
    }

    /**
     * Public Accessibility callbacks do not identify who initiated a touch. A target-package
     * click/selection/scroll callback is therefore attributed to the target application's control
     * stream, not to an external actor. A unique match against Freedom's dispatch ledger remains
     * SELF_INJECTED; otherwise the target control feedback is APPLICATION_INTERNAL. This does not
     * claim that the app autonomously caused the underlying state change; it prevents its control
     * feedback from becoming an UNKNOWN interruption signal. Global touch boundaries are handled
     * separately and remain UNKNOWN.
     */
    private fun isTargetApplicationControlFeedback(
        event: AccessibilityEventEnvelope,
        targetPackage: String
    ): Boolean =
        targetPackage.isNotBlank() &&
            event.packageName == targetPackage &&
            event.type in TARGET_CONTROL_FEEDBACK_TYPES

    fun clear() = synchronized(lock) { actions.clear() }

    private fun emitDiagnostic(message: String) {
        runCatching { onDiagnostic(message) }
    }

    private fun isWithinFeedbackWindow(record: ActionRecord, eventTime: Long): Boolean =
        eventTime >= record.submittedAtUptimeMs - EVENT_EARLY_TOLERANCE_MS &&
            eventTime <= record.feedbackExpiresAtUptimeMs

    /** A global touch boundary cannot be attributed, so overlapping actions become ambiguous. */
    private fun markPossibleExternalTouch(eventTime: Long, pageGeneration: Long) {
        synchronized(lock) {
            actions.values.forEach { record ->
                if (record.pageGeneration == pageGeneration &&
                    eventTime >= record.submittedAtUptimeMs - EVENT_EARLY_TOLERANCE_MS &&
                    eventTime <= record.expiresAtUptimeMs
                ) {
                    record.possibleExternalTouchOverlap = true
                }
                record.navigationTransitionTicket?.let { ticket ->
                    if (ticket.targetPageGeneration == pageGeneration &&
                        eventTime >= ticket.transitionAtUptimeMs && eventTime <= ticket.expiresAtUptimeMs
                    ) {
                        ticket.possibleExternalTouch = true
                    }
                }
            }
        }
    }

    /**
     * Scroll feedback is owned only by a movement gesture whose trajectory intersects the
     * reported scrollable region. Navigation-tab effects are resolved by page snapshots, never
     * by assigning an adjacent RecyclerView event to the tap.
     */
    private fun matchesScrollFeedback(
        record: ActionRecord,
        event: AccessibilityEventEnvelope
    ): Boolean {
        val source = event.source ?: return false
        val scrollable = source.scrollable ||
            source.className.contains("RecyclerView", ignoreCase = true) ||
            source.className.contains("ListView", ignoreCase = true)
        return scrollable && record.isMovement && matchesScrollGeometry(record, source.bounds)
    }

    private fun clickMismatchReason(
        event: AccessibilityEventEnvelope,
        currentActivityClass: String,
        currentPageGeneration: Long,
        eventTime: Long
    ): String {
        val bounds = event.source?.bounds
        val recentTapRecords = actions.values
            .filter { it.isTapLike && it.packageName == event.packageName }
            .takeLast(3)
        if (recentTapRecords.isEmpty()) {
            return if (bounds == null) {
                "点击反馈缺少控件边界，无法与自主点击坐标关联"
            } else {
                "没有唯一匹配的 Freedom 自主动作；不能推断为物理用户或外部程序"
            }
        }
        val details = recentTapRecords.joinToString(";") { record ->
            val point = record.strokes.firstOrNull { it.isTapLike }?.end
            val padding = clickBoundsTolerancePx.coerceAtLeast(0)
            val contained = point != null && bounds != null && !bounds.isEmpty &&
                point.x >= bounds.left - padding && point.x <= bounds.right + padding &&
                point.y >= bounds.top - padding && point.y <= bounds.bottom + padding
            "actionId=${record.id},pageGeneration=${record.pageGeneration == currentPageGeneration}," +
                "windowId=${record.windowId == event.windowId},activity=${record.activityClass == currentActivityClass}," +
                "time=${isWithinFeedbackWindow(record, eventTime)},tapPoint=${point?.let { "(${it.x},${it.y})" } ?: "NONE"}," +
                "sourceBounds=${bounds?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" } ?: "NONE"}," +
                "sourceClass=${event.source?.className.orEmpty()},sourceViewId=${event.source?.viewId.orEmpty()}," +
                "sourceClickable=${event.source?.clickable == true},clickableParentContainsTap=$contained"
        }
        return "VIEW_CLICKED未匹配；检查包名/Activity/页面代次/窗口/事件时间/可点击父控件边界；候选[$details]"
    }

    private fun scrollMismatchReason(
        event: AccessibilityEventEnvelope,
        currentActivityClass: String,
        currentPageGeneration: Long,
        eventTime: Long
    ): String {
        val recentMovements = actions.values
            .filter { it.isMovement && it.packageName == event.packageName }
            .takeLast(3)
        if (recentMovements.isEmpty()) {
            return "没有匹配的自主滑动；导航标签点击不参与滚动反馈归属；滚动反馈保持UNKNOWN"
        }
        val details = recentMovements.joinToString(";") { record ->
            val bounds = event.source?.bounds
            val point = record.strokes.firstOrNull { !it.isTapLike }?.start
            "actionId=${record.id},pageGeneration=${record.pageGeneration == currentPageGeneration}," +
                "windowId=${record.windowId == event.windowId},activity=${record.activityClass == currentActivityClass}," +
                "time=${isWithinFeedbackWindow(record, eventTime)}," +
                "sourceBounds=${bounds?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" } ?: "NONE"}," +
                "movementStart=${point?.let { "(${it.x},${it.y})" } ?: "NONE"}"
        }
        return "VIEW_SCROLLED未匹配自主滑动；导航标签点击不参与滚动反馈归属；保持UNKNOWN；候选诊断[$details]"
    }

    private fun matchesClickGeometry(record: ActionRecord, bounds: ScreenRect?): Boolean {
        if (bounds == null || bounds.isEmpty) return false
        val padding = clickBoundsTolerancePx.coerceAtLeast(0)
        return record.strokes.any { stroke ->
            if (!stroke.isTapLike) return@any false
            val x = stroke.end.x
            val y = stroke.end.y
            x >= bounds.left - padding && x <= bounds.right + padding &&
                y >= bounds.top - padding && y <= bounds.bottom + padding
        }
    }

    private fun matchesScrollGeometry(record: ActionRecord, bounds: ScreenRect?): Boolean {
        if (bounds == null || bounds.isEmpty) return false
        val padding = clickBoundsTolerancePx.coerceAtLeast(0).toFloat()
        return record.strokes.any { stroke ->
            if (stroke.isTapLike) return@any false
            stroke.start.x >= bounds.left - padding && stroke.start.x <= bounds.right + padding &&
                stroke.start.y >= bounds.top - padding && stroke.start.y <= bounds.bottom + padding &&
                stroke.end.x >= bounds.left - padding && stroke.end.x <= bounds.right + padding &&
                stroke.end.y >= bounds.top - padding && stroke.end.y <= bounds.bottom + padding
        }
    }

    private fun pruneLocked(nowUptimeMs: Long) {
        val iterator = actions.entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            val ticket = record.navigationTransitionTicket
            if (ticket != null && nowUptimeMs > ticket.expiresAtUptimeMs) {
                record.navigationTransitionTicket = null
            }
            val hasLiveNavigationTicket = ticket != null && !ticket.consumed &&
                !ticket.possibleExternalTouch && nowUptimeMs <= ticket.expiresAtUptimeMs
            if (record.state == DispatchState.FEEDBACK_PENDING &&
                nowUptimeMs > record.feedbackExpiresAtUptimeMs && !hasLiveNavigationTicket
            ) {
                record.state = DispatchState.RETAINED
            }
            if (nowUptimeMs > record.expiresAtUptimeMs) iterator.remove()
        }
    }

    private fun actionSummary(record: ActionRecord): String =
        "actionId=${record.id},gesture=${if (record.isTapLike) "TAP_LIKE" else "MOVEMENT"}," +
            "feedbackIntent=${record.feedbackIntent},state=${record.state}," +
            "submittedAt=${record.submittedAtUptimeMs},settledAt=${record.settledAtUptimeMs ?: "PENDING"}," +
            "feedbackExpiresAt=${record.feedbackExpiresAtUptimeMs},retainedUntil=${record.expiresAtUptimeMs}," +
            "pageGeneration=${record.pageGeneration},activity=${record.activityClass},windowId=${record.windowId}," +
            "externalTouchOverlap=${record.possibleExternalTouchOverlap}"

    private fun isScrollableSource(source: AccessibilitySourceSnapshot?): Boolean {
        source ?: return false
        return source.scrollable || source.className.contains("RecyclerView", ignoreCase = true) ||
            source.className.contains("ListView", ignoreCase = true)
    }

    private companion object {
        const val DEFAULT_RETENTION_AFTER_COMPLETION_MS = 1_800L
        const val DEFAULT_FEEDBACK_EVENT_GRACE_MS = 400L
        const val DEFAULT_MOVEMENT_FEEDBACK_GRACE_AFTER_COMPLETION_MS = 1_400L
        const val DEFAULT_MAXIMUM_ACTIONS = 32
        const val DEFAULT_TAP_MOVEMENT_THRESHOLD_PX = 28f
        const val DEFAULT_CLICK_BOUNDS_TOLERANCE_PX = 28
        const val NAVIGATION_ENTRY_TRANSITION_CAUSALITY_MS = 2_500L
        const val NAVIGATION_ENTRY_TRANSITION_GRACE_MS = 1_800L
        const val EVENT_EARLY_TOLERANCE_MS = 80L

        val CLICK_FEEDBACK_TYPES = setOf(
            AccessibilityEventType.VIEW_CLICKED,
            AccessibilityEventType.VIEW_LONG_CLICKED,
            AccessibilityEventType.VIEW_CONTEXT_CLICKED,
            AccessibilityEventType.VIEW_SELECTED
        )

        val TARGET_CONTROL_FEEDBACK_TYPES = CLICK_FEEDBACK_TYPES +
            AccessibilityEventType.VIEW_SCROLLED
    }


}
