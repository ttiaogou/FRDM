package com.freedom.adapters.accessibility.android

import android.view.accessibility.AccessibilityEvent
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.events.AccessibilitySourceSnapshot
import com.freedom.core.accessibility.events.DefaultAccessibilityEventRoutePolicy
import com.freedom.core.accessibility.events.InputOrigin
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.accessibility.events.InputOriginEvidence
import com.freedom.core.runtime.RunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilityEventPipelineTest {
    @Test
    fun browseClickUsesActualPlatformLedgerAfterLongQueueDelayWithoutChangingAiRouting() {
        val classifier = InputOriginClassifier()
        val trace = mutableListOf<String>()
        val fallbackEvents = mutableListOf<AccessibilityEventEnvelope>()
        val pipeline = pipeline(trace, currentActivityClass = { ACTIVITY },
            currentWindowGeneration = { 7L }, inputOriginClassifier = classifier,
            onManualFallback = { fallbackEvents += it })
        // The request was prepared at 1000; Android actually dispatches much later.
        classifier.registerDispatchGesture("card", TARGET_PACKAGE, ACTIVITY, 7L, 12,
            com.freedom.core.accessibility.api.GestureSpec(
                commands = listOf(com.freedom.core.accessibility.api.GesturePathCommand.MoveTo(
                    com.freedom.core.accessibility.api.GesturePoint(5f, 5f))),
                durationMs = 90L,
                feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.NAVIGATION_ENTRY
            ), 2500L)
        classifier.settleDispatchGesture("card", completed = true, settledAtUptimeMs = 2590L)
        val click = event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE)
            .copy(eventTimeMillis = 2650L, windowId = 12)
        pipeline.processNormalized(click, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.ACCOUNT_BROWSE, 2650L)
        assertEquals(InputOrigin.SELF_INJECTED, fallbackEvents.single().inputOriginDecision?.origin)
        assertEquals("card", fallbackEvents.single().inputOriginDecision?.actionId)
        val policy = com.freedom.features.accountbrowse.AccountBrowseInteractionEventClassifier()
        assertTrue(policy.isVerifiedSelfClick(fallbackEvents.single(), false))
        assertFalse(policy.isVerifiedSelfClick(fallbackEvents.single(), true))
        pipeline.processNormalized(click.copy(windowId = 13, eventTimeMillis = 3100L),
            AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.ACCOUNT_BROWSE, 3100L)
        assertFalse(policy.isVerifiedSelfClick(fallbackEvents.last(), false))
    }

    @Test
    fun accountBrowseDispatchesTargetEventBeforeDuplicateLogSuppression() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)
        val event = event(AccessibilityEventType.VIEW_SCROLLED, TARGET_PACKAGE,
            "androidx.recyclerview.widget.RecyclerView")

        pipeline.processNormalized(event, AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.ACCOUNT_BROWSE, 1_000L)
        pipeline.processNormalized(event, AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.ACCOUNT_BROWSE, 1_100L)

        assertEquals(2, trace.count { it == "dispatch-target" })
        assertEquals(1, trace.count { it == "append-log" })
        assertEquals(1, trace.count { it == "manual-fallback" })
        assertEquals(1, trace.count { it == "schedule-scan" })
        assertTrue(trace.indexOf("dispatch-target") < trace.indexOf("append-log"))
        assertTrue(trace.indexOf("append-log") < trace.indexOf("manual-fallback"))
        assertTrue(trace.indexOf("manual-fallback") < trace.indexOf("schedule-scan"))
    }

    @Test
    fun aiReplyDispatchesAfterVisibleLogAndNeverLogsEventTextOrDescription() {
        val trace = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val pipeline = pipeline(trace, appendLog = { logged += it; trace += "append-log" })
        val event = event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE,
            "android.widget.Button", listOf("private message text"), "private accessibility description")

        pipeline.processNormalized(event, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.AI_REPLY, 2_000L)

        assertTrue(trace.indexOf("append-log") < trace.indexOf("dispatch-target"))
        assertFalse(logged.joinToString().contains("private message text"))
        assertFalse(logged.joinToString().contains("private accessibility description"))
        assertFalse(trace.contains("manual-fallback"))
        assertFalse(trace.contains("schedule-scan"))
        assertTrue(logged.joinToString().contains("UNKNOWN 不参与任务调度"))
    }

    @Test
    fun aiReplyClickAndScrollEventsRemainOrdinaryPageObservationEvents() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)
        val click = event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE, "android.widget.RelativeLayout")
        val scroll = event(AccessibilityEventType.VIEW_SCROLLED, TARGET_PACKAGE, "androidx.recyclerview.widget.RecyclerView")

        pipeline.processNormalized(click, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.AI_REPLY, 2_100L)
        pipeline.processNormalized(click, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.AI_REPLY, 2_150L)
        pipeline.processNormalized(scroll, AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.AI_REPLY, 2_300L)

        assertEquals(2, trace.count { it == "dispatch-target" })
        assertFalse(trace.contains("manual-fallback"))
        assertTrue(trace.contains("append-log"))
    }

    @Test
    fun aiReplyDoesNotClassifyMatchedGestureFeedbackForTaskArbitration() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)

        pipeline.processNormalized(
            event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE),
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            RunMode.AI_REPLY,
            2_100L
        )

        assertEquals(1, trace.count { it == "dispatch-target" })
        assertFalse(trace.contains("manual-fallback"))
    }

    @Test
    fun aiReplyDropsGlobalTouchBoundariesWithoutPausingOrForwardingThem() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)

        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            RunMode.AI_REPLY,
            2_100L
        )
        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_END, ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            RunMode.AI_REPLY,
            2_150L
        )

        assertFalse(trace.contains("dispatch-global-touch"))
        assertFalse(trace.contains("dispatch-target"))
        assertTrue(trace.contains("append-log"))
    }

    @Test
    fun aiReplyWindowTransitionEventsContinueToReachThePageObserver() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)
        val transition = event(
            AccessibilityEventType.WINDOW_STATE_CHANGED,
            TARGET_PACKAGE,
            "com.immomo.momo.maintab.MaintabActivity"
        )

        pipeline.processNormalized(
            transition,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            RunMode.AI_REPLY,
            2_250L
        )

        assertTrue(trace.contains("activity-transition"))
        assertTrue(trace.contains("dispatch-target"))
        assertTrue(trace.indexOf("activity-transition") < trace.indexOf("append-log"))
        assertTrue(trace.indexOf("append-log") < trace.indexOf("dispatch-target"))
        assertFalse(trace.contains("manual-fallback"))
    }

    @Test
    fun windowEventDiagnosticsRunBeforeVisibleDedupAndDoNotChangeRouting() {
        val trace = mutableListOf<String>()
        val diagnoses = mutableListOf<String>()
        var currentActivity = ""
        val pipeline = AndroidAccessibilityEventPipeline(
            targetPackage = { TARGET_PACKAGE },
            freedomPackage = FREEDOM_PACKAGE,
            resolveAppLabel = { "Momo" },
            unifiedAccessibilityEntry = com.freedom.core.accessibility.api.UnifiedAccessibilityEntry { event ->
                when (event) {
                    is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.TargetApplication ->
                        trace += "dispatch-target"
                    is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.ActivityTransition ->
                        trace += "activity-transition"
                    else -> Unit
                }
            },
            interactionDiagnostics = { _, _ -> emptyList() },
            handleManualInteractionFallback = { },
            updateAppPage = { _, _ -> },
            appendLog = { trace += "append-log" },
            currentActivityClass = { currentActivity },
            observeWindowStateEvent = { clazz, _, _ -> if (isActivityWindowClassName(clazz)) currentActivity = clazz },
            onBrowseEventLogged = { },
            onActivityWindowEventDiagnostic = { clazz, window, generation, tracked ->
                diagnoses += "$clazz|$window|$generation|$tracked"
            }
        )
        val activity = event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE, ACTIVITY)
        pipeline.processNormalized(activity, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 1000L)
        pipeline.processNormalized(activity, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 1100L)
        assertEquals(2, diagnoses.size)
        assertTrue(diagnoses.all { it == "$ACTIVITY|-1|0|true" })
        assertEquals(1, trace.count { it == "activity-transition" })
        assertEquals(1, trace.count { it == "dispatch-target" })
        assertEquals(ACTIVITY, currentActivity)

        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE, "android.widget.FrameLayout"),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 1300L
        )
        assertEquals("android.widget.FrameLayout|-1|0|false", diagnoses.last())
        assertEquals(ACTIVITY, currentActivity)
    }

    @Test
    fun aiReplyWindowContentChangesContinueToReachThePageObserver() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)

        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_CONTENT_CHANGED, TARGET_PACKAGE),
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            RunMode.AI_REPLY,
            2_260L
        )

        assertEquals(1, trace.count { it == "dispatch-target" })
        assertFalse(trace.contains("manual-fallback"))
    }

    @Test
    fun clearedActivityAfterReconnectRequiresANewTargetWindowStateEvent() {
        val trace = mutableListOf<String>()
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 0L })
        tracker.observeWindowStateEvent(ACTIVITY, 12, 0L)
        tracker.clearActivityClass()

        val pipeline = pipeline(
            trace,
            currentActivityClass = tracker::currentActivityClass,
            observeWindowStateEvent = tracker::observeWindowStateEvent
        )
        // A fresh content or scroll event does not prove which Activity owns the window.
        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_CONTENT_CHANGED, TARGET_PACKAGE, ACTIVITY),
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, RunMode.AI_REPLY, 2_300L
        )
        pipeline.processNormalized(
            event(AccessibilityEventType.VIEW_SCROLLED, TARGET_PACKAGE, "androidx.recyclerview.widget.RecyclerView"),
            AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.AI_REPLY, 2_500L
        )
        // Neither a widget-class window event nor a foreign application's event is Activity proof.
        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE, "android.widget.FrameLayout"),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 2_700L
        )
        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_STATE_CHANGED, "com.other.app", ACTIVITY),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 2_900L
        )
        assertEquals("", tracker.currentActivityClass())

        // When Android actually emits a new target Activity event, the existing pipeline records it.
        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE, ACTIVITY).copy(windowId = 12),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 3_100L
        )
        assertEquals(ACTIVITY, tracker.currentActivityClass())
    }

    @Test
    fun aiReplyLongClickContextClickAndSelectionRemainOrdinaryPageEvents() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)
        val interactionTypes = listOf(
            AccessibilityEventType.VIEW_LONG_CLICKED,
            AccessibilityEventType.VIEW_CONTEXT_CLICKED,
            AccessibilityEventType.VIEW_SELECTED
        )

        interactionTypes.forEachIndexed { index, type ->
            pipeline.processNormalized(
                event(type, TARGET_PACKAGE, "android.widget.RelativeLayout"),
                when (type) {
                    AccessibilityEventType.VIEW_LONG_CLICKED -> AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
                    AccessibilityEventType.VIEW_CONTEXT_CLICKED -> AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED
                    else -> AccessibilityEvent.TYPE_VIEW_SELECTED
                },
                RunMode.AI_REPLY,
                2_200L + index
            )
        }

        assertEquals(3, trace.count { it == "dispatch-target" })
        assertFalse(trace.contains("manual-fallback"))
    }

    @Test
    fun accountBrowseEventContentIsNotSilentlyTruncatedAtThreeHundredCharacters() {
        val trace = mutableListOf<String>()
        val logged = mutableListOf<String>()
        val pipeline = pipeline(trace, appendLog = { logged += it })
        val longEventText = "diagnostic-".repeat(120)

        pipeline.processNormalized(
            event(
                AccessibilityEventType.WINDOW_CONTENT_CHANGED,
                TARGET_PACKAGE,
                "android.view.View",
                listOf(longEventText)
            ),
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            RunMode.ACCOUNT_BROWSE,
            2_500L
        )

        assertEquals(1, logged.size)
        assertTrue(logged.single().contains(longEventText))
        assertTrue(logged.single().length > 300)
        assertTrue(logged.single().length < 5_000)
    }

    @Test
    fun persistedPageLabelUsesOnlyControlClassAndNeverEventPayload() {
        val trace = mutableListOf<String>()
        val pageLabels = mutableListOf<String>()
        val pipeline = pipeline(
            trace,
            updateAppPage = { _, page -> pageLabels += page }
        )
        pipeline.processNormalized(
            event(
                AccessibilityEventType.VIEW_TEXT_CHANGED,
                TARGET_PACKAGE,
                "android.widget.EditText",
                listOf("private message body"),
                "private content description"
            ),
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            RunMode.ACCOUNT_BROWSE,
            2_300L
        )
        assertEquals(listOf("EditText"), pageLabels)
        assertFalse(pageLabels.joinToString().contains("private message body"))
        assertFalse(pageLabels.joinToString().contains("private content description"))
    }

    @Test
    fun eventTextCaptureRemainsEnabledOnlyForAccountBrowse() {
        val pipeline = pipeline(mutableListOf())
        assertFalse(pipeline.shouldCaptureText(RunMode.AI_REPLY))
        assertTrue(pipeline.shouldCaptureText(RunMode.ACCOUNT_BROWSE))
    }

    @Test
    fun globalTouchIsRoutedBeforeTargetPackageFiltering() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)

        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, "", ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            RunMode.ACCOUNT_BROWSE,
            3_000L
        )

        assertTrue(trace.contains("dispatch-global-touch"))
        assertFalse(trace.contains("dispatch-target"))
        assertTrue(trace.count { it == "append-log" } >= 2)
    }

    @Test
    fun globalTouchIsClassifiedAndStampedBeforeRuntimeDispatch() {
        val trace = mutableListOf<String>()
        val dispatched = mutableListOf<AccessibilityEventEnvelope>()
        val pipeline = pipeline(
            trace,
            dispatchGlobalTouchEvent = { dispatched += it },
            currentActivityClass = { "com.example.CurrentActivity" },
            currentWindowGeneration = { 17L }
        )

        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, "", ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            RunMode.ACCOUNT_BROWSE,
            3_000L
        )

        val forwarded = dispatched.single()
        assertEquals(InputOrigin.UNKNOWN, forwarded.inputOriginDecision?.origin)
        assertEquals(
            InputOriginEvidence.GLOBAL_TOUCH_EVENT_WITHOUT_ACTOR_PROVENANCE,
            forwarded.inputOriginDecision?.evidence
        )
    }

    @Test
    fun aiReplyGlobalTouchIsNotDispatchedToAnyRuntimeInteractionHandler() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)

        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            RunMode.AI_REPLY,
            2_100L
        )

        assertFalse(trace.contains("dispatch-global-touch"))
        assertFalse(trace.contains("dispatch-target"))
    }

    @Test
    fun nonTargetApplicationEventsAreIgnoredWithoutRuntimeOrLogSideEffects() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)

        pipeline.processNormalized(
            event(AccessibilityEventType.VIEW_CLICKED, "com.other.app"),
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            RunMode.ACCOUNT_BROWSE,
            4_000L
        )

        assertTrue(trace.isEmpty())
    }

    @Test
    fun activityTransitionIsDispatchedAfterDedupButActivityClassIsUpdatedBeforeIt() {
        val trace = mutableListOf<String>()
        var currentActivity = ""
        val pipeline = pipeline(
            trace,
            currentActivityClass = { currentActivity },
            observeWindowStateEvent = { clazz, _, _ -> currentActivity = clazz; trace += "update-activity" }
        )
        val event = event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE,
            "com.immomo.momo.maintab.MaintabActivity")

        pipeline.processNormalized(event, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 5_000L)
        pipeline.processNormalized(event, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 5_100L)

        assertEquals("com.immomo.momo.maintab.MaintabActivity", currentActivity)
        assertEquals(2, trace.count { it == "update-activity" })
        assertEquals(1, trace.count { it == "activity-transition" })
        assertTrue(trace.indexOf("update-activity") < trace.indexOf("append-log"))
        assertTrue(trace.indexOf("append-log") < trace.indexOf("dispatch-target"))
    }

    @Test
    fun duplicateWindowEventsRefreshBoundActivityExactlyOnceBeforeBusinessDelivery() {
        val trace = mutableListOf<String>()
        val tracker = AndroidWindowContextTracker(uptimeMillis = { 0L })
        var generation = 5L
        val pipeline = pipeline(
            trace,
            currentActivityClass = tracker::currentActivityClass,
            currentWindowGeneration = { generation },
            observeWindowStateEvent = { clazz, windowId, pageGeneration ->
                tracker.observeWindowStateEvent(clazz, windowId, pageGeneration)
                trace += "bound:$windowId:$pageGeneration"
            }
        )
        val event = event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE, ACTIVITY)
        pipeline.processNormalized(event.copy(windowId = 12),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.ACCOUNT_BROWSE, 1_000L)
        assertEquals(ACTIVITY, tracker.activityForWindow(12, 5L))
        assertTrue(trace.indexOf("bound:12:5") < trace.indexOf("dispatch-target"))
        generation = 6L
        pipeline.processNormalized(event.copy(windowId = 13),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.ACCOUNT_BROWSE, 1_100L)
        assertEquals("", tracker.activityForWindow(12, 6L))
        assertEquals(ACTIVITY, tracker.activityForWindow(13, 6L))
        assertEquals(1, trace.count { it == "bound:12:5" })
        assertEquals(1, trace.count { it == "bound:13:6" })
        assertEquals(2, trace.count { it == "dispatch-target" })
        assertEquals(1, trace.count { it == "activity-transition" })
    }

    @Test
    fun activityWindowClassWithSingleSuffixUpdatesTheTrackedActivity() {
        val trace = mutableListOf<String>()
        var currentActivity = ""
        val pipeline = pipeline(
            trace,
            currentActivityClass = { currentActivity },
            observeWindowStateEvent = { clazz, _, _ -> currentActivity = clazz; trace += "update-activity" }
        )
        val profileActivity = "com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK"

        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE, profileActivity),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            RunMode.AI_REPLY,
            5_200L
        )

        assertEquals(profileActivity, currentActivity)
        assertTrue(trace.contains("update-activity"))
        assertTrue(isActivityWindowClassName("com.example.MainActivity"))
        assertTrue(isActivityWindowClassName(profileActivity))
        assertFalse(isActivityWindowClassName("android.widget.ActivityToolbar"))
    }

    @Test
    fun browseWindowTransitionUsesPreflightThenTransitionThenLogThenFallback() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)
        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE,
                "com.immomo.momo.maintab.MaintabActivity"),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            RunMode.ACCOUNT_BROWSE,
            6_000L
        )

        assertTrue(trace.indexOf("dispatch-target") < trace.indexOf("activity-transition"))
        assertTrue(trace.indexOf("activity-transition") < trace.indexOf("append-log"))
        assertTrue(trace.indexOf("append-log") < trace.indexOf("manual-fallback"))
    }

    @Test
    fun unifiedEntryPreservesBrowsePreDedupAndAiPostDedupOrder() {
        val trace = mutableListOf<String>()
        val delivered = mutableListOf<com.freedom.core.accessibility.api.UnifiedAccessibilityEvent>()
        val unified = com.freedom.core.accessibility.api.UnifiedAccessibilityEntry { notification ->
            delivered += notification
            trace += when (notification) {
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.TargetApplication -> "unified-target"
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.GlobalTouch -> "unified-touch"
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.ActivityTransition -> "unified-activity"
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.NavigationObservation -> "unified-navigation"
            }
        }
        val pipeline = AndroidAccessibilityEventPipeline(
            targetPackage = { TARGET_PACKAGE },
            freedomPackage = FREEDOM_PACKAGE,
            resolveAppLabel = { "Momo" },
            interactionDiagnostics = { _, _ -> emptyList() },
            handleManualInteractionFallback = { trace += "manual-fallback" },
            updateAppPage = { _, _ -> },
            appendLog = { trace += "append-log" },
            currentActivityClass = { "" },
            observeWindowStateEvent = { _, _, _ -> },
            onBrowseEventLogged = {},
            unifiedAccessibilityEntry = unified
        )
        val browse = event(AccessibilityEventType.VIEW_SCROLLED, TARGET_PACKAGE, "android.view.View")
        pipeline.processNormalized(browse, AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.ACCOUNT_BROWSE, 1000L)
        pipeline.processNormalized(browse, AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.ACCOUNT_BROWSE, 1100L)
        assertEquals(2, delivered.filterIsInstance<com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.TargetApplication>().size)
        assertEquals(1, trace.count { it == "append-log" })
        assertTrue(trace.indexOf("unified-target") < trace.indexOf("append-log"))

        trace.clear()
        delivered.clear()
        pipeline.resetDuplicateWindow()
        val ai = event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE, "android.view.View")
        pipeline.processNormalized(ai, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.AI_REPLY, 2000L)
        pipeline.processNormalized(ai, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.AI_REPLY, 2100L)
        assertEquals(1, delivered.filterIsInstance<com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.TargetApplication>().size)
        assertTrue(trace.indexOf("append-log") < trace.indexOf("unified-target"))
    }

    @Test
    fun unifiedEntryReceivesBrowseTouchAndActivityBeforeAiTarget() {
        val received = mutableListOf<com.freedom.core.accessibility.api.UnifiedAccessibilityEvent>()
        val pipeline = AndroidAccessibilityEventPipeline(
            targetPackage = { TARGET_PACKAGE },
            freedomPackage = FREEDOM_PACKAGE,
            resolveAppLabel = { "Momo" },
            interactionDiagnostics = { _, _ -> emptyList() },
            handleManualInteractionFallback = {},
            updateAppPage = { _, _ -> },
            appendLog = {},
            currentActivityClass = { "" },
            observeWindowStateEvent = { _, _, _ -> },
            onBrowseEventLogged = {},
            unifiedAccessibilityEntry = com.freedom.core.accessibility.api.UnifiedAccessibilityEntry { received += it }
        )
        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, RunMode.ACCOUNT_BROWSE, 1000L
        )
        assertTrue(received.first() is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.GlobalTouch)
        received.clear()
        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE, ACTIVITY),
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 2000L
        )
        assertTrue(received[0] is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.ActivityTransition)
        assertTrue(received[1] is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.TargetApplication)
    }

    @Test
    fun unassignedRuntimeObservationRedactsPayloadAndDoesNotDispatchBusinessEvents() {
        val trace = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val pipeline = pipeline(trace, appendLog = { logs += it })
        assertFalse(pipeline.shouldCaptureText(null))
        pipeline.processNormalized(
            event(AccessibilityEventType.WINDOW_CONTENT_CHANGED, TARGET_PACKAGE,
                eventText = listOf("private-payload"), contentDescription = "private-description"),
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, null
        )
        assertFalse(trace.contains("dispatch-target"))
        assertFalse(trace.contains("manual-fallback"))
        assertTrue(trace.contains("update-app-page"))
        assertTrue(logs.none { it.contains("private-payload") || it.contains("private-description") })
    }

    private fun pipeline(
        trace: MutableList<String>,
        appendLog: (String) -> Unit = { trace += "append-log" },
        updateAppPage: (String, String) -> Unit = { _, _ -> trace += "update-app-page" },
        currentActivityClass: () -> String = { "" },
        observeWindowStateEvent: (String, Int, Long) -> Unit = { _, _, _ -> trace += "update-activity" },
        dispatchGlobalTouchEvent: (AccessibilityEventEnvelope) -> Unit = { trace += "dispatch-global-touch" },
        currentWindowGeneration: () -> Long = { 0L },
        inputOriginClassifier: InputOriginClassifier = InputOriginClassifier(),
        onManualFallback: (AccessibilityEventEnvelope) -> Unit = {}
    ) = AndroidAccessibilityEventPipeline(
        routePolicy = DefaultAccessibilityEventRoutePolicy(),
        targetPackage = { TARGET_PACKAGE },
        freedomPackage = FREEDOM_PACKAGE,
        resolveAppLabel = { packageName -> "label:$packageName" },
        unifiedAccessibilityEntry = com.freedom.core.accessibility.api.UnifiedAccessibilityEntry { event ->
            when (event) {
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.TargetApplication ->
                    trace += "dispatch-target"
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.GlobalTouch ->
                    dispatchGlobalTouchEvent(event.event)
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.ActivityTransition ->
                    trace += "activity-transition"
                is com.freedom.core.accessibility.api.UnifiedAccessibilityEvent.NavigationObservation ->
                    Unit
            }
        },
        interactionDiagnostics = { _, _ -> listOf("test-diagnostics") },
        handleManualInteractionFallback = { trace += "manual-fallback"; onManualFallback(it) },
        updateAppPage = updateAppPage,
        appendLog = appendLog,
        currentActivityClass = currentActivityClass,
        observeWindowStateEvent = observeWindowStateEvent,
        onBrowseEventLogged = { trace += "schedule-scan" },
        currentWindowGeneration = currentWindowGeneration,
        inputOriginClassifier = inputOriginClassifier
    )

    private fun event(
        type: AccessibilityEventType,
        packageName: String,
        className: String = "android.view.View",
        eventText: List<String> = emptyList(),
        contentDescription: String? = null
    ) = AccessibilityEventEnvelope(
        type = type,
        packageName = packageName,
        className = className,
        contentChanges = setOf(AccessibilityContentChange.OTHER),
        eventTimeMillis = 1L,
        eventText = eventText,
        contentDescription = contentDescription,
        source = AccessibilitySourceSnapshot(
            className = className,
            viewId = "",
            text = null,
            contentDescription = null,
            bounds = ScreenRect(0, 0, 10, 10),
            clickable = false,
            longClickable = false,
            scrollable = false,
            editable = false
        ),
        windowId = -1
    )

    private companion object {
        const val TARGET_PACKAGE = "com.immomo.momo"
        const val ACTIVITY = "com.immomo.momo.maintab.MaintabActivity"
        const val FREEDOM_PACKAGE = "com.freedom"
    }
}
