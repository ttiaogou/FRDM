package com.freedom.features.accountbrowse

import com.freedom.core.accessibility.events.AccessibilityEventType

/** Immutable, platform-neutral state required to classify one target-app interaction event. */
data class AccountBrowseInteractionContext(
    val phase: AccountBrowsePhase,
    val isMainActivity: Boolean,
    val isDetailActivity: Boolean,
    val touchActive: Boolean,
    val lastGlobalTouchEventAt: Long,
    val lastGlobalTouchEventName: String,
    val lastGlobalTouchEventOrigin: String,
    val gestureInFlight: Boolean,
    val ignoreManualEventsUntil: Long,
    val programmaticActionLabel: String,
    val dynamicTabWaitStartedAt: Long,
    val feedRefreshRecoveryActive: Boolean,
    val detailDwellStartedAt: Long,
    val feedRefreshActionAt: Long
)

/**
 * Classifies target-app accessibility events against the active AccountBrowse phase.
 * It consumes semantic page/widget evidence from the adapter, not app-specific identifiers.
 */
class AccountBrowseInteractionEventClassifier {

    fun isTrackedInteractionEvent(eventType: AccessibilityEventType): Boolean =
        eventType == AccessibilityEventType.VIEW_CLICKED ||
            eventType == AccessibilityEventType.VIEW_LONG_CLICKED ||
            eventType == AccessibilityEventType.VIEW_SCROLLED ||
            eventType == AccessibilityEventType.VIEW_TEXT_CHANGED ||
            eventType == AccessibilityEventType.VIEW_TEXT_SELECTION_CHANGED ||
            eventType == AccessibilityEventType.VIEW_CONTEXT_CLICKED

    /** True only when the current event matches an action Freedom itself submitted. */
    fun isProgrammaticInteractionEvent(
        eventType: AccessibilityEventType,
        now: Long,
        context: AccountBrowseInteractionContext
    ): Boolean {
        if (eventType == AccessibilityEventType.VIEW_SCROLLED &&
            (context.gestureInFlight || context.phase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE)
        ) {
            return true
        }
        if (now >= context.ignoreManualEventsUntil) return false
        return when {
            eventType == AccessibilityEventType.VIEW_SCROLLED &&
                (context.programmaticActionLabel.contains("滑动") ||
                    context.programmaticActionLabel.contains("滚动")) -> true
            eventType == AccessibilityEventType.VIEW_CLICKED &&
                (context.programmaticActionLabel.contains("COORDINATE_TAP") ||
                    context.programmaticActionLabel.contains("COORDINATE_DOUBLE_TAP") ||
                    context.programmaticActionLabel.contains("弹窗关闭")) -> true
            else -> false
        }
    }

    /**
     * Returns the existing diagnostic log for startup/layout scrolls that must not
     * trigger manual takeover. Null means normal fallback classification continues.
     */
    fun startupScrollSuppressionLog(
        eventType: AccessibilityEventType,
        now: Long,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): String? = when {
        isDynamicFeedRecoveryStartupScroll(eventType, now, isRecyclerView, context) ->
            "主动刷新恢复仍在等待目标应用动态页就绪；收到列表初始化滚动事件，" +
                "未发现独立触摸证据，不触发人工操作暂停，继续等待页面重新扫描"
        isDynamicTabStartupScroll(eventType, now, isRecyclerView, context) ->
            "目标应用首页刚进入动态标签识别阶段；2 秒缓冲期内检测到列表初始化滚动事件，没有独立触摸证据，不触发暂停"
        isDetailLoadContainerScroll(eventType, isRecyclerView, context) ->
            "详情页面正在初始化；检测到列表滚动/布局事件，但没有独立用户触摸证据，来源标记为待确认，不触发暂停"
        isDetailDwellStartupScroll(eventType, now, isRecyclerView, context) ->
            "动态详情刚确认载入；2 秒缓冲期内检测到列表滚动/布局事件，没有独立触摸证据，不触发暂停"
        isNavigationVerificationScroll(eventType, isRecyclerView, context) ->
            "导航页面正在切换或加载；检测到列表滚动事件，但没有独立用户触摸证据，暂不触发暂停"
        isDynamicFeedRefreshStartupScroll(eventType, now, isRecyclerView, context) ->
            "首页连续双击后检测到列表滚动/回顶事件；按刷新动作反馈处理，不触发暂停"
        else -> null
    }

