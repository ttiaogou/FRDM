package com.freedom.features.aireply

import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatNodeTextSource
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.gesture.api.GestureApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMessagesTaskExecutorTest {
    @Test
    fun refusesToInspectOrDispatchWhenTaskLeaseIsAlreadyStale() {
        var inspections = 0
        var dispatches = 0
        val executor = executor(
            snapshotProvider = { inspections++; validSnapshot() },
            ownsTask = { false },
            onDispatch = { dispatches++ }
        )

        val result = executor.openSession(key(candidate()), candidate().rowTarget.window, emptySet()) { _, _, _, _ -> }

        assertTrue(result is PrivateMessagesTaskExecutor.Attempt.Rejected)
        assertEquals(
            PrivateMessagesTaskExecutor.RejectionReason.OWNERSHIP_LOST,
            (result as PrivateMessagesTaskExecutor.Attempt.Rejected).reason
        )
        assertEquals(0, inspections)
        assertEquals(0, dispatches)
    }

    @Test
    fun refusesWhenFreshCandidateIsNoLongerExplicitlyUnread() {
        var dispatches = 0
        val changedCandidate = candidate(unreadStatus = ChatUnreadStatus.UNKNOWN)
        val executor = executor(
            snapshotProvider = { validSnapshot(changedCandidate) },
            onDispatch = { dispatches++ }
        )

        val result = executor.openSession(key(candidate()), candidate().rowTarget.window, emptySet()) { _, _, _, _ -> }

        assertTrue(result is PrivateMessagesTaskExecutor.Attempt.Rejected)
        assertEquals(
            PrivateMessagesTaskExecutor.RejectionReason.UNREAD_ADMISSION_CHANGED,
            (result as PrivateMessagesTaskExecutor.Attempt.Rejected).reason
        )
        assertEquals(ChatUnreadStatus.UNKNOWN, result.unreadStatus)
        assertEquals(0, dispatches)
    }

    @Test
    fun submitsOnlyAfterFreshRowIdentityBoundsViewportAndOwnershipAreConfirmed() {
        var dispatched: GestureIntentRequest? = null
        val freshCandidate = candidate()
        val executor = executor(
            snapshotProvider = { validSnapshot(freshCandidate) },
            onDispatch = { request -> dispatched = request }
        )
        var callbackId: String? = null

        val result = executor.openSession(key(candidate()), candidate().rowTarget.window, emptySet()) { requestId, _, _, _ ->
            callbackId = requestId
        }

        assertTrue(result is PrivateMessagesTaskExecutor.Attempt.Submitted)
        val submitted = result as PrivateMessagesTaskExecutor.Attempt.Submitted
        assertEquals(freshCandidate, submitted.candidate)
        assertEquals(GestureSubmission.ACCEPTED, submitted.submission)
        assertEquals(submitted.requestId, dispatched?.id)
        assertEquals(submitted.requestId, callbackId)
        assertTrue(dispatched?.description?.contains("打开已复核的会话行") == true)
        assertEquals(
            GestureFeedbackIntent.NAVIGATION_ENTRY,
            dispatched?.feedbackIntent
        )
    }

    @Test
    fun rechecksOwnershipAfterBuildingGestureAndBeforeDispatch() {
        var ownershipChecks = 0
        var dispatches = 0
        val executor = executor(
            snapshotProvider = { validSnapshot() },
            ownsTask = { ++ownershipChecks == 1 },
            onDispatch = { dispatches++ }
        )

        val result = executor.openSession(key(candidate()), candidate().rowTarget.window, emptySet()) { _, _, _, _ -> }

        assertTrue(result is PrivateMessagesTaskExecutor.Attempt.Rejected)
        assertEquals(
            PrivateMessagesTaskExecutor.RejectionReason.OWNERSHIP_LOST,
            (result as PrivateMessagesTaskExecutor.Attempt.Rejected).reason
        )
        assertEquals(2, ownershipChecks)
        assertEquals(0, dispatches)
    }

    @Test
    fun reacquiresUniqueIdentityAfterChildIndexAndCoordinatesChange() {
        val selected = candidate()
        val moved = candidate(rowIndex = 3, top = 650)
        val other = candidate(rowIndex = 0, top = 200, name = "Other")
        var dispatched: GestureIntentRequest? = null
        val executor = executor(
            snapshotProvider = { validSnapshot().copy(chatListCandidates = listOf(other, moved)) },
            onDispatch = { dispatched = it }
        )
        val result = executor.openSession(key(selected), selected.rowTarget.window, emptySet()) { _, _, _, _ -> }
        assertTrue(result is PrivateMessagesTaskExecutor.Attempt.Submitted)
        assertEquals(moved, (result as PrivateMessagesTaskExecutor.Attempt.Submitted).candidate)
        val tap = requireNotNull(dispatched).intent as GestureIntent.Tap
        assertEquals(moved.rowBounds, tap.targetBounds)
        assertEquals(ScreenRect(100, 670, 300, 750), tap.preferredBounds)
        assertTrue(requireNotNull(dispatched).description.contains("会话行 4"))
    }

    @Test
    fun rejectsFreshDuplicateEvenWhenOneRowHasIncompletePreviewOrVerifiedIdentity() {
        val selected = candidate()
        val moved = candidate(rowIndex = 2, top = 500)
        val incomplete = candidate(rowIndex = 3, top = 700, previewValid = false)
        val verified = candidate(rowIndex = 3, top = 700).apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "verified", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        for (duplicate in listOf(incomplete, verified)) {
            var dispatches = 0
            val executor = executor(
                snapshotProvider = { validSnapshot().copy(chatListCandidates = listOf(moved, duplicate)) },
                onDispatch = { dispatches++ }
            )
            val result = executor.openSession(key(selected), selected.rowTarget.window, emptySet()) { _, _, _, _ -> }
            assertEquals(PrivateMessagesTaskExecutor.RejectionReason.AMBIGUOUS_SESSION_IDENTITY,
                (result as PrivateMessagesTaskExecutor.Attempt.Rejected).reason)
            assertEquals(0, dispatches)
        }
    }

    @Test
    fun rejectsMissingIdentityAndNeverFallsBackToSameTitleForVerifiedContact() {
        val selected = candidate().apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "selected-id", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        val other = candidate().apply {
            contactIdentity = ContactRowIdentity("snapshot", "temp", "other-id", ContactIdentityConfidence.PLATFORM_ID_VERIFIED)
        }
        var dispatches = 0
        val executor = executor(snapshotProvider = { validSnapshot(other) }, onDispatch = { dispatches++ })
        val result = executor.openSession(key(selected), selected.rowTarget.window, emptySet()) { _, _, _, _ -> }
        assertEquals(PrivateMessagesTaskExecutor.RejectionReason.SESSION_ROW_NOT_FOUND,
            (result as PrivateMessagesTaskExecutor.Attempt.Rejected).reason)
        assertEquals(0, dispatches)
    }

    @Test
    fun rejectsChangedWindowGenerationAndTruncatedEvidenceBeforeResolvingRow() {
        val selected = candidate()
        for ((page, expected) in listOf(
            validSnapshot(candidate(windowGeneration = 2L)).copy(windowGeneration = 2L) to
                PrivateMessagesTaskExecutor.RejectionReason.WINDOW_CONTEXT_CHANGED,
            validSnapshot().copy(chatListExtractionTruncated = true) to
                PrivateMessagesTaskExecutor.RejectionReason.PAGE_CHANGED
        )) {
            var dispatches = 0
            val executor = executor(snapshotProvider = { page }, onDispatch = { dispatches++ })
            val result = executor.openSession(key(selected), selected.rowTarget.window, emptySet()) { _, _, _, _ -> }
            assertEquals(expected, (result as PrivateMessagesTaskExecutor.Attempt.Rejected).reason)
            assertEquals(0, dispatches)
        }
    }

    @Test
    fun rechecksVisualIdentityQuarantineImmediatelyBeforeDispatch() {
        val selected = candidate()
        var blockedChecks = 0
        var dispatches = 0
        val executor = executor(
            snapshotProvider = { validSnapshot() },
            blocked = { ++blockedChecks >= 2 },
            onDispatch = { dispatches++ }
        )
        val result = executor.openSession(key(selected), selected.rowTarget.window, emptySet()) { _, _, _, _ -> }
        assertEquals(PrivateMessagesTaskExecutor.RejectionReason.AMBIGUOUS_SESSION_IDENTITY,
            (result as PrivateMessagesTaskExecutor.Attempt.Rejected).reason)
        assertEquals(0, dispatches)
    }

    private fun key(candidate: ChatListCandidateSnapshot) = ChatSessionManagementPolicy.stableSessionKey(candidate)

    private fun executor(
        snapshotProvider: () -> ChatPageSnapshot?,
        ownsTask: () -> Boolean = { true },
        blocked: (String) -> Boolean = { false },
        onDispatch: (GestureIntentRequest) -> Unit
    ) = PrivateMessagesTaskExecutor(
        sessionPort = object : ChatSessionManagementPort {
            override fun beginSessionManagement(): String? = null
            override fun finishSessionManagement(): String? = null
            override fun captureSessionListSample(snapshot: ChatPageSnapshot, onComplete: (String?) -> Unit) =
                onComplete(null)
            override fun isSessionIdentityBlocked(sessionKey: String): Boolean = blocked(sessionKey)
            override fun resolveSessionTapBounds(
                snapshot: ChatPageSnapshot,
                candidate: ChatListCandidateSnapshot
            ): ScreenRect = ScreenRect(100, candidate.rowBounds.top + 20, 300, candidate.rowBounds.top + 100)
            override fun persistChatSample(
                snapshot: ChatPageSnapshot,
                batch: com.freedom.features.aireply.domain.message.ChatMessageBatch,
                sessionName: String?
            ): Int = 0
            override fun navigateBackToSessionList(): Boolean = false
        },
        gesturePolicy = object : GestureApi {
            override fun captureContext(
                expectedWindow: WindowRef?,
                expectedCoordinates: GestureCoordinateContext?
            ) = GestureExecutionContext(
                runGeneration = 3L,
                targetWindow = requireNotNull(expectedWindow),
                coordinateContext = expectedCoordinates ?: GestureCoordinateContext(
                    displayId = 0,
                    widthPixels = 720,
                    heightPixels = 1600,
                    density = 2f,
                    rotationDegrees = 0
                )
            )

            override fun execute(
                request: GestureIntentRequest,
                listener: GestureExecutionListener
            ): GestureSubmission {
                onDispatch(request)
                listener.onResult(GestureExecutionResult(request.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }

            override fun cancelActive(commandId: String, reason: String): Boolean = false
        },
        inspectFreshPageSnapshot = snapshotProvider,
        targetPackage = { "com.immomo.momo" },
        ownsTask = ownsTask,
        nowMillis = { 123L }
    )

    private fun validSnapshot(
        candidate: ChatListCandidateSnapshot = candidate()
    ) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "MaintabActivity",
        pageKind = ChatPageKind.CHAT_LIST,
        scannedNodeCount = 30,
        editableInputCount = 0,
        messageListCount = 1,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = true,
        messageListTarget = null,
        inputFieldTarget = null,
        chatListViewport = ScreenRect(0, 100, 720, 1400),
        screenBounds = ScreenRect(0, 0, 720, 1600),
        chatListCandidates = listOf(candidate),
        windowGeneration = 1L
    )

    private fun candidate(
        unreadStatus: ChatUnreadStatus = ChatUnreadStatus.UNREAD,
        rowIndex: Int = 0,
        top: Int = 200,
        name: String = "Sample Contact",
        windowGeneration: Long = 1L,
        previewValid: Boolean = true
    ): ChatListCandidateSnapshot {
        val row = ScreenRect(0, top, 720, top + 150)
        return ChatListCandidateSnapshot(
            rowIndex = rowIndex,
            rowTarget = NodeTarget(
                window = WindowRef("com.immomo.momo", "MaintabActivity", windowGeneration, 1),
                path = NodePath(listOf(0, rowIndex)),
                expectedClassName = "android.widget.RelativeLayout"
            ),
            rowBounds = row,
            rowVisibleToUser = true,
            intersectsViewport = true,
            fullyInsideViewport = true,
            rowBoundsValid = true,
            avatarBounds = ScreenRect(10, top + 10, 90, top + 130),
            avatarPath = NodePath(listOf(0, rowIndex, 0)),
            nameText = name,
            nameSource = ChatNodeTextSource.NODE_TEXT,
            nameNodePath = NodePath(listOf(0, rowIndex, 1)),
            nameBounds = ScreenRect(120, top + 15, 360, top + 50),
            nameNodeVisibleToUser = true,
            nameBoundsIntersectViewport = true,
            nameBoundsFullyInsideViewport = true,
            nameBoundsValid = true,
            previewText = "Preview",
            previewSource = ChatNodeTextSource.NODE_TEXT,
            previewNodePath = NodePath(listOf(0, rowIndex, 2)),
            previewBounds = ScreenRect(120, top + 60, 620, top + 100),
            previewNodeVisibleToUser = true,
            previewBoundsIntersectViewport = true,
            previewBoundsFullyInsideViewport = true,
            previewBoundsValid = previewValid,
            timestampText = "10:20",
            timestampSource = ChatNodeTextSource.NODE_TEXT,
            timestampNodePath = null,
            timestampBounds = null,
            timestampNodeVisibleToUser = false,
            unreadStatus = unreadStatus
        )
    }
}
