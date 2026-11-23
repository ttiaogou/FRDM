package com.freedom.features.accountbrowse

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.events.AccessibilitySourceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountBrowseInteractionEvidenceClassifierTest {
    private val classifier = AccountBrowseInteractionEvidenceClassifier()


    @Test
    fun programmaticScrollWithIndependentTouchIsLoggedAsOverlappingEvidence() {
        val result = classifier.classifyTargetEvent(
            event(AccessibilityEventType.VIEW_SCROLLED),
            now = 10_500L,
            isRecyclerView = true,
            context(phase = AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE,
                lastGlobalTouchEventAt = 10_200L,
                lastGlobalTouchEventName = "TYPE_TOUCH_INTERACTION_START",
                lastGlobalTouchEventOrigin = "全局触摸事件；系统未提供来源应用"),
            lastProgrammaticScrollAt = 10_000L,
            lastProgrammaticScrollLabel = "Freedom dispatchGesture 自动坐标滑动"
        )

        assertEquals("PROGRAMMATIC_ACTION_ASSOCIATED", result.category)
        assertTrue(result.description.contains("近期独立全局触摸证据"))
        assertTrue(result.description.contains("无法区分"))
    }

    @Test
    fun startupListLayoutScrollIsLoggedAsStartupEvidenceNotManualCandidate() {
        val result = classifier.classifyTargetEvent(
            event = event(AccessibilityEventType.VIEW_SCROLLED),
            now = 2_000L,
            isRecyclerView = true,
            context = context(
                phase = AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB,
                dynamicTabWaitStartedAt = 1_000L
            ),
            lastProgrammaticScrollAt = 0L,
            lastProgrammaticScrollLabel = ""
        )

        assertEquals("STARTUP_OR_LAYOUT_EVENT", result.category)
        assertTrue(result.description.contains("不触发暂停"))
    }

    @Test
    fun closeSemanticClickWithRecentTouchIsOnlyACorrelatedCloseCandidate() {
        val result = classifier.classifyTargetEvent(
            event = event(
                type = AccessibilityEventType.VIEW_CLICKED,
                source = source(viewId = "momo:id/btn_close", text = "关闭")
            ),
            now = 10_000L,
            isRecyclerView = false,
            context = context(lastGlobalTouchEventAt = 9_800L),
            lastProgrammaticScrollAt = 0L,
            lastProgrammaticScrollLabel = ""
        )

        assertEquals("TOUCH_CORRELATED_CLOSE_CONTROL_CLICK", result.category)
        assertTrue(result.hasIndependentTouchEvidence)
        assertTrue(result.isCloseControlCandidate)
        assertTrue(result.description.contains("不能确认是否关闭了广告"))
    }

    @Test
    fun closeSemanticClickWithoutTouchIsNotAssertedAsHumanOrAdClose() {
        val result = classifier.classifyTargetEvent(
            event = event(
                type = AccessibilityEventType.VIEW_CLICKED,
                source = source(viewId = "web_close_icon", description = "Close")
            ),
            now = 10_000L,
            isRecyclerView = false,
            context = context(),
            lastProgrammaticScrollAt = 0L,
            lastProgrammaticScrollLabel = ""
        )

        assertEquals("CLOSE_CONTROL_CLICK_UNCONFIRMED", result.category)
        assertTrue(!result.hasIndependentTouchEvidence)
        assertTrue(result.description.contains("不能确认是否关闭了广告"))
    }

    @Test
    fun recyclerScrollWithRecentGlobalTouchIsTouchCorrelated() {
        val result = classifier.classifyTargetEvent(
            event = event(AccessibilityEventType.VIEW_SCROLLED),
            now = 10_000L,
            isRecyclerView = true,
            context = context(lastGlobalTouchEventAt = 9_500L),
            lastProgrammaticScrollAt = 9_800L,
            lastProgrammaticScrollLabel = "Freedom dispatchGesture 自动坐标滑动"
        )

        assertEquals("TOUCH_CORRELATED_SCROLL", result.category)
        assertTrue(result.hasIndependentTouchEvidence)
    }

    @Test
    fun touchSignalGeneratedDuringFreedomGestureIsNotIndependentTouchEvidence() {
        val result = classifier.classifyTargetEvent(
            event = event(AccessibilityEventType.VIEW_SCROLLED),
            now = 10_000L,
            isRecyclerView = true,
            context = context(
                lastGlobalTouchEventAt = 9_900L,
                lastGlobalTouchEventOrigin = "Freedom 自动滑动期间收到；具体来源待确认"
            ),
            lastProgrammaticScrollAt = 0L,
            lastProgrammaticScrollLabel = ""
        )

        assertEquals("UNATTRIBUTED_TARGET_SCROLL", result.category)
        assertTrue(!result.hasIndependentTouchEvidence)
    }

    @Test
    fun scrollAfterProgrammaticDiagnosticWindowIsMarkedPossibleButNotSuppressed() {
        val result = classifier.classifyTargetEvent(
            event = event(AccessibilityEventType.VIEW_SCROLLED),
            now = 13_000L,
            isRecyclerView = true,
            context = context(
                phase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE,
                ignoreManualEventsUntil = 11_000L,
                programmaticActionLabel = "Freedom dispatchGesture 自动坐标滑动"
            ),
            lastProgrammaticScrollAt = 10_000L,
            lastProgrammaticScrollLabel = "Freedom dispatchGesture 自动坐标滑动"
        )

        assertEquals("POSSIBLE_DELAYED_PROGRAMMATIC_SCROLL", result.category)
        assertTrue(result.description.contains("本诊断不改变原有人工接管判断"))
    }

    @Test
    fun uncorrelatedRecyclerScrollRemainsUnknownCandidate() {
        val result = classifier.classifyTargetEvent(
            event = event(AccessibilityEventType.VIEW_SCROLLED),
            now = 20_000L,
            isRecyclerView = true,
            context = context(),
            lastProgrammaticScrollAt = 0L,
            lastProgrammaticScrollLabel = ""
        )

        assertEquals("UNATTRIBUTED_TARGET_SCROLL", result.category)
        assertTrue(result.description.contains("来源未知"))
        assertTrue(result.description.contains("人工操作候选"))
    }

    @Test
    fun pageTransitionAfterCloseCandidateDoesNotClaimAdWasClosed() {
        val result = classifier.describePageTransition(
            now = 12_000L,
            lastGlobalTouchEventAt = 0L,
            lastGlobalTouchEventName = "",
            lastGlobalTouchEventOrigin = "",
            lastTargetInteractionAt = 11_500L,
            lastTargetInteractionCategory = "CLOSE_CONTROL_CLICK_UNCONFIRMED",
            lastTargetInteractionDescription = "关闭语义控件点击候选"
        )

        assertTrue(result.contains("疑似关闭控件点击后的页面变化"))
        assertTrue(result.contains("不能确认是广告关闭"))
    }

    @Test
    fun pageTransitionAfterTouchOnlyReportsTemporalCorrelation() {
        val result = classifier.describePageTransition(
            now = 12_000L,
            lastGlobalTouchEventAt = 11_700L,
            lastGlobalTouchEventName = "TYPE_TOUCH_INTERACTION_END",
            lastGlobalTouchEventOrigin = "全局触摸事件；系统未提供来源应用",
            lastTargetInteractionAt = 0L,
            lastTargetInteractionCategory = "",
            lastTargetInteractionDescription = ""
        )

        assertTrue(result.contains("全局触摸事件"))
        assertTrue(result.contains("无法定位具体按钮"))
    }

    @Test
    fun pageTransitionWithoutRecentEvidenceIsUnknown() {
        val result = classifier.describePageTransition(
            now = 20_000L,
            lastGlobalTouchEventAt = 0L,
            lastGlobalTouchEventName = "",
            lastGlobalTouchEventOrigin = "",
            lastTargetInteractionAt = 0L,
            lastTargetInteractionCategory = "",
            lastTargetInteractionDescription = ""
        )

        assertTrue(result.contains("来源未知"))
    }

    private fun context(
        phase: AccountBrowsePhase = AccountBrowsePhase.WAIT_FOR_FEED_STABLE,
        touchActive: Boolean = false,
        lastGlobalTouchEventAt: Long = 0L,
        lastGlobalTouchEventName: String = "TYPE_TOUCH_INTERACTION_END",
        lastGlobalTouchEventOrigin: String = "全局触摸事件；系统未提供来源应用",
        gestureInFlight: Boolean = false,
        ignoreManualEventsUntil: Long = 0L,
        programmaticActionLabel: String = "",
        dynamicTabWaitStartedAt: Long = 0L
    ) = AccountBrowseInteractionContext(
        phase = phase,
        isMainActivity = true,
        isDetailActivity = false,
        touchActive = touchActive,
        lastGlobalTouchEventAt = lastGlobalTouchEventAt,
        lastGlobalTouchEventName = lastGlobalTouchEventName,
        lastGlobalTouchEventOrigin = lastGlobalTouchEventOrigin,
        gestureInFlight = gestureInFlight,
        ignoreManualEventsUntil = ignoreManualEventsUntil,
        programmaticActionLabel = programmaticActionLabel,
        dynamicTabWaitStartedAt = dynamicTabWaitStartedAt,
        feedRefreshRecoveryActive = false,
        detailDwellStartedAt = 0L,
        feedRefreshActionAt = 0L
    )

    private fun event(
        type: AccessibilityEventType,
        source: AccessibilitySourceSnapshot? = null
    ) = AccessibilityEventEnvelope(
        type = type,
        packageName = "com.immomo.momo",
        className = source?.className ?: "androidx.recyclerview.widget.RecyclerView",
        contentChanges = emptySet(),
        eventTimeMillis = 0L,
        eventText = emptyList(),
        contentDescription = null,
        source = source
    )

    private fun source(
        viewId: String = "",
        text: String? = null,
        description: String? = null
    ) = AccessibilitySourceSnapshot(
        className = "android.widget.ImageButton",
        viewId = viewId,
        text = text,
        contentDescription = description,
        bounds = null,
        clickable = true,
        longClickable = false,
        scrollable = false,
        editable = false
    )
}