    /** Same event-origin descriptions previously emitted by the workflow/service. */
    fun classifyOrigin(
        eventType: AccessibilityEventType,
        now: Long,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): String = when {
        isDynamicFeedRecoveryStartupScroll(eventType, now, isRecyclerView, context) ->
            "主动刷新恢复启动阶段；目标应用主页面列表初始化滚动事件且没有独立触摸证据，不按用户操作处理"
        isDynamicTabStartupScroll(eventType, now, isRecyclerView, context) ->
            "目标应用首页刚进入动态标签识别阶段；2 秒内列表初始化滚动事件且没有触摸证据，不按用户操作处理"
        isDetailLoadContainerScroll(eventType, isRecyclerView, context) ->
            "动态详情加载期间列表容器滚动/布局事件；缺少独立触摸证据，不按用户操作处理"
        isDetailDwellStartupScroll(eventType, now, isRecyclerView, context) ->
            "动态详情刚确认载入；2 秒缓冲期内列表滚动/布局事件不单独认定为用户操作"
        isNavigationVerificationScroll(eventType, isRecyclerView, context) ->
            "导航动作后的页面切换/列表加载事件；正在验证页面层级，单独的列表滚动不足以认定为用户操作"
        isDynamicFeedRefreshStartupScroll(eventType, now, isRecyclerView, context) ->
            "首页连续双击刷新后的列表加载/回顶事件；处于刷新动作验证窗口，不按用户操作处理"
        eventType == AccessibilityEventType.VIEW_SCROLLED &&
            context.phase == AccountBrowsePhase.WAIT_FOR_SCROLL_SETTLE ->
            "Freedom 自动滑动确认阶段的列表事件；仅凭列表事件无法区分系统手势反馈与同期手动滚动，不归类为用户操作"
        isProgrammaticInteractionEvent(eventType, now, context) ->
            "${context.programmaticActionLabel.ifBlank { "Freedom 自动动作" }}的系统回调；不按手动操作处理"
        else ->
            "目标应用交互事件；未匹配到 Freedom 对应的自动动作窗口，作为用户手动操作候选"
    }

    private fun hasTouchEvidence(now: Long, context: AccountBrowseInteractionContext): Boolean =
        context.touchActive || now - context.lastGlobalTouchEventAt in 0..GLOBAL_TOUCH_EVENT_ASSOCIATION_MS

    private fun isDynamicTabStartupScroll(
        eventType: AccessibilityEventType,
        now: Long,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): Boolean = DynamicTabStartupScrollPolicy.shouldIgnore(
        isScrollEvent = eventType == AccessibilityEventType.VIEW_SCROLLED,
        isDynamicTabWait = context.phase == AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB,
        isMainActivity = context.isMainActivity,
        isRecyclerView = isRecyclerView,
        elapsedSinceWaitStartedMs = now - context.dynamicTabWaitStartedAt,
        hasTouchEvidence = hasTouchEvidence(now, context)
    )

    private fun isDynamicFeedRecoveryStartupScroll(
        eventType: AccessibilityEventType,
        now: Long,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): Boolean = DynamicFeedRecoveryStartupScrollPolicy.shouldIgnore(
        isScrollEvent = eventType == AccessibilityEventType.VIEW_SCROLLED,
        isRecoveryActive = context.feedRefreshRecoveryActive,
        isWaitingForDynamicTab = context.phase == AccountBrowsePhase.WAIT_FOR_DYNAMIC_TAB,
        isMainActivity = context.isMainActivity,
        isRecyclerView = isRecyclerView,
        hasTouchEvidence = hasTouchEvidence(now, context)
    )

    private fun isDetailLoadContainerScroll(
        eventType: AccessibilityEventType,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): Boolean = eventType == AccessibilityEventType.VIEW_SCROLLED &&
        context.phase == AccountBrowsePhase.WAIT_FOR_DETAIL_LOAD &&
        context.isDetailActivity && isRecyclerView

    private fun isDetailDwellStartupScroll(
        eventType: AccessibilityEventType,
        now: Long,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): Boolean = DetailDwellScrollPolicy.shouldIgnore(
        isScrollEvent = eventType == AccessibilityEventType.VIEW_SCROLLED,
        isDetailActivity = context.isDetailActivity,
        isDetailDwell = context.phase == AccountBrowsePhase.DETAIL_DWELL,
        isRecyclerView = isRecyclerView,
        elapsedSinceDwellStartMs = now - context.detailDwellStartedAt
    )

    private fun isNavigationVerificationScroll(
        eventType: AccessibilityEventType,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): Boolean = eventType == AccessibilityEventType.VIEW_SCROLLED &&
        context.phase == AccountBrowsePhase.VERIFY_NAVIGATION_ACTION && isRecyclerView

    private fun isDynamicFeedRefreshStartupScroll(
        eventType: AccessibilityEventType,
        now: Long,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext
    ): Boolean = eventType == AccessibilityEventType.VIEW_SCROLLED &&
        context.phase == AccountBrowsePhase.VERIFY_DYNAMIC_FEED_REFRESH &&
        isRecyclerView &&
        now - context.feedRefreshActionAt in 0..HOME_REFRESH_PROGRAMMATIC_EVENT_WINDOW_MS

    private companion object {
        const val GLOBAL_TOUCH_EVENT_ASSOCIATION_MS = 1_500L
        const val HOME_REFRESH_PROGRAMMATIC_EVENT_WINDOW_MS = 1_800L
    }
}
