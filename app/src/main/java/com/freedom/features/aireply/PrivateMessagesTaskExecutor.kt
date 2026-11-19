package com.freedom.features.aireply

import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureFeedbackIntent
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.gesture.api.GestureApi

/**
 * 私聊任务的会话打开执行器。
 *
 * 负责对候选会话重新采集并校验最新列表快照、行身份、未读状态、触控边界和窗口上下文，
 * 然后通过统一 GestureApi 提交打开会话动作。遍历顺序、任务阶段和失败后的调度策略仍由
 * AiReplyObservationRuntime 决定；本执行器不持有列表遍历状态。
 */
internal class PrivateMessagesTaskExecutor(
    private val sessionPort: ChatSessionManagementPort,
    private val gesturePolicy: GestureApi,
    private val inspectFreshPageSnapshot: () -> ChatPageSnapshot?,
    private val targetPackage: () -> String,
    private val ownsTask: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    enum class RejectionReason {
        OWNERSHIP_LOST,
        PAGE_CHANGED,
        AMBIGUOUS_SESSION_IDENTITY,
        SESSION_ROW_NOT_FOUND,
        UNREAD_ADMISSION_CHANGED,
        UNSAFE_TAP_BOUNDS,
        MISSING_LIST_VIEWPORT,
        WINDOW_CONTEXT_CHANGED
    }

    sealed interface Attempt {
        data class Rejected(
            val selectedKey: String,
            val reason: RejectionReason,
            val unreadStatus: ChatUnreadStatus? = null
        ) : Attempt

        data class Submitted(
            val selectedKey: String,
            val snapshot: ChatPageSnapshot,
            val candidate: ChatListCandidateSnapshot,
            val requestId: String,
            val submission: GestureSubmission
        ) : Attempt
    }

    companion object {
        private const val SESSION_TAP_DURATION_MS = 90L
    }

    fun openSession(
        candidate: ChatListCandidateSnapshot,
        blockedIdentityKeys: Set<String>,
        onGestureResult: (requestId: String, snapshot: ChatPageSnapshot, selectedKey: String, result: GestureExecutionResult) -> Unit
    ): Attempt {
        val selectedKey = ChatSessionManagementPolicy.stableSessionKey(candidate)
        if (!ownsTask()) {
            return Attempt.Rejected(selectedKey, RejectionReason.OWNERSHIP_LOST)
        }

        val refreshed = inspectFreshPageSnapshot()
        if (refreshed == null || refreshed.pageKind != ChatPageKind.CHAT_LIST ||
            refreshed.packageName != targetPackage()
        ) {
            return Attempt.Rejected(selectedKey, RejectionReason.PAGE_CHANGED)
        }

        val refreshedAmbiguousKeys = ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(
            refreshed.chatListCandidates
        )
        if (selectedKey in blockedIdentityKeys || selectedKey in refreshedAmbiguousKeys) {
            return Attempt.Rejected(selectedKey, RejectionReason.AMBIGUOUS_SESSION_IDENTITY)
        }

        val currentCandidate = refreshed.chatListCandidates.firstOrNull {
            ChatSessionManagementPolicy.stableSessionKey(it) == selectedKey && it.rowIndex == candidate.rowIndex
        } ?: return Attempt.Rejected(selectedKey, RejectionReason.SESSION_ROW_NOT_FOUND)

        // Reapply the complete admission rule to the fresh snapshot immediately before resolving
        // a tap. The row may have changed from UNREAD to READ/UNKNOWN while the tree was relaid out.
        if (currentCandidate.unreadStatus != ChatUnreadStatus.UNREAD ||
            !ChatSessionManagementPolicy.isEligible(currentCandidate)
        ) {
            return Attempt.Rejected(
                selectedKey,
                RejectionReason.UNREAD_ADMISSION_CHANGED,
                currentCandidate.unreadStatus
            )
        }

        val bounds = sessionPort.resolveSessionTapBounds(refreshed, currentCandidate)
        val row = currentCandidate.rowBounds
        val tapBoundsInsideRow = bounds != null &&
            bounds.left >= row.left && bounds.top >= row.top &&
            bounds.right <= row.right && bounds.bottom <= row.bottom
        if (bounds == null || bounds.isEmpty || bounds.width < 80 || bounds.height < 48 ||
            !tapBoundsInsideRow
        ) {
            return Attempt.Rejected(selectedKey, RejectionReason.UNSAFE_TAP_BOUNDS)
        }

        val visibleViewport = refreshed.chatListViewport
        if (visibleViewport == null || visibleViewport.isEmpty) {
            return Attempt.Rejected(selectedKey, RejectionReason.MISSING_LIST_VIEWPORT)
        }

        val executionContext: GestureExecutionContext =
            gesturePolicy.captureContext(currentCandidate.rowTarget.window)
                ?: return Attempt.Rejected(selectedKey, RejectionReason.WINDOW_CONTEXT_CHANGED)

        val request = GestureIntentRequest(
            id = "momo-session-${nowMillis()}-${candidate.rowIndex}",
            intent = GestureIntent.Tap(
                preferredBounds = bounds,
                targetBounds = row,
                durationMs = SESSION_TAP_DURATION_MS,
                displayBounds = visibleViewport,
                feedbackIntent = GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "AI 会话管理：打开已复核的会话行 ${candidate.rowIndex + 1}",
            executionContext = executionContext
        )

        // The task lease can be transferred while the fresh snapshot and gesture are being built.
        // Recheck immediately before dispatch so an old task cannot submit a stale tap.
        if (!ownsTask()) {
            return Attempt.Rejected(selectedKey, RejectionReason.OWNERSHIP_LOST)
        }

        val submission = runCatching {
            gesturePolicy.execute(request) { result -> onGestureResult(request.id, refreshed, selectedKey, result) }
        }.getOrDefault(GestureSubmission.REJECTED)

        return Attempt.Submitted(
            selectedKey = selectedKey,
            snapshot = refreshed,
            candidate = currentCandidate,
            requestId = request.id,
            submission = submission
        )
    }
}
