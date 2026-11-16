package com.freedom.core.accessibility.events

import com.freedom.core.accessibility.api.GesturePathCommand
import com.freedom.core.accessibility.api.GesturePoint
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureSpec
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputOriginClassifierTest {
    @Test
    fun manualReviewLatchSurvivesLedgerClearUntilExplicitAcknowledgement() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "review-test", TARGET_PACKAGE, ACTIVITY, 7L, 12, swipe(), 10_000L
        )
        classifier.requireManualReview("unattributed touch")
        classifier.clear()
        assertTrue(classifier.isManualReviewRequired())
        assertEquals("unattributed touch", classifier.manualReviewReason())

        classifier.acknowledgeManualReview("explicit new run")
        assertFalse(classifier.isManualReviewRequired())
        assertEquals("", classifier.manualReviewReason())
    }

    @Test
    fun clickInsideRegisteredTapTargetIsSelfInjected() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            actionId = "tap-1",
            packageName = TARGET_PACKAGE,
            activityClass = ACTIVITY,
            pageGeneration = 7L,
            windowId = 12,
            gesture = tap(242f, 883f),
            submittedAtUptimeMs = 10_000L
        )
        classifier.settleDispatchGesture("tap-1", completed = true, settledAtUptimeMs = 10_180L)

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_CLICKED,
                eventTime = 10_180L,
                bounds = ScreenRect(0, 832, 720, 979),
                windowId = 12
            ),
            currentActivityClass = ACTIVITY,
            currentPageGeneration = 7L
        )

        assertEquals(InputOrigin.SELF_INJECTED, decision.origin)
        assertEquals(InputOriginEvidence.FREEDOM_DISPATCH_GESTURE_LEDGER_MATCH, decision.evidence)
        assertEquals("tap-1", decision.actionId)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun clickOutsideRegisteredTapGeometryIsApplicationInternal() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-2", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.settleDispatchGesture("tap-2", completed = true, settledAtUptimeMs = 10_180L)

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_180L, ScreenRect(0, 100, 720, 300), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertFalse(decision.shouldYieldAutomation)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
    }

    @Test
    fun clickFromDifferentPageGenerationCannotMatchOldDispatch() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-3", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.settleDispatchGesture("tap-3", completed = true, settledAtUptimeMs = 10_180L)

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_180L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            8L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
    }

    @Test
    fun clickFromDifferentWindowCannotMatchOldDispatch() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-4", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.settleDispatchGesture("tap-4", completed = true, settledAtUptimeMs = 10_180L)

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_180L, ScreenRect(0, 832, 720, 979), 13),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
    }

    @Test
    fun clickOutsideActionTimeWindowCannotMatch() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-5", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.settleDispatchGesture("tap-5", completed = true, settledAtUptimeMs = 10_180L)

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 20_000L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
    }

    @Test
    fun ownFeedbackCanCorrelateBeforePlatformCallbackCompletes() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-pending", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_120L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.SELF_INJECTED, decision.origin)
        assertEquals("tap-pending", decision.actionId)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun cancelledDispatchControlFeedbackRemainsApplicationInternal() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-cancelled", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.settleDispatchGesture("tap-cancelled", completed = false, settledAtUptimeMs = 10_100L)

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_120L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun scrollFromRegisteredSwipeIsSelfInjected() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            actionId = "swipe-1",
            packageName = TARGET_PACKAGE,
            activityClass = ACTIVITY,
            pageGeneration = 7L,
            windowId = 12,
            gesture = swipe(),
            submittedAtUptimeMs = 10_000L
        )
        classifier.settleDispatchGesture("swipe-1", completed = true, settledAtUptimeMs = 10_160L)

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 10_160L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 12,
                scrollable = true
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.SELF_INJECTED, decision.origin)
        assertEquals("swipe-1", decision.actionId)
    }

    @Test
    fun delayedScrollFeedbackAfterCompletedSwipeRemainsSelfInjected() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            actionId = "swipe-delayed-feedback",
            packageName = TARGET_PACKAGE,
            activityClass = ACTIVITY,
            pageGeneration = 7L,
            windowId = 12,
            gesture = swipe(),
            submittedAtUptimeMs = 10_000L
        )
        // Android completes the 450 ms stroke at 10_450 ms. The reproduced failure arrived
        // more than 400 ms after completion and just beyond the old submit-relative deadline.
        classifier.settleDispatchGesture(
            "swipe-delayed-feedback",
            completed = true,
            settledAtUptimeMs = 10_450L
        )

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 10_883L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 12,
                scrollable = true
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.SELF_INJECTED, decision.origin)
        assertEquals("swipe-delayed-feedback", decision.actionId)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun navigationTabTapCannotClaimScrollablePageFeedback() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            actionId = "message-tab-refresh",
            packageName = TARGET_PACKAGE,
            activityClass = ACTIVITY,
            pageGeneration = 7L,
            windowId = 12,
            gesture = tap(334f, 1519f, GestureFeedbackIntent.NAVIGATION_TAB),
            submittedAtUptimeMs = 10_000L
        )
        classifier.settleDispatchGesture(
            "message-tab-refresh",
            completed = true,
            settledAtUptimeMs = 10_090L
        )

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 13_700L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 12,
                scrollable = true
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
        assertTrue(decision.actionId == null)
    }

    @Test
    fun ordinaryTapCannotClaimAdjacentRecyclerViewRefresh() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "ordinary-tap", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(334f, 1519f), 10_000L
        )
        classifier.settleDispatchGesture("ordinary-tap", completed = true, settledAtUptimeMs = 10_090L)

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 10_200L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 12,
                scrollable = true
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
    }

    @Test
    fun navigationTabTapCannotClaimDistantOrLateScrollFeedback() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "message-tab-bounded", TARGET_PACKAGE, ACTIVITY, 7L, 12,
            tap(334f, 1519f, GestureFeedbackIntent.NAVIGATION_TAB), 10_000L
        )
        classifier.settleDispatchGesture("message-tab-bounded", completed = true, settledAtUptimeMs = 10_090L)

        val distant = classifier.classify(
            event(AccessibilityEventType.VIEW_SCROLLED, 10_200L, ScreenRect(0, 156, 720, 1300), 12, scrollable = true),
            ACTIVITY,
            7L
        )
        val late = classifier.classify(
            event(AccessibilityEventType.VIEW_SCROLLED, 14_591L, ScreenRect(0, 156, 720, 1486), 12, scrollable = true),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, distant.origin)
        assertEquals(InputOrigin.APPLICATION_INTERNAL, late.origin)
    }

    @Test
    fun navigationTabTapNeverOwnsScrollFeedbackAcrossPageGenerationsOrWindows() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "message-tab-generation-bound", TARGET_PACKAGE, ACTIVITY, 7L, 12,
            tap(334f, 1519f, GestureFeedbackIntent.NAVIGATION_TAB), 10_000L
        )
        classifier.settleDispatchGesture(
            "message-tab-generation-bound", completed = true, settledAtUptimeMs = 10_090L
        )

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 10_200L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 13,
                scrollable = true
            ),
            ACTIVITY,
            8L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
        assertEquals(null, decision.actionId)
    }

    @Test
    fun uncorrelatedScrollAfterNavigationTapIsTargetApplicationFeedback() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "message-tab-touch-overlap", TARGET_PACKAGE, ACTIVITY, 7L, 12,
            tap(334f, 1519f, GestureFeedbackIntent.NAVIGATION_TAB), 10_000L
        )
        classifier.settleDispatchGesture(
            "message-tab-touch-overlap", completed = true, settledAtUptimeMs = 10_090L
        )
        classifier.classify(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, 10_100L, null, -1),
            ACTIVITY,
            7L
        )

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 10_200L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 12,
                scrollable = true
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
    }

    @Test
    fun scrollFeedbackBeyondPostCompletionGraceIsApplicationInternalWhenNotOverlapping() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "swipe-too-late",
            TARGET_PACKAGE,
            ACTIVITY,
            7L,
            12,
            swipe(),
            10_000L
        )
        classifier.settleDispatchGesture("swipe-too-late", completed = true, settledAtUptimeMs = 10_450L)

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 11_851L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 12,
                scrollable = true
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun touchBoundaryRemainsUnknownButTargetScrollFeedbackIsApplicationInternal() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "swipe-touch-overlap", TARGET_PACKAGE, ACTIVITY, 7L, 12, swipe(), 10_000L
        )
        classifier.settleDispatchGesture(
            "swipe-touch-overlap",
            completed = true,
            settledAtUptimeMs = 10_450L
        )

        val touch = classifier.classify(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, 10_500L, null, -1),
            ACTIVITY,
            7L
        )
        val scroll = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                eventTime = 10_883L,
                bounds = ScreenRect(0, 156, 720, 1486),
                windowId = 12,
                scrollable = true
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.UNKNOWN, touch.origin)
        assertEquals(InputOrigin.APPLICATION_INTERNAL, scroll.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, scroll.evidence)
        assertFalse(scroll.shouldYieldAutomation)
    }

    @Test
    fun touchInteractionBoundaryRemainsUnknownEvenWhileFreedomGestureIsActive() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "swipe-active", TARGET_PACKAGE, ACTIVITY, 7L, 12, swipe(), 10_000L
        )

        val decision = classifier.classify(
            event(
                AccessibilityEventType.TOUCH_INTERACTION_START,
                eventTime = 10_100L,
                bounds = null,
                windowId = -1
            ),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.UNKNOWN, decision.origin)
        assertEquals(InputOriginEvidence.GLOBAL_TOUCH_EVENT_WITHOUT_ACTOR_PROVENANCE, decision.evidence)
        assertTrue(decision.shouldYieldAutomation)
    }

    @Test
    fun globalTouchBoundaryRemainsUnknownButTargetClickFeedbackIsApplicationInternal() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-overlap", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )

        val touch = classifier.classify(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, 10_090L, null, -1),
            ACTIVITY,
            7L
        )
        val click = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_180L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.UNKNOWN, touch.origin)
        assertEquals(InputOrigin.APPLICATION_INTERNAL, click.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, click.evidence)
        assertFalse(click.shouldYieldAutomation)
    }

    @Test
    fun clickAfterFeedbackGraceWindowIsNotAttributedToOldAction() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-expired", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.settleDispatchGesture("tap-expired", completed = true, settledAtUptimeMs = 10_180L)

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_581L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun knownWindowMustMatchWhenEventWindowIsUnavailable() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-no-window", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_180L, ScreenRect(0, 832, 720, 979), -1),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
    }

    @Test
    fun ambiguousFreedomActionCorrelationDoesNotMakeTargetControlExternal() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-multi-1", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.registerDispatchGesture(
            "tap-multi-2", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_050L
        )

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_180L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun settledActionBecomesRetainedAndCannotRemainACandidate() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-retained", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )
        classifier.settleDispatchGesture("tap-retained", completed = true, settledAtUptimeMs = 10_100L)

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_700L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
    }

    @Test
    fun navigationEntryTransitionClaimsOnlyFirstScrollableFeedbackOnExactTargetPage() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "open-greetings", TARGET_PACKAGE, ACTIVITY, 7L, 12,
            tap(242f, 883f, GestureFeedbackIntent.NAVIGATION_ENTRY), 10_000L
        )
        classifier.settleDispatchGesture("open-greetings", completed = true, settledAtUptimeMs = 10_100L)
        classifier.noteActivityTransition(
            packageName = TARGET_PACKAGE,
            targetActivityClass = "com.immomo.momo.sayhi.NewSayHiSessionListActivity",
            sourcePageGeneration = 7L,
            targetPageGeneration = 8L,
            targetWindowId = 21,
            transitionAtUptimeMs = 10_180L
        )

        val first = classifier.classify(
            event(AccessibilityEventType.VIEW_SCROLLED, 10_220L, ScreenRect(0, 100, 720, 1_400), 21, scrollable = true),
            "com.immomo.momo.sayhi.NewSayHiSessionListActivity",
            8L
        )
        val second = classifier.classify(
            event(AccessibilityEventType.VIEW_SCROLLED, 10_240L, ScreenRect(0, 100, 720, 1_400), 21, scrollable = true),
            "com.immomo.momo.sayhi.NewSayHiSessionListActivity",
            8L
        )

        assertEquals(InputOrigin.SELF_INJECTED, first.origin)
        assertEquals(InputOriginEvidence.AUTONOMOUS_NAVIGATION_TRANSITION_MATCH, first.evidence)
        assertEquals("open-greetings", first.actionId)
        assertEquals(InputOrigin.APPLICATION_INTERNAL, second.origin)
    }

    @Test
    fun ambiguousNavigationTicketsDoNotTurnTargetListInitializationIntoUnknown() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "open-list-a", TARGET_PACKAGE, ACTIVITY, 6L, 12,
            tap(242f, 883f, GestureFeedbackIntent.NAVIGATION_ENTRY), 10_000L
        )
        classifier.settleDispatchGesture("open-list-a", completed = true, settledAtUptimeMs = 10_080L)
        classifier.noteActivityTransition(
            TARGET_PACKAGE, "com.immomo.momo.message.MessageListActivity", 6L, 8L, 21, 10_100L
        )
        classifier.registerDispatchGesture(
            "open-list-b", TARGET_PACKAGE, ACTIVITY, 7L, 13,
            tap(334f, 1_519f, GestureFeedbackIntent.NAVIGATION_ENTRY), 10_200L
        )
        classifier.settleDispatchGesture("open-list-b", completed = true, settledAtUptimeMs = 10_280L)
        classifier.noteActivityTransition(
            TARGET_PACKAGE, "com.immomo.momo.message.MessageListActivity", 7L, 8L, 21, 10_300L
        )

        val decision = classifier.classify(
            event(
                AccessibilityEventType.VIEW_SCROLLED,
                10_400L,
                ScreenRect(0, 156, 720, 336),
                21,
                scrollable = true
            ),
            "com.immomo.momo.message.MessageListActivity",
            8L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun globalTouchOnNavigationTargetPageInvalidatesTransitionTicket() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "open-greetings-touch", TARGET_PACKAGE, ACTIVITY, 7L, 12,
            tap(242f, 883f, GestureFeedbackIntent.NAVIGATION_ENTRY), 10_000L
        )
        classifier.settleDispatchGesture("open-greetings-touch", completed = true, settledAtUptimeMs = 10_100L)
        classifier.noteActivityTransition(
            TARGET_PACKAGE,
            "com.immomo.momo.sayhi.NewSayHiSessionListActivity",
            7L,
            8L,
            21,
            10_180L
        )
        classifier.classify(
            event(AccessibilityEventType.TOUCH_INTERACTION_START, 10_200L, null, -1),
            "com.immomo.momo.sayhi.NewSayHiSessionListActivity",
            8L
        )

        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_SCROLLED, 10_240L, ScreenRect(0, 100, 720, 1_400), 21, scrollable = true),
            "com.immomo.momo.sayhi.NewSayHiSessionListActivity",
            8L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
    }

    @Test
    fun sameAutonomousTapFeedbackCannotBeConsumedTwice() {
        val classifier = InputOriginClassifier()
        classifier.registerDispatchGesture(
            "tap-once", TARGET_PACKAGE, ACTIVITY, 7L, 12, tap(242f, 883f), 10_000L
        )

        val first = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_100L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )
        val second = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_120L, ScreenRect(0, 832, 720, 979), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.SELF_INJECTED, first.origin)
        assertEquals(InputOrigin.APPLICATION_INTERNAL, second.origin)
    }

    @Test
    fun uncorrelatedTargetClickDoesNotBecomePhysicalUserByInference() {
        val classifier = InputOriginClassifier()
        val decision = classifier.classify(
            event(AccessibilityEventType.VIEW_CLICKED, 10_180L, ScreenRect(10, 10, 100, 100), 12),
            ACTIVITY,
            7L
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun targetApplicationControlFeedbackIsFilteredEvenWithInvalidGeometry() {
        val classifier = InputOriginClassifier()
        val invalidBounds = event(
            AccessibilityEventType.VIEW_SCROLLED,
            10_180L,
            ScreenRect(720, 156, 720, 1_486),
            12,
            scrollable = true
        )

        val decision = classifier.classify(
            invalidBounds,
            ACTIVITY,
            7L,
            targetPackage = TARGET_PACKAGE
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun targetApplicationControlFeedbackIsFilteredEvenWhenSourceSnapshotIsMissing() {
        val classifier = InputOriginClassifier()
        val targetEvent = event(
            AccessibilityEventType.VIEW_SCROLLED,
            10_180L,
            null,
            12
        ).copy(source = null)

        val decision = classifier.classify(
            targetEvent,
            ACTIVITY,
            7L,
            targetPackage = TARGET_PACKAGE
        )

        assertEquals(InputOrigin.APPLICATION_INTERNAL, decision.origin)
        assertEquals(InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK, decision.evidence)
        assertFalse(decision.shouldYieldAutomation)
    }

    @Test
    fun eventFromAnotherPackageIsNotFilteredAsTargetApplicationInternal() {
        val classifier = InputOriginClassifier()
        val otherAppEvent = event(
            AccessibilityEventType.VIEW_CLICKED,
            10_180L,
            ScreenRect(10, 10, 100, 100),
            12
        ).copy(packageName = "com.other.app")

        val decision = classifier.classify(
            otherAppEvent,
            ACTIVITY,
            7L,
            targetPackage = TARGET_PACKAGE
        )

        assertEquals(InputOrigin.UNKNOWN, decision.origin)
        assertTrue(decision.shouldYieldAutomation)
    }

    private fun tap(
        x: Float,
        y: Float,
        feedbackIntent: GestureFeedbackIntent = GestureFeedbackIntent.STANDARD
    ) = GestureSpec(
        commands = listOf(GesturePathCommand.MoveTo(GesturePoint(x, y))),
        durationMs = 180L,
        feedbackIntent = feedbackIntent
    )

    private fun swipe() = GestureSpec(
        commands = listOf(
            GesturePathCommand.MoveTo(GesturePoint(360f, 1_300f)),
            GesturePathCommand.LineTo(GesturePoint(360f, 500f))
        ),
        durationMs = 450L
    )

    private fun event(
        type: AccessibilityEventType,
        eventTime: Long,
        bounds: ScreenRect?,
        windowId: Int,
        scrollable: Boolean = false
    ) = AccessibilityEventEnvelope(
        type = type,
        packageName = TARGET_PACKAGE,
        className = "androidx.recyclerview.widget.RecyclerView",
        contentChanges = setOf(AccessibilityContentChange.OTHER),
        eventTimeMillis = eventTime,
        eventText = emptyList(),
        contentDescription = null,
        source = AccessibilitySourceSnapshot(
            className = "androidx.recyclerview.widget.RecyclerView",
            viewId = "com.immomo.momo:id/recyclerview",
            text = null,
            contentDescription = null,
            bounds = bounds,
            clickable = type == AccessibilityEventType.VIEW_CLICKED,
            longClickable = false,
            scrollable = scrollable,
            editable = false
        ),
        windowId = windowId
    )

    private companion object {
        const val TARGET_PACKAGE = "com.immomo.momo"
        const val ACTIVITY = "com.immomo.momo.maintab.MaintabActivity"
    }
}
