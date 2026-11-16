package com.freedom.features.accountbrowse

import com.freedom.core.accessibility.events.AccessibilityEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseInteractionEventClassifierTest {
    private val classifier = AccountBrowseInteractionEventClassifier()

    @Test
    fun trackedInteractionEventsIncludeClickAndScrollButNotWindowContentChange() {
        assertTrue(classifier.isTrackedInteractionEvent(AccessibilityEventType.VIEW_CLICKED))
        assertTrue(classifier.isTrackedInteractionEvent(AccessibilityEventType.VIEW_SCROLLED))
        assertFalse(classifier.isTrackedInteractionEvent(AccessibilityEventType.WINDOW_CONTENT_CHANGED))
    }

    @Test
    fun scrollDuringFreedomGestureIsClassifiedAsProgrammatic() {
        val context = context(
            phase = AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE,
            gestureInFlight = true
        )
        assertTrue(
            classifier.isProgrammaticInteractionEvent(
                AccessibilityEventType.VIEW_SCROLLED,
                now = 10_000L,
                context = context
            )
        )
        assertTrue(
            classifier.classifyOrigin(
                AccessibilityEventType.VIEW_SCROLLED,
                10_000L,
                true,
                context
            ).contains("Freedom 自动滑动确认阶段")
        )
    }

    @Test
    fun dynamicTabStartupScrollIsSuppressedOnlyDuringGracePeriodWithoutTouchEvidence() {
        val context = context(
            phase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB,
            isMainActivity = true,
            dynamicTabWaitStartedAt = 1_000L
        )
        val scrollEvent = AccessibilityEventType.VIEW_SCROLLED
        assertTrue(
            classifier.startupScrollSuppressionLog(scrollEvent, 2_000L, true, context)
                ?.contains("没有独立触摸证据") == true
        )
        assertEquals(null, classifier.startupScrollSuppressionLog(scrollEvent, 3_001L, true, context))
        assertEquals(
            null,
            classifier.startupScrollSuppressionLog(
                scrollEvent,
                2_000L,
                true,
                context.copy(touchActive = true)
            )
        )
    }

    @Test
    fun detailDwellStartupGraceSuppressesLayoutScrollButLaterScrollRemainsManualCandidate() {
        val context = context(
            phase = AccountBrowsePhase.DETAIL_DWELL,
            isDetailActivity = true,
            detailDwellStartedAt = 10_000L
        )
        val scrollEvent = AccessibilityEventType.VIEW_SCROLLED
        assertTrue(
            classifier.startupScrollSuppressionLog(scrollEvent, 11_999L, true, context)
                ?.contains("动态详情刚确认载入") == true
        )
        assertEquals(null, classifier.startupScrollSuppressionLog(scrollEvent, 12_000L, true, context))
        assertTrue(
            classifier.classifyOrigin(scrollEvent, 12_000L, true, context)
                .contains("作为用户手动操作候选")
        )
    }

    @Test
    fun coordinateTapOnlySuppressesClicksInsideItsProgrammaticWindow() {
        val context = context(
            phase = AccountBrowsePhase.WAIT_FOR_CARD_OPEN,
            ignoreManualEventsUntil = 5_000L,
            programmaticActionLabel = "Freedom COORDINATE_TAP 动态卡片"
        )
        assertTrue(
            classifier.isProgrammaticInteractionEvent(
                AccessibilityEventType.VIEW_CLICKED,
                now = 4_999L,
                context = context
            )
        )
        assertFalse(
            classifier.isProgrammaticInteractionEvent(
                AccessibilityEventType.VIEW_CLICKED,
                now = 5_000L,
                context = context
            )
        )
    }

    @Test
    fun coordinateDoubleTapOnlySuppressesClicksInsideItsProgrammaticWindow() {
        val context = context(
            phase = AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH,
            ignoreManualEventsUntil = 8_000L,
            programmaticActionLabel = "Freedom COORDINATE_DOUBLE_TAP 首页刷新动态"
        )
        assertTrue(
            classifier.isProgrammaticInteractionEvent(
                AccessibilityEventType.VIEW_CLICKED,
                now = 7_999L,
                context = context
            )
        )
        assertFalse(
            classifier.isProgrammaticInteractionEvent(
                AccessibilityEventType.VIEW_CLICKED,
                now = 8_000L,
                context = context
            )
        )
    }

    private fun context(
        phase: AccountBrowsePhase = AccountBrowsePhase.IDLE,
        isMainActivity: Boolean = false,
        isDetailActivity: Boolean = false,
        touchActive: Boolean = false,
        lastGlobalTouchEventAt: Long = 0L,
        lastGlobalTouchEventName: String = "TYPE_TOUCH_INTERACTION_END",
        lastGlobalTouchEventOrigin: String = "全局触摸事件；系统未提供来源应用",
        gestureInFlight: Boolean = false,
        ignoreManualEventsUntil: Long = 0L,
        programmaticActionLabel: String = "",
        dynamicTabWaitStartedAt: Long = 0L,
        feedRefreshRecoveryActive: Boolean = false,
        detailDwellStartedAt: Long = 0L,
        feedRefreshActionAt: Long = 0L
    ) = AccountBrowseInteractionContext(
        phase = phase,
        isMainActivity = isMainActivity,
        isDetailActivity = isDetailActivity,
        touchActive = touchActive,
        lastGlobalTouchEventAt = lastGlobalTouchEventAt,
        lastGlobalTouchEventName = lastGlobalTouchEventName,
        lastGlobalTouchEventOrigin = lastGlobalTouchEventOrigin,
        gestureInFlight = gestureInFlight,
        ignoreManualEventsUntil = ignoreManualEventsUntil,
        programmaticActionLabel = programmaticActionLabel,
        dynamicTabWaitStartedAt = dynamicTabWaitStartedAt,
        feedRefreshRecoveryActive = feedRefreshRecoveryActive,
        detailDwellStartedAt = detailDwellStartedAt,
        feedRefreshActionAt = feedRefreshActionAt
    )
}
