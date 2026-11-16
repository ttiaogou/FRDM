package com.freedom.adapters.accessibility.android

import android.view.accessibility.AccessibilityEvent
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.events.AccessibilitySourceSnapshot
import com.freedom.core.accessibility.events.InputOriginClassifier
import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureSpec
import com.freedom.core.accessibility.events.DefaultAccessibilityEventRoutePolicy
import com.freedom.core.runtime.RunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilityEventPipelineTest {
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
        assertTrue(logged.joinToString().contains("目标事件仍投递到 AI 被动观察链路"))
    }

    @Test
    fun aiReplyTargetControlClickIsFilteredBeforeUnknownArbitration() {
        val trace = mutableListOf<String>()
        val logged = mutableListOf<String>()
        var preemptions = 0
        val pipeline = pipeline(
            trace,
            appendLog = { logged += it; trace += "append-log" },
            dispatchPreemptiveTargetInteraction = { preemptions++; trace += "preemptive-interaction" },
            inputOriginClassifier = InputOriginClassifier()
        )
        val click = event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE, "android.widget.RelativeLayout")

        pipeline.processNormalized(click, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.AI_REPLY, 2_100L)
        pipeline.processNormalized(click, AccessibilityEvent.TYPE_VIEW_CLICKED, RunMode.AI_REPLY, 2_150L)

        assertEquals(0, preemptions)
        assertEquals(1, trace.count { it == "dispatch-target" })
        assertFalse(trace.contains("preemptive-interaction"))
        assertTrue(logged.any { it.contains("APPLICATION_CONTROL_FILTERED") })
    }


    @Test
    fun aiReplyTargetRecyclerViewScrollFeedbackIsFilteredFromUnknownArbitration() {
        val trace = mutableListOf<String>()
        val preempted = mutableListOf<AccessibilityEventEnvelope>()
        val pipeline = pipeline(
            trace,
            dispatchPreemptiveTargetInteraction = { preempted += it; trace += "preemptive-scroll" },
            currentWindowGeneration = { 7L },
            inputOriginClassifier = InputOriginClassifier()
        )
        val scroll = event(
            AccessibilityEventType.VIEW_SCROLLED,
            TARGET_PACKAGE,
            "androidx.recyclerview.widget.RecyclerView"
        )

        pipeline.processNormalized(scroll, AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.AI_REPLY, 2_120L)
        pipeline.processNormalized(scroll, AccessibilityEvent.TYPE_VIEW_SCROLLED, RunMode.AI_REPLY, 2_150L)

        assertEquals(0, preempted.size)
        assertEquals(1, trace.count { it == "dispatch-target" })
    }

    @Test
    fun aiReplyConsumesClickFeedbackMatchedToRegisteredFreedomDispatchWithoutPreemption() {
        val trace = mutableListOf<String>()
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            actionId = "freedom-tap-1",
            packageName = TARGET_PACKAGE,
            activityClass = ACTIVITY,
            pageGeneration = 0L,
            windowId = -1,
            gesture = GestureSpec(
                commands = listOf(GesturePathCommand.MoveTo(GesturePoint(5f, 5f))),
                durationMs = 120L
            ),
            submittedAtUptimeMs = 1L
        )
        classifier.settleDispatchGesture("freedom-tap-1", completed = true, settledAtUptimeMs = 120L)
        val pipeline = pipeline(
            trace,
            currentActivityClass = { ACTIVITY },
            inputOriginClassifier = classifier,
            dispatchPreemptiveTargetInteraction = { trace += "preemptive" }
        )

        pipeline.processNormalized(
            event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE),
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            RunMode.AI_REPLY,
            2_100L
        )

        assertFalse(trace.contains("preemptive"))
        assertTrue(trace.contains("dispatch-target"))
    }

    @Test
    fun aiReplyTreatsUnattributedGlobalTouchAsUnknownAndForwardsItForSafePause() {
        val trace = mutableListOf<String>()
        var forwarded: AccessibilityEventEnvelope? = null
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            actionId = "freedom-swipe-1",
            packageName = TARGET_PACKAGE,
            activityClass = ACTIVITY,
            pageGeneration = 0L,
            windowId = -1,
            gesture = GestureSpec(
                commands = listOf(
                    GesturePathCommand.MoveTo(GesturePoint(5f, 90f)),
                    GesturePathCommand.LineTo(GesturePoint(5f, 10f))
                ),
                durationMs = 180L
            ),
            submittedAtUptimeMs = 1L
        )
        val pipeline = pipeline(
            trace,
            inputOriginClassifier = classifier,
            dispatchGlobalTouchEvent = { forwarded = it; trace += "dispatch-global-touch" }
        )

        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, "").copy(windowId = 44),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            RunMode.AI_REPLY,
            2_200L
        )

        assertTrue(trace.contains("dispatch-global-touch"))
        assertFalse(trace.contains("dispatch-target"))
        assertEquals(44, forwarded?.windowId)
        assertEquals(com.freedom.core.accessibility.events.InputOrigin.UNKNOWN, forwarded?.inputOriginDecision?.origin)
    }

    @Test
    fun globalTouchBoundaryRemainsUnknownWhileTargetApplicationClickIsFiltered() {
        val trace = mutableListOf<String>()
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            actionId = "freedom-tap-overlap",
            packageName = TARGET_PACKAGE,
            activityClass = "",
            pageGeneration = 0L,
            windowId = -1,
            gesture = GestureSpec(
                commands = listOf(GesturePathCommand.MoveTo(GesturePoint(5f, 5f))),
                durationMs = 120L
            ),
            submittedAtUptimeMs = 1L
        )
        val pipeline = pipeline(
            trace,
            currentActivityClass = { ACTIVITY },
            inputOriginClassifier = classifier,
            dispatchPreemptiveTargetInteraction = { trace += "preemptive" }
        )

        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            RunMode.AI_REPLY,
            1L
        )
        pipeline.processNormalized(
            event(AccessibilityEventType.VIEW_CLICKED, TARGET_PACKAGE),
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            RunMode.AI_REPLY,
            1L
        )

        assertTrue(trace.contains("dispatch-global-touch"))
        assertFalse(trace.contains("preemptive"))
    }

    @Test
    fun aiReplyFiltersTargetControlLongContextAndSelectionFeedback() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(
            trace,
            dispatchPreemptiveTargetInteraction = { trace += "preemptive:${it.type}" },
            inputOriginClassifier = InputOriginClassifier()
        )
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

        assertTrue(trace.none { it.startsWith("preemptive:") })
        assertTrue(trace.any { it == "append-log" })
        assertEquals(3, trace.count { it == "dispatch-target" })
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
    fun aiReplyDispatchesGlobalTouchEventsToUserControlArbiter() {
        val trace = mutableListOf<String>()
        val pipeline = pipeline(trace)

        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, "", ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            RunMode.AI_REPLY,
            3_100L
        )
        pipeline.processNormalized(
            event(AccessibilityEventType.TOUCH_INTERACTION_END, "", ""),
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            RunMode.AI_REPLY,
            3_200L
        )

        assertEquals(2, trace.count { it == "dispatch-global-touch" })
        assertFalse(trace.contains("dispatch-target"))
        assertTrue(trace.count { it == "append-log" } >= 4)
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
            updateCurrentActivityClass = { currentActivity = it; trace += "update-activity" }
        )
        val event = event(AccessibilityEventType.WINDOW_STATE_CHANGED, TARGET_PACKAGE,
            "com.immomo.momo.maintab.MaintabActivity")

        pipeline.processNormalized(event, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 5_000L)
        pipeline.processNormalized(event, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, RunMode.AI_REPLY, 5_100L)

        assertEquals("com.immomo.momo.maintab.MaintabActivity", currentActivity)
        assertEquals(3, trace.count { it == "update-activity" })
        assertEquals(1, trace.count { it == "activity-transition" })
        assertTrue(trace.indexOf("update-activity") < trace.indexOf("append-log"))
        assertTrue(trace.indexOf("append-log") < trace.indexOf("dispatch-target"))
    }

    @Test
    fun activityWindowClassWithSingleSuffixUpdatesTheTrackedActivity() {
        val trace = mutableListOf<String>()
        var currentActivity = ""
        val pipeline = pipeline(
            trace,
            currentActivityClass = { currentActivity },
            updateCurrentActivityClass = { currentActivity = it; trace += "update-activity" }
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

    private fun pipeline(
        trace: MutableList<String>,
        appendLog: (String) -> Unit = { trace += "append-log" },
        updateAppPage: (String, String) -> Unit = { _, _ -> trace += "update-app-page" },
        currentActivityClass: () -> String = { "" },
        updateCurrentActivityClass: (String) -> Unit = { trace += "update-activity" },
        dispatchPreemptiveTargetInteraction: (AccessibilityEventEnvelope) -> Unit = {},
        inputOriginClassifier: InputOriginClassifier? = null,
        dispatchGlobalTouchEvent: (AccessibilityEventEnvelope) -> Unit = { trace += "dispatch-global-touch" },
        currentWindowGeneration: () -> Long = { 0L }
    ) = AndroidAccessibilityEventPipeline(
        routePolicy = DefaultAccessibilityEventRoutePolicy(),
        targetPackage = { TARGET_PACKAGE },
        freedomPackage = FREEDOM_PACKAGE,
        resolveAppLabel = { packageName -> "label:$packageName" },
        dispatchTargetApplicationEvent = { trace += "dispatch-target" },
        dispatchGlobalTouchEvent = dispatchGlobalTouchEvent,
        dispatchActivityTransition = { trace += "activity-transition" },
        interactionDiagnostics = { _, _ -> listOf("test-diagnostics") },
        handleManualInteractionFallback = { trace += "manual-fallback" },
        updateAppPage = updateAppPage,
        appendLog = appendLog,
        currentActivityClass = currentActivityClass,
        updateCurrentActivityClass = updateCurrentActivityClass,
        onBrowseEventLogged = { trace += "schedule-scan" },
        dispatchPreemptiveTargetInteraction = dispatchPreemptiveTargetInteraction,
        inputOriginClassifier = inputOriginClassifier,
        currentWindowGeneration = currentWindowGeneration
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
