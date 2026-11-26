package com.freedom.features.aireply

import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionListener
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
        TRAVERSAL_ORDER_CHANGED,
        UNSAFE_TAP_BOUNDS,
        MISSING_LIST_VIEWPORT,
        WINDOW_CONTEXT_CHANGED
    }

    sealed interface Attempt {
        data class Rejected(
            val selectedKey: String,
            val reason: RejectionReason,
            val unreadStatus: ChatUnreadStatus? = null,
            val confirmedIdentityBlock: Boolean = false
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
        selectedKey: String,
        sourceWindow: WindowRef,
        blockedIdentityKeys: Set<String>,
        isCurrentTraversalSelection: (ChatPageSnapshot, String) -> Boolean = { _, _ -> true },
        isInterruptedReadRecovery: (ChatPageSnapshot, ChatListCandidateSnapshot) -> Boolean = { _, _ -> false },
        onGestureDispatched: (String, Long) -> Unit = { _, _ -> },
        onGestureResult: (requestId: String, snapshot: ChatPageSnapshot, selectedKey: String, result: GestureExecutionResult) -> Unit
    ): Attempt {
        if (!ownsTask()) {
            return Attempt.Rejected(selectedKey, RejectionReason.OWNERSHIP_LOST)
        }

        val refreshed = inspectFreshPageSnapshot()
        if (refreshed == null || refreshed.pageKind != ChatPageKind.CHAT_LIST ||
            refreshed.packageName != targetPackage() || refreshed.chatListExtractionTruncated
        ) {
            return Attempt.Rejected(selectedKey, RejectionReason.PAGE_CHANGED)
        }

        val sourceWindowKey = "${sourceWindow.packageName}|windowId=${sourceWindow.windowId}|generation=${sourceWindow.generation}"
        if (sourceWindow.windowId < 0 || sourceWindow.generation <= 0L ||
            refreshed.windowIdentityKey() != sourceWindowKey
        ) return Attempt.Rejected(selectedKey, RejectionReason.WINDOW_CONTEXT_CHANGED)

        val refreshedAmbiguousKeys = ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(
            refreshed.chatListCandidates
        )
        if (selectedKey in blockedIdentityKeys || selectedKey in refreshedAmbiguousKeys ||
            sessionPort.isSessionIdentityBlocked(selectedKey)
        ) {
            return Attempt.Rejected(selectedKey, RejectionReason.AMBIGUOUS_SESSION_IDENTITY,
                confirmedIdentityBlock = true)
        }

        // The original child index, path and bounds are never part of reacquisition.
        // Count before eligibility filtering: an incomplete duplicate is still ambiguous.
        val matches = refreshed.chatListCandidates.filter {
            it.selectionClass == "PRIVATE_CHAT" &&
                ChatSessionManagementPolicy.stableSessionKey(it) == selectedKey
        }
        if (selectedKey.isBlank() || matches.isEmpty()) {
            return Attempt.Rejected(selectedKey, RejectionReason.SESSION_ROW_NOT_FOUND)
        }
        if (matches.size != 1) {
            // Repeated extraction of one row is unsafe to click, but does not prove
            // a collision between two different rows and must remain retryable.
            return Attempt.Rejected(selectedKey, RejectionReason.AMBIGUOUS_SESSION_IDENTITY,
                confirmedIdentityBlock = matches.map { it.rowIndex }.distinct().size > 1)
        }
        val currentCandidate = matches.single()
        // A weak title cannot become unique merely because a second same-title row
        // has a verified ID, incomplete evidence, or is temporarily unclassified.
        val titleKey = ChatSessionManagementPolicy.stableSessionKeyForTitle(currentCandidate.nameText)
        val sameTitleRows = refreshed.chatListCandidates.filter {
            it.selectionClass != "SYSTEM_ENTRY" &&
                ChatSessionManagementPolicy.stableSessionKeyForTitle(it.nameText) == titleKey
        }
        if (selectedKey == titleKey && sameTitleRows.size != 1) {
            return Attempt.Rejected(selectedKey, RejectionReason.AMBIGUOUS_SESSION_IDENTITY,
                confirmedIdentityBlock = sameTitleRows.map { it.rowIndex }.distinct().size > 1)
        }
        val currentWindow = currentCandidate.rowTarget.window
        if (currentWindow.packageName != sourceWindow.packageName ||
            currentWindow.windowId != sourceWindow.windowId ||
            currentWindow.generation != sourceWindow.generation ||
            currentWindow.activityClass != refreshed.activityClass
        ) return Attempt.Rejected(selectedKey, RejectionReason.WINDOW_CONTEXT_CHANGED)

        // Reapply the complete admission rule to the fresh snapshot immediately before resolving
        // a tap. The row may have changed from UNREAD to READ/UNKNOWN while the tree was relaid out.
        if ((currentCandidate.unreadStatus != ChatUnreadStatus.UNREAD &&
                !(currentCandidate.unreadStatus == ChatUnreadStatus.READ &&
                    isInterruptedReadRecovery(refreshed, currentCandidate))) ||
            !ChatSessionManagementPolicy.isEligible(currentCandidate) ||
            currentCandidate.rowClippedAtViewportEdge
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
            id = "momo-session-${nowMillis()}-${currentCandidate.rowIndex}",
            intent = GestureIntent.Tap(
                preferredBounds = bounds,
                targetBounds = row,
                durationMs = SESSION_TAP_DURATION_MS,
                displayBounds = visibleViewport,
                feedbackIntent = GestureFeedbackIntent.NAVIGATION_ENTRY
            ),
            description = "AI 会话管理：打开已复核的会话行 ${currentCandidate.rowIndex + 1}",
            executionContext = executionContext
        )

        // The task lease can be transferred while the fresh snapshot and gesture are being built.
        // Recheck immediately before dispatch so an old task cannot submit a stale tap.
        if (!ownsTask()) {
            return Attempt.Rejected(selectedKey, RejectionReason.OWNERSHIP_LOST)
        }

        if (selectedKey in blockedIdentityKeys || sessionPort.isSessionIdentityBlocked(selectedKey)) {
            return Attempt.Rejected(selectedKey, RejectionReason.AMBIGUOUS_SESSION_IDENTITY,
                confirmedIdentityBlock = true)
        }
        if (!isCurrentTraversalSelection(refreshed, selectedKey)) {
            return Attempt.Rejected(selectedKey, RejectionReason.TRAVERSAL_ORDER_CHANGED)
        }
        val submission = runCatching {
            gesturePolicy.execute(request, object : GestureExecutionListener {
                override fun isDispatchTargetCurrent(commandId: String): Boolean =
                    commandId == request.id && preparedTargetStillCurrent(
                        selectedKey, sourceWindow, row, bounds, visibleViewport,
                        blockedIdentityKeys, isCurrentTraversalSelection, isInterruptedReadRecovery
                    )

                override fun onDispatched(commandId: String, dispatchedAtUptimeMs: Long) =
                    onGestureDispatched(commandId, dispatchedAtUptimeMs)

                override fun onResult(result: GestureExecutionResult) =
                    onGestureResult(request.id, refreshed, selectedKey, result)
            })
        }.getOrDefault(GestureSubmission.REJECTED)

        return Attempt.Submitted(
            selectedKey = selectedKey,
            snapshot = refreshed,
            candidate = currentCandidate,
            requestId = request.id,
            submission = submission
        )
    }

    /** The compiled trajectory is immutable. Only fresh, unique identity and identical
     * safe geometry may authorize it after FIFO/visualization/pre-dispatch delay. */
    private fun preparedTargetStillCurrent(
        selectedKey: String,
        sourceWindow: WindowRef,
        preparedRow: ScreenRect,
        preparedTapBounds: ScreenRect,
        preparedViewport: ScreenRect,
        blockedIdentityKeys: Set<String>,
        isCurrentTraversalSelection: (ChatPageSnapshot, String) -> Boolean,
        isInterruptedReadRecovery: (ChatPageSnapshot, ChatListCandidateSnapshot) -> Boolean
    ): Boolean {
        if (!ownsTask() || selectedKey in blockedIdentityKeys ||
            sessionPort.isSessionIdentityBlocked(selectedKey)
        ) return false
        val fresh = inspectFreshPageSnapshot() ?: return false
        val expectedWindow = "${sourceWindow.packageName}|windowId=${sourceWindow.windowId}|generation=${sourceWindow.generation}"
        if (fresh.pageKind != ChatPageKind.CHAT_LIST || fresh.packageName != targetPackage() ||
            fresh.chatListExtractionTruncated || fresh.windowIdentityKey() != expectedWindow ||
            fresh.chatListViewport != preparedViewport
        ) return false
        val row = fresh.chatListCandidates.filter {
            it.selectionClass == "PRIVATE_CHAT" && ChatSessionManagementPolicy.stableSessionKey(it) == selectedKey
        }.singleOrNull() ?: return false
        val titleKey = ChatSessionManagementPolicy.stableSessionKeyForTitle(row.nameText)
        if (selectedKey == titleKey && fresh.chatListCandidates.count {
                it.selectionClass != "SYSTEM_ENTRY" &&
                    ChatSessionManagementPolicy.stableSessionKeyForTitle(it.nameText) == titleKey
            } != 1
        ) return false
        if (selectedKey in ChatSessionManagementPolicy.ambiguousSessionIdentityKeys(fresh.chatListCandidates) ||
            row.rowTarget.window != sourceWindow ||
            row.rowTarget.window.activityClass != fresh.activityClass ||
            (row.unreadStatus != ChatUnreadStatus.UNREAD &&
                !(row.unreadStatus == ChatUnreadStatus.READ && isInterruptedReadRecovery(fresh, row))) ||
            !ChatSessionManagementPolicy.isEligible(row) ||
            row.rowClippedAtViewportEdge || row.rowBounds != preparedRow ||
            !isCurrentTraversalSelection(fresh, selectedKey)
        ) return false
        // The adapter performs its existing live node/overlay and safe-bounds checks.
        // A change invalidates this command; recovery will build a new one.
        return sessionPort.resolveSessionTapBounds(fresh, row) == preparedTapBounds &&
            ownsTask() && selectedKey !in blockedIdentityKeys &&
            !sessionPort.isSessionIdentityBlocked(selectedKey)
    }
}
