package com.freedom.features.accountbrowse

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType

/** Diagnostic-only result. It must never decide whether AccountBrowse pauses or resumes. */
data class AccountBrowseInteractionEvidence(
    val category: String,
    val description: String,
    val hasIndependentTouchEvidence: Boolean = false,
    val isCloseControlCandidate: Boolean = false
)

/**
 * Describes the evidence available for a target-app interaction without changing the
 * existing manual-takeover policy. In particular, a possible delayed programmatic scroll
 * is reported as a clue only; it is not suppressed here.
 */
class AccountBrowseInteractionEvidenceClassifier(
    private val eventClassifier: AccountBrowseInteractionEventClassifier =
        AccountBrowseInteractionEventClassifier()
) {

    fun classifyTargetEvent(
        event: AccessibilityEventEnvelope,
        now: Long,
        isRecyclerView: Boolean,
        context: AccountBrowseInteractionContext,
        lastProgrammaticScrollAt: Long,
        lastProgrammaticScrollLabel: String
    ): AccountBrowseInteractionEvidence {
        val hasTouch = hasIndependentTouchEvidence(now, context)
        val closeControlCandidate = event.type == AccessibilityEventType.VIEW_CLICKED &&
            hasCloseControlSemantics(event)

        if (eventClassifier.isVerifiedSelfClick(event, context.touchActive)) {
            return AccountBrowseInteractionEvidence(
                category = "SELF_CLICK_LEDGER_MATCH",
                description = "点击事件已匹配实际平台手势账本；actionId=${event.inputOriginDecision?.actionId}"
            )
        }
        if (eventClassifier.isProgrammaticInteractionEvent(event.type, now, context)) {
            val touchOverlap = if (hasTouch) {
                "；同时存在近期独立全局触摸证据，无法区分自动动作反馈与同期人工触摸"
            } else {
                ""
            }
            return AccountBrowseInteractionEvidence(
                category = "PROGRAMMATIC_ACTION_ASSOCIATED",
                description = "与 Freedom 自动动作关联的事件；动作=${context.programmaticActionLabel.ifBlank { "自动动作" }}；当前处于原有程序动作关联窗口或自动滑动确认阶段；不据此认定为人工操作$touchOverlap"
            )
        }

        val startupSuppression = eventClassifier.startupScrollSuppressionLog(
            eventType = event.type,
            now = now,
            isRecyclerView = isRecyclerView,
            context = context
        )
        if (startupSuppression != null) {
            return AccountBrowseInteractionEvidence(
                category = "STARTUP_OR_LAYOUT_EVENT",
                description = "$startupSuppression；此事件按现有启动/页面验证策略处理，不单独认定为人工操作"
            )
        }

        if (closeControlCandidate) {
            val touchDescription = if (hasTouch) {
                "存在近期全局触摸证据=${context.lastGlobalTouchEventName}"
            } else {
                "没有收到独立全局触摸证据"
            }
            return AccountBrowseInteractionEvidence(
                category = if (hasTouch) "TOUCH_CORRELATED_CLOSE_CONTROL_CLICK" else "CLOSE_CONTROL_CLICK_UNCONFIRMED",
                description = "检测到目标控件点击事件，控件文本/描述/ViewId 含关闭或跳过语义；$touchDescription；只能标记为关闭控件点击候选，不能确认是否关闭了广告",
                hasIndependentTouchEvidence = hasTouch,
                isCloseControlCandidate = true
            )
        }

        if (hasTouch) {
            val category = when {
                event.type == AccessibilityEventType.VIEW_SCROLLED && isRecyclerView -> "TOUCH_CORRELATED_SCROLL"
                event.type == AccessibilityEventType.VIEW_CLICKED -> "TOUCH_CORRELATED_CLICK"
                else -> "TOUCH_CORRELATED_TARGET_EVENT"
            }
            return AccountBrowseInteractionEvidence(
                category = category,
                description = "目标应用控件事件与近期全局触摸事件在时间上相关；最近触摸事件=${context.lastGlobalTouchEventName}；系统未提供手指坐标，无法确认具体触点或操作目标",
                hasIndependentTouchEvidence = true
            )
        }

        if (event.type == AccessibilityEventType.VIEW_SCROLLED && isRecyclerView) {
            val elapsedSinceProgrammaticScroll = now - lastProgrammaticScrollAt
            if (lastProgrammaticScrollAt > 0L &&
                elapsedSinceProgrammaticScroll in 0..DELAYED_PROGRAMMATIC_SCROLL_DIAGNOSTIC_WINDOW_MS
            ) {
                return AccountBrowseInteractionEvidence(
                    category = "POSSIBLE_DELAYED_PROGRAMMATIC_SCROLL",
                    description = "距最近一次 Freedom 自动滚动动作 ${elapsedSinceProgrammaticScroll}ms；动作=${lastProgrammaticScrollLabel.ifBlank { "自动滚动" }}；可能是延迟滚动反馈，但证据不足以确认；本诊断不改变原有人工接管判断"
                )
            }
            return AccountBrowseInteractionEvidence(
                category = "UNATTRIBUTED_TARGET_SCROLL",
                description = "检测到目标应用 RecyclerView 滚动事件，但没有独立全局触摸证据，也没有落在自动滚动诊断关联窗口内；来源未知；控件滚动事件不等于手指坐标，仍按原有安全策略作为人工操作候选"
            )
        }

        return AccountBrowseInteractionEvidence(
            category = "UNATTRIBUTED_TARGET_INTERACTION",
            description = "检测到目标应用控件交互事件，但没有独立全局触摸证据，也未匹配到已知 Freedom 自动动作；事件来源未确认，仍按原有安全策略作为人工操作候选"
        )
    }

    fun describePageTransition(
        now: Long,
        lastGlobalTouchEventAt: Long,
        lastGlobalTouchEventName: String,
        lastGlobalTouchEventOrigin: String,
        lastTargetInteractionAt: Long,
        lastTargetInteractionCategory: String,
        lastTargetInteractionDescription: String
    ): String {
        val targetInteractionAge = now - lastTargetInteractionAt
        if (lastTargetInteractionAt > 0L &&
            targetInteractionAge in 0..PAGE_TRANSITION_ASSOCIATION_WINDOW_MS &&
            (lastTargetInteractionCategory == "TOUCH_CORRELATED_CLOSE_CONTROL_CLICK" ||
                lastTargetInteractionCategory == "CLOSE_CONTROL_CLICK_UNCONFIRMED")
        ) {
            return "页面切换发生在关闭语义候选点击后 ${targetInteractionAge}ms；前序证据=$lastTargetInteractionDescription；只能判断为疑似关闭控件点击后的页面变化，不能确认是广告关闭"
        }

        val touchAge = now - lastGlobalTouchEventAt
        val independentTouch = lastGlobalTouchEventAt > 0L &&
            touchAge in 0..GLOBAL_TOUCH_EVENT_ASSOCIATION_MS &&
            !lastGlobalTouchEventOrigin.contains("Freedom 自动滑动期间")
        if (independentTouch) {
            return "页面切换发生在全局触摸事件=${lastGlobalTouchEventName} 后 ${touchAge}ms；只能确认时间相邻，无法定位具体按钮或确认页面切换原因"
        }

        if (lastTargetInteractionAt > 0L &&
            targetInteractionAge in 0..PAGE_TRANSITION_ASSOCIATION_WINDOW_MS
        ) {
            return "页面切换发生在最近目标控件交互后 ${targetInteractionAge}ms；前序证据=$lastTargetInteractionDescription；仅表示时间相关，不证明因果关系"
        }

        return "仅确认 Activity 发生变化；没有可关联的近期触摸或目标控件交互证据，来源未知"
    }

    private fun hasIndependentTouchEvidence(
        now: Long,
        context: AccountBrowseInteractionContext
    ): Boolean {
        if (context.lastGlobalTouchEventOrigin.contains("Freedom 自动滑动期间")) return false
        if (context.touchActive) return true
        val elapsed = now - context.lastGlobalTouchEventAt
        return context.lastGlobalTouchEventAt > 0L && elapsed in 0..GLOBAL_TOUCH_EVENT_ASSOCIATION_MS
    }

    private fun hasCloseControlSemantics(event: AccessibilityEventEnvelope): Boolean {
        val evidence = buildList {
            event.source?.viewId?.let(::add)
            event.source?.text?.let(::add)
            event.source?.contentDescription?.let(::add)
            event.contentDescription?.let(::add)
            addAll(event.eventText)
        }.joinToString(" ").lowercase()
        return CLOSE_CONTROL_HINTS.any(evidence::contains)
    }

    private companion object {
        const val GLOBAL_TOUCH_EVENT_ASSOCIATION_MS = 1_500L
        const val PAGE_TRANSITION_ASSOCIATION_WINDOW_MS = 2_000L
        const val DELAYED_PROGRAMMATIC_SCROLL_DIAGNOSTIC_WINDOW_MS = 3_500L
        val CLOSE_CONTROL_HINTS = setOf("关闭", "close", "dismiss", "跳过", "skip")
    }
}
