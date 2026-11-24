package com.freedom.features.aireply

import com.freedom.gesture.api.GestureApi
import com.freedom.core.accessibility.api.GestureFeedbackIntent
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.events.AccessibilityContentChange
import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import com.freedom.core.accessibility.events.AccessibilitySourceSnapshot
import com.freedom.core.accessibility.events.AccessibilityUiSignal
import com.freedom.core.accessibility.events.InputOrigin
import com.freedom.core.accessibility.events.SelfNavigationTransition
import com.freedom.core.accessibility.events.InputOriginDecision
import com.freedom.core.accessibility.events.InputOriginEvidence
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.PageObservationCoordinator
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TargetAppReadinessSnapshot
import com.freedom.core.runtime.TargetAppReadinessStatus
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.features.aireply.domain.message.ChatMessage
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.ChatMessageOcrTarget
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.DirectionEvidence
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionContext
import com.freedom.core.accessibility.gesture.GestureCoordinateContext
import com.freedom.core.accessibility.gesture.GestureKind
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.GestureExecutionResult
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
import com.freedom.features.aireply.ports.ChatHeaderNodeDiagnostic
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatUnreadStatus
import com.freedom.features.aireply.ports.ChatPageNodeEvidence
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.MessageWorkAreaNavigationOutcome
import com.freedom.features.aireply.ports.ReceivedGreetingsBackResult
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.ContactIdentityCaptureOutcome
import com.freedom.features.aireply.ports.ContactIdentityCaptureRequestResult
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatListUnreadDiagnostics
import com.freedom.features.aireply.ports.ChatRegionSnapshot
import com.freedom.features.aireply.ports.SessionListNavigationTarget
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpointRepository
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


private interface TestGestureApi : GestureApi {
    override fun cancelActive(commandId: String, reason: String): Boolean = false

    override fun captureContext(
        expectedWindow: WindowRef?,
        expectedCoordinates: GestureCoordinateContext?
    ): GestureExecutionContext =
        GestureExecutionContext(
            runGeneration = 0L,
            targetWindow = expectedWindow ?: WindowRef("com.immomo.momo", "MainActivity", 0L, 1),
            coordinateContext = expectedCoordinates ?: GestureCoordinateContext(
                displayId = 0,
                widthPixels = 1080,
                heightPixels = 1920,
                density = 3f,
                rotationDegrees = 0
            )
        )
}

private interface TestGestureApiWithCancellation : TestGestureApi

/** Existing feature fixtures explicitly supply READY; production always rechecks the live gateway. */
private fun testAndroidRecoveryReadiness(page: ChatPageSnapshot): TargetAppReadinessSnapshot {
    val window = page.inputFieldTarget?.window ?: page.messageListTarget?.window
        ?: page.sessionListNavigationTarget?.nodeTarget?.window
        ?: page.chatListCandidates.firstOrNull()?.rowTarget?.window
    return TargetAppReadinessSnapshot(
        status = TargetAppReadinessStatus.READY,
        targetPackage = "com.immomo.momo",
        foregroundPackage = "com.immomo.momo",
        activityClass = page.activityClass.ifBlank { window?.activityClass.orEmpty() },
        runMode = RunMode.AI_REPLY,
        windowId = window?.windowId ?: 116,
        pageGeneration = window?.generation ?: page.windowGeneration
    )
}

class AiReplyObservationRuntimeTest {

    @Test
    fun samePersistedCheckpointGetsOneIdAcrossUnknownReconnectAndRestart() {
        val task = "AI_REPLY_PRIVATE_MESSAGES"
        val otherTask = "AI_REPLY_RECEIVED_GREETINGS"
        val stamp = 1_700_000_000_999L
        val id = AiReplyObservationRuntime.durableRecoveryCheckpointId(task, stamp, "PAGE_ROUTE_CHANGED")
        assertEquals(id, AiReplyObservationRuntime.durableRecoveryCheckpointId(task, stamp, "SERVICE_RECONNECT_CHECKPOINT"))
        assertEquals(id, AiReplyObservationRuntime.durableRecoveryCheckpointId(task, stamp, "PROCESS_RESTART_CHECKPOINT"))
        assertFalse(id == AiReplyObservationRuntime.durableRecoveryCheckpointId(task, stamp + 1L, "PROCESS_RESTART_CHECKPOINT"))
        assertFalse(id == AiReplyObservationRuntime.durableRecoveryCheckpointId(otherTask, stamp, "PROCESS_RESTART_CHECKPOINT"))
        assertFalse(
            AiReplyObservationRuntime.durableRecoveryCheckpointId(task, null, "UNKNOWN_PAGE") ==
                AiReplyObservationRuntime.durableRecoveryCheckpointId(task, null, "SERVICE_RECONNECT_CHECKPOINT")
        )
    }

    @Test
    fun serviceDisconnectPreservesDurableRecoveryPlansButExplicitRunStopClearsBoth() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort()
        var messageClears = 0
        var greetingsClears = 0
        val messageStore = object : RecoveryScheduleRepository {
            override fun load() = null
            override fun save(snapshot: com.freedom.features.recovery.RecoveryScheduleSnapshot) = Unit
            override fun clear() { messageClears++ }
        }
        val greetingsStore = object : RecoveryScheduleRepository {
            override fun load() = null
            override fun save(snapshot: com.freedom.features.recovery.RecoveryScheduleSnapshot) = Unit
            override fun clear() { greetingsClears++ }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            privateMessagesRecoveryScheduleRepository = messageStore,
            receivedGreetingsRecoveryScheduleRepository = greetingsStore
        )

        runtime.reset("无障碍服务断开连接")
        assertEquals(0, messageClears)
        assertEquals(0, greetingsClears)

        runtime.clearRecoverySchedulesAfterRunStop()
        assertEquals(1, messageClears)
        assertEquals(1, greetingsClears)
    }

    @Test
    fun receivedGreetingsRestoredCheckpointRequiresSharedProofAndAdapterConfirmation() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
            receivedGreetingsCheckpointFingerprint = "greetings-current-window"
            receivedGreetingsRecoverySyncAccepted = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "NewSayHiSessionListActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 40,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null
            )
        }
        val checkpoint = MomoSayHiTraversalCheckpointRecord(
            processedRowKeys = setOf("hashed-row"),
            processedContactIdHashes = setOf("hashed-contact"),
            deferredRows = emptyMap(),
            deferredReturnToTopActive = false,
            deferredReplayStarted = false
        )
        val repository = object : MomoSayHiTraversalCheckpointRepository {
            override fun load(nowEpochMs: Long): MomoSayHiTraversalCheckpointRecord? = checkpoint
            override fun save(checkpoint: MomoSayHiTraversalCheckpointRecord) = Unit
            override fun clear() = Unit
        }
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = ownership,
            currentRunGeneration = { 12L },
            receivedGreetingsCheckpointRepository = repository
        )
        runtime.scheduleInitialObservation()
        // The existing Momo adapter may report an unconfirmed page at the reconnect moment;
        // the new durable checkpoint path must still enter read-only verification.
        assertFalse(runtime.reloadReceivedGreetingsCheckpointAfterServiceReconnect())
        scheduler.runNextWithDelay(0L)
        assertTrue(logs.any { it.contains("收到招呼通用检查点[VERIFYING]") })
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)

        scheduler.runNextWithDelay(500L)
        scheduler.runNextWithDelay(0L)
        assertTrue(logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") })
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())
        assertEquals(setOf("hashed-row"), checkpoint.processedRowKeys)
    }

    @Test
    fun processRestartWaitsForAndroidReadyBeforeTwoFreshCheckpointSnapshots() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        var androidStatus = TargetAppReadinessStatus.STARTING
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "MainActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480)
            )
        }
        val checkpoint = PrivateMessagesTraversalCheckpoint(
            attemptedSessionKeys = setOf("attempted-before-restart"),
            lockedUnreadSessionKeys = emptySet(),
            ambiguousSessionIdentityKeys = emptySet(),
            lastStableVisibleKeys = emptyList(),
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM.name,
            atTop = false,
            initialInboxPositionCheckCompleted = false,
            initialInboxTopPositioningCompleted = false,
            initialUnreadGateSwipeCompleted = false,
            lockedUnreadSetFinalized = false,
            normalInboxHadUnread = false,
            normalInboxUnreadStateUnknown = false
        )
        val repo = object : PrivateMessagesTraversalCheckpointRepository {
            override fun load(nowEpochMs: Long): PrivateMessagesTraversalCheckpoint = checkpoint
            override fun save(checkpoint: PrivateMessagesTraversalCheckpoint) = Unit
            override fun clear() = Unit
        }
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            inspectAndroidRecoveryReadiness = { snapshot ->
                testAndroidRecoveryReadiness(snapshot).copy(status = androidStatus)
            },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = ownership,
            currentRunGeneration = { 7L },
            privateMessagesCheckpointRepository = repo
        )
        runtime.scheduleInitialObservation()
        scheduler.runNextWithDelay(0L)
        assertTrue(logs.any { it.contains("ANDROID_READINESS_PENDING") && it.contains("STARTING") })
        assertFalse(logs.any { it.contains("RECOVERY_VERIFIED") })
        assertFalse(logs.any { it.contains("消息列表检查点恢复[CONTINUE]") })

        // Android only became READY after the first business observation; the first
        // eligible snapshot must start a NEW two-snapshot sequence.
        androidStatus = TargetAppReadinessStatus.READY
        scheduler.runNextWithDelay(500L)
        assertTrue(logs.any { it.contains("ANDROID_READINESS_CONFIRMED") })
        assertTrue(logs.any { it.contains("消息列表统一检查点[VERIFYING]") })
        assertFalse(logs.any { it.contains("RECOVERY_VERIFIED") })
        scheduler.runNextWithDelay(500L)
        scheduler.runNextWithDelay(0L)
        assertTrue(logs.any { it.contains("RECOVERY_EXECUTION_READY") })
        assertTrue(logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") })
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
    }

    @Test
    fun serviceInterruptionSavesCurrentCheckpointOnceAndResetDoesNotDeleteIt() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply { sessionRunEnabled = true }
        val logs = ArrayList<String>()
        var saved: PrivateMessagesTraversalCheckpoint? = null
        var saveCount = 0
        val repo = object : PrivateMessagesTraversalCheckpointRepository {
            override fun load(nowEpochMs: Long): PrivateMessagesTraversalCheckpoint? = saved
            override fun save(checkpoint: PrivateMessagesTraversalCheckpoint) {
                saved = checkpoint
                saveCount++
            }
            override fun clear() = Unit
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator()),
            currentRunGeneration = { 6L },
            privateMessagesCheckpointRepository = repo
        )
        runtime.scheduleInitialObservation()
        runtime.persistCheckpointBeforeServiceInterruption("SERVICE_INTERRUPTED")
        runtime.persistCheckpointBeforeServiceInterruption("SERVICE_DISCONNECTED")
        assertEquals(1, saveCount)
        assertTrue(logs.any { it.contains("SERVICE_BOUNDARY_CHECKPOINT_SAVED") })
        runtime.reset("无障碍服务中断")
        runtime.persistCheckpointBeforeServiceInterruption("SERVICE_DESTROYED")
        assertEquals(1, saveCount)
        assertTrue(saved != null)
    }


    private data class PreScrollPageRecoveryFixture(
        val scheduler: FakeScheduler,
        val runtime: AiReplyObservationRuntime,
        val port: FakeChatPort,
        val listPage: ChatPageSnapshot,
        val logs: MutableList<String>,
        val ownership: TaskCoordinator,
        val checkpoint: () -> PrivateMessagesTraversalCheckpoint?,
        val dispatchedGestures: () -> Int
    )

    private fun preScrollPageRecoveryFixture(): PreScrollPageRecoveryFixture {
        val scheduler = FakeScheduler()
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate(
                completeBoundaryEvidence = true, unreadStatus = ChatUnreadStatus.READ
            )),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = listPage
        }
        var saved: PrivateMessagesTraversalCheckpoint? = null
        val repository = object : PrivateMessagesTraversalCheckpointRepository {
            override fun load(nowEpochMs: Long): PrivateMessagesTraversalCheckpoint? = saved
            override fun save(checkpoint: PrivateMessagesTraversalCheckpoint) { saved = checkpoint }
            override fun clear() = Unit
        }
        val logs = mutableListOf<String>()
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        var gestures = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest, callback: GestureExecutionListener
                ): GestureSubmission {
                    gestures++
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = ownership,
            currentRunGeneration = { 6L },
            privateMessagesCheckpointRepository = repository
        )
        runtime.scheduleInitialObservation()
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        return PreScrollPageRecoveryFixture(
            scheduler, runtime, port, listPage, logs, ownership, { saved }, { gestures }
        )
    }

    private fun invokePreScrollPageValidation(fixture: PreScrollPageRecoveryFixture) {
        val method = fixture.runtime.javaClass.getDeclaredMethod(
            "scrollSessionList", ChatPageSnapshot::class.java, Boolean::class.javaPrimitiveType!!
        ).apply { isAccessible = true }
        method.invoke(fixture.runtime, fixture.listPage, false)
    }

    private fun awaitPreScrollRecovery(fixture: PreScrollPageRecoveryFixture) {
        var steps = 0
        while (fixture.logs.none { it.contains("RECOVERY_EXECUTION_READY") } &&
            steps < 80 && fixture.scheduler.pendingCount() > 0
        ) {
            // A 0ms page read must run before the recurring 500ms+ recovery timer.
            fixture.scheduler.runNextShortestDelay()
            steps++
        }
        assertTrue(
            "Recovery not reached after $steps steps; pending=${fixture.scheduler.pendingCount()}; " +
                "recentLogs=${fixture.logs.takeLast(45)}",
            fixture.logs.any { it.contains("RECOVERY_EXECUTION_READY") }
        )
    }

    private fun desktopPage() = ChatPageSnapshot(
        packageName = "com.bbk.launcher2",
        activityClass = "com.bbk.launcher2.Launcher",
        pageKind = ChatPageKind.UNKNOWN,
        scannedNodeCount = 12,
        editableInputCount = 0,
        messageListCount = 0,
        sendActionCount = 0,
        messageRelatedViewIdCount = 0,
        hasChatTab = false,
        messageListTarget = null,
        inputFieldTarget = null
    )

    @Test
    fun p4RecoverablePrivatePauseUsesSharedCheckpointAndWaitsForFreshInboxProof() {
        val fixture = preScrollPageRecoveryFixture()
        fixture.port.page = desktopPage()
        invokePreScrollPageValidation(fixture)
        assertTrue(fixture.logs.any {
            it.contains("PAUSED_CHECKPOINT_SAVED") &&
                it.contains("MESSAGE_LIST_PRE_SCROLL_PAGE_IDENTITY_MISMATCH")
        })
        assertTrue(fixture.logs.any {
            it.contains("AI 私聊暂停分类[RETRYABLE]") &&
                it.contains("MESSAGE_LIST_PRE_SCROLL_PAGE_IDENTITY_MISMATCH")
        })
        assertTrue("Retryable pause must save the original traversal cursor", fixture.checkpoint() != null)
        assertEquals("No stale gesture can be submitted during a pause", 0, fixture.dispatchedGestures())
        fixture.port.page = fixture.listPage
        awaitPreScrollRecovery(fixture)
        assertTrue(fixture.logs.any { it.contains("RECOVERY_EXECUTION_READY") })
    }

    @Test
    fun p7IncompleteUnreadRowCheckpointsAndBlocksGesturesWithoutPermanentIdentityBlacklist() {
        val fixture = preScrollPageRecoveryFixture()
        // Full-size visible private row, but it lacks a readable identity; a confirmed
        // UNREAD badge alone must not turn it into an eligible, completed or ignored row.
        val incomplete = sessionCandidate(nameText = "", completeBoundaryEvidence = true)
        val altered = fixture.listPage.copy(chatListCandidates = listOf(incomplete))
        val handle = fixture.runtime.javaClass.getDeclaredMethod(
            "handleSessionListPage", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        handle.invoke(fixture.runtime, altered)

        assertTrue(fixture.logs.any { it.contains("INCOMPLETE_UNREAD_RETRYABLE") })
        assertTrue(fixture.logs.any { it.contains("PRIVATE_INCOMPLETE_UNREAD_ROW") &&
            it.contains("PAUSED_CHECKPOINT_SAVED") })
        assertEquals(0, fixture.dispatchedGestures())
        val saved = requireNotNull(fixture.checkpoint())
        assertTrue(saved.unresolvedIncompleteUnreadKeys.isNotEmpty())
        assertTrue(saved.ambiguousSessionIdentityKeys.isEmpty())
        assertTrue(saved.attemptedSessionKeys.isEmpty())
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
    }

    @Test
    fun p0MixedListRetainsNamedAnomalyAndAllowsExistingStartupScroll() {
        val fixture = preScrollPageRecoveryFixture()
        val damaged = sessionCandidate(0, "Incomplete A", completeBoundaryEvidence = true,
            previewBoundsValid = false)
        val healthy = sessionCandidate(1, "Healthy B", completeBoundaryEvidence = true)
        val mixed = fixture.listPage.copy(chatListCandidates = listOf(damaged, healthy))
        fixture.port.page = mixed
        val handle = fixture.runtime.javaClass.getDeclaredMethod(
            "handleSessionListPage", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        handle.invoke(fixture.runtime, mixed)

        assertTrue(fixture.logs.any { it.contains("ROW_SCOPED_BLOCK") })
        assertFalse(fixture.logs.any { it.contains("PAUSED_CHECKPOINT_SAVED") &&
            it.contains("PRIVATE_INCOMPLETE_UNREAD_ROW") })
        assertEquals(1, fixture.dispatchedGestures())
        val saved = requireNotNull(fixture.checkpoint())
        assertEquals(1, saved.unresolvedIncompleteUnreadKeys.size)
        assertTrue(saved.attemptedSessionKeys.isEmpty())
        assertTrue(saved.ambiguousSessionIdentityKeys.isEmpty())
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
    }

    @Test
    fun p0PendingNamedAnomalyCannotCompletePrivateScanOrHandoff() {
        val fixture = preScrollPageRecoveryFixture()
        val executor = fixture.runtime.javaClass.getDeclaredField(
            "privateMessagesTraversalTaskExecutor"
        ).apply { isAccessible = true }.get(fixture.runtime) as PrivateMessagesTraversalTaskExecutor
        val damaged = sessionCandidate(0, "Incomplete A", completeBoundaryEvidence = true,
            previewBoundsValid = false)
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.atTop = true
        assertTrue(executor.observeIncompleteUnreadEvidence(
            fixture.listPage.copy(chatListCandidates = listOf(damaged))
        ))
        val handoff = fixture.runtime.javaClass.getDeclaredMethod(
            "startReceivedGreetingsTask", String::class.java
        ).apply { isAccessible = true }
        handoff.invoke(fixture.runtime, "P0_PENDING_INCOMPLETE")

        assertTrue(fixture.logs.any { it.contains("HANDOFF_BLOCKED") })
        assertFalse(fixture.logs.any { it.contains("PAUSED_CHECKPOINT_SAVED") })
        assertTrue(requireNotNull(fixture.checkpoint()).unresolvedIncompleteUnreadKeys.isNotEmpty())
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
        assertEquals(0, fixture.dispatchedGestures())
    }

    @Test
    fun p1RelocationTimerIsIntentOnlyAndReusesExistingScrollAfterFreshProof() {
        val fixture = preScrollPageRecoveryFixture()
        // The startup observation and the relocation timer both use 500ms. Isolate
        // the manually constructed top-of-list state from the fixture's startup probe.
        cancelFixtureStartupObservation(fixture)
        val executor = fixture.runtime.javaClass.getDeclaredField(
            "privateMessagesTraversalTaskExecutor"
        ).apply { isAccessible = true }.get(fixture.runtime) as PrivateMessagesTraversalTaskExecutor
        val damaged = sessionCandidate(0, "Incomplete A", completeBoundaryEvidence = true,
            previewBoundsValid = false)
        val healthy = sessionCandidate(1, "Healthy B", ChatUnreadStatus.READ, true)
        val mixed = fixture.listPage.copy(chatListCandidates = listOf(damaged, healthy))
        fixture.port.page = mixed
        executor.initialInboxPositionCheckCompleted = true
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.lockedUnreadSetFinalized = true
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.atTop = true
        assertTrue(executor.observeIncompleteUnreadEvidence(mixed))

        val continueList = fixture.runtime.javaClass.getDeclaredMethod(
            "continueSessionListWithoutCandidate", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        continueList.invoke(fixture.runtime, mixed)
        continueList.invoke(fixture.runtime, mixed)
        assertEquals(1, fixture.logs.count { it.contains("RELOCATION_WAIT") })
        assertEquals(0, fixture.dispatchedGestures())
        val delay = com.freedom.features.recovery.RecoveryScheduler.readOnlyRetryDelayMs(1)
        fixture.scheduler.runNextWithDelay(delay)
        assertEquals("Timer alone cannot dispatch a gesture", 0, fixture.dispatchedGestures())
        assertFalse(fixture.logs.any { it.contains("RELOCATION_STARTED") })

        // Simulate the subsequent confirmed fresh list observation with no candidate.
        continueList.invoke(fixture.runtime, mixed)
        assertTrue(fixture.logs.any { it.contains("RELOCATION_STARTED") })
        assertEquals(1, fixture.dispatchedGestures())
        val saved = requireNotNull(fixture.checkpoint())
        assertTrue(saved.fullListPollingRoundRequired)
        assertTrue(saved.unresolvedIncompleteUnreadKeys.isNotEmpty())
        assertTrue(saved.attemptedSessionKeys.isEmpty())
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
    }

    private fun cancelFixtureStartupObservation(fixture: PreScrollPageRecoveryFixture) {
        val field = fixture.runtime.javaClass.getDeclaredField("pageInspectionTask")
            .apply { isAccessible = true }
        (field.get(fixture.runtime) as? ScheduledTask)?.cancel()
        field.set(fixture.runtime, null)
    }

    @Test
    fun p1RelocationCannotReuseExpiredWindowIntent() {
        val fixture = preScrollPageRecoveryFixture()
        cancelFixtureStartupObservation(fixture)
        val executor = fixture.runtime.javaClass.getDeclaredField(
            "privateMessagesTraversalTaskExecutor"
        ).apply { isAccessible = true }.get(fixture.runtime) as PrivateMessagesTraversalTaskExecutor
        fun page(generation: Long) = fixture.listPage.copy(
            windowGeneration = generation,
            chatListCandidates = listOf(
                sessionCandidate(0, "Incomplete A", completeBoundaryEvidence = true,
                    windowGeneration = generation, previewBoundsValid = false),
                sessionCandidate(1, "Healthy B", ChatUnreadStatus.READ, true, generation)
            )
        )
        val original = page(1L)
        fixture.port.page = original
        executor.initialInboxPositionCheckCompleted = true
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        executor.lockedUnreadSetFinalized = true
        executor.phase = SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP
        executor.atTop = true
        assertTrue(executor.observeIncompleteUnreadEvidence(original))
        val continueList = fixture.runtime.javaClass.getDeclaredMethod(
            "continueSessionListWithoutCandidate", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        continueList.invoke(fixture.runtime, original)
        fixture.scheduler.runNextWithDelay(
            com.freedom.features.recovery.RecoveryScheduler.readOnlyRetryDelayMs(1)
        )
        val replacement = page(2L)
        fixture.port.page = replacement
        continueList.invoke(fixture.runtime, replacement)
        assertEquals(0, fixture.dispatchedGestures())
        assertFalse(fixture.logs.any { it.contains("RELOCATION_STARTED") })
        assertEquals(2, fixture.logs.count { it.contains("RELOCATION_WAIT") })
        assertTrue(requireNotNull(fixture.checkpoint()).unresolvedIncompleteUnreadKeys.isNotEmpty())
        assertEquals(SessionListTraversalPhase.SCANNING_BOTTOM_TO_TOP, executor.phase)
    }

    @Test
    fun p2OriginalIncompleteFaultKeepsSameRecoveryUntilFreshRepair() {
        val fixture = preScrollPageRecoveryFixture()
        cancelFixtureStartupObservation(fixture)
        val damaged = sessionCandidate(0, "Incomplete A", completeBoundaryEvidence = true,
            previewBoundsValid = false)
        val blocked = fixture.listPage.copy(chatListCandidates = listOf(damaged))
        fixture.port.page = blocked
        val handle = fixture.runtime.javaClass.getDeclaredMethod(
            "handleSessionListPage", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        handle.invoke(fixture.runtime, blocked)
        val saved = requireNotNull(fixture.checkpoint())
        assertTrue(saved.unresolvedIncompleteUnreadKeys.isNotEmpty())
        var steps = 0
        while (fixture.logs.count { it.contains("ORIGINAL_FAULT_WAIT") } < 2 &&
            steps < 80 && fixture.scheduler.pendingCount() > 0
        ) {
            fixture.scheduler.runNextShortestDelay()
            steps++
        }
        assertTrue("Original fault wait not observed: ${fixture.logs.takeLast(25)}",
            fixture.logs.count { it.contains("ORIGINAL_FAULT_WAIT") } >= 2)
        assertEquals(1, fixture.logs.count { it.contains("PAUSED_CHECKPOINT_SAVED") &&
            it.contains("PRIVATE_INCOMPLETE_UNREAD_ROW") })
        assertFalse(fixture.logs.any { it.contains("RECOVERY_EXECUTION_READY") })
        assertEquals(saved.unresolvedIncompleteUnreadKeys, requireNotNull(fixture.checkpoint()).unresolvedIncompleteUnreadKeys)
        assertEquals(0, fixture.dispatchedGestures())

        fixture.port.page = fixture.listPage.copy(chatListCandidates = listOf(
            sessionCandidate(0, "Incomplete A", completeBoundaryEvidence = true)
        ))
        awaitPreScrollRecovery(fixture)
        assertTrue(fixture.logs.any { it.contains("ORIGINAL_FAULT_RECHECKED") &&
            it.contains("evidence=CLEARED") })
        assertTrue(requireNotNull(fixture.checkpoint()).unresolvedIncompleteUnreadKeys.isEmpty())
    }

    @Test
    fun p6TimedPollingWakeupSchedulesReadOnlyInspectionWithoutNewMessages() {
        val fixture = preScrollPageRecoveryFixture()
        val enterWait = fixture.runtime.javaClass.getDeclaredMethod(
            "enterUnreadWait", String::class.java
        ).apply { isAccessible = true }
        enterWait.invoke(fixture.runtime, "P6_TIMER_ONLY")
        val scheduleLog = fixture.logs.last { it.contains("AI Reply 低频待机：") }
        val waitSeconds = requireNotNull(
            Regex("低频待机：(\\d+) 秒后").find(scheduleLog)
        ).groupValues[1].toInt()
        assertTrue("Existing idle polling interval must remain 60-120 seconds",
            waitSeconds in 60..120)
        assertTrue(fixture.scheduler.hasPendingDelay(waitSeconds * 1_000L))
        assertEquals(0, fixture.dispatchedGestures())

        // No accessibility event or unread callback is delivered. The saved timer alone
        // must request a new, read-only inbox snapshot.
        fixture.scheduler.runNextWithDelay(waitSeconds * 1_000L)
        assertTrue(fixture.logs.any { it.contains("自主轮询[ROUND_DUE]") })
        assertTrue(fixture.scheduler.hasPendingDelay(0L))
        assertEquals(0, fixture.dispatchedGestures())
    }

    @Test
    fun p6TimedFullScanCannotHandoffOnOneViewportWithNoUnread() {
        val fixture = preScrollPageRecoveryFixture()
        val executor = fixture.runtime.javaClass.getDeclaredField(
            "privateMessagesTraversalTaskExecutor"
        ).apply { isAccessible = true }.get(fixture.runtime) as PrivateMessagesTraversalTaskExecutor

        // The timed round begins after the verified inbox wakeup in production.
        // Recreate its pure traversal state, including the original first-swipe proof.
        executor.beginNextPollingRound()
        executor.initialInboxTopPositioningCompleted = true
        executor.initialUnreadGateSwipeCompleted = true
        assertTrue(executor.fullListPollingRoundRequired)
        assertTrue(executor.lockedUnreadSessionKeys.isEmpty())
        assertFalse(executor.normalInboxHadUnread)

        val handoff = fixture.runtime.javaClass.getDeclaredMethod(
            "startReceivedGreetingsTask", String::class.java
        ).apply { isAccessible = true }
        handoff.invoke(fixture.runtime, "P6_ONE_EMPTY_VIEWPORT")

        assertTrue("A full polling scan may not skip unobserved lower viewports",
            fixture.logs.any { it.contains("PRIVATE_SCAN_NOT_COMPLETE_FOR_HANDOFF") })
        assertTrue(fixture.checkpoint()?.fullListPollingRoundRequired == true)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
        assertEquals(0, fixture.dispatchedGestures())
    }

    @Test
    fun p4IncompletePrivateScanDoesNotHandOffToGreetingsOrTerminate() {
        val fixture = preScrollPageRecoveryFixture()
        val beginGreetings = fixture.runtime.javaClass.getDeclaredMethod(
            "startReceivedGreetingsTask", String::class.java
        ).apply { isAccessible = true }
        beginGreetings.invoke(fixture.runtime, "P4_INCOMPLETE_PRIVATE_SCAN")
        assertTrue(fixture.logs.any { it.contains("PRIVATE_SCAN_NOT_COMPLETE_FOR_HANDOFF") })
        assertTrue(fixture.logs.any { it.contains("PAUSED_CHECKPOINT_SAVED") &&
            it.contains("PRIVATE_SCAN_NOT_COMPLETE_FOR_HANDOFF") })
        assertTrue(fixture.checkpoint() != null)
        assertEquals("Incomplete private scan must not enter greetings", "AI_REPLY_PRIVATE_MESSAGES",
            fixture.ownership.activeTaskId())
        assertEquals(0, fixture.dispatchedGestures())
    }

    @Test
    fun p4ExplicitUserStopDoesNotRearmRecoverablePrivatePause() {
        val fixture = preScrollPageRecoveryFixture()
        fixture.port.page = desktopPage()
        invokePreScrollPageValidation(fixture)
        val savedPauses = fixture.logs.count { it.contains("PAUSED_CHECKPOINT_SAVED") }
        assertTrue(savedPauses > 0)
        fixture.runtime.reset("用户主动停止")
        val invoke = fixture.runtime.javaClass.getDeclaredMethod(
            "recoverRetryablePrivateList", String::class.java
        ).apply { isAccessible = true }
        invoke.invoke(fixture.runtime, "PRIVATE_STOPPED_CALLBACK")
        assertEquals(savedPauses, fixture.logs.count { it.contains("PAUSED_CHECKPOINT_SAVED") })
        assertEquals(0, fixture.dispatchedGestures())
        assertTrue(fixture.logs.none { it.contains("PRIVATE_STOPPED_CALLBACK") })
    }

    @Test
    fun preScrollDesktopReturnUsesCheckpointRecoveryWithoutOldGesture() {
        val fixture = preScrollPageRecoveryFixture()
        fixture.port.page = desktopPage()
        invokePreScrollPageValidation(fixture)
        assertTrue(fixture.logs.any {
            it.contains("PAUSED_CHECKPOINT_SAVED") &&
                it.contains("MESSAGE_LIST_PRE_SCROLL_PAGE_IDENTITY_MISMATCH")
        })
        assertTrue(fixture.checkpoint() != null)
        assertEquals(0, fixture.dispatchedGestures())

        // The launcher cannot authorize recovery or a previously queued list scroll.
        repeat(2) {
            if (fixture.scheduler.pendingCount() > 0) fixture.scheduler.runNextShortestDelay()
        }
        assertFalse(fixture.logs.any { it.contains("RECOVERY_EXECUTION_READY") })
        assertEquals(0, fixture.dispatchedGestures())

        fixture.port.page = fixture.listPage
        fixture.runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        awaitPreScrollRecovery(fixture)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
        assertTrue(fixture.logs.any {
            it.contains("统一页面恢复[RECOVERY_VERIFIED]") &&
                it.contains("AI_REPLY_PRIVATE_MESSAGES")
        })
    }

    @Test
    fun preScrollChatDetailDoesNotResumeUntilOriginalListIsVerified() {
        val fixture = preScrollPageRecoveryFixture()
        fixture.port.page = desktopPage()
        invokePreScrollPageValidation(fixture)
        assertTrue(fixture.checkpoint() != null)
        fixture.port.page = chatDetailSnapshot()
        repeat(3) {
            if (fixture.scheduler.pendingCount() > 0) fixture.scheduler.runNextShortestDelay()
        }
        assertFalse(fixture.logs.any { it.contains("RECOVERY_EXECUTION_READY") })
        assertEquals(0, fixture.dispatchedGestures())

        fixture.port.page = fixture.listPage
        fixture.runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        awaitPreScrollRecovery(fixture)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
    }

    @Test
    fun preScrollSnapshotReadFailureAlsoEntersReadOnlyCheckpointRecovery() {
        val fixture = preScrollPageRecoveryFixture()
        fixture.port.failNextInspection = true
        invokePreScrollPageValidation(fixture)
        assertTrue(fixture.logs.any {
            it.contains("PAUSED_CHECKPOINT_SAVED") &&
                it.contains("MESSAGE_LIST_PRE_SCROLL_SNAPSHOT_UNAVAILABLE")
        })
        assertTrue(fixture.checkpoint() != null)
        assertEquals(0, fixture.dispatchedGestures())

        awaitPreScrollRecovery(fixture)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", fixture.ownership.activeTaskId())
    }

    @Test
    fun cancelledRejectedAndFailedScrollsEnterCheckpointRecoveryInsteadOfTerminalPause() {
        for (status in listOf(
            GestureExecutionStatus.CANCELLED, GestureExecutionStatus.REJECTED,
            GestureExecutionStatus.FAILED
        )) {
            val fixture = capturedScrollRecoveryFixture()
            fixture.listener.onResult(GestureExecutionResult(fixture.request.id, status))
            runUntilLog(fixture.scheduler, fixture.logs, "GESTURE_RECOVERY_REQUESTED")
            assertTrue("No checkpoint after $status", fixture.savedCheckpoint() != null)
            assertTrue(fixture.logs.any {
                it.contains("PAUSED_CHECKPOINT_SAVED") && it.contains("GESTURE_SCROLL_")
            })
            assertEquals("A failed scroll must not be immediately replayed", 1, fixture.dispatchedGestures())
            assertFalse(fixture.logs.any { it.contains("达到恢复上限") })
        }
    }

    @Test
    fun windowTransitionDuringScrollInvalidatesOldGestureCallbackAndKeepsCheckpoint() {
        val fixture = capturedScrollRecoveryFixture()
        fixture.runtime.schedule(event(AccessibilityEventType.WINDOWS_CHANGED))
        assertTrue(fixture.logs.any {
            it.contains("GESTURE_RECOVERY_REQUESTED") && it.contains("GESTURE_WINDOW_CHANGED")
        })
        assertTrue(fixture.savedCheckpoint() != null)
        // A delayed completion from the old window must not be interpreted as a success.
        fixture.listener.onResult(
            GestureExecutionResult(fixture.request.id, GestureExecutionStatus.COMPLETED)
        )
        runUntilLog(fixture.scheduler, fixture.logs, "已忽略过期自主滑动回调")
        assertEquals(1, fixture.dispatchedGestures())
        assertFalse(fixture.logs.any { it.contains("首次向上准入滑动已完成") })
    }

    @Test
    fun restoredMessageListWaitsForSharedCheckpointVerificationBeforeExistingTraversal() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "MainActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480)
            )
        }
        val saved = PrivateMessagesTraversalCheckpoint(
            attemptedSessionKeys = setOf("already-attempted-hash"),
            lockedUnreadSessionKeys = emptySet(),
            ambiguousSessionIdentityKeys = emptySet(),
            lastStableVisibleKeys = emptyList(),
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM.name,
            atTop = false,
            initialInboxPositionCheckCompleted = false,
            initialInboxTopPositioningCompleted = false,
            initialUnreadGateSwipeCompleted = false,
            lockedUnreadSetFinalized = false,
            normalInboxHadUnread = false,
            normalInboxUnreadStateUnknown = false
        )
        val repository = object : PrivateMessagesTraversalCheckpointRepository {
            var current: PrivateMessagesTraversalCheckpoint? = saved
            override fun load(nowEpochMs: Long): PrivateMessagesTraversalCheckpoint? = current
            override fun save(checkpoint: PrivateMessagesTraversalCheckpoint) { current = checkpoint }
            override fun clear() { current = null }
        }
        var gestures = 0
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestures++
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = ownership,
            currentRunGeneration = { 7L },
            privateMessagesCheckpointRepository = repository
        )
        runtime.scheduleInitialObservation()
        // P5: a task slot may already be reserved, but a matching self-navigation
        // attribution must not turn that reservation into permission to execute.
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        runtime.onSelfNavigationAttached(
            SelfNavigationTransition(
                actionId = "self-nav-before-checkpoint-verification",
                sourcePageGeneration = 0L,
                targetActivityClass = port.page.activityClass,
                targetPageGeneration = port.page.windowGeneration,
                targetWindowId = -1,
                transitionAtUptimeMs = 1L
            )
        )
        scheduler.runNextWithDelay(0L)
        assertTrue(logs.any { it.contains("SELF_NAVIGATION_NOT_PROOF") })
        assertFalse(logs.any { it.contains("SELF_NAVIGATION_CONSUMED") })
        assertTrue(logs.any { it.contains("消息列表统一检查点[VERIFYING]") })
        assertFalse(logs.any { it.contains("消息列表检查点恢复[CONTINUE]") })
        assertEquals(0, gestures)

        // P5: the reserved private-message lease must not authorize an old queued
        // scroll while the checkpoint is still waiting for its second fresh sample.
        val readsBeforeBlockedScroll = port.fullInspections
        val scrollMethod = runtime.javaClass.getDeclaredMethod(
            "scrollSessionList", ChatPageSnapshot::class.java, Boolean::class.javaPrimitiveType!!
        ).apply { isAccessible = true }
        scrollMethod.invoke(runtime, port.page, false)
        assertEquals(readsBeforeBlockedScroll, port.fullInspections)
        assertEquals(0, gestures)

        scheduler.runNextWithDelay(500L)
        scheduler.runNextWithDelay(0L)
        assertTrue(logs.any { it.contains("消息列表检查点恢复[CONTINUE]") })
        assertTrue(logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") })
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
    }


    private data class CrossTaskRecoveryFixture(
        val runtime: AiReplyObservationRuntime,
        val scheduler: FakeScheduler,
        val port: FakeChatPort,
        val ownership: TaskCoordinator,
        val manager: UnifiedRecoveryTransactionManager,
        val repository: PrivateMessagesTraversalCheckpointRepository,
        val originalCheckpoint: PrivateMessagesTraversalCheckpoint,
        val logs: MutableList<String>
    )

    private fun crossTaskRecoveryFixture(
        arrivalStatus: ReceivedGreetingsPageArrivalStatus = ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED,
        adapterConfirmation: Boolean = true
    ): CrossTaskRecoveryFixture {
        val scheduler = FakeScheduler()
        val logs = mutableListOf<String>()
        val original = PrivateMessagesTraversalCheckpoint(
            attemptedSessionKeys = setOf("processed-before-handoff"),
            lockedUnreadSessionKeys = emptySet(),
            ambiguousSessionIdentityKeys = emptySet(),
            lastStableVisibleKeys = emptyList(),
            phase = SessionListTraversalPhase.POSITIONING_AT_BOTTOM.name,
            atTop = false,
            initialInboxPositionCheckCompleted = false,
            initialInboxTopPositioningCompleted = false,
            initialUnreadGateSwipeCompleted = false,
            lockedUnreadSetFinalized = false,
            normalInboxHadUnread = false,
            normalInboxUnreadStateUnknown = false
        )
        val repository = object : PrivateMessagesTraversalCheckpointRepository {
            private var value = original
            override fun load(nowEpochMs: Long): PrivateMessagesTraversalCheckpoint = value
            override fun save(checkpoint: PrivateMessagesTraversalCheckpoint) { value = checkpoint }
            override fun clear() = error("Cross-task handoff must never erase the private checkpoint")
        }
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
            receivedGreetingsCheckpointFingerprint = "sayhi-window-1110-generation-12"
            receivedGreetingsRecoverySyncAccepted = adapterConfirmation
            manualGreetingsArrivalStatus = arrivalStatus
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 144,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null,
                windowGeneration = 12L
            )
        }
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        val manager = UnifiedRecoveryTransactionManager({ 7L }, logs::add)
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = ownership,
            currentRunGeneration = { 7L },
            privateMessagesCheckpointRepository = repository,
            recoveryTransactionManager = manager
        )
        return CrossTaskRecoveryFixture(runtime, scheduler, port, ownership, manager, repository, original, logs)
    }

    @Test
    fun pendingPrivateCheckpointTransfersOnlyAfterIndependentGreetingsEvidenceAndKeepsPrimaryRecovery() {
        val fixture = crossTaskRecoveryFixture()
        val (runtime, scheduler, port, ownership, manager, repository, original, logs) = fixture
        runtime.scheduleInitialObservation()
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        val originalRecovery = manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")
        assertTrue(originalRecovery != null)

        scheduler.runNextWithDelay(0L)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_VERIFYING") })
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())

        scheduler.runNextWithDelay(350L)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_TRANSFERRED") })
        assertTrue(logs.any {
            it.contains("GREETING_LIST_READY") && it.contains("source=CROSS_TASK_VERIFIED")
        })
        assertFalse(logs.any { it.contains("PRIVATE_TO_GREETINGS_ROLLBACK") })
        assertEquals(original, repository.load())
        assertEquals(originalRecovery?.checkpointId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertEquals(null, manager.snapshot("AI_REPLY_RECEIVED_GREETINGS"))
    }

    @Test
    fun failedCrossTaskStartUsesReadOnlyBackoffWithoutRepeatedLeaseRollbacks() {
        val (runtime, scheduler, port, ownership, manager, repository, original, logs) =
            crossTaskRecoveryFixture()
        port.beginCurrentListResult = false
        runtime.scheduleInitialObservation()
        val originalRecoveryId = manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId

        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertEquals(null, ownership.activeTaskId())
        assertEquals(original, repository.load())
        assertEquals(originalRecoveryId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_ROLLBACK") })
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_READ_ONLY_WAIT") &&
            it.contains("reason=GREETING_START_FAILED") && it.contains("delayMs=500") })

        // Extra content events on the unchanged page are read-only; no lease churn.
        repeat(2) {
            runtime.schedule(event(AccessibilityEventType.WINDOW_CONTENT_CHANGED))
            scheduler.runNextWithDelay(300L)
        }
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(originalRecoveryId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)

        port.beginCurrentListResult = true
        scheduler.runNextWithDelay(500L)
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals(2, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())
        assertEquals(original, repository.load())
        assertEquals(originalRecoveryId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_TRANSFERRED") })
    }

    @Test
    fun repeatedCrossTaskStartFailuresBackOffWithoutLosingPrivateCheckpointOrReplayingTask() {
        val (runtime, scheduler, port, ownership, manager, repository, original, logs) =
            crossTaskRecoveryFixture()
        port.beginCurrentListResult = false
        runtime.scheduleInitialObservation()
        val originalRecoveryId = manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId
        assertTrue(originalRecoveryId != null)

        // Initial double-snapshot verification completes, but adapter start still rejects.
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertEquals(originalRecoveryId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertEquals(original, repository.load())

        // First read-only timer (500ms) permits a NEW two-snapshot verification.
        scheduler.runNextWithDelay(500L)
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals(2, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertEquals(2, logs.count { it.contains("PRIVATE_TO_GREETINGS_ROLLBACK") })
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_READ_ONLY_WAIT") &&
            it.contains("attempt=2") && it.contains("delayMs=1000") })
        assertEquals(originalRecoveryId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertEquals(original, repository.load())
        assertEquals(null, ownership.activeTaskId())

        // More events on the unchanged page cannot bypass the queued 1000ms observation.
        runtime.schedule(event(AccessibilityEventType.WINDOW_CONTENT_CHANGED))
        scheduler.runNextWithDelay(300L)
        assertEquals(2, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)

        port.beginCurrentListResult = true
        scheduler.runNextWithDelay(1_000L)
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals(3, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertEquals(2, logs.count { it.contains("PRIVATE_TO_GREETINGS_ROLLBACK") })
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())
        assertEquals(originalRecoveryId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertEquals(original, repository.load())
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_TRANSFERRED") })
    }

    @Test
    fun adapterAdmissionBlockKeepsPrivateLeaseAndRetriesOnlyAfterReadOnlyWait() {
        val (runtime, scheduler, port, ownership, manager, repository, original, logs) =
            crossTaskRecoveryFixture(adapterConfirmation = false)
        runtime.scheduleInitialObservation()
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        assertEquals(original, repository.load())
        assertTrue(manager.snapshot("AI_REPLY_PRIVATE_MESSAGES") != null)
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_READ_ONLY_WAIT") &&
            it.contains("reason=ADAPTER_ADMISSION_NOT_READY") })

        port.receivedGreetingsRecoverySyncAccepted = true
        scheduler.runNextWithDelay(500L)
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())
        assertTrue(logs.any { it.contains("PRIVATE_TO_GREETINGS_TRANSFERRED") })
        assertEquals(original, repository.load())
    }

    @Test
    fun incompleteOrUnsynchronizedGreetingsEvidenceCannotPreemptPendingPrivateRecovery() {
        listOf(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING to true,
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED to false
        ).forEach { (arrival, adapterConfirmed) ->
            val (runtime, scheduler, port, ownership, manager, repository, original, logs) =
                crossTaskRecoveryFixture(arrival, adapterConfirmed)
            runtime.scheduleInitialObservation()
            scheduler.runNextWithDelay(0L)
            scheduler.runNextWithDelay(350L)
            assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
            assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
            assertEquals(0, port.receivedGreetingsIdleSteps)
            assertEquals(original, repository.load())
            assertTrue(manager.snapshot("AI_REPLY_PRIVATE_MESSAGES") != null)
            assertFalse(logs.any { it.contains("PRIVATE_TO_GREETINGS_TRANSFERRED") })
        }
    }

    @Test
    fun completedGreetingsRearmsOriginalPrivateCheckpointAndRequiresFreshResumeProof() {
        val fixture = crossTaskRecoveryFixture()
        val (runtime, scheduler, port, ownership, manager, repository, original, logs) = fixture
        runtime.scheduleInitialObservation()
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())

        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        runUntilLog(scheduler, logs, "收到的招呼任务[RETURN_PENDING]")
        assertEquals(1, port.receivedGreetingsPriorityReturns)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())
        assertEquals(0, port.receivedGreetingsConfirmedReturns)

        // A second observation of the unchanged source must not submit another BACK or
        // retire the traversal checkpoint before the independently confirmed destination.
        runUntilLog(scheduler, logs, "result=WAITING_FOR_PAGE_CONFIRMATION")
        assertEquals(1, port.receivedGreetingsPriorityReturns)
        assertEquals(0, port.receivedGreetingsConfirmedReturns)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())

        // An empty/UNKNOWN observation during completion must preserve the return lifecycle,
        // not re-enter the list traversal or strand the original private checkpoint.
        port.failNextInspection = true
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runUntilLog(scheduler, logs, "收到的招呼返回恢复[HOLD]")
        assertEquals(1, port.receivedGreetingsPriorityReturns)
        assertEquals(0, port.receivedGreetingsConfirmedReturns)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())

        port.receivedGreetingsCheckpointFingerprint = null
        port.provideMessageTabPositionEvidence = true
        port.page = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(
                sessionCandidate(
                    unreadStatus = ChatUnreadStatus.READ,
                    completeBoundaryEvidence = true,
                    windowGeneration = 20L
                )
            ),
            screenBounds = ScreenRect(0, 0, 720, 1600),
            windowGeneration = 20L
        )
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runUntilLog(scheduler, logs, "收到的招呼返回恢复[RESUMED]")
        assertEquals(0, port.receivedGreetingsConfirmedReturns)
        runUntilLog(scheduler, logs, "跨任务交接[INBOX_VERIFYING]")
        assertEquals(0, port.receivedGreetingsConfirmedReturns)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", ownership.activeTaskId())
        runUntilLog(scheduler, logs, "跨任务恢复[PRIVATE_CHECKPOINT_REARMED]")
        assertEquals(1, port.receivedGreetingsConfirmedReturns)
        assertEquals(null, ownership.activeTaskId())
        assertEquals(original, repository.load())
        assertTrue(manager.snapshot("AI_REPLY_PRIVATE_MESSAGES") != null)
        assertFalse(logs.any { it.contains("已从任务=AI_REPLY_PRIVATE_MESSAGES恢复任务=AI_REPLY_PRIVATE_MESSAGES") })

        // Force two distinct fresh page observations. The fake scheduler does not model
        // wall-clock priority: iterating runNext() can starve immediate page reads behind
        // recurring long-backoff observation tasks while keeping the source gate unchanged.
        repeat(6) {
            if (logs.none { it.contains("消息列表统一检查点[VERIFYING]") } &&
                scheduler.hasPendingDelay(0L)
            ) scheduler.runNextWithDelay(0L)
        }
        assertTrue("Private checkpoint must request a fresh first sample", logs.any {
            it.contains("消息列表统一检查点[VERIFYING]")
        })
        assertEquals(null, ownership.activeTaskId())
        repeat(4) {
            if (logs.none { it.contains("统一页面恢复[RECOVERY_VERIFIED]") }) {
                // A new content snapshot in the SAME window is independent evidence.
                // WINDOW_STATE_CHANGED intentionally invalidates the first sample; it must
                // be used only for the actual return transition, not each stable sample.
                runtime.schedule(event(AccessibilityEventType.WINDOW_CONTENT_CHANGED))
                scheduler.runNextWithDelay(300L)
            }
        }
        assertTrue(
            "Private task must only resume after second independent list proof; recent logs=${logs.takeLast(35)}",
            logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") }
        )
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        assertEquals(null, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES"))
        // The checkpoint is identical while suspended and while rearming; AFTER verified
        // resumption the private inbox is allowed to advance and persist a new row/position.
        // Never lose or re-process the already-attempted identities from the original cursor.
        val resumedCheckpoint = repository.load()
        assertTrue(resumedCheckpoint != null)
        assertEquals(original.attemptedSessionKeys, resumedCheckpoint?.attemptedSessionKeys)
        assertEquals(original.lockedUnreadSessionKeys, resumedCheckpoint?.lockedUnreadSessionKeys)
        assertTrue(resumedCheckpoint!!.initialInboxPositionCheckCompleted)
        assertTrue(resumedCheckpoint.lastStableVisibleKeys.isNotEmpty())
        assertTrue(resumedCheckpoint.savedAtEpochMs >= original.savedAtEpochMs)
    }

    /**
     * Regression for the real-device message-tab -> dynamics -> message-tab path. AI Agent
     * must treat the dynamic surface as NON_CHAT; a private checkpoint stays dormant there,
     * and the return to the inbox must use two NEW same-window snapshots to resume.
     * This fixture does not claim that AI Agent should itself run AccountBrowse.
     */
    @Test
    fun dynamicsTabDoesNotResumePrivateCheckpointUntilFreshInboxReturn() {
        val fixture = crossTaskRecoveryFixture()
        val (runtime, scheduler, port, ownership, manager, repository, original, logs) = fixture
        port.receivedGreetingsCheckpointFingerprint = null
        port.manualGreetingsArrivalStatus = ReceivedGreetingsPageArrivalStatus.NOT_TARGET
        port.page = port.page.copy(
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.NON_CHAT,
            windowGeneration = 12L
        )

        runtime.scheduleInitialObservation()
        val pending = manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")
        assertTrue(pending != null)
        scheduler.runNextWithDelay(0L)
        assertTrue(logs.any { it.contains("消息列表统一检查点[ROUTE_WAIT]") })

        // Scroll/change events from the dynamics tab never grant the AI private/greetings task.
        repeat(2) {
            runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN))
            scheduler.runNextWithDelay(300L)
        }
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertEquals(0, port.messageReads)
        assertEquals(pending?.checkpointId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertEquals(original, repository.load())
        assertFalse(logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") })

        port.page = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate(
                unreadStatus = ChatUnreadStatus.READ,
                completeBoundaryEvidence = true,
                windowGeneration = 20L
            )),
            screenBounds = ScreenRect(0, 0, 720, 1600),
            windowGeneration = 20L
        )

        // Actual navigation invalidates any earlier window stability proof.
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runUntilLog(scheduler, logs, "消息列表统一检查点[VERIFYING]")
        assertFalse(logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") })
        assertEquals(pending?.checkpointId, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES")?.checkpointId)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)

        repeat(4) {
            if (logs.none { it.contains("统一页面恢复[RECOVERY_VERIFIED]") }) {
                runtime.schedule(event(AccessibilityEventType.WINDOW_CONTENT_CHANGED))
                scheduler.runNextWithDelay(300L)
            }
        }
        assertTrue("Message list must resume only after two fresh inbox samples; logs=${logs.takeLast(30)}",
            logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") })
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        assertEquals(null, manager.snapshot("AI_REPLY_PRIVATE_MESSAGES"))
        assertEquals(original.attemptedSessionKeys, repository.load()?.attemptedSessionKeys)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
    }

    /** A stale or coincidental route fingerprint is not a confirmed greetings-list arrival. */
    @Test
    fun dynamicsPageCannotStealPendingPrivateCheckpointWithUnconfirmedGreetingsAppearance() {
        val (runtime, scheduler, port, ownership, manager, repository, original, logs) =
            crossTaskRecoveryFixture(arrivalStatus = ReceivedGreetingsPageArrivalStatus.NOT_TARGET)
        port.page = port.page.copy(
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.NON_CHAT
        )
        // Mimic a stale positive route fingerprint: the independent list recognizer still
        // denies the page, so even two repeated observations may not transfer UI ownership.
        runtime.scheduleInitialObservation()
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(350L)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        assertTrue(manager.snapshot("AI_REPLY_PRIVATE_MESSAGES") != null)
        assertEquals(original, repository.load())
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertFalse(logs.any { it.contains("PRIVATE_TO_GREETINGS_TRANSFERRED") })
    }

    @Test
    fun manuallyOpenedReceivedGreetingsListTakesPriorityOverMessageTabRecovery() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 20,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null
            )
            manualGreetingsArrivalStatus = ReceivedGreetingsPageArrivalStatus.WAITING_FOR_USER_NAVIGATION
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
        }
        var gestureCount = 0
        val logs = ArrayList<String>()
        val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestureCount++
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = taskCoordinator,
            currentRunGeneration = { 5L }
        )

        runtime.scheduleInitialObservation()
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", taskCoordinator.activeTaskId())
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN))
        scheduler.runNextWithDelay(300L)

        assertEquals(1, port.manualGreetingsArrivalObservations)
        assertEquals(0, gestureCount)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertFalse("Stabilizing list is only read-only evidence, not an operation lease",
            logs.any { it.contains("GREETING_LIST_READY") })

        port.manualGreetingsArrivalStatuses = listOf(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
        )
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN))
        scheduler.runNextWithDelay(300L)
        assertEquals(0, gestureCount)
        scheduler.runNextWithDelay(350L)

        assertEquals(0, gestureCount)
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertEquals(0, port.receivedGreetingsNavigationTaskStarts)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", taskCoordinator.activeTaskId())
        assertTrue(logs.any { it.contains("收到的招呼任务[STARTED]") })
        assertTrue(logs.any {
            it.contains("GREETING_LIST_READY") && it.contains("source=PAGE_OBSERVATION")
        })

        // Completion retains the task lease and requests the existing safe return path.
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        scheduler.runNextWithDelay(1_300L)
        assertEquals(1, port.receivedGreetingsPriorityReturns)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", taskCoordinator.activeTaskId())
        assertTrue(logs.any { it.contains("收到的招呼任务[RETURN_PENDING]") })
    }


    @Test
    fun confirmedGreetingsListRequiresAdapterRecheckBeforeRetainingTaskOwnership() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            manualGreetingsArrivalStatuses = listOf(
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
            beginCurrentListResult = false
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 20,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null
            )
        }
        val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = taskCoordinator,
            currentRunGeneration = { 5L }
        )

        runtime.scheduleInitialObservation()
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN))
        scheduler.runNextWithDelay(300L)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertFalse(logs.any { it.contains("GREETING_LIST_READY") })
        scheduler.runNextWithDelay(350L)

        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertNull("Adapter rejected the current-window list proof; no usable UI lease remains",
            taskCoordinator.activeTaskId())
        assertFalse(logs.any { it.contains("GREETING_LIST_READY") })
        assertTrue(logs.any { it.contains("GREETING_LIST_ADMISSION_REJECTED reason=START_FAILED") })
    }

    /**
     * Reproduces the device trace: a manually confirmed greetings list is NON_CHAT,
     * the avatar tap opens a profile, then dialog.h advances the observed page generation.
     * The generic source ticket no longer matches; the exact adapter proof must preserve
     * the already-owned task so its existing safe profile BACK stage can run.
     */
    @Test
    fun verifiedGreetingAvatarProfileStaysInOwnedWorkflowAcrossDialogGeneration() {
        val result = runGreetingAvatarProfileRouteCase(
            profileVerified = true,
            stage = ReceivedGreetingsTraversalStatus.NAVIGATING
        )
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", result.owner)
        assertTrue(result.idleStepsAfterProfile > result.idleStepsBeforeProfile)
        assertTrue(result.logs.any { it.contains("IDENTITY_PROFILE_VERIFIED") })
        assertFalse(result.logs.any { it.contains("统一恢复[PAUSED_CHECKPOINT_SAVED]") })
    }

    @Test
    fun unverifiedOrNonNavigatingGreetingProfileMustKeepRouteRecoveryGate() {
        listOf(
            false to ReceivedGreetingsTraversalStatus.NAVIGATING,
            true to ReceivedGreetingsTraversalStatus.SCANNING
        ).forEach { (profileVerified, stage) ->
            val result = runGreetingAvatarProfileRouteCase(profileVerified, stage)
            assertEquals("AI_REPLY_RECEIVED_GREETINGS", result.owner)
            assertEquals(result.idleStepsBeforeProfile, result.idleStepsAfterProfile)
            assertFalse(result.logs.any { it.contains("IDENTITY_PROFILE_VERIFIED") })
            assertTrue(result.logs.any {
                it.contains("统一恢复[PAUSED_CHECKPOINT_SAVED]") && it.contains("PAGE_ROUTE_CHANGED")
            })
        }
    }

    private data class GreetingProfileRouteCaseResult(
        val owner: String?,
        val idleStepsBeforeProfile: Int,
        val idleStepsAfterProfile: Int,
        val logs: List<String>
    )

    private fun runGreetingAvatarProfileRouteCase(
        profileVerified: Boolean,
        stage: ReceivedGreetingsTraversalStatus
    ): GreetingProfileRouteCaseResult {
        val scheduler = FakeScheduler()
        val listActivity = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity"
        val profileActivity = "com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK"
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = listActivity,
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 32,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null,
                windowGeneration = 12L
            )
            manualGreetingsArrivalStatuses = listOf(
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
        }
        val logs = mutableListOf<String>()
        val owner = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = owner,
            currentRunGeneration = { 5L }
        )
        runtime.scheduleInitialObservation()
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(350L)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", owner.activeTaskId())
        val idleBefore = port.receivedGreetingsIdleSteps

        port.receivedGreetingsIdentityProfileProof = profileVerified
        port.receivedGreetingsTraversalStatusValue = stage
        port.page = port.page.copy(
            activityClass = profileActivity,
            pageKind = ChatPageKind.NON_CHAT,
            scannedNodeCount = 170,
            windowGeneration = 14L
        )
        runtime.onSelfNavigationAttached(
            SelfNavigationTransition(
                actionId = "verified-avatar-gesture",
                sourcePageGeneration = 12L,
                targetActivityClass = profileActivity,
                targetPageGeneration = 13L,
                targetWindowId = 1113,
                transitionAtUptimeMs = 1_000L
            )
        )
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNextWithDelay(300L)
        return GreetingProfileRouteCaseResult(
            owner = owner.activeTaskId(),
            idleStepsBeforeProfile = idleBefore,
            idleStepsAfterProfile = port.receivedGreetingsIdleSteps,
            logs = logs
        )
    }

    @Test
    fun manuallyOpenedReceivedGreetingsListCanStartWithoutLegacyMessageTabGuard() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 24,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null
            )
            manualGreetingsArrivalStatuses = listOf(
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
        }
        var gestures = 0
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestures++
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runtime.schedule(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER)
                .copy(signals = setOf(AccessibilityUiSignal.CHAT_NAVIGATION))
        )
        scheduler.runNextWithDelay(300L) // A debounced fresh page snapshot probes the manual arrival.

        assertEquals(1, port.manualGreetingsArrivalObservations)
        assertTrue(scheduler.hasPendingDelay(350L))
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, gestures)
        scheduler.runNextWithDelay(350L) // A second fresh same-window list snapshot confirms arrival.

        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertEquals(0, port.receivedGreetingsNavigationTaskStarts)
        assertEquals(0, gestures)
        assertTrue(logs.any { it.contains("收到的招呼任务[STARTED]") })
    }

    @Test
    fun receivedGreetingsWatcherAcceptsPageChangeEventsRegardlessOfInputOrigin() {
        InputOrigin.values().forEach { origin ->
            val scheduler = FakeScheduler()
            val port = FakeChatPort().apply {
                sessionRunEnabled = true
                receivedGreetingsWorkflowEnabled = true
                page = ChatPageSnapshot(
                    packageName = "com.immomo.momo",
                    activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
                    pageKind = ChatPageKind.NON_CHAT,
                    scannedNodeCount = 24,
                    editableInputCount = 0,
                    messageListCount = 0,
                    sendActionCount = 0,
                    messageRelatedViewIdCount = 0,
                    hasChatTab = false,
                    messageListTarget = null,
                    inputFieldTarget = null
                )
                manualGreetingsArrivalStatuses = listOf(
                    ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                    ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
                )
                receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
            }
            val logs = ArrayList<String>()
            val runtime = AiReplyObservationRuntime(
                scheduler = scheduler,
                sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
                chatPagePort = port,
                chatMessagePort = port,
                chatSessionManagementPort = port,
                gesturePolicy = NoOpGestureApi,
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = logs::add,
                inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L,
                chooseRandomInt = { minimum, _ -> minimum }
            )

        runtime.scheduleInitialObservation()
            runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, origin))
            scheduler.runNextWithDelay(300L)

            assertEquals("First read-only probe must run for $origin", 1, port.manualGreetingsArrivalObservations)
            assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
            assertTrue(scheduler.hasPendingDelay(350L))

            scheduler.runNextWithDelay(350L)

            assertEquals("Stable list must start the task for $origin", 1, port.receivedGreetingsCurrentListStartAttempts)
            assertEquals(1, port.receivedGreetingsIdleSteps)
            assertTrue(logs.any { it.contains("收到的招呼任务[STARTED]") })
        }
    }

    @Test
    fun lowFrequencyFallbackCanDiscoverAndStartTheListWithoutAnotherPageEvent() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            manualGreetingsArrivalStatuses = listOf(
                ReceivedGreetingsPageArrivalStatus.NOT_TARGET,
                ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
                ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
            )
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN))
        scheduler.runNextWithDelay(300L)

        assertEquals(1, port.manualGreetingsArrivalObservations)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertTrue(scheduler.hasPendingDelay(60_000L))

        // No further accessibility event is delivered. The low-frequency watchdog requests a fresh read-only probe.
        scheduler.runNextWithDelay(60_000L)
        scheduler.runNextWithDelay(300L)

        assertEquals(2, port.manualGreetingsArrivalObservations)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertTrue(scheduler.hasPendingDelay(350L))

        scheduler.runNextWithDelay(350L)

        assertEquals(3, port.manualGreetingsArrivalObservations)
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertTrue(logs.any { it.contains("收到的招呼页面观察[LOW_FREQUENCY_CHECK]") })
        assertTrue(logs.any { it.contains("收到的招呼任务[STARTED]") })
    }

    @Test
    fun repeatedUnknownAndSelfInjectedPageEventsAreCoalescedIntoOneReadOnlyProbe() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            manualGreetingsArrivalStatus = ReceivedGreetingsPageArrivalStatus.NOT_TARGET
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN))
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED))
        runtime.schedule(eventWithOrigin(AccessibilityEventType.WINDOW_CONTENT_CHANGED, InputOrigin.UNKNOWN))

        scheduler.runNextWithDelay(300L)

        assertEquals(1, port.manualGreetingsArrivalObservations)
        assertTrue(scheduler.hasPendingDelay(60_000L))
    }

    @Test
    fun physicalPageScrollDoesNotDisableTheStructureSelectedPrivateTask() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.WINDOW_CONTENT_CHANGED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun physicalTouchDoesNotYieldPrivateTaskOwnership() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER)
            )
        )
    }

    @Test
    fun unknownInteractionDoesNotStartManualHandoffEscalation() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun unknownSignalsDoNotHoldPageTaskInManualResumeState() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SELECTED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.WINDOW_CONTENT_CHANGED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun pageTaskOwnershipIsUnaffectedByPhysicalInteractionProvenance() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.VIEW_SELECTED, InputOrigin.PHYSICAL_USER)
            )
        )
    }

    @Test
    fun recognizedInboxPageReacquiresLeaseAfterAnIntermediatePageTransition() {
        val scheduler = FakeScheduler()
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate())
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = inbox
            manualGreetingsArrivalStatus = ReceivedGreetingsPageArrivalStatus.INTERMEDIATE_PAGE
        }
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = ownership,
            currentRunGeneration = { 5L }
        )

        runtime.scheduleInitialObservation()
        runtime.schedule(event(AccessibilityEventType.VIEW_CLICKED))
        scheduler.runNextWithDelay(300L)
        assertNull(ownership.activeTaskId())

        port.manualGreetingsArrivalStatus = ReceivedGreetingsPageArrivalStatus.NOT_TARGET
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNextWithDelay(300L)

        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
    }

    @Test
    fun unknownPageArrivalResultDoesNotReleaseARecognizedInboxTaskLease() {
        val scheduler = FakeScheduler()
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate())
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = inbox
            manualGreetingsArrivalStatus = ReceivedGreetingsPageArrivalStatus.UNKNOWN
        }
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = ownership,
            currentRunGeneration = { 5L }
        )

        runtime.scheduleInitialObservation()
        runtime.schedule(event(AccessibilityEventType.VIEW_CLICKED))
        scheduler.runNextWithDelay(300L)

        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
    }

    @Test
    fun startupPositionsListAtTopBeforeFirstUpwardUnreadAdmissionSwipe() {
        val scheduler = FakeScheduler()
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate(
                unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
                completeBoundaryEvidence = true
            )),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        val submittedCommands = mutableListOf<GestureIntentRequest>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    submittedCommands += command
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntil(scheduler, 60) {
            submittedCommands.any {
                (it.intent as? GestureIntent.VerticalSwipe)?.direction == VerticalSwipeDirection.UP
            }
        }

        val directions = submittedCommands.mapNotNull {
            (it.intent as? GestureIntent.VerticalSwipe)?.direction
        }
        val firstUpwardSwipe = directions.indexOfFirst { it == VerticalSwipeDirection.UP }
        assertTrue("启动阶段必须先产生向下回顶手势", firstUpwardSwipe > 0)
        assertTrue(
            "首次向上准入前的所有滑动都必须是向下回顶",
            directions.take(firstUpwardSwipe).all { it == VerticalSwipeDirection.DOWN }
        )
        assertTrue(logs.any { it.contains("启动顶部定位[CONFIRMED]") })
        assertTrue(logs.any { it.contains("首次向上滑动；完成后才检查未读") })
    }

    @Test
    fun startupUnknownUnreadStateIsCheckedOnlyAfterTopResetAndFirstUpwardSwipe() {
        val scheduler = FakeScheduler()
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate(
                unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNKNOWN,
                completeBoundaryEvidence = true
            )),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        val submittedCommands = mutableListOf<GestureIntentRequest>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    submittedCommands += command
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_STATE_UNKNOWN]")

        val directions = submittedCommands.mapNotNull {
            (it.intent as? GestureIntent.VerticalSwipe)?.direction
        }
        val firstUpwardSwipe = directions.indexOfFirst { it == VerticalSwipeDirection.UP }
        assertTrue("UNKNOWN 不得阻止先执行向下回顶", firstUpwardSwipe > 0)
        assertTrue(directions.take(firstUpwardSwipe).all { it == VerticalSwipeDirection.DOWN })
        assertTrue(logs.any { it.contains("启动顶部定位[CONFIRMED]") })
        assertTrue(logs.any { it.contains("首次向上滑动已由 Android 确认成功") })
        assertFalse(scheduler.hasPendingDelay(1_200L))
        assertFalse(scheduler.hasPendingDelay(30_000L))
    }

    @Test
    fun startupNoUnreadRunsTopResetBeforeExactlyOneAdmissionSwipe() {
        val scheduler = FakeScheduler()
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate(
                unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ,
                completeBoundaryEvidence = true
            )),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        val submittedCommands = mutableListOf<GestureIntentRequest>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    submittedCommands += command
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[NO_UNREAD_AFTER_FIRST_SWIPE]")

        val directions = submittedCommands.mapNotNull {
            (it.intent as? GestureIntent.VerticalSwipe)?.direction
        }
        val firstUpwardSwipe = directions.indexOfFirst { it == VerticalSwipeDirection.UP }
        assertTrue("无未读判断前必须先完成向下回顶", firstUpwardSwipe > 0)
        assertTrue(directions.take(firstUpwardSwipe).all { it == VerticalSwipeDirection.DOWN })
        assertEquals(1, directions.count { it == VerticalSwipeDirection.UP })
        assertTrue(logs.any { it.contains("启动顶部定位[CONFIRMED]") })
        assertTrue(logs.any { it.contains("首次成功向上滑动后的视口没有明确未读") })
        assertFalse(logs.any { it.contains("底部边界经延迟独立快照复核通过") })
    }

    @Test
    fun startupWaitsForMainActivityThenVerifiesMessageTabBeforeUnreadAdmission() {
        val scheduler = FakeScheduler()
        val splash = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.SplashActivity",
            pageKind = ChatPageKind.UNKNOWN,
            scannedNodeCount = 12,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = false,
            messageListTarget = null,
            inputFieldTarget = null,
            windowGeneration = 4L
        )
        val mainFeed = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.NON_CHAT,
            scannedNodeCount = 60,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            screenBounds = ScreenRect(0, 0, 720, 1600),
            sessionListNavigationTarget = testNavigationTarget(
                "com.immomo.momo.maintab.MaintabActivity",
                generation = 5L
            ),
            windowGeneration = 5L
        )
        val inbox = mainFeed.copy(
            pageKind = ChatPageKind.CHAT_LIST,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(
                sessionCandidate(
                    unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ,
                    completeBoundaryEvidence = true,
                    windowGeneration = 5L
                )
            )
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            verifiedMessageWorkAreaNavigationSupported = true
            provideMessageTabPositionEvidence = true
            page = splash
            pageAfterMessageWorkAreaEntry = inbox
        }
        val logs = mutableListOf<String>()
        val gestures = mutableListOf<GestureKind>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestures += command.kind
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        assertTrue(logs.any { it.contains("AI 启动导航[RETRY]") })
        assertEquals("Activity 尚未就绪时不得暂停，也不得提交手势", 0, gestures.size)

        // The application finishes initializing and exposes the main-tab host.
        port.page = mainFeed
        scheduler.runNextWithDelay(400L)
        assertEquals(1, port.messageWorkAreaEntryAttempts)
        assertTrue(logs.any { it.contains("AI 启动导航[TAP_SUBMITTED]") })
        assertTrue("点击后必须等待新鲜快照和位置验证", gestures.isEmpty())

        scheduler.runNextWithDelay(900L)
        assertTrue(logs.any { it.contains("AI 启动导航[POSITION_CONFIRMED]") })
        assertTrue("位置验证后仍须等待连续稳定快照", gestures.isEmpty())

        scheduler.runNextWithDelay(300L)
        assertTrue(gestures.isEmpty())
        scheduler.runNextWithDelay(300L)

        assertTrue(logs.any { it.contains("AI 启动导航[CONFIRMED]") })
        runUntilLog(scheduler, logs, "AI Reply 启动顶部定位[CONFIRMED]")
        runUntilLog(scheduler, logs, "AI Reply 启动准入：执行进入消息工作区后的首次向上滑动")
        assertEquals(
            "先完成向下回顶，再执行首次向上准入滑动",
            listOf(GestureKind.SWIPE, GestureKind.SWIPE, GestureKind.SWIPE),
            gestures
        )
    }


    @Test
    fun deferredMessageTabGestureDoesNotConsumeTapLimitOrAcceptOldCallback() {
        val scheduler = FakeScheduler()
        val feed = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.NON_CHAT,
            scannedNodeCount = 60,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            screenBounds = ScreenRect(0, 0, 720, 1600),
            sessionListNavigationTarget = testNavigationTarget(
                "com.immomo.momo.maintab.MaintabActivity", generation = 5L
            ),
            windowGeneration = 5L
        )
        val port = FakeChatPort().apply {
            page = feed
            provideMessageTabPositionEvidence = true
            captureMessageWorkAreaGestureOutcome = true
        }
        val logs = mutableListOf<String>()
        val coordinator = AiReplyStartupReadinessCoordinator(
            scheduler = scheduler,
            chatPagePort = port,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            isSessionRunStarted = { true },
            inspectFreshPageSnapshot = port::inspectChatPage,
            isMessageViewportPositionedAgainstTab = { _, _, _ -> true },
            hasRelevantMessageStructureDelta = { _, _, _, _ -> true },
            onConfirmed = {},
            onTimedOut = {},
            onLog = logs::add
        )
        coordinator.resetForRun(sessionStarted = true)
        coordinator.beginVerification()
        assertEquals(1, port.messageWorkAreaEntryAttempts)
        assertTrue(scheduler.hasPendingDelay(900L))

        port.messageWorkAreaOutcomeCallbacks[0](MessageWorkAreaNavigationOutcome.DEFERRED_NOT_DISPATCHED)
        assertTrue(logs.any { it.contains("GESTURE_DEFERRED") })
        assertTrue(scheduler.hasPendingDelay(400L))
        scheduler.runNextWithDelay(400L)
        assertEquals("未派发的点击不应耗尽一次导航额度", 2, port.messageWorkAreaEntryAttempts)

        // An old callback cannot invalidate the new attempt's pending confirmation.
        port.messageWorkAreaOutcomeCallbacks[0](MessageWorkAreaNavigationOutcome.DEFERRED_NOT_DISPATCHED)
        assertTrue(scheduler.hasPendingDelay(900L))
        port.messageWorkAreaOutcomeCallbacks[1](MessageWorkAreaNavigationOutcome.UNCONFIRMED)
        scheduler.runNextWithDelay(900L)
        scheduler.runNextWithDelay(400L)
        assertEquals("结果不确定的点击仍应保留一次点击上限", 2, port.messageWorkAreaEntryAttempts)
    }

    @Test
    fun unknownEventsDoNotAccumulateIntoTaskArbitration() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_LONG_CLICKED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun unknownPageEventsDoNotChangeTheCurrentTaskLease() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_TEXT_CHANGED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun eventBurstDoesNotCreateAQuietPeriodTaskGate() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun unknownClickDuringStartupDoesNotDeferPageReadiness() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun unknownStartupPageEventsDoNotCreateLegacyHold() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_TEXT_CHANGED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun unknownClickDoesNotControlStartupPageEligibility() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun threeUnknownClicksDoNotTriggerTaskProtection() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun singleUnknownScrollDoesNotChangeTaskOwnership() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun pageStructureObservationDoesNotDependOnMessageTabRecoveryGuard() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.WINDOW_CONTENT_CHANGED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun changedPageContextIsHandledByFreshPageInspection() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.WINDOW_STATE_CHANGED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun missingPageEvidenceDoesNotCreateAnInteractionArbitrationState() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_SELECTED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun physicalScrollNeverExecutesNavigationGesture() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER)
            )
        )
    }

    @Test
    fun externalInjectedClickDoesNotChangeTaskOwnership() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.EXTERNAL_INJECTED)
            )
        )
    }

    @Test
    fun externalInjectedNonClickDoesNotChangeTaskOwnership() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.EXTERNAL_INJECTED)
            )
        )
    }

    @Test
    fun returningToInboxIsResolvedByPageStructureOnly() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.WINDOW_STATE_CHANGED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN)
            )
        )
    }

    @Test
    fun delayedScrollEventDoesNotTriggerInteractionArbitration() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN),
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED)
            )
        )
    }

    @Test
    fun unavailableSafeSwipeViewportIsRecomputedUntilAFreshGestureCanBeSubmitted() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        val viewport = ScreenRect(0, 120, 720, 1480)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = viewport,
                chatListCandidates = listOf(candidate)
            )
            // Simulate the floating control temporarily covering too much of the list, then
            // disappearing. The runtime must reacquire and resolve again rather than pause.
            sessionSwipeViewportResults = List<ScreenRect?>(9) { null } + viewport
        }
        val logs = ArrayList<String>()
        var pendingGestureListener: GestureExecutionListener? = null
        var pendingGestureRequest: GestureIntentRequest? = null
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    pendingGestureRequest = command
                    pendingGestureListener = listener
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntil(scheduler, 40) { pendingGestureListener != null }

        assertEquals(10, port.sessionSwipeViewportResolutions)
        assertTrue(pendingGestureRequest?.intent is GestureIntent.VerticalSwipe)
        assertEquals(
            viewport,
            (pendingGestureRequest?.intent as GestureIntent.VerticalSwipe).viewport
        )
        assertTrue(logs.any { it.contains("SAFE_VIEWPORT_RETRY") && it.contains("不暂停遍历") })
        assertTrue(logs.any { it.contains("SAFE_VIEWPORT_RECALCULATE") })
        assertTrue(logs.any { it.contains("第 9 次重新采集；5000ms 后重新计算") })
        assertFalse(logs.any { it.contains("已暂停：排除 mf_view") })
        assertFalse(logs.any { it.contains("AI 会话管理已暂停：") })
    }

    @Test
    fun manualClickDoesNotCreateASecondTaskPreemptionPath() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED)
            )
        )
    }

    @Test
    fun clickLikeEventsNeverCreateManualTaskPreemption() {
        assertLegacySignalsDoNotControlPageSelectedTask(
            listOf(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.VIEW_LONG_CLICKED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.VIEW_CONTEXT_CLICKED, InputOrigin.PHYSICAL_USER),
            eventWithOrigin(AccessibilityEventType.VIEW_SELECTED, InputOrigin.PHYSICAL_USER)
            )
        )
    }

    @Test
    fun messageTabPositionFingerprintIgnoresLiveRowsButTracksNavigationAndViewportGeometry() {
        val messageTab = SessionListNavigationTarget(
            nodeTarget = NodeTarget(
                window = WindowRef("com.immomo.momo", "com.immomo.momo.maintab.MaintabActivity", 1L, 41),
                path = NodePath(listOf(0, 2, 1)),
                expectedViewId = "com.immomo.momo:id/maintab_layout_chat",
                expectedClassName = "android.widget.FrameLayout"
            ),
            bounds = ScreenRect(288, 1486, 432, 1600)
        )
        val base = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 156, 720, 1486),
            screenBounds = ScreenRect(0, 0, 720, 1600),
            sessionListNavigationTarget = messageTab
        )
        val changedMessageRows = base.copy(
            chatListCandidates = listOf(sessionCandidate(nameText = "Different Contact")),
            chatListUnreadDiagnostics = ChatListUnreadDiagnostics(statusNewNodeCount = 4)
        )
        val movedViewport = base.copy(chatListViewport = ScreenRect(0, 180, 720, 1486))
        val movedMessageTab = base.copy(
            sessionListNavigationTarget = messageTab.copy(bounds = ScreenRect(288, 1470, 432, 1600))
        )

        val anotherBottomTab = base.copy(
            pageKind = ChatPageKind.NON_CHAT,
            chatListViewport = null
        )

        assertEquals(base.messageTabContextPositionKey(), anotherBottomTab.messageTabContextPositionKey())
        assertEquals(base.messageTabPositionKey(), changedMessageRows.messageTabPositionKey())
        assertFalse(base.messageTabPositionKey() == movedViewport.messageTabPositionKey())
        assertFalse(base.messageTabPositionKey() == movedMessageTab.messageTabPositionKey())
        assertEquals(null, anotherBottomTab.messageTabPositionKey())
    }

    @Test
    fun chatPageStableKeyIncludesWindowGeneration() {
        assertFalse(
            chatDetailSnapshot(windowGeneration = 1L).stableKey() ==
                chatDetailSnapshot(windowGeneration = 2L).stableKey()
        )
    }

    @Test
    fun chatPageStableKeyExcludesUnreadAuditCounters() {
        val base = chatDetailSnapshot(windowGeneration = 1L)
        val withAuditCounters = base.copy(
            chatListUnreadDiagnostics = ChatListUnreadDiagnostics(
                statusNewNodeCount = 2,
                statusNewUnknownCount = 2,
                statusNewNonNumericValueCount = 2
            )
        )
        assertEquals(base.stableKey(), withAuditCounters.stableKey())
    }

    @Test
    fun aiSessionManagerRetriesTransientEmptyListWithFreshSnapshotBeforePausing() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val emptyListPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 40,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = emptyList(),
            windowGeneration = 1L,
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = emptyListPage
            resolvedTapBounds = candidate.rowBounds
        }
        val logs = ArrayList<String>()
        var executed: GestureIntentRequest? = null
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                executed = command
                listener.onResult(
                    GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED)
                )
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "当前列表暂未提取到会话行")
        assertEquals(500L, scheduler.nextDelay())

        port.page = emptyListPage.copy(chatListCandidates = listOf(candidate))
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
        // Boundary confirmation and sample persistence add asynchronous scheduler entries.
        // Wait for the observable gesture instead of assuming an exact number/order of tasks.
        runUntil(scheduler, 40) { executed?.kind == GestureKind.TAP }

        assertEquals(1, port.sessionListSamples)
        assertEquals(GestureKind.TAP, executed?.kind)
        assertTrue(logs.none { it.contains("连续 3 次未稳定") })
    }

    @Test
    fun aiSessionManagerRevalidatesAndSubmitsOnlyTheTopmostSessionRowTap() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L,
            )
        }
        val logs = ArrayList<String>()
        val commands = ArrayList<GestureKind>()
        var executed: GestureIntentRequest? = null
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                commands += command.kind
                executed = command
                if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                listener.onResult(
                    GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED)
                )
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
        runUntil(scheduler, 12) { executed?.kind == GestureKind.TAP } // Fresh row check submits the tap.

        assertEquals(GestureKind.TAP, executed?.kind)
        assertEquals(1, commands.count { it == GestureKind.TAP })
        assertTrue("Unread traversal must position at the list bottom first", commands.first() == GestureKind.SWIPE)
        assertEquals(1, port.sessionTapResolutions)
        assertEquals(1, port.sessionListSamples)
    }

    /** A no-op gesture must not poison the traverser's attempted-session checkpoint. */
    @Test
    fun acceptedTapThatNeverNavigatesRetriesSameUnreadSessionAfterFreshListRecovery() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = inbox
        }
        val logs = ArrayList<String>()
        var tapCount = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    if (command.kind == GestureKind.TAP) {
                        tapCount++
                        if (tapCount == 2) port.page = chatDetailSnapshot()
                    }
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 160) { logs.any { it.contains("NO_NAVIGATION_RECOVERY") } }
        val traversal = AiReplyObservationRuntime::class.java.getDeclaredField(
            "privateMessagesTraversalTaskExecutor"
        ).apply { isAccessible = true }.get(runtime) as PrivateMessagesTraversalTaskExecutor
        val selectedKey = com.freedom.features.aireply.ports.ChatSessionManagementPolicy.stableSessionKey(candidate)
        assertEquals(1, tapCount)
        assertFalse("No-op tap must not stay in attempted-session memory",
            traversal.attemptedSessionKeys.contains(selectedKey))
        assertFalse("No-op tap must not be saved as attempted",
            traversal.checkpoint().attemptedSessionKeys.contains(selectedKey))
        assertEquals("No-op tap is not a completed session action", 0, traversal.sessionActionCount)
        assertTrue(logs.any { it.contains("NO_NAVIGATION_RECOVERY") &&
            it.contains("attempt=1") && it.contains("delayMs=500") })

        runUntil(scheduler, 220) { logs.any { it.contains("AI 会话绑定：已建立来源行/聊天窗口/聊天表面绑定") } }
        assertEquals("The same unread row must be reselected from a fresh snapshot", 2, tapCount)
        assertTrue(traversal.attemptedSessionKeys.contains(selectedKey))
        assertEquals(1, traversal.sessionActionCount)
        assertTrue(logs.any { it.contains("统一页面恢复[RECOVERY_VERIFIED]") })
    }

    @Test
    fun repeatedNoOpSessionTapsUseIncreasingRecoveryBackoff() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L
            )
        }
        val logs = ArrayList<String>()
        var taps = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    if (command.kind == GestureKind.TAP) taps++
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 350) {
            logs.any { it.contains("NO_NAVIGATION_RECOVERY") && it.contains("attempt=2") }
        }
        assertEquals("The second retry must follow a newly verified list and actual tap", 2, taps)
        assertTrue(logs.any { it.contains("NO_NAVIGATION_RECOVERY") &&
            it.contains("attempt=2") && it.contains("delayMs=1000") })
        assertTrue(logs.none { it.contains("点击后仍停留在会话列表；记录本次未命中") })
    }

    @Test
    fun openedPrivateSessionBuildsBindingForBothEventAndDelayedVerification() {
        for (eventFirst in listOf(false, true)) {
            val scheduler = FakeScheduler()
            val candidate = sessionCandidate(completeBoundaryEvidence = true)
            val port = FakeChatPort().apply {
                sessionRunEnabled = true
                resolvedTapBounds = candidate.rowBounds
                page = ChatPageSnapshot(
                    packageName = "com.immomo.momo",
                    activityClass = "com.immomo.momo.maintab.MaintabActivity",
                    pageKind = ChatPageKind.CHAT_LIST,
                    scannedNodeCount = 80,
                    editableInputCount = 0,
                    messageListCount = 0,
                    sendActionCount = 0,
                    messageRelatedViewIdCount = 0,
                    hasChatTab = true,
                    messageListTarget = null,
                    inputFieldTarget = null,
                    chatListViewport = ScreenRect(0, 120, 720, 1480),
                    chatListCandidates = listOf(candidate),
                    windowGeneration = 1L
                )
            }
            val logs = ArrayList<String>()
            val gesture = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            }
            val runtime = AiReplyObservationRuntime(
                scheduler = scheduler,
                sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
                chatPagePort = port,
                chatMessagePort = port,
                chatSessionManagementPort = port,
                gesturePolicy = gesture,
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = logs::add,
                inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L
            )

            runtime.scheduleInitialObservation()
            runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
            runUntil(scheduler, 40) { port.sessionTapResolutions > 0 }
            if (eventFirst) {
                scheduler.runNextWithDelay(0L)
                runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED).copy(
                    windowId = 1,
                    pageGeneration = 1L
                ))
                scheduler.runNextWithDelay(300L)
                assertTrue(logs.any { it.contains("聊天页顶部名称与目标会话匹配") })
            } else {
                runUntil(scheduler, 80) {
                    logs.any { it.contains("页面跳转及聊天页顶部名称验证通过") }
                }
            }

            assertEquals("eventFirst=$eventFirst", 1, logs.count {
                it.contains("AI 会话绑定：已建立来源行/聊天窗口/聊天表面绑定")
            })
            assertTrue("eventFirst=$eventFirst must read the opened chat", port.messageReads > 0)
        }
    }

    @Test
    fun pendingPrivateSendRouteChangeKeepsDetailRecoveryUntilOriginalChatIsVerified() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            simulateStructureThenOcr = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        val gestures = mutableListOf<GestureKind>()
        var pendingSend = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestures += command.kind
                    if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isSendConfirmationPending = { pendingSend },
            onSendTransaction = { _, _ ->
                com.freedom.features.aireply.ai.ReplySendTransactionOutcome(
                    com.freedom.features.aireply.ai.SendConfirmationResult.WAITING
                )
            }
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 180) {
            logs.any { it.contains("AI 会话绑定：已建立来源行/聊天窗口/聊天表面绑定") }
        }
        assertTrue("The private conversation must be bound before a send", logs.any {
            it.contains("AI 会话绑定：已建立来源行/聊天窗口/聊天表面绑定")
        })

        // The gesture was already submitted. Its outcome is still unconfirmed when
        // Momo returns to the inbox through an unattributed page transition.
        pendingSend = true
        val inspect = runtime.javaClass.getDeclaredMethod("runFullInspection").apply {
            isAccessible = true
        }
        port.page = inbox.copy(windowGeneration = 2L)
        runtime.onUnmatchedNavigationObserved(port.page.activityClass)
        inspect.invoke(runtime)
        assertTrue("The pending send must select the detail recovery route", logs.any {
            it.contains("AI 发送恢复[DETAIL_ROUTE_HELD]") &&
                it.contains("reason=PAGE_ROUTE_CHANGED")
        })
        val gesturesAtPause = gestures.size
        repeat(3) { inspect.invoke(runtime) }
        assertFalse("Inbox snapshots must not complete the pending-send recovery", logs.any {
            it.contains("统一页面恢复[RECOVERY_EXECUTION_READY]") &&
                it.contains("AI_REPLY_CHAT_DETAIL")
        })
        assertEquals("No list traversal or replay while the send is unconfirmed",
            gesturesAtPause, gestures.size)

        // A different conversation cannot steal the pending send, even with a stable page.
        port.page = chatDetailSnapshot().copy(chatTitleText = "与Another Contact对话")
        repeat(3) { inspect.invoke(runtime) }
        assertTrue(logs.any { it.contains("AI 聊天详情恢复[ROUTE_WAIT]") })
        assertEquals(gesturesAtPause, gestures.size)

        // Only the independently verified original detail can resume read-only proof.
        val readsBeforeResume = port.messageReads
        port.page = chatDetailSnapshot()
        repeat(12) { inspect.invoke(runtime) }
        assertTrue("The original chat should complete two-snapshot recovery; recent logs=${logs.takeLast(35)}", logs.any {
            it.contains("AI 发送恢复[RECOVERY_VERIFIED]")
        })
        assertTrue("Resumption must read messages instead of re-sending",
            port.messageReads > readsBeforeResume)
        assertEquals("Recovery must not submit another send or list gesture",
            gesturesAtPause, gestures.size)
        assertEquals(0, port.navigateBackCalls)
    }

    @Test
    fun privateUnconfirmedSendRetriesRejectedBindingUntilFreshOcrWithoutReplayingGesture() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            simulateStructureThenOcr = true
            ocrDebugTextByRowIndex = mapOf(1 to "verified message")
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L
            )
        }
        val logs = ArrayList<String>()
        var pendingSend = false
        var confirmationCalls = 0
        var gestureCalls = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestureCalls++
                    if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isSendConfirmationPending = { pendingSend },
            onSendTransaction = { _, _ ->
                // A real coordinator has no pending transaction before the send is submitted.
                val result = if (pendingSend) {
                    confirmationCalls++
                    com.freedom.features.aireply.ai.SendConfirmationResult.WAITING
                } else com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING
                com.freedom.features.aireply.ai.ReplySendTransactionOutcome(result)
            }
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 180) {
            logs.any { it.contains("AI 会话绑定：已建立来源行/聊天窗口/聊天表面绑定") }
        }
        val bindingField = runtime.javaClass.getDeclaredField("currentSessionBinding").apply {
            isAccessible = true
        }
        val originalBinding = requireNotNull(bindingField.get(runtime))
        val inspect = runtime.javaClass.getDeclaredMethod("runFullInspection").apply {
            isAccessible = true
        }

        // Simulate the previously observed temporary source-binding admission failure.
        // Pending is the already-submitted send: only read-only inspection is permitted.
        pendingSend = true
        bindingField.set(runtime, null)
        val gesturesBefore = gestureCalls
        inspect.invoke(runtime)
        assertTrue(logs.any {
            it.contains("AI 发送确认[READ_DEFERRED] reason=SOURCE_CHAT_BINDING_NOT_VERIFIED")
        })
        assertTrue(logs.any {
            it.contains("AI 发送确认[READ_ONLY_RETRY_SCHEDULED]") &&
                it.contains("attempt=1") && it.contains("delayMs=500")
        })
        assertTrue(scheduler.hasPendingDelay(500L))
        assertEquals(0, confirmationCalls)
        assertEquals(gesturesBefore, gestureCalls)

        scheduler.runNextWithDelay(500L)
        assertTrue("An unverified binding must remain on autonomous read-only backoff",
            logs.any { it.contains("attempt=2") && it.contains("delayMs=1000") })
        assertTrue(scheduler.hasPendingDelay(1_000L))
        assertEquals(0, confirmationCalls)

        // An independently restored original binding is allowed to resume the
        // unchanged OCR/send matcher on the next timer, without input or a new gesture.
        bindingField.set(runtime, originalBinding)
        scheduler.runNextWithDelay(1_000L)
        runUntil(scheduler, 50) { confirmationCalls > 0 }
        assertTrue(logs.any { it.contains("AI 发送确认[READ_ONLY_RECHECK]") })
        assertTrue(logs.any { it.contains("AI 发送确认[OCR_REVALIDATED]") })
        assertEquals("No new UI gestures may be submitted during send-proof recovery",
            gesturesBefore, gestureCalls)
        assertEquals(0, port.navigateBackCalls)

        runtime.reset("user stopped")
        assertFalse("A stopped run must cancel a pending send-proof recheck",
            scheduler.hasPendingDelay(500L))
    }

    @Test
    fun boundPrivateDetailEmptySnapshotRecoversOnlyAfterFreshTargetAndIdentityReturnProof() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            // Structure read -> identity terminal -> OCR callback -> draft, as in Momo.
            simulateStructureThenOcr = true
            ocrDebugTextByRowIndex = mapOf(1 to "OCR_CONFIRMED_BODY")
            identityCaptureOutcomeOverride = ContactIdentityCaptureOutcome.waiting(
                ContactIdentityCaptureRequestResult.RETRY_LATER
            )
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L
            )
        }
        val logs = ArrayList<String>()
        var draftCalls = 0
        var draftHadOcrEvidence = false
        var draftIdentityVerified = false
        var boundAtRebind = false
        var sendGateReasonAtRebind: String? = null
        lateinit var runtime: AiReplyObservationRuntime
        val gesture = object : TestGestureApi {
            override fun execute(
                command: GestureIntentRequest,
                listener: GestureExecutionListener
            ): GestureSubmission {
                if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = gesture,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = { entry ->
            logs += entry
            if (entry.contains("AI 会话绑定[VERIFIED_CHAT_REBOUND]")) {
                boundAtRebind = runtime.javaClass.getDeclaredMethod("hasBoundPrivateDetailRecoverySession")
                    .apply { isAccessible = true }.invoke(runtime) as Boolean
                sendGateReasonAtRebind = runtime.javaClass.getDeclaredMethod(
                    "privateSendConfirmationDetailReason", ChatPageSnapshot::class.java
                ).apply { isAccessible = true }.invoke(runtime, port.page) as String
            }
        },
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onDraftConversationObserved = { batch, _, identity, _ ->
                draftCalls++
                draftHadOcrEvidence = batch.ocrAttempted && batch.ocrRecognizedTargetCount > 0
                draftIdentityVerified = identity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED
                true
            }
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 180) {
            logs.any { it.contains("AI 联系人身份采集中") }
        }
        assertEquals(0, draftCalls)
        port.deferNextStructureRead = true
        val freshInspection = runtime.javaClass.getDeclaredMethod("runFullInspection").apply {
            isAccessible = true
        }
        freshInspection.invoke(runtime)
        assertTrue("Expected a pre-recovery in-flight read", port.hasDeferredStructureRead())

        // Simulate a transient empty accessibility tree during profile-to-chat BACK.
        port.page = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "ChatActivity",
            pageKind = ChatPageKind.UNKNOWN,
            scannedNodeCount = 0,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = false,
            messageListTarget = null,
            inputFieldTarget = null
        )
        freshInspection.invoke(runtime)
        assertTrue(logs.any { it.contains("AI 聊天详情恢复[ROUTE_SELECTED]") })
        val readsBeforeRecovery = port.messageReads

        // An old in-flight structure callback cannot cross this recovery boundary.
        port.completeDeferredStructureRead()
        // Delayed adapter callbacks are marshalled through the runtime scheduler.
        // Drain the zero-delay deliveries while the recovery transaction is still pending.
        repeat(8) {
            if (scheduler.hasPendingDelay(0L)) scheduler.runNextWithDelay(0L)
        }
        assertEquals("Stale pre-recovery callback must not trigger a draft", 0, draftCalls)

        // A different chat title cannot complete the detail checkpoint even with two samples.
        port.page = chatDetailSnapshot(windowGeneration = 2L).copy(
            chatTitleText = "与Another Contact对话"
        )
        repeat(4) { freshInspection.invoke(runtime) }
        assertTrue(logs.any { it.contains("AI 聊天详情恢复[ROUTE_WAIT]") })
        assertEquals(0, draftCalls)

        // A new window with the right title still needs the adapter's verified return signal.
        port.page = chatDetailSnapshot(windowGeneration = 2L)
        repeat(4) { freshInspection.invoke(runtime) }
        assertTrue(logs.any { it.contains("AI 聊天详情恢复[WINDOW_REBIND_WAIT]") })
        assertEquals(0, draftCalls)

        port.pendingIdentityReturnReinspection = true
        port.identityCaptureOutcomeOverride = ContactIdentityCaptureOutcome.completed(
            ContactRowIdentity(
                snapshotId = "test-platform-id",
                temporaryUserId = "test-temp",
                contactId = "test-platform-user",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
        )
        var samples = 0
        while (draftCalls == 0 && samples < 12) {
            freshInspection.invoke(runtime)
            samples++
        }
        assertTrue(logs.any { it.contains("AI 聊天详情恢复[RECOVERY_VERIFIED]") })
        assertTrue("Fresh detail must be read after recovery", port.messageReads > readsBeforeRecovery)
        assertTrue("OCR may run only after identity terminal", logs.any { it.contains("AI OCR 启动") })
        assertEquals("Exactly one OCR read from the restored chat", 1, port.ocrAsyncReadRequests)
        assertTrue("Draft must have verified OCR content", draftHadOcrEvidence)
        assertTrue("Draft must keep the platform-confirmed identity", draftIdentityVerified)
        assertTrue(logs.any { it.contains("AI 身份回接[REQUEST_NOT_CONSUMED]") &&
            it.contains("reason=RECOVERY_TRANSACTION_PENDING") })
        assertTrue(logs.any { it.contains("AI 身份回接[RECOVERY_HANDOFF]") })
        assertTrue("At the verified recovery handoff, the source row and fresh window must be bound", boundAtRebind)
        assertEquals("At rebind the same chat must pass the read-only send confirmation identity gate",
            "CHAT_DETAIL_NOT_VERIFIED", sendGateReasonAtRebind)
        assertTrue(logs.any { it.contains("AI 会话绑定[VERIFIED_CHAT_REBOUND]") })
        assertEquals("Only the verified current target may draft", 1, draftCalls)
        assertEquals(0, port.navigateBackCalls)
    }

    @Test
    fun identityReturnKeepsReceiptUntilSelectedChatIsIndependentlyVerified() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            simulateStructureThenOcr = true
            ocrDebugTextByRowIndex = mapOf(1 to "OCR_CONFIRMED_BODY")
            identityCaptureOutcomeOverride = ContactIdentityCaptureOutcome.waiting(
                ContactIdentityCaptureRequestResult.RETRY_LATER
            )
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L
            )
        }
        val logs = ArrayList<String>()
        var draftCalls = 0
        var passedIdentity: ContactRowIdentity? = null
        var draftHadOcrEvidence = false
        var boundAtRebind = false
        lateinit var runtime: AiReplyObservationRuntime
        val gesture = object : TestGestureApi {
            override fun execute(
                command: GestureIntentRequest,
                listener: GestureExecutionListener
            ): GestureSubmission {
                if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = gesture,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = { entry ->
            logs += entry
            if (entry.contains("AI 会话绑定[VERIFIED_CHAT_REBOUND]")) {
                boundAtRebind = runtime.javaClass.getDeclaredMethod("hasBoundPrivateDetailRecoverySession")
                    .apply { isAccessible = true }.invoke(runtime) as Boolean
            }
        },
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onDraftConversationObserved = { batch, _, identity, _ ->
                draftCalls++
                passedIdentity = identity
                draftHadOcrEvidence = batch.ocrAttempted && batch.ocrRecognizedTargetCount > 0
                true
            }
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 180) { logs.any { it.contains("AI 联系人身份采集中") } }

        port.pendingIdentityReturnReinspection = true
        port.identityCaptureOutcomeOverride = ContactIdentityCaptureOutcome.completed(
            ContactRowIdentity(
                snapshotId = "test-platform-id",
                temporaryUserId = "test-temp",
                contactId = "test-platform-user",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
        )
        val read = runtime.javaClass.getDeclaredMethod("runFullInspection").apply {
            isAccessible = true
        }
        // Another title in the SAME Android window must not consume the one-shot receipt.
        port.page = chatDetailSnapshot().copy(chatTitleText = "与Another Contact对话")
        read.invoke(runtime)
        assertTrue("Foreign chat must preserve the return receipt", port.pendingIdentityReturnReinspection)
        assertTrue(logs.any { it.contains("AI 身份回接[REQUEST_NOT_CONSUMED]") &&
            it.contains("reason=CHAT_TITLE_MISMATCH") })
        assertEquals(0, draftCalls)

        port.page = chatDetailSnapshot()
        var attempts = 0
        while (draftCalls == 0 && attempts < 12) {
            read.invoke(runtime)
            attempts++
        }
        assertTrue(logs.any { it.contains("AI 身份业务续接[IDENTITY_RETURN_CONFIRMED]") })
        assertTrue(logs.any { it.contains("AI 身份回接[BUSINESS_HANDOFF]") })
        assertTrue("At the ordinary identity-return handoff, source session binding must remain valid",
            boundAtRebind)
        assertTrue(logs.any { it.contains("AI OCR 启动") })
        assertFalse(port.pendingIdentityReturnReinspection)
        assertEquals(1, port.ocrAsyncReadRequests)
        assertTrue(draftHadOcrEvidence)
        assertEquals(1, draftCalls)
        assertEquals(ContactIdentityConfidence.PLATFORM_ID_VERIFIED, passedIdentity?.confidence)
        assertEquals(0, port.navigateBackCalls)
    }

    @Test
    fun failedOrUnboundIdentityUsesOnlyVerifiedCurrentWindowWithoutNameHistoryLookup() {
        for (mode in listOf("FAILED", "UNBOUND", "UNSAFE_FRESH_PAGE")) {
            val scheduler = FakeScheduler()
            val candidate = sessionCandidate(completeBoundaryEvidence = true)
            val verified = ContactRowIdentity(
                snapshotId = "test-platform-id",
                temporaryUserId = "test-temp",
                contactId = "test-platform-user",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
            val port = FakeChatPort().apply {
                sessionRunEnabled = true
                resolvedTapBounds = candidate.rowBounds
                identityCaptureOutcomeOverride = if (mode == "FAILED") {
                    ContactIdentityCaptureOutcome.failed(
                        ContactIdentityCaptureRequestResult.NOT_SUPPORTED,
                        "IDENTITY_CAPTURE_NOT_SUPPORTED"
                    )
                } else {
                    ContactIdentityCaptureOutcome.completed(verified)
                }
                page = ChatPageSnapshot(
                    packageName = "com.immomo.momo",
                    activityClass = "com.immomo.momo.maintab.MaintabActivity",
                    pageKind = ChatPageKind.CHAT_LIST,
                    scannedNodeCount = 80,
                    editableInputCount = 0,
                    messageListCount = 0,
                    sendActionCount = 0,
                    messageRelatedViewIdCount = 0,
                    hasChatTab = true,
                    messageListTarget = null,
                    inputFieldTarget = null,
                    chatListViewport = ScreenRect(0, 120, 720, 1480),
                    chatListCandidates = listOf(candidate),
                    windowGeneration = 1L
                )
            }
            val logs = ArrayList<String>()
            var draftCalls = 0
            var historyLookups = 0
            var passedIdentity: ContactRowIdentity? = verified
            lateinit var runtime: AiReplyObservationRuntime
            val gesture = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            }
            runtime = AiReplyObservationRuntime(
                scheduler = scheduler,
                sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
                chatPagePort = port,
                chatMessagePort = port,
                chatSessionManagementPort = port,
                gesturePolicy = gesture,
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = { line ->
                    logs.add(line)
                    if (mode != "FAILED" && line.contains("AI 会话绑定：已建立")) {
                        // Simulate the missing binding while keeping the verified row/navigation.
                        AiReplyObservationRuntime::class.java.getDeclaredField("currentSessionBinding")
                            .apply { isAccessible = true }.set(runtime, null)
                    }
                    if (mode == "UNSAFE_FRESH_PAGE" &&
                        line.contains("已采集平台ID但当前会话绑定未就绪")
                    ) {
                        // The first message-read callback has already validated the original
                        // window. Change the live page just before fallback's independent read.
                        port.page = chatDetailSnapshot().copy(chatTitleText = "与Another Contact对话")
                    }
                },
                inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L,
                resolveConversationIdentity = { _, _ ->
                    historyLookups++
                    verified // A name-only historical match must never be consulted in fallback.
                },
                prepareDraftHistory = { _, _ -> historyLookups++ },
                onDraftConversationObserved = { _, _, identity, _ ->
                    draftCalls++
                    passedIdentity = identity
                    true
                }
            )
            runtime.scheduleInitialObservation()
            runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
            fun expectedOutcomeReached(): Boolean = if (mode == "UNSAFE_FRESH_PAGE") {
                logs.any { it.contains("CURRENT_TARGET_NOT_CONFIRMED") }
            } else draftCalls == 1
            var steps = 0
            while (!expectedOutcomeReached() && steps < 100 && scheduler.pendingCount() > 0) {
                scheduler.runNext()
                steps++
            }
            assertTrue(
                "mode=$mode; steps=$steps; draftCalls=$draftCalls; " +
                    "lastLogs=${logs.takeLast(30)}",
                expectedOutcomeReached()
            )
            if (mode == "UNSAFE_FRESH_PAGE") {
                assertEquals(0, draftCalls)
                assertTrue(logs.any { it.contains("CURRENT_TARGET_NOT_CONFIRMED") })
            } else {
                assertEquals(1, draftCalls)
                assertNull("mode=$mode must not bind an unconfirmed platform ID", passedIdentity)
                assertEquals("mode=$mode must not load name-only history", 0, historyLookups)
            }
        }
    }

    @Test
    fun retryLaterDoesNotDraftBeforeIdentityTimeoutThenFallsBackToCurrentWindow() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            identityCaptureOutcomeOverride = ContactIdentityCaptureOutcome.waiting(
                ContactIdentityCaptureRequestResult.RETRY_LATER
            )
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L
            )
        }
        val logs = ArrayList<String>()
        var draftCalls = 0
        var historyLookups = 0
        val gesture = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = gesture,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            resolveConversationIdentity = { _, _ -> historyLookups++; null },
            onDraftConversationObserved = { _, _, identity, _ ->
                assertNull(identity)
                draftCalls++
                true
            }
        )
        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
        runUntilLog(scheduler, logs, "AI 联系人身份采集中")
        assertEquals(0, draftCalls)
        scheduler.runNextWithDelay(15_000L) // No new page event: identity timeout still advances.
        runUntil(scheduler, 100) { draftCalls == 1 }
        assertEquals(0, historyLookups)
        assertTrue(logs.any { it.contains("IDENTITY_CAPTURE_TIMEOUT") })
    }

    @Test
    fun aiSessionManagerInspectsEachNewViewportBeforeContinuingUpward() {
        val scheduler = FakeScheduler()
        val bottomUnknown = sessionCandidate(
            nameText = "Bottom Contact",
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        val middleUnread = sessionCandidate(nameText = "Middle Contact", completeBoundaryEvidence = true)
        val topUnknown = sessionCandidate(
            nameText = "Top Contact",
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        fun listPage(candidate: ChatListCandidateSnapshot) = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val topPage = listPage(topUnknown)
        val bottomPage = listPage(bottomUnknown)
        val middlePage = listPage(middleUnread)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolveTapBoundsFromCandidate = true
            page = topPage
            pageAfterBack = middlePage
        }
        val commands = ArrayList<GestureIntentRequest>()
        var bottomSwipes = 0
        var upwardSwipes = 0
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                commands += command
                when {
                    command.kind == GestureKind.SWIPE && command.description.contains("启动定位底部") -> {
                        bottomSwipes++
                        when (bottomSwipes) {
                            1 -> port.page = middlePage // First admission swipe exposes explicit unread.
                            2 -> port.page = bottomPage // Next swipe positions the locked set at the bottom.
                        }
                    }
                    command.kind == GestureKind.SWIPE && command.description.contains("从底部向上审视") -> {
                        upwardSwipes++
                        if (upwardSwipes == 1) port.page = middlePage
                    }
                    command.kind == GestureKind.TAP -> {
                        port.page = chatDetailSnapshot().copy(chatTitleText = "与Middle Contact对话")
                    }
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntil(scheduler, 60) { port.messageReads == 1 }

        assertEquals(2, bottomSwipes) // Admission swipe, then stop after the stable read-only suffix is confirmed.
        assertEquals(1, upwardSwipes) // The first upward swipe exposes the middle page.
        assertEquals(
            listOf(
                GestureKind.SWIPE, GestureKind.SWIPE, // Startup: establish list top.
                GestureKind.SWIPE, GestureKind.SWIPE, GestureKind.SWIPE,
                GestureKind.TAP
            ),
            commands.map { it.kind }
        ) // The stable read-only suffix stops bottom positioning before another upward swipe.
        val swipeDirections = commands.mapNotNull {
            (it.intent as? GestureIntent.VerticalSwipe)?.direction
        }
        assertEquals(VerticalSwipeDirection.DOWN, swipeDirections[0])
        assertEquals(VerticalSwipeDirection.DOWN, swipeDirections[1])
        assertEquals(VerticalSwipeDirection.UP, swipeDirections[2]) // First unread-admission swipe.
        assertTrue(logs.any { it.contains("顶部方向滑动后可见会话行发生变化；先审视当前新区域") })
        assertEquals("Middle Contact", port.resolvedTapCandidateNames.single())
        assertEquals(2, port.sessionListSamples) // Bottom and middle viewports are both archived.
    }

    @Test
    fun aiSessionManagerDoesNotConfirmBottomUntilDelayedFreshSnapshotsRemainStable() {
        val scheduler = FakeScheduler()
        val first = sessionCandidate(
            nameText = "First Viewport",
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD
        )
        val late = sessionCandidate(
            rowIndex = 1,
            nameText = "First Viewport",
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        fun listPage(candidate: ChatListCandidateSnapshot) = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val firstPage = listPage(first)
        val latePage = listPage(late)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = firstPage
            // Startup top positioning performs two DOWN swipes and two independent delayed
            // boundary samples before the first UP admission swipe. Keep the late viewport
            // change for the delayed bottom-boundary confirmation itself.
            inspectionPages = List(13) { firstPage } + latePage
        }
        var bottomSwipes = 0
        val logs = ArrayList<String>()
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.SWIPE && command.description.contains("启动定位底部")) {
                    bottomSwipes++
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "延迟边界复核发现列表发生迟到变化")

        assertTrue(bottomSwipes >= 3)
        assertTrue(logs.none { it.contains("底部边界经延迟独立快照复核通过") })
        assertTrue(logs.any { it.contains("撤销边界候选") })
    }

    @Test
    fun aiSessionManagerDoesNotConfirmBoundaryWhenVisibleRowEvidenceIsIncomplete() {
        val scheduler = FakeScheduler()
        val completeStartupCandidate = sessionCandidate(
            nameText = "Partially Observed Contact",
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
            completeBoundaryEvidence = true
        )
        val incompleteCandidate = sessionCandidate(
            nameText = "Partially Observed Contact",
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD
        )
        fun listPage(candidate: ChatListCandidateSnapshot) = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        // Only the startup top-boundary fixture is complete. The post-admission row is
        // deliberately incomplete so the bottom boundary must remain unconfirmed.
        val completeStartupPage = listPage(completeStartupCandidate)
        val incompletePage = listPage(incompleteCandidate)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = completeStartupPage
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    if (command.description.contains("首次向上滑动检查新区域")) {
                        port.page = incompletePage
                    }
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "边界候选缺少完整可见行证据或稳定窗口身份")

        assertTrue(logs.any { it.contains("底部定位无进展计数=2") })
        assertTrue(logs.none { it.contains("底部边界经延迟独立快照复核通过") })
    }

    @Test
    fun aiSessionManagerDoesNotConfirmBoundaryWithoutWindowIdentity() {
        val scheduler = FakeScheduler()
        val visibleCandidate = sessionCandidate(
            nameText = "Visible Contact",
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
            completeBoundaryEvidence = true,
            windowGeneration = 1L
        )
        val noWindowCandidate = sessionCandidate(
            nameText = "Visible Contact",
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
            completeBoundaryEvidence = true,
            windowGeneration = 0L
        )
        fun listPage(candidate: ChatListCandidateSnapshot, generation: Long) = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = generation
        )
        // Keep a valid identity while proving the startup top boundary; remove it only after
        // admission so this test still targets the bottom-boundary safety check.
        val startupPage = listPage(visibleCandidate, 1L)
        val noWindowPage = listPage(noWindowCandidate, 0L)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = startupPage
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    if (command.description.contains("首次向上滑动检查新区域")) {
                        port.page = noWindowPage
                    }
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "边界候选缺少完整可见行证据或稳定窗口身份")

        assertTrue(logs.any { it.contains("底部定位无进展计数=2") })
        assertTrue(logs.none { it.contains("底部边界经延迟独立快照复核通过") })
    }

    @Test
    fun aiSessionManagerPausesBoundaryConfirmationWhenWindowGenerationChanges() {
        val scheduler = FakeScheduler()
        val first = sessionCandidate(
            nameText = "First Viewport",
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
            completeBoundaryEvidence = true,
            windowGeneration = 1L
        )
        val switchedWindow = sessionCandidate(
            nameText = "First Viewport",
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
            completeBoundaryEvidence = true,
            windowGeneration = 2L
        )
        fun listPage(candidate: ChatListCandidateSnapshot, generation: Long) = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = generation
        )
        val firstPage = listPage(first, 1L)
        val switchedWindowPage = listPage(switchedWindow, 2L)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = firstPage
            // The mandatory startup-admission swipe adds one pre-dispatch inspection.
            // Present the changed generation only after the boundary candidate is formed,
            // so this test covers delayed confirmation rather than pre-scroll rejection.
            inspectionPages = listOf(
                firstPage, firstPage, firstPage, firstPage,
                firstPage, firstPage, switchedWindowPage
            )
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI 会话管理已暂停：延迟边界复核未能确认同一会话列表页面")

        assertTrue(logs.any { it.contains("PAUSED_CHECKPOINT_SAVED") &&
            it.contains("PRIVATE_LIST_BOUNDARY_PAGE_CHANGED") })
        assertTrue(logs.any { it.contains("AI 私聊暂停分类[RETRYABLE]") &&
            it.contains("PRIVATE_LIST_BOUNDARY_PAGE_CHANGED") })
        assertTrue(logs.none { it.contains("底部边界经延迟独立快照复核通过") })
    }

    @Test
    fun aiSessionManagerContinuesScanWhenReturnedListViewportChangesAfterSession() {
        val scheduler = FakeScheduler()
        val firstCandidate = sessionCandidate(nameText = "First Contact", completeBoundaryEvidence = true)
        val externallyVisibleRead = sessionCandidate(nameText = "Other Contact", completeBoundaryEvidence = true, unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ)
        fun listPage(candidate: ChatListCandidateSnapshot) = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val originalPage = listPage(firstCandidate)
        val externallyMovedPage = listPage(externallyVisibleRead)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolveTapBoundsFromCandidate = true
            page = originalPage
            pageAfterBack = externallyMovedPage
        }
        val commands = ArrayList<GestureIntentRequest>()
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                commands += command
                if (command.kind == GestureKind.TAP) {
                    port.page = chatDetailSnapshot().copy(chatTitleText = "与First Contact对话")
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntil(scheduler, 60) { port.messageReads == 1 }

        assertEquals(1, commands.count { it.kind == GestureKind.TAP })
        assertEquals(GestureKind.TAP, commands.last().kind)
        assertTrue(logs.none { it.contains("未发出滚动手势时可见会话身份序列发生变化；当前列表位置不可信") })
    }

    @Test
    fun aiSessionManagerRejectsHeaderMismatchAndSchedulesSafeReturn() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L,
            )
        }
        val logs = ArrayList<String>()
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.TAP) {
                    port.page = chatDetailSnapshot().copy(chatTitleText = "与Different Contact对话")
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
        runUntilLog(scheduler, logs, "聊天页顶部名称校验未通过")

        assertEquals(0, port.messageReads)
        assertEquals(1, port.headerValidationDiagnostics)
        assertEquals(candidate.rowIndex, port.lastExpectedSessionCandidate?.rowIndex)
        assertEquals(candidate.nameSource, port.lastExpectedSessionCandidate?.nameSource)
        assertTrue(logs.any { it.contains("聊天页顶部名称校验未通过") })
        assertTrue(logs.any { it.contains("诊断入口=延迟页面复核") })
        assertTrue(logs.any { it.contains("共同前缀字符数=") && it.contains("页面未匹配字符数=") })
        assertTrue(logs.none { it.contains("Different Contact") || it.contains("Sample Contact") })
    }

    @Test
    fun aiSessionManagerSkipsHeaderMismatchAndContinuesWithNextPrivateSession() {
        val scheduler = FakeScheduler()
        val first = sessionCandidate(
            rowIndex = 0,
            nameText = "Sample Contact",
            completeBoundaryEvidence = true
        )
        val second = sessionCandidate(
            rowIndex = 1,
            nameText = "Next Contact",
            completeBoundaryEvidence = true
        )
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(first, second),
            windowGeneration = 1L,
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolveTapBoundsFromCandidate = true
            page = listPage
            pageAfterBack = listPage
        }
        val logs = ArrayList<String>()
        val commands = ArrayList<GestureKind>()
        val executor = object : TestGestureApi {
            var tapCount = 0
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                commands += command.kind
                if (command.kind == GestureKind.TAP) {
                    tapCount++
                    port.page = chatDetailSnapshot().copy(
                        chatTitleText = if (tapCount == 1) "与Wrong Contact对话" else "与Sample Contact对话"
                    )
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
        runUntil(scheduler, 40) { port.messageReads == 1 }

        assertEquals(1, port.headerValidationDiagnostics)
        assertEquals(1, port.navigateBackCalls)
        assertEquals(listOf("Next Contact", "Sample Contact"), port.resolvedTapCandidateNames)
        assertEquals(
            listOf(GestureKind.TAP, GestureKind.TAP),
            commands.filter { it == GestureKind.TAP }
        ) // The two unread sessions are processed in order after bottom positioning.
        assertTrue(commands.first() == GestureKind.SWIPE)
        assertEquals(1, port.messageReads)
        assertTrue(logs.any { it.contains("已拒绝当前会话并提交安全返回") })
        assertTrue(logs.any { it.contains("页面跳转及聊天页顶部名称验证通过；匹配方式=WRAPPED_CONVERSATION_TITLE；开始只读采集") })
        assertTrue(logs.none { it.contains("Wrong Contact") })
    }

    @Test
    fun aiReplyChecksCurrentPositionBeforeAnySwipeAndDoesNotTreatUnknownAsNoUnread() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNKNOWN,
            completeBoundaryEvidence = true
        )
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            chatListUnreadDiagnostics = ChatListUnreadDiagnostics(
                statusNewNodeCount = 1,
                statusNewUnknownCount = 1,
                statusNewNonNumericValueCount = 1
            ),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = listPage
        }
        val sequence = ArrayList<String>()
        val logs = ArrayList<String>()
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.SWIPE) sequence += "SWIPE"
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = { message ->
                logs += message
                if (message.contains("AI Reply 启动检查")) sequence += "CURRENT_POSITION_CHECK"
            },
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_STATE_UNKNOWN]")

        assertTrue(sequence.indexOf("CURRENT_POSITION_CHECK") >= 0)
        assertTrue("UNKNOWN is checked only after the mandatory first upward admission swipe", sequence.contains("SWIPE"))
        assertTrue(logs.any { it.contains("首次向上滑动") })
        assertTrue(logs.any { it.contains("UNKNOWN") && it.contains("暂停") })
        assertFalse(scheduler.hasPendingDelay(1_200L))
        assertFalse(scheduler.hasPendingDelay(30_000L))
        assertEquals(0, port.receivedGreetingsIdleSteps)
    }

    @Test
    fun confirmedNoUnreadOrdinaryInboxTransitionsToReceivedGreetingsOnce() {
        val scheduler = FakeScheduler()
        val readCandidate = sessionCandidate(
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(readCandidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = listPage
        }
        val submittedCommands = ArrayList<GestureIntentRequest>()
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    submittedCommands += command
                    listener.onResult(
                        GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED)
                    )
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼自动回复任务")

        val directions = submittedCommands.mapNotNull {
            (it.intent as? GestureIntent.VerticalSwipe)?.direction
        }
        assertEquals(
            listOf(VerticalSwipeDirection.DOWN, VerticalSwipeDirection.DOWN, VerticalSwipeDirection.UP),
            directions
        )
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertTrue(logs.any { it.contains("启动顶部定位[CONFIRMED]") })
        assertTrue(logs.any { it.contains("首次成功向上滑动后的视口没有明确未读") })
        assertFalse(logs.any { it.contains("底部边界经延迟独立快照复核通过") })
        assertFalse(logs.any { it.contains("随机待机") || it.contains("模拟回复人数") || it.contains("模拟处理目标") })

        // A fully searched but absent greeting entry is normal completion in the inbox.
        port.provideMessageTabPositionEvidence = true
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
        runUntilLog(scheduler, logs, "跨任务交接[ENTRY_ABSENT]")
        runUntilLog(scheduler, logs, "跨任务交接[PRIVATE_LEASE_TRANSFERRED]")
        assertEquals(0, port.receivedGreetingsPriorityReturns)
        assertEquals(1, port.receivedGreetingsConfirmedReturns)
        assertTrue(logs.any { it.contains("AI Reply 低频待机") })
    }

    @Test
    fun receivedGreetingsDraftUsesCurrentIdentityWithoutStoredHistoryAndPreservesSample() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val verified = ContactRowIdentity(
            snapshotId = "greeting-avatar-proof",
            temporaryUserId = "greeting-row",
            contactId = "greeting-platform-id",
            confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.DETAIL_READY
            receivedGreetingsContactIdentity = verified
            receivedGreetingsRowKeyHash = "greeting-row-hash"
            receivedGreetingsDisplayName = "Sample Contact"
            simulateStructureThenOcr = true
            ocrDebugTextByRowIndex = mapOf(1 to "CURRENT_GREETING_OCR")
            page = chatDetailSnapshot()
        }
        var storedIdentityLookups = 0
        var historyPreloads = 0
        var privateDraftCallbacks = 0
        var greetingDraftCallbacks = 0
        var observedIdentity: ContactRowIdentity? = null
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            resolveConversationIdentity = { _, _ ->
                storedIdentityLookups++
                error("Greeting draft must not consult stored contacts")
            },
            prepareDraftHistory = { _, _ -> historyPreloads++ },
            onDraftConversationObserved = { _, _, _, _ ->
                privateDraftCallbacks++
                true
            },
            onGreetingDraftConversationObserved = { batch, _, identity, _ ->
                greetingDraftCallbacks++
                observedIdentity = identity
                assertTrue(batch.ocrAttempted)
                assertEquals(1, batch.ocrRecognizedTargetCount)
                true
            }
        )
        AiReplyObservationRuntime::class.java.getDeclaredField("sessionRunStarted").apply {
            isAccessible = true
        }.setBoolean(runtime, true)
        val greetingsFlow = AiReplyObservationRuntime::class.java.getDeclaredField("receivedGreetingsTaskFlow").apply {
            isAccessible = true
        }.get(runtime) as ReceivedGreetingsTaskFlowController
        assertTrue(greetingsFlow.claimOwnership("test current greeting context"))
        greetingsFlow.markStarted()
        val readVisible = AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "readVisibleMessages", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        readVisible.invoke(runtime, port.page)
        runUntil(scheduler, 40) { greetingDraftCallbacks == 1 }
        assertEquals(1, greetingDraftCallbacks)
        assertEquals(0, privateDraftCallbacks)
        assertEquals(0, storedIdentityLookups)
        assertEquals(0, historyPreloads)
        assertEquals(verified, observedIdentity)
        assertEquals(1, port.ocrAsyncReadRequests)
        assertEquals(1, port.persistedChatSamples)
        assertTrue("Greeting sample must keep its current OCR batch", port.lastPersistedBatch?.ocrAttempted == true)

        // An expired greeting lease must not dispatch another callback or save the same batch.
        assertTrue(greetingsFlow.releaseOwnership("test stale OCR callback"))
        val handleBatch = AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "handleMessageReadBatch", ChatMessageBatch::class.java, ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        handleBatch.invoke(runtime, requireNotNull(port.lastPersistedBatch), port.page)
        assertEquals(1, greetingDraftCallbacks)
        assertEquals(1, port.persistedChatSamples)
        assertTrue(logs.any { it.contains("STALE_TASK_IGNORED") })
    }

    @Test
    fun greetingSingleAdmissionRejectsIncompleteProofBeforeAnyMessageRead() {
        for (scenario in listOf("DETAIL_NOT_READY", "IDENTITY_UNVERIFIED", "SOURCE_ROW_MISSING",
            "HEADER_MISMATCH", "WINDOW_MISMATCH")
        ) {
            val scheduler = FakeScheduler()
            val logs = ArrayList<String>()
            val port = greetingDraftTestPort()
            when (scenario) {
                "DETAIL_NOT_READY" ->
                    port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.NAVIGATING
                "IDENTITY_UNVERIFIED" ->
                    port.receivedGreetingsContactIdentity =
                        requireNotNull(port.receivedGreetingsContactIdentity).copy(
                            confidence = ContactIdentityConfidence.UNRESOLVED
                        )
                "SOURCE_ROW_MISSING" -> port.receivedGreetingsRowKeyHash = null
                "HEADER_MISMATCH" -> port.page = port.page.copy(chatTitleText = "Other chat")
                "WINDOW_MISMATCH" -> port.page = port.page.copy(packageName = "foreign.package")
            }
            var drafts = 0
            val runtime = greetingRuntimeFixture(
                scheduler, port, logs, greetingDraft = { drafts++; true }
            )
            activateGreetingTestFlow(runtime)
            readGreetingTestMessages(runtime, port.page)
            assertEquals("scenario=$scenario", 0, port.messageReads)
            assertEquals("scenario=$scenario", 0, port.ocrAsyncReadRequests)
            assertEquals("scenario=$scenario", 0, port.persistedChatSamples)
            assertEquals("scenario=$scenario", 0, drafts)
            assertFalse("scenario=$scenario", logs.any { it.contains("GREETING_CHAT_BINDING_CREATED") })
            assertTrue("scenario=$scenario logs=$logs", logs.any { it.contains("GREETING_CHAT_BINDING_REJECTED") })
        }
    }

    @Test
    fun greetingDetailBindingIsEstablishedOnceBeforeOcrAndReusedForSameWindow() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort().apply { deferNextOcrRead = true }
        var drafts = 0
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { drafts++; true }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        assertEquals(1, port.ocrAsyncReadRequests)
        assertEquals(1, logs.count { it.contains("GREETING_CHAT_BINDING_CREATED") })

        // Re-entry from a fresh observer must keep the exact same binding. An adapter
        // status change does not grant a new conversation or invalidate a verified OCR.
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.NAVIGATING
        val ensureBinding = AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "ensureGreetingChatBinding", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        assertTrue(ensureBinding.invoke(runtime, port.page) as Boolean)
        assertEquals(1, port.ocrAsyncReadRequests)
        assertEquals(1, logs.count { it.contains("GREETING_CHAT_BINDING_CREATED") })
        assertFalse(logs.any { it.contains("GREETING_CHAT_BINDING_REJECTED") })
        port.completeDeferredOcrRead()
        runUntil(scheduler, 40) { drafts == 1 }
        assertEquals(1, drafts)
        assertEquals(1, port.persistedChatSamples)
        assertTrue(logs.any { it.contains("GREETING_CHAT_OCR_REVALIDATED") })
    }

    @Test
    fun receivedGreetingBindsBeforeOcrAndKeepsSameChatWhenDetailStatusTemporarilyChanges() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val verified = ContactRowIdentity(
            snapshotId = "verified-profile",
            temporaryUserId = "greeting-row",
            contactId = "greeting-contact-123",
            confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.DETAIL_READY
            receivedGreetingsContactIdentity = verified
            receivedGreetingsRowKeyHash = "greeting-hashed-row"
            receivedGreetingsDisplayName = "Sample Contact"
            simulateStructureThenOcr = true
            deferNextOcrRead = true
            ocrDebugTextByRowIndex = mapOf(1 to "newly-read-ocr")
            page = chatDetailSnapshot()
        }
        var greetingDrafts = 0
        var privateDrafts = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onDraftConversationObserved = { _, _, _, _ -> privateDrafts++; true },
            onGreetingDraftConversationObserved = { batch, _, identity, _ ->
                greetingDrafts++
                assertEquals(verified, identity)
                assertTrue(batch.ocrAttempted)
                true
            }
        )
        AiReplyObservationRuntime::class.java.getDeclaredField("sessionRunStarted").apply {
            isAccessible = true
        }.setBoolean(runtime, true)
        val flow = AiReplyObservationRuntime::class.java.getDeclaredField("receivedGreetingsTaskFlow").apply {
            isAccessible = true
        }.get(runtime) as ReceivedGreetingsTaskFlowController
        assertTrue(flow.claimOwnership("greeting test ownership"))
        flow.markStarted()
        AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "readVisibleMessages", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }.invoke(runtime, port.page)
        assertTrue("Source binding must precede the OCR callback", logs.any { it.contains("GREETING_CHAT_BINDING_CREATED") })
        assertEquals(1, port.ocrAsyncReadRequests)
        assertEquals(0, greetingDrafts)
        assertEquals(0, port.persistedChatSamples)

        // The selected row and live chat are unchanged, but the adapter's one-off status
        // temporarily loses DETAIL_READY while OCR is running. The bound conversation wins.
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.NAVIGATING
        port.completeDeferredOcrRead()
        runUntil(scheduler, 40) { greetingDrafts == 1 }
        assertEquals(1, greetingDrafts)
        assertEquals(0, privateDrafts)
        assertEquals(1, port.persistedChatSamples)
        assertTrue(logs.any { it.contains("GREETING_CHAT_OCR_REVALIDATED") })
        assertFalse(logs.any { it.contains("CHAT_ENTRY_NOT_BOUND") })
    }

    @Test
    fun receivedGreetingLateOcrRejectsChangedRowIdWindowHeaderOrTaskLease() {
        for (change in listOf("ROW", "ID", "TITLE", "WINDOW", "LEASE", "RUN")) {
            val scheduler = FakeScheduler()
            val logs = ArrayList<String>()
            val taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
            var runGeneration = 12L
            val verified = ContactRowIdentity(
                snapshotId = "verified-profile",
                temporaryUserId = "greeting-row",
                contactId = "greeting-platform-123",
                confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
            )
            val port = FakeChatPort().apply {
                sessionRunEnabled = true
                receivedGreetingsWorkflowEnabled = true
                receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.DETAIL_READY
                receivedGreetingsContactIdentity = verified
                receivedGreetingsRowKeyHash = "original-greeting-row-hash"
                receivedGreetingsDisplayName = "Sample Contact"
                simulateStructureThenOcr = true
                deferNextOcrRead = true
                ocrDebugTextByRowIndex = mapOf(1 to "greeting-current-message")
                page = chatDetailSnapshot()
            }
            var greetingDrafts = 0
            val runtime = AiReplyObservationRuntime(
                scheduler = scheduler,
                sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
                chatPagePort = port,
                chatMessagePort = port,
                chatSessionManagementPort = port,
                gesturePolicy = NoOpGestureApi,
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = logs::add,
                inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L,
                taskCoordinator = taskCoordinator,
                currentRunGeneration = { runGeneration },
                onGreetingDraftConversationObserved = { _, _, _, _ -> greetingDrafts++; true }
            )
            AiReplyObservationRuntime::class.java.getDeclaredField("sessionRunStarted").apply {
                isAccessible = true
            }.setBoolean(runtime, true)
            val flow = AiReplyObservationRuntime::class.java.getDeclaredField("receivedGreetingsTaskFlow").apply {
                isAccessible = true
            }.get(runtime) as ReceivedGreetingsTaskFlowController
            assertTrue(flow.claimOwnership("start greeting OCR"))
            flow.markStarted()
            AiReplyObservationRuntime::class.java.getDeclaredMethod(
                "readVisibleMessages", ChatPageSnapshot::class.java
            ).apply { isAccessible = true }.invoke(runtime, port.page)
            assertEquals("change=$change", 1, port.ocrAsyncReadRequests)
            assertTrue(logs.any { it.contains("GREETING_CHAT_BINDING_CREATED") })
            when (change) {
                "ROW" -> port.receivedGreetingsRowKeyHash = "different-row-hash"
                "ID" -> port.receivedGreetingsContactIdentity = verified.copy(contactId = "different-contact")
                "TITLE" -> port.page = chatDetailSnapshot().copy(chatTitleText = "与Another Person对话")
                "WINDOW" -> port.page = chatDetailSnapshot(windowGeneration = 2L)
                "LEASE" -> {
                    val old = requireNotNull(taskCoordinator.currentLease())
                    assertTrue(taskCoordinator.transfer(old, "AI_REPLY_PRIVATE_MESSAGES") != null)
                }
                "RUN" -> runGeneration = 13L
            }
            port.completeDeferredOcrRead()
            runUntil(scheduler, 40) { logs.any {
                it.contains("GREETING_CHAT_OCR_REJECTED") || it.contains("GREETING_CHAT_BATCH_REJECTED")
            } }
            assertEquals("change=$change", 0, greetingDrafts)
            assertEquals("change=$change", 0, port.persistedChatSamples)
        }
    }

    @Test
    fun greetingOcrSavedWithoutDraftNeverCompletesOrReturns() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        val runtime = greetingRuntimeFixture(
            scheduler = scheduler, port = port, logs = logs,
            greetingDraft = { false }
        )
        val flow = activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 50) { logs.any { it.contains("GREETING_DRAFT_NOT_READY") } }
        assertEquals(1, port.persistedChatSamples)
        assertEquals(0, port.greetingDetailCompletionCalls)
        assertEquals(0, port.greetingDetailSuccessfulCompletions)
        assertTrue(flow.isActive)
        assertFalse(logs.any { it.contains("GREETING_SEND_COMPLETED") })
    }

    @Test
    fun submittedGreetingSendRequiresOcrBeforeConfirmationAndSchedulesNextRead() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        var confirmationCalls = 0
        val runtime = greetingRuntimeFixture(
            scheduler = scheduler, port = port, logs = logs, greetingDraft = { false },
            sendConfirmation = {
                confirmationCalls++
                com.freedom.features.aireply.ai.SendConfirmationResult.WAITING
            },
            sendConfirmationPending = { true }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        assertEquals(1, port.ocrAsyncReadRequests)
        assertEquals(0, confirmationCalls) // No confirmation on the structure-only batch.
        runUntil(scheduler, 30) { confirmationCalls == 1 }
        assertTrue(logs.any { it.contains("AI 发送确认[OCR_REVALIDATED]") })
        assertTrue(logs.any { it.contains("AI 发送确认[RECHECK_SCHEDULED]") })
        assertTrue(scheduler.hasPendingDelay(500L))
        assertEquals(0, port.greetingDetailCompletionCalls)
        assertEquals(0, port.navigateBackCalls)
    }

    @Test
    fun sendConfirmationReadDeferredExplainsPageAndTaskLeaseWithoutChangingGate() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { false },
            sendConfirmationPending = { true }
        )
        val method = AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "sendConfirmationReadDeferredReason", ChatPageSnapshot::class.java, java.lang.Boolean.TYPE
        ).apply { isAccessible = true }
        assertEquals("PAGE_NOT_CONFIRMED", method.invoke(runtime, port.page, false))
        assertEquals("RUN_NOT_STARTED", method.invoke(runtime, port.page, true))
        val flow = activateGreetingTestFlow(runtime)
        assertEquals("GREETING_BINDING_MISSING", method.invoke(runtime, port.page, true))
        readGreetingTestMessages(runtime, port.page)
        assertTrue(flow.isActive)
        assertEquals("GREETING_CHAT_HEADER_MISMATCH",
            method.invoke(runtime, port.page.copy(chatTitleText = "Other chat"), true))
        assertTrue(flow.releaseOwnership("diagnostic lease test"))
        assertEquals("TASK_LEASE_NOT_HELD", method.invoke(runtime, port.page, true))
    }

    @Test
    fun sendConfirmationOcrRejectedLogsPreciseBindingReasonAndPreservesSendGate() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort().apply { deferNextOcrRead = true }
        var confirmationCalls = 0
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { false },
            sendConfirmation = {
                confirmationCalls++
                com.freedom.features.aireply.ai.SendConfirmationResult.WAITING
            },
            sendConfirmationPending = { true }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        assertEquals(1, port.ocrAsyncReadRequests)
        port.page = port.page.copy(chatTitleText = "Other chat")
        port.completeDeferredOcrRead()
        runUntil(scheduler, 30) { logs.any { it.contains("AI 发送确认[OCR_REJECTED]") } }
        assertTrue(logs.any {
            it.contains("AI 发送确认[OCR_REJECTED] reason=GREETING_CHAT_HEADER_MISMATCH")
        })
        assertEquals(0, confirmationCalls)
        assertEquals(0, port.greetingDetailCompletionCalls)
        assertEquals(0, port.navigateBackCalls)
    }

    @Test
    fun sendConfirmationOcrBatchDiagnosticsDistinguishWindowGenerationAndBatchKey() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        val runtime = greetingRuntimeFixture(scheduler, port, logs, greetingDraft = { false })
        val method = AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "sendConfirmationMessageBatchRejectionReason",
            ChatPageSnapshot::class.java, ChatMessageBatch::class.java, ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        val source = port.page
        val batch = port.readRecentMessages(source, 10)
        assertEquals("OCR_BATCH_WINDOW_MISMATCH",
            method.invoke(runtime, source, batch.copy(windowSessionKey = "different"), source))
        val changedInput = requireNotNull(source.inputFieldTarget).copy(
            window = requireNotNull(source.inputFieldTarget).window.copy(generation = 200L)
        )
        val changedPage = source.copy(inputFieldTarget = changedInput)
        assertEquals("WINDOW_IDENTITY_CHANGED",
            method.invoke(runtime, source, batch, changedPage))
    }

    @Test
    fun greetingConfirmedSendArchivesFinalChatAndUsesOnlyGreetingSafeReturn() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        var sendOutcome = com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING
        var sendIdReads = 0
        var draftCalls = 0
        val runtime = greetingRuntimeFixture(
            scheduler = scheduler, port = port, logs = logs,
            greetingDraft = { draftCalls++; true },
            sendConfirmation = { sendOutcome },
            consumeSendId = { sendIdReads++; "confirmed-greeting-uuid" }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 50) { draftCalls == 1 && port.persistedChatSamples == 1 }
        assertEquals(0, port.greetingDetailCompletionCalls)
        sendOutcome = com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED
        val confirmedBatch = requireNotNull(port.lastPersistedBatch)
        handleGreetingTestBatch(runtime, confirmedBatch, port.page)
        assertEquals(1, sendIdReads)
        assertEquals(2, port.persistedChatSamples)
        assertEquals(1, port.greetingDetailCompletionCalls)
        assertEquals(1, port.greetingDetailSuccessfulCompletions)
        assertEquals(0, port.navigateBackCalls) // Never use the private-message return.
        assertTrue(logs.any { it.contains("GREETING_SEND_CONFIRMED") })
        assertTrue(logs.any { it.contains("GREETING_RETURN_BACK_ACCEPTED") })
        assertEquals(1, port.greetingSendJournalWrites)
        // The completed transaction invalidates the source binding. Old callbacks cannot
        // archive a third time or initiate a second BACK.
        handleGreetingTestBatch(runtime, confirmedBatch, port.page)
        assertEquals(1, port.greetingDetailCompletionCalls)
        assertEquals(2, port.persistedChatSamples)
        assertEquals(1, sendIdReads)
    }

    @Test
    fun greetingSendWaitingUnknownAndAbsentIdNeverMarkRowProcessed() {
        for (scenario in listOf("WAITING", "UNKNOWN", "NO_ID")) {
            val scheduler = FakeScheduler()
            val logs = ArrayList<String>()
            val port = greetingDraftTestPort()
            val result = if (scenario == "WAITING") {
                com.freedom.features.aireply.ai.SendConfirmationResult.WAITING
            } else if (scenario == "UNKNOWN") {
                com.freedom.features.aireply.ai.SendConfirmationResult.UNKNOWN
            } else {
                com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED
            }
            var sendReceiptReads = 0
            val runtime = greetingRuntimeFixture(
                scheduler = scheduler, port = port, logs = logs,
                greetingDraft = { true },
                sendConfirmation = { result },
                consumeSendId = {
                    sendReceiptReads++
                    if (scenario == "NO_ID") null else "safe-confirm-id"
                }
            )
            activateGreetingTestFlow(runtime)
            readGreetingTestMessages(runtime, port.page)
            // The mock returns the selected state at the first read; even a "confirmed"
            // result without a stable ID cannot authorize a return or a processed row.
            assertEquals("scenario=$scenario", 0, port.greetingDetailCompletionCalls)
            assertEquals("scenario=$scenario", 0, port.greetingDetailSuccessfulCompletions)
            assertEquals("scenario=$scenario", 0, port.navigateBackCalls)
            assertEquals("scenario=$scenario", if (scenario == "NO_ID") 1 else 0, sendReceiptReads)
            assertTrue("scenario=$scenario logs=$logs", logs.any {
                it.contains("GREETING_SEND_UNKNOWN") || it.contains("CONFIRMATION_ID_MISSING") ||
                    scenario == "WAITING"
            })
        }
    }

    @Test
    fun greetingConfirmedSendRetainsProofWhenSafeReturnRejectedAndNeverResends() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort().apply { greetingDetailReturnAccepted = false }
        var sendOutcome = com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING
        var sendIdReads = 0
        val runtime = greetingRuntimeFixture(
            scheduler = scheduler, port = port, logs = logs,
            greetingDraft = { true },
            sendConfirmation = { sendOutcome },
            consumeSendId = { sendIdReads++; "confirmed-id" }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 50) { port.persistedChatSamples == 1 }
        sendOutcome = com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED
        val confirmedBatch = requireNotNull(port.lastPersistedBatch)
        handleGreetingTestBatch(runtime, confirmedBatch, port.page)
        assertEquals(1, port.greetingDetailCompletionCalls)
        assertEquals(0, port.greetingDetailSuccessfulCompletions)
        assertEquals(2, port.persistedChatSamples)
        port.greetingDetailReturnAccepted = true
        handleGreetingTestBatch(runtime, confirmedBatch, port.page)
        assertEquals(2, port.greetingDetailCompletionCalls)
        assertEquals(1, port.greetingDetailSuccessfulCompletions)
        assertEquals(2, port.persistedChatSamples) // Reuse archived final sample.
        assertEquals(1, sendIdReads) // Never consume a second send proof.
        assertTrue(logs.any { it.contains("GREETING_SEND_RETURN_BLOCKED reason=SAFE_BACK_NOT_CONFIRMED") })
    }

    @Test
    fun greetingCannotRequestBackUntilConfirmedSendIsDurablyRecorded() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort().apply { greetingSendJournalAccepted = false }
        var outcome = com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING
        var consumed = 0
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { true },
            sendConfirmation = { outcome },
            consumeSendId = { consumed++; "confirmed-send-id" }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 50) { port.persistedChatSamples == 1 }
        outcome = com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED
        val batch = requireNotNull(port.lastPersistedBatch)
        handleGreetingTestBatch(runtime, batch, port.page)
        assertEquals(0, port.greetingDetailCompletionCalls)
        assertEquals(1, port.persistedChatSamples)
        assertTrue(logs.any { it.contains("SEND_FACT_NOT_PERSISTED") })
        port.greetingSendJournalAccepted = true
        handleGreetingTestBatch(runtime, batch, port.page)
        assertEquals(1, port.greetingDetailCompletionCalls)
        assertEquals(2, port.persistedChatSamples)
        assertEquals(1, consumed)
        assertEquals(2, port.greetingSendJournalWrites)
    }


    /** Reads the established binding, not a newly invented task lease or conversation. */
    private fun greetingModelFailureEvent(
        runtime: AiReplyObservationRuntime, attempts: Int = 2
    ): com.freedom.features.aireply.ai.AiReplyPreSendModelFailure {
        val binding = AiReplyObservationRuntime::class.java.getDeclaredField("greetingChatBinding")
            .apply { isAccessible = true }.get(runtime)
            ?: error("Greeting binding was not established")
        fun bindingField(name: String): Any? =
            binding.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(binding)
        return com.freedom.features.aireply.ai.AiReplyPreSendModelFailure(
            requestId = "greeting-model-test-request-${attempts}",
            requestKey = "greeting-model-test-request-key",
            conversationKey = bindingField("chatTitleKey") as String,
            windowSessionKey = bindingField("windowSessionKey") as String,
            visitToken = bindingField("visitToken") as String,
            taskLease = bindingField("taskLease") as? com.freedom.core.runtime.TaskLease,
            reasonCode = "MODEL_DECISION_MODEL_CALL_FAILED",
            attempts = attempts,
            retryDelayMs = 30_000L,
            readyToDefer = attempts >= 2
        )
    }

    @Test fun greetingTechnicalModelFailureRetriesThenDefersWithoutMarkingSendSuccess() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        var deferredCallbacks = 0
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { true },
            preSendModelFailureVerifier = { _, _ -> true },
            modelFailureDeferred = { deferredCallbacks++ }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_SEND_PENDING") } }
        runtime.onPreSendModelFailure(greetingModelFailureEvent(runtime, attempts = 1))
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_MODEL_FAILURE_RETRY_ADMITTED") } }
        assertEquals(0, port.greetingModelFailureDeferralCalls)
        assertEquals(0, deferredCallbacks)
        runtime.onPreSendModelFailure(greetingModelFailureEvent(runtime, attempts = 2))
        runUntil(scheduler, 80) { port.greetingModelFailureDeferralCalls == 1 }
        assertEquals(1, deferredCallbacks)
        assertEquals(0, port.greetingDetailSuccessfulCompletions)
        assertEquals(0, port.greetingSendJournalWrites)
        assertTrue(logs.any { it.contains("GREETING_REPLY_DEFERRED") })
        assertFalse(logs.any { it.contains("GREETING_SEND_CONFIRMED") })
    }

    @Test fun greetingFailedDraftNeverReturnsWhenComposerOrSafeBackIsNotVerified() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort().apply { greetingModelFailureDeferralAccepted = false }
        var composerSafe = false
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { true },
            preSendModelFailureVerifier = { _, _ -> composerSafe }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_SEND_PENDING") } }
        runtime.onPreSendModelFailure(greetingModelFailureEvent(runtime))
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_MODEL_FAILURE_VERIFY_WAIT") } }
        assertEquals(0, port.greetingModelFailureDeferralCalls)
        composerSafe = true
        runUntil(scheduler, 80) { port.greetingModelFailureDeferralCalls == 1 }
        assertEquals(0, port.greetingDetailSuccessfulCompletions)
        assertEquals(0, port.greetingSendJournalWrites)
        assertTrue(logs.any { it.contains("GREETING_MODEL_FAILURE_DEFER_WAIT") })
        port.greetingModelFailureDeferralAccepted = true
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_REPLY_DEFERRED") } }
        assertEquals(2, port.greetingModelFailureDeferralCalls)
    }

    @Test fun staleGreetingModelFailureCallbackCannotDeferAfterTaskRelease() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { true },
            preSendModelFailureVerifier = { _, _ -> true }
        )
        val flow = activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_SEND_PENDING") } }
        runtime.onPreSendModelFailure(greetingModelFailureEvent(runtime))
        assertTrue(flow.releaseOwnership("model callback ownership revoked"))
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_MODEL_FAILURE_STALE_IGNORED") } }
        assertEquals(0, port.greetingModelFailureDeferralCalls)
    }


    @Test fun greetingUnknownSendBlocksConcurrentModelFailureDeferralEvenWithValidComposer() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        var pendingUnconfirmedSend = true
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs,
            greetingDraft = { true },
            sendConfirmation = { com.freedom.features.aireply.ai.SendConfirmationResult.UNKNOWN },
            sendConfirmationPending = { pendingUnconfirmedSend },
            preSendModelFailureVerifier = { _, _ -> true }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_SEND_UNKNOWN") } }
        runtime.onPreSendModelFailure(greetingModelFailureEvent(runtime))
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_MODEL_FAILURE_VERIFY_WAIT") } }
        assertEquals(0, port.greetingModelFailureDeferralCalls)
        assertEquals(0, port.greetingDetailCompletionCalls)
        assertEquals(0, port.greetingSendJournalWrites)
        assertTrue(logs.any { it.contains("GREETING_SEND_UNKNOWN") })
        pendingUnconfirmedSend = true
        // The recovery loop may observe indefinitely but cannot treat UNKNOWN as unsent.
        repeat(3) { scheduler.runNext() }
        assertEquals(0, port.greetingModelFailureDeferralCalls)
        assertEquals(0, port.greetingDetailCompletionCalls)
    }

    @Test fun modelFailureRecoveryScheduledBeforeLeaseReleaseCannotNavigateAfterRevocation() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { true },
            preSendModelFailureVerifier = { _, _ -> true }
        )
        val flow = activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_SEND_PENDING") } }
        runtime.onPreSendModelFailure(greetingModelFailureEvent(runtime, attempts = 1))
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_MODEL_FAILURE_RECORDED") } }
        assertTrue(flow.releaseOwnership("revoked during backoff"))
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_MODEL_FAILURE_STALE_IGNORED") } }
        assertEquals(0, port.greetingModelFailureDeferralCalls)
        assertEquals(0, port.greetingDetailSuccessfulCompletions)
        assertEquals(0, port.greetingSendJournalWrites)
        assertFalse(logs.any { it.contains("GREETING_MODEL_FAILURE_RETRY_ADMITTED") })
    }

    @Test fun staleGreetingVisitFailureCannotDeferOrSuppressExistingSendReceipt() {
        val scheduler = FakeScheduler()
        val logs = ArrayList<String>()
        val port = greetingDraftTestPort()
        var confirmation = com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING
        val runtime = greetingRuntimeFixture(
            scheduler, port, logs, greetingDraft = { true },
            sendConfirmation = { confirmation }, consumeSendId = { "confirmed-once" },
            preSendModelFailureVerifier = { _, _ -> true }
        )
        activateGreetingTestFlow(runtime)
        readGreetingTestMessages(runtime, port.page)
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_SEND_PENDING") } }
        val oldVisit = greetingModelFailureEvent(runtime).copy(visitToken = "stale-visit")
        runtime.onPreSendModelFailure(oldVisit)
        runUntil(scheduler, 80) { logs.any { it.contains("GREETING_MODEL_FAILURE_STALE_IGNORED") } }
        assertEquals(0, port.greetingModelFailureDeferralCalls)
        confirmation = com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED
        val batch = requireNotNull(port.lastPersistedBatch)
        handleGreetingTestBatch(runtime, batch, port.page)
        assertEquals(1, port.greetingSendJournalWrites)
        assertEquals(1, port.greetingDetailSuccessfulCompletions)
        assertEquals(0, port.greetingModelFailureDeferralCalls)
        assertTrue(logs.any { it.contains("GREETING_SEND_CONFIRMED") })
    }

    private fun greetingDraftTestPort(): FakeChatPort {
        val identity = ContactRowIdentity(
            snapshotId = "greeting-profile-proof", temporaryUserId = "greeting-row",
            contactId = "verified-platform-contact", confidence = ContactIdentityConfidence.PLATFORM_ID_VERIFIED
        )
        return FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.DETAIL_READY
            receivedGreetingsContactIdentity = identity
            receivedGreetingsRowKeyHash = "hashed-original-row"
            receivedGreetingsDisplayName = "Sample Contact"
            simulateStructureThenOcr = true
            ocrDebugTextByRowIndex = mapOf(1 to "hello greeting")
            page = chatDetailSnapshot()
        }
    }

    private fun greetingRuntimeFixture(
        scheduler: FakeScheduler,
        port: FakeChatPort,
        logs: MutableList<String>,
        greetingDraft: (ChatMessageBatch) -> Boolean,
        sendConfirmation: () -> com.freedom.features.aireply.ai.SendConfirmationResult = {
            com.freedom.features.aireply.ai.SendConfirmationResult.NOT_PENDING
        },
        consumeSendId: () -> String? = { null },
        sendConfirmationPending: () -> Boolean = { false },
        preSendModelFailureVerifier: (
            com.freedom.features.aireply.ai.AiReplyPreSendModelFailure, ChatPageSnapshot
        ) -> Boolean = { _, _ -> false },
        modelFailureDeferred: (com.freedom.features.aireply.ai.AiReplyPreSendModelFailure) -> Unit = {}
    ): AiReplyObservationRuntime = AiReplyObservationRuntime(
        scheduler = scheduler,
        sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
        chatPagePort = port,
        chatMessagePort = port,
        chatSessionManagementPort = port,
        gesturePolicy = NoOpGestureApi,
        targetPackage = { "com.immomo.momo" },
        isAiReplyActive = { true },
        onLog = logs::add,
        inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
        fullInspectionDebounceMs = 300L,
        regionRefreshDebounceMs = 500L,
        onGreetingDraftConversationObserved = { batch, _, _, _ -> greetingDraft(batch) },
        onSendTransaction = { _, _ ->
            val result = sendConfirmation()
            com.freedom.features.aireply.ai.ReplySendTransactionOutcome(
                status = result,
                finalSendConfirmationId = if (result == com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED) {
                    consumeSendId()
                } else null
            )
        },
        isSendConfirmationPending = sendConfirmationPending,
        verifyGreetingPreSendModelFailure = preSendModelFailureVerifier,
        onGreetingModelFailureDeferred = modelFailureDeferred
    )

    private fun activateGreetingTestFlow(runtime: AiReplyObservationRuntime): ReceivedGreetingsTaskFlowController {
        AiReplyObservationRuntime::class.java.getDeclaredField("sessionRunStarted").apply {
            isAccessible = true
        }.setBoolean(runtime, true)
        val flow = AiReplyObservationRuntime::class.java.getDeclaredField("receivedGreetingsTaskFlow").apply {
            isAccessible = true
        }.get(runtime) as ReceivedGreetingsTaskFlowController
        assertTrue(flow.claimOwnership("greeting test active"))
        flow.markStarted()
        return flow
    }

    private fun readGreetingTestMessages(runtime: AiReplyObservationRuntime, snapshot: ChatPageSnapshot) {
        AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "readVisibleMessages", ChatPageSnapshot::class.java
        ).apply { isAccessible = true }.invoke(runtime, snapshot)
    }

    private fun handleGreetingTestBatch(
        runtime: AiReplyObservationRuntime, batch: ChatMessageBatch, snapshot: ChatPageSnapshot
    ) {
        AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "handleMessageReadBatch", ChatMessageBatch::class.java, ChatPageSnapshot::class.java
        ).apply { isAccessible = true }.invoke(runtime, batch, snapshot)
    }

    @Test
    fun receivedGreetingsNavigationPhaseDefersStaleMainInboxSnapshots() {
        val scheduler = FakeScheduler()
        val readCandidate = sessionCandidate(
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        val ordinaryList = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(readCandidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.NAVIGATING
            page = ordinaryList
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼自动回复任务")
        val idleStepsAfterEntryTap = port.receivedGreetingsIdleSteps
        val ordinaryInboxSamplesBeforeTransition = port.sessionListSamples
        // The ordinary CHAT_LIST can remain visible while the adapter is still searching for
        // the received-greetings label. UNKNOWN must not strand the branch after one probe.
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.UNKNOWN
        runUntilLog(scheduler, logs, "页面状态已变化为 UNKNOWN")

        assertEquals(idleStepsAfterEntryTap + 1, port.receivedGreetingsIdleSteps)
        assertEquals(ordinaryInboxSamplesBeforeTransition, port.sessionListSamples)
        assertFalse(logs.any { it.contains("收到的招呼测试遍历暂停") })
        assertFalse(logs.any { it.contains("收到的招呼导航暂停") })
        assertTrue(scheduler.hasPendingDelay(750L))
    }

    @Test
    fun receivedGreetingsProfileCardRetriesVerifiedListControlNavigation() {
        val scheduler = FakeScheduler()
        val readCandidate = sessionCandidate(
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        val ordinaryList = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(readCandidate),
            windowGeneration = 1L
        )
        val profileCard = ordinaryList.copy(
            activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiCardActivity",
            pageKind = ChatPageKind.NON_CHAT,
            hasChatTab = false,
            chatListViewport = null,
            chatListCandidates = emptyList()
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = ordinaryList
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼自动回复任务")
        port.page = profileCard
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.PROFILE_CARD
        runUntilLog(scheduler, logs, "等待 NewSayHiSessionListActivity")

        assertTrue(logs.any { it.contains("已识别资料卡") && it.contains("顶部‘列表’控件") })
        assertFalse(logs.any { it.contains("暂停：资料卡顶部‘列表’控件未能切换") })
        assertTrue(scheduler.hasPendingDelay(1_300L))
    }

    @Test
    fun receivedGreetingsListTraversalCompletesAndRequestsReturn() {
        val scheduler = FakeScheduler()
        val readCandidate = sessionCandidate(
            completeBoundaryEvidence = true,
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ
        )
        val ordinaryList = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(readCandidate),
            windowGeneration = 1L
        )
        val nonChat = ordinaryList.copy(
            activityClass = "com.immomo.momo.newsayhi.NewSayHiListActivity",
            pageKind = ChatPageKind.NON_CHAT,
            hasChatTab = false,
            chatListCandidates = emptyList()
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = ordinaryList
            receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
            pageAfterReceivedGreetingsReturn = ordinaryList
        }
        val logs = ArrayList<String>()
        var active = true
        var draftPending = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { active },
            isConversationDraftPending = { draftPending },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼自动回复任务")
        port.page = nonChat
        scheduler.runNext()
        assertTrue(logs.any { it.contains("依次采集头像身份、绑定聊天、读取本次消息；回复完成需独立发送确认") })
        assertTrue(scheduler.hasPendingDelay(1_300L))

        // An all-exhausted stable list must switch to low-frequency, read-only recovery,
        // without touching the adapter's gesture algorithm or prematurely completing.
        port.greetingAwaitingSafeTargetEvidenceOnly = true
        scheduler.runNextWithDelay(1_300L)
        assertTrue(scheduler.hasPendingDelay(30_000L))
        assertTrue(logs.any { it.contains("GREETING_DEFERRED_EVIDENCE_ONLY") })
        port.greetingAwaitingSafeTargetEvidenceOnly = false
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        port.receivedGreetingsBackDeferred = true
        runUntilLog(scheduler, logs, "result=GESTURE_IN_FLIGHT")
        assertEquals(0, port.receivedGreetingsPriorityReturns)
        assertEquals(0, port.receivedGreetingsConfirmedReturns)
        assertEquals(nonChat, port.page)

        port.receivedGreetingsBackDeferred = false
        runUntilLog(scheduler, logs, "收到的招呼任务[BACK_SUBMITTED]")
        assertEquals(1, port.receivedGreetingsPriorityReturns)
        assertTrue(port.receivedGreetingsBackAttempts >= 2)
        assertEquals(0, port.receivedGreetingsConfirmedReturns)
        assertEquals(ordinaryList, port.page)
        assertTrue(logs.any { it.contains("返回步骤已受理") })
        assertFalse(logs.any { it.contains("模拟回复人数") })

        port.provideMessageTabPositionEvidence = true
        runUntilLog(scheduler, logs, "跨任务交接[INBOX_VERIFYING]")
        assertEquals(0, port.receivedGreetingsConfirmedReturns)
        assertEquals(1, port.receivedGreetingsPriorityReturns)
        runUntilLog(scheduler, logs, "跨任务交接[PRIVATE_LEASE_TRANSFERRED]")
        assertEquals(1, port.receivedGreetingsConfirmedReturns)
        assertEquals(1, port.receivedGreetingsPriorityReturns)
        assertTrue(logs.any { it.contains("AI Reply 低频待机") })

        // No user event and no unread are required for the second round.
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
        draftPending = true
        repeat(4) {
            if (logs.none { it.contains("自主轮询[ROUND_DUE]") }) {
                scheduler.runNextWithDelay(60_000L)
            }
        }
        assertTrue(logs.any { it.contains("自主轮询[ROUND_DUE]") })
        runUntilLog(scheduler, logs, "reason=DRAFT_PENDING")
        assertEquals(1, port.receivedGreetingsNavigationTaskStarts)
        assertFalse(logs.any { it.contains("自主轮询[ROUND_STARTED]") })
        draftPending = false
        // No new page event: the read-only retry must resume the due round.
        runUntilLog(scheduler, logs, "自主轮询[ROUND_VERIFYING]")
        assertEquals(1, port.receivedGreetingsNavigationTaskStarts)
        runUntilLog(scheduler, logs, "自主轮询[ROUND_STARTED]")
        repeat(100) {
            if (port.receivedGreetingsNavigationTaskStarts < 2) scheduler.runNextShortestDelay()
        }
        assertEquals(2, port.receivedGreetingsNavigationTaskStarts)
        assertEquals(1, port.receivedGreetingsPriorityReturns)

        // Complete a second full pair, rather than merely entering its greeting task.
        port.page = nonChat
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        repeat(100) {
            if (port.receivedGreetingsConfirmedReturns < 2) scheduler.runNextShortestDelay()
        }
        assertEquals(2, port.receivedGreetingsConfirmedReturns)
        assertEquals(2, port.receivedGreetingsPriorityReturns)
        assertEquals(2, port.receivedGreetingsNavigationTaskStarts)
        assertEquals(2, logs.count { it.contains("跨任务交接[PRIVATE_LEASE_TRANSFERRED]") })

        // Stopping with a pending next-round timer must not start a third round.
        assertTrue(scheduler.hasPendingDelay(60_000L))
        active = false
        runtime.reset("P5 explicit stop")
        repeat(20) {
            if (scheduler.pendingCount() > 0) scheduler.runNextShortestDelay()
        }
        assertEquals(2, port.receivedGreetingsNavigationTaskStarts)
        assertEquals(2, port.receivedGreetingsPriorityReturns)
        assertEquals(1, logs.count { it.contains("自主轮询[ROUND_STARTED]") })
    }

    @Test
    fun unknownUnreadRowsPauseInsteadOfBeingReportedAsNoUnread() {
        val scheduler = FakeScheduler()
        val unknownCandidate = sessionCandidate(
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNKNOWN,
            completeBoundaryEvidence = true
        )
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(unknownCandidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = listPage
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    listener.onResult(
                        GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED)
                    )
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_STATE_UNKNOWN]")

        assertTrue(logs.any { it.contains("AI Reply 启动准入[UNREAD_STATE_UNKNOWN]") && it.contains("首次成功滑动后的视口") })
        assertEquals(0, port.receivedGreetingsIdleSteps)
        assertFalse(scheduler.hasPendingDelay(1_200L))
        assertFalse(scheduler.hasPendingDelay(30_000L))
    }

    @Test
    fun normalUnreadCandidateRemainsInBottomToTopOrdinaryTraversal() {
        val scheduler = FakeScheduler()
        val unreadCandidate = sessionCandidate(
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
            completeBoundaryEvidence = true
        )
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(unreadCandidate),
            windowGeneration = 1L,
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = listPage
            resolveTapBoundsFromCandidate = true
        }
        val actions = ArrayList<GestureKind>()
        val logs = ArrayList<String>()
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                actions += command.kind
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI 会话管理：已提交会话行点击")

        assertTrue(logs.any { it.contains("AI Reply 启动准入[UNREAD_LOCKING]") })
        assertEquals(listOf(GestureKind.TAP), actions.filter { it == GestureKind.TAP })
        assertTrue(actions.first() == GestureKind.SWIPE)
        assertTrue(logs.any { it.contains("未读身份集合已冻结；进入从底部向顶部的处理阶段") })
        assertTrue(logs.none { it.contains("安全上限=1000") })
    }

    @Test
    fun unsafeSessionTapBoundsDoNotConsumeAttemptAndCanBeRetriedWithFreshSnapshot() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L,
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = listPage
            resolvedTapBoundsSequence = listOf(null, candidate.rowBounds)
            resolveTapBoundsFromCandidate = true
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI 会话管理：已提交会话行点击")

        assertEquals(2, port.sessionTapResolutions)
        assertTrue(logs.any { it.contains("点击未派发、不消耗会话尝试次数") })
        assertTrue(logs.any { it.contains("已提交会话行点击") })
    }

    @Test
    fun aiSessionManagerDoesNotBackOutOfChangedWindowAfterHeaderMismatch() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L,
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = listPage
        }
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.TAP) {
                    port.page = chatDetailSnapshot(windowGeneration = 1L).copy(chatTitleText = "与Wrong Contact对话")
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
        runUntilLog(scheduler, logs, "聊天页顶部名称校验未通过")
        port.page = chatDetailSnapshot(windowGeneration = 2L).copy(chatTitleText = "与Another Window对话")
        runUntil(scheduler, 8) { logs.any { it.contains("返回前复核失败；不执行全局返回") } }

        assertEquals(0, port.navigateBackCalls)
        assertEquals(0, port.messageReads)
        assertEquals(1, port.sessionTapResolutions)
        assertTrue(logs.any { it.contains("返回前复核失败；不执行全局返回") })
    }

    @Test
    fun aiSessionManagerPersistsHeaderDiagnosticWhenWindowEventBeatsDelayedVerification() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val headerDiagnostic = ChatHeaderNodeDiagnostic(
            found = true,
            textLength = 16,
            contentDescriptionLength = 0,
            isVisibleToUser = true,
            left = 122,
            top = 70,
            right = 354,
            bottom = 120,
            boundsValid = true,
            parentFound = true,
            parentClassName = "android.widget.FrameLayout",
            parentViewId = "com.immomo.momo:id/chat_title_container",
            parentIsVisibleToUser = true,
            parentBoundsValid = true,
            readOutcome = "TEXT_READ"
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L,
            )
        }
        val logs = ArrayList<String>()
        val executor = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.TAP) {
                    port.page = chatDetailSnapshot().copy(
                        chatTitleText = "与Different Contact对话",
                        chatHeaderDiagnostic = headerDiagnostic
                    )
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply 启动准入[UNREAD_LOCKING]")
        runUntil(scheduler, 40) { port.sessionTapResolutions > 0 }
        scheduler.runNextWithDelay(0L) // Drain the accepted gesture's completion callback before the window event.
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED).copy(
            windowId = 1,
            pageGeneration = 1L
        ))
        scheduler.runNextWithDelay(300L) // Event-driven inspection preempts the 2.2s fallback.

        assertEquals(0, port.messageReads)
        assertEquals(1, port.headerValidationDiagnostics)
        assertEquals(headerDiagnostic, port.lastHeaderValidationDiagnostic)
        assertTrue(logs.any { it.contains("诊断入口=页面事件检查") })
        assertTrue(logs.any { it.contains("目标列表行=1") && it.contains("目标文本来源=NODE_TEXT") })
        assertTrue(logs.any { it.contains("标题节点找到=true") && it.contains("读取结果=TEXT_READ") })
        assertTrue(logs.none { it.contains("Different Contact") })
        assertEquals(1, scheduler.pendingCount())
        assertEquals(1_500L, scheduler.nextDelay()) // Only the verified-return task remains; no duplicate diagnostic.
    }

    @Test
    fun initialInspectionUsesConfiguredPageDebounce() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort()
        val runtime = runtime(scheduler, port)

        runtime.scheduleInitialObservation()

        assertEquals(1, scheduler.pendingCount())
        assertEquals(300L, scheduler.nextDelay())
        scheduler.runNext()
        assertEquals(1, port.fullInspections)
    }

    @Test
    fun knownChatDetailRoutineChangeRefreshesOnlyKnownRegions() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply { page = chatDetailSnapshot() }
        val runtime = runtime(scheduler, port)
        runtime.scheduleInitialObservation()
        scheduler.runNext()

        runtime.schedule(event(AccessibilityEventType.VIEW_TEXT_CHANGED))

        assertEquals(1, scheduler.pendingCount())
        assertEquals(500L, scheduler.nextDelay())
        scheduler.runNext()
        assertEquals(1, port.regionRefreshes)
        assertEquals(1, port.fullInspections)
    }

    @Test
    fun chatNavigationSignalSchedulesFullInspectionWhenPageIsNotKnown() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "MainActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 10,
                editableInputCount = 0,
                messageListCount = 1,
                sendActionCount = 0,
                messageRelatedViewIdCount = 1,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null
            )
        }
        val runtime = runtime(scheduler, port)
        runtime.schedule(event(AccessibilityEventType.VIEW_CLICKED, AccessibilityUiSignal.CHAT_NAVIGATION))

        assertEquals(300L, scheduler.nextDelay())
        scheduler.runNext()
        assertEquals(1, port.fullInspections)
    }

    @Test
    fun clickFromKnownChatListTriggersFullInspectionWithoutTextSignal() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "MainActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 10,
                editableInputCount = 0,
                messageListCount = 1,
                sendActionCount = 0,
                messageRelatedViewIdCount = 1,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null
            )
        }
        val runtime = runtime(scheduler, port)
        runtime.scheduleInitialObservation()
        scheduler.runNext()

        // A contact row may expose no chat-specific ViewId or visible text. 2.1
        // treated any click/selection from a known chat list as navigation evidence.
        runtime.schedule(event(AccessibilityEventType.VIEW_CLICKED))
        assertEquals(300L, scheduler.nextDelay())
        scheduler.runNext()
        assertEquals(2, port.fullInspections)
    }

    @Test
    fun structureDiagnosticsAreRedactedAndBounded() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot().copy(
                nodeTreeProbeVisitedNodeCount = 120,
                nodeTreeProbeTruncated = true,
                structureEvidence = (1..60).map { index ->
                    ChatPageNodeEvidence(
                        path = "0/$index",
                        className = if (index == 3) "android.widget.ImageView" else "android.widget.TextView",
                        viewId = if (index == 3) "com.immomo.momo:id/chat_avatar" else "com.immomo.momo:id/chat_row_$index",
                        left = 10,
                        top = index * 40,
                        right = 700,
                        bottom = index * 40 + 36,
                        childCount = 0,
                        isEditable = false,
                        isScrollable = false,
                        isClickable = false,
                        hasText = true,
                        insideListCandidate = true,
                        depth = 7,
                        parentPath = "0/1/2/3/4/5",
                        isVisibleToUser = true,
                        isListProbeRoot = index == 1,
                        isAlternateListProbe = index == 1,
                        isMessageRowCandidate = index == 2
                    )
                }
            )
        }
        val logs = ArrayList<String>()
        val runtime = runtime(scheduler, port, logs::add)

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        val diagnostic = logs.first { it.contains("AI 模式页面结构识别") }
        val detailLogs = logs.filter { it.contains("AI 模式节点树明细[") }
        val allDetails = detailLogs.joinToString("\n")
        assertTrue(diagnostic.contains("节点树探针扫描=120"))
        assertTrue(diagnostic.contains("节点树探针截断=true"))
        assertTrue(detailLogs.size > 1)
        assertTrue(detailLogs.any { it.length > 700 })
        assertTrue(detailLogs.all { it.length <= 5_000 })
        assertTrue(allDetails.contains("com.immomo.momo:id/chat_row_1"))
        assertTrue(allDetails.contains("depth=7"))
        assertTrue(allDetails.contains("parent=0/1/2/3/4/5"))
        assertTrue(allDetails.contains("alternateListProbe"))
        assertTrue(allDetails.contains("messageRowCandidate"))
        assertTrue(allDetails.contains("visible=true"))
        assertTrue(allDetails.contains("imageLike"))
        assertEquals(60, Regex("path=").findAll(allDetails).count())
        assertFalse(allDetails.contains("SENSITIVE_BODY_SENTINEL"))
    }

    @Test
    fun invalidKnownPathsClearLocatorsAndQueueFullInspection() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            regionResult = ChatRegionSnapshot(true, 0, 0, 0, 0, false)
        }
        val runtime = runtime(scheduler, port)
        runtime.scheduleInitialObservation()
        scheduler.runNext()
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED))
        scheduler.runNext()

        // The failed region refresh must invalidate the cached locators before the
        // queued full-page inspection runs; a routine scroll event must not reuse them.
        assertEquals(1, port.fullInspections)
        assertEquals(1, port.regionRefreshes)
        assertEquals(300L, scheduler.nextDelay())
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED))
        assertEquals(1, scheduler.pendingCount())
        scheduler.runNext()
        assertEquals(2, port.fullInspections)
    }


    @Test
    fun selectedPackageMustBeSupportedByTheActiveSocialAdapter() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.other.social.app" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        assertEquals(0, port.fullInspections)
        assertEquals(0, port.messageReads)
    }

    @Test
    fun confirmedChatDetailReadsOnlyBoundedVisibleMessagesAndDeliversEphemeralBatch() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply { page = chatDetailSnapshot() }
        val observed = ArrayList<ChatMessageBatch>()
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = observed::add
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        assertEquals(1, port.messageReads)
        assertEquals(20, port.lastMessageLimit)
        assertEquals(1, observed.size)
        assertEquals("PRIVATE_CHAT_BODY_SENTINEL", observed.single().messages.single().content)
        assertTrue(logs.any { it.contains("最新对方文字消息=已识别") })
        assertTrue(logs.none { it.contains("PRIVATE_CHAT_BODY_SENTINEL") })
    }

    @Test
    fun asynchronousMessageReadRevalidatesActiveChatBeforeDeliveringOcrBatch() {
        val scheduler = FakeScheduler()
        val port = DeferredChatPort().apply { page = chatDetailSnapshot() }
        val observed = ArrayList<ChatMessageBatch>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = observed::add
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()
        assertTrue(observed.isEmpty())

        port.completeRead()
        assertEquals(1, scheduler.pendingCount())
        assertEquals(0L, scheduler.nextDelay())
        scheduler.runNext()

        assertEquals(1, observed.size)
        assertEquals("PRIVATE_CHAT_BODY_SENTINEL", observed.single().messages.single().content)
        assertEquals(2, port.fullInspections)
    }

    @Test
    fun asynchronousMessageReadWithoutCallbackTimesOutAndAllowsFreshRetry() {
        val scheduler = FakeScheduler()
        val port = DeferredChatPort().apply { page = chatDetailSnapshot() }
        val observed = ArrayList<ChatMessageBatch>()
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = observed::add
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()
        assertEquals(1, port.asyncReadRequests)
        assertTrue(scheduler.hasPendingDelay(65_000L))

        scheduler.runNextWithDelay(65_000L)
        assertTrue(logs.any { it.contains("消息读取超时") && it.contains("丢弃该读取代次") })
        assertTrue(scheduler.hasPendingDelay(120L))

        // The bounded retry obtains a fresh page snapshot and starts a new read generation.
        scheduler.runNextWithDelay(120L)
        assertEquals(2, port.asyncReadRequests)

        // A result from the timed-out generation must not reach observers.
        port.completeRead(index = 0)
        scheduler.runNextWithDelay(0L)
        assertTrue(observed.isEmpty())
        assertTrue(scheduler.hasPendingDelay(65_000L)) // The newer read's watchdog remains armed.
    }

    @Test
    fun asynchronousMessageReadResultIsDroppedAfterNavigatingAwayFromChat() {
        val scheduler = FakeScheduler()
        val port = DeferredChatPort().apply { page = chatDetailSnapshot() }
        val observed = ArrayList<ChatMessageBatch>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = observed::add
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()
        port.page = port.page.copy(
            pageKind = ChatPageKind.NON_CHAT,
            messageListTarget = null,
            inputFieldTarget = null
        )
        port.completeRead()
        scheduler.runNext()

        assertTrue(observed.isEmpty())
    }

    @Test
    fun asynchronousMessageReadResultIsDroppedWhenWindowGenerationChangesInSameActivity() {
        val scheduler = FakeScheduler()
        val original = chatDetailSnapshot()
        val port = DeferredChatPort().apply {
            page = original
            ocrDebugTextByRowIndex = mapOf(1 to "STALE_OCR_SHOULD_NOT_BE_LOGGED")
        }
        val observed = ArrayList<ChatMessageBatch>()
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = observed::add
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()
        port.page = chatDetailSnapshot(windowGeneration = 2L)
        port.completeRead()
        scheduler.runNext()

        assertTrue(observed.isEmpty())
        assertTrue(logs.none { it.contains("STALE_OCR_SHOULD_NOT_BE_LOGGED") })
        assertEquals(2, port.fullInspections)
    }

    @Test
    fun windowTransitionInvalidatesInflightReadBeforeDebouncedPageInspection() {
        val scheduler = FakeScheduler()
        val port = DeferredChatPort().apply { page = chatDetailSnapshot() }
        val observed = ArrayList<ChatMessageBatch>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = observed::add
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        port.page = chatDetailSnapshot(windowGeneration = 2L)
        port.completeRead()
        scheduler.runNext()

        assertTrue(observed.isEmpty())
    }

    @Test
    fun routineMessageChangesRefreshKnownRegionThenReadMessagesAgain() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply { page = chatDetailSnapshot() }
        val runtime = runtime(scheduler, port)
        runtime.scheduleInitialObservation()
        scheduler.runNext()
        assertEquals(1, port.messageReads)

        runtime.schedule(event(AccessibilityEventType.VIEW_TEXT_CHANGED))
        scheduler.runNext()

        assertEquals(1, port.regionRefreshes)
        assertEquals(2, port.messageReads)
    }

    @Test
    fun messageListRetryRemainsBoundedWhenFreshPageStructureChanges() {
        val scheduler = FakeScheduler()
        val initial = chatDetailSnapshot()
        val port = FakeChatPort().apply {
            page = initial
            inspectionPages = listOf(
                initial,
                initial.copy(structureEvidence = listOf(
                    ChatPageNodeEvidence("0", "android.view.ViewGroup", "layout_one", 0, 0, 10, 10,
                        1, false, false, false, false, true)
                )),
                initial.copy(structureEvidence = listOf(
                    ChatPageNodeEvidence("1", "android.view.ViewGroup", "layout_two", 0, 0, 10, 10,
                        2, false, false, false, false, true)
                ))
            )
            messageReadStatuses = List(3) { MessageReadStatus.MESSAGE_LIST_UNAVAILABLE }
        }
        val logs = ArrayList<String>()
        val runtime = runtime(scheduler, port, logs::add)
        runtime.scheduleInitialObservation()
        scheduler.runNext()
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(3, port.messageReads)
        assertEquals(0, scheduler.pendingCount())
        assertTrue(logs.any { it.contains("已完成 2 次延迟重试") })
    }


    @Test
    fun readsVisibleMessagesWhenComposerNodeIsNotExposed() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot().copy(
                editableInputCount = 0,
                inputFieldTarget = null
            )
        }
        val runtime = runtime(scheduler, port)

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        assertEquals(1, port.messageReads)
        assertTrue(port.lastMessagePage?.inputFieldTarget == null)
    }

    @Test
    fun messageReadFailureDoesNotLeakExceptionOrStopPageObservation() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            throwOnMessageRead = true
        }
        val logs = ArrayList<String>()
        val runtime = runtime(scheduler, port, logs::add)
        runtime.scheduleInitialObservation()
        scheduler.runNext()
        assertEquals(1, port.fullInspections)
        assertTrue(logs.any { it.contains("消息读取失败") })
    }

    @Test
    fun transientUnavailableMessageListIsRetriedAsynchronouslyWithFreshSnapshot() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            messageReadStatuses = listOf(
                MessageReadStatus.MESSAGE_LIST_UNAVAILABLE,
                MessageReadStatus.SUCCESS
            )
        }
        val runtime = runtime(scheduler, port)

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        assertEquals(1, port.messageReads)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(120L, scheduler.nextDelay())

        scheduler.runNext()

        assertEquals(2, port.fullInspections)
        assertEquals(2, port.messageReads)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun messageListRetryStopsWhenFreshSnapshotIsNoLongerChatDetail() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            messageReadStatuses = listOf(MessageReadStatus.MESSAGE_LIST_UNAVAILABLE)
        }
        val runtime = runtime(scheduler, port)
        runtime.scheduleInitialObservation()
        scheduler.runNext()

        port.page = chatDetailSnapshot().copy(
            activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
            pageKind = ChatPageKind.NON_CHAT,
            messageListTarget = null,
            inputFieldTarget = null
        )
        scheduler.runNext()

        assertEquals(2, port.fullInspections)
        assertEquals(1, port.messageReads)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun newerChatContentEventSupersedesPendingMessageListRetry() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            messageReadStatuses = listOf(
                MessageReadStatus.MESSAGE_LIST_UNAVAILABLE,
                MessageReadStatus.SUCCESS
            )
        }
        val runtime = runtime(scheduler, port)
        runtime.scheduleInitialObservation()
        scheduler.runNext()

        runtime.schedule(event(AccessibilityEventType.VIEW_TEXT_CHANGED))

        assertEquals(1, scheduler.pendingCount())
        assertEquals(500L, scheduler.nextDelay())
        scheduler.runNext()

        assertEquals(1, port.regionRefreshes)
        assertEquals(2, port.messageReads)
        assertEquals(0, scheduler.pendingCount())
    }

    @Test
    fun messageListRetryIsBoundedToTwoAdditionalAttempts() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            messageReadStatuses = List(3) { MessageReadStatus.MESSAGE_LIST_UNAVAILABLE }
        }
        val logs = ArrayList<String>()
        val runtime = runtime(scheduler, port, logs::add)
        runtime.scheduleInitialObservation()
        scheduler.runNext()
        scheduler.runNext()
        scheduler.runNext()

        assertEquals(3, port.messageReads)
        assertEquals(0, scheduler.pendingCount())
        assertTrue(logs.any { it.contains("已完成 2 次延迟重试") })
    }

    @Test
    fun acceptedOcrDebugTextIsLoggedWithDirectionAndDeduplicated() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            ocrDebugTextByRowIndex = mapOf(1 to "人工校验\n收到的消息\tOK")
        }
        val logs = ArrayList<String>()
        val runtime = runtime(scheduler, port, logs::add)

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        val ocrLogs = logs.filter { it.contains("OCR 识别结果[临时人工校验/仅本地]") }
        assertEquals(1, ocrLogs.size)
        assertTrue(ocrLogs.single().contains("方向=对方"))
        assertTrue(ocrLogs.single().contains("人工校验\\n收到的消息\\tOK"))
        assertTrue(logs.any { it.contains("调试版 OCR 结果已按临时人工校验策略单独记录") })

        // A repeated observation of the same OCR payload must not flood the local log buffer.
        runtime.schedule(event(AccessibilityEventType.VIEW_TEXT_CHANGED))
        scheduler.runNext()
        assertEquals(1, logs.count { it.contains("OCR 识别结果[临时人工校验/仅本地]") })
    }

    @Test
    fun enabledOcrDebugLoggingRecordsEmptyRecognitionForEachCandidate() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            ocrDebugTextByRowIndex = emptyMap()
        }
        val logs = ArrayList<String>()
        val runtime = runtime(scheduler, port, logs::add)

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        val ocrLogs = logs.filter { it.contains("OCR 识别结果[临时人工校验/仅本地]") }
        assertEquals(1, ocrLogs.size)
        assertTrue(ocrLogs.single().contains("状态=空结果"))
        assertTrue(ocrLogs.single().contains("[未识别到文字]"))
        assertTrue(ocrLogs.single().contains("正文区域="))
    }

    @Test
    fun normalBatchWithoutDebugPayloadDoesNotWriteOcrTextLogs() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            simulateOcrWithoutDebugPayload = true
        }
        val logs = ArrayList<String>()
        val runtime = runtime(scheduler, port, logs::add)

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        assertTrue(logs.none { it.contains("OCR 识别结果[临时人工校验/仅本地]") })
        assertTrue(logs.any { it.contains("消息正文未写入日志") })
    }

    @Test
    fun ocrDebugPayloadIsRemovedBeforeObserverCallback() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = chatDetailSnapshot()
            ocrDebugTextByRowIndex = mapOf(1 to "仅供人工校验的正文")
            messageContentQuality = MessageContentQuality.UNVERIFIED
        }
        var observed: ChatMessageBatch? = null
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = { observed = it }
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext()

        assertTrue(observed != null)
        assertEquals(null, observed?.ocrDebugTextByRowIndex)
        assertEquals(null, observed?.ocrDebugChineseTextByRowIndex)
        assertEquals(null, observed?.ocrDebugLatinTextByRowIndex)
        assertTrue(observed?.messages?.isEmpty() == true)
        assertTrue(observed?.messages?.all { it.isAiInputEligible } == true)
    }

    private fun assertLegacySignalsDoNotControlPageSelectedTask(events: List<AccessibilityEventEnvelope>) {
        val scheduler = FakeScheduler()
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(sessionCandidate())
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = inbox
            manualGreetingsArrivalStatus = ReceivedGreetingsPageArrivalStatus.NOT_TARGET
        }
        val ownership = TaskCoordinator(UiOperationOwnershipCoordinator())
        val logs = mutableListOf<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum },
            taskCoordinator = ownership,
            currentRunGeneration = { 5L }
        )

        runtime.scheduleInitialObservation()
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        events.forEach(runtime::schedule)
        runtime.schedule(event(AccessibilityEventType.WINDOW_CONTENT_CHANGED))
        scheduler.runNextWithDelay(300L)

        assertTrue("A fresh page inspection must still run", port.fullInspections > 0)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", ownership.activeTaskId())
        assertFalse(logs.any { it.contains("未知交互仲裁[PROTECTION_TRIGGERED]") })
        assertFalse(logs.any { it.contains("人工接管") || it.contains("人工复核锁") })
    }

    private data class CapturedScrollRecoveryFixture(
        val scheduler: FakeScheduler,
        val runtime: AiReplyObservationRuntime,
        val logs: MutableList<String>,
        val request: GestureIntentRequest,
        val listener: GestureExecutionListener,
        val savedCheckpoint: () -> PrivateMessagesTraversalCheckpoint?,
        val dispatchedGestures: () -> Int
    )

    private fun capturedScrollRecoveryFixture(): CapturedScrollRecoveryFixture {
        val scheduler = FakeScheduler()
        val viewport = ScreenRect(0, 120, 720, 1480)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = viewport,
                chatListCandidates = listOf(sessionCandidate(unreadStatus = ChatUnreadStatus.READ))
            )
        }
        val logs = mutableListOf<String>()
        var request: GestureIntentRequest? = null
        var listener: GestureExecutionListener? = null
        var dispatchCount = 0
        var saved: PrivateMessagesTraversalCheckpoint? = null
        val repo = object : PrivateMessagesTraversalCheckpointRepository {
            override fun load(nowEpochMs: Long): PrivateMessagesTraversalCheckpoint? = saved
            override fun save(checkpoint: PrivateMessagesTraversalCheckpoint) { saved = checkpoint }
            override fun clear() = Unit
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    callback: GestureExecutionListener
                ): GestureSubmission {
                    dispatchCount++
                    request = command
                    listener = callback
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = TaskCoordinator(UiOperationOwnershipCoordinator()),
            currentRunGeneration = { 6L },
            privateMessagesCheckpointRepository = repo
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 60) { request?.intent is GestureIntent.VerticalSwipe && listener != null }
        return CapturedScrollRecoveryFixture(
            scheduler, runtime, logs, requireNotNull(request), requireNotNull(listener),
            { saved }, { dispatchCount }
        )
    }

    private fun runtime(
        scheduler: FakeScheduler,
        port: FakeChatPort,
        onLog: (String) -> Unit = {}
    ) = AiReplyObservationRuntime(
        scheduler = scheduler,
        sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
        chatPagePort = port,
        chatMessagePort = port,
        chatSessionManagementPort = port,
        gesturePolicy = NoOpGestureApi,
        targetPackage = { "com.immomo.momo" },
        isAiReplyActive = { true },
        onLog = onLog,
        inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
        fullInspectionDebounceMs = 300L,
        regionRefreshDebounceMs = 500L
    )


    private fun testNavigationTarget(
        activityClass: String,
        generation: Long = 1L,
        expectedViewId: String = "com.immomo.momo:id/maintab_layout_chat",
        expectedClassName: String = "android.widget.FrameLayout",
        bounds: ScreenRect = ScreenRect(288, 1486, 432, 1600)
    ) = SessionListNavigationTarget(
        nodeTarget = NodeTarget(
            WindowRef("com.immomo.momo", activityClass, generation, 1),
            NodePath(listOf(0, 2, 1)),
            expectedViewId = expectedViewId,
            expectedClassName = expectedClassName
        ),
        bounds = bounds
    )

    private fun runUntilLog(scheduler: FakeScheduler, logs: List<String>, marker: String) {
        var steps = 0
        while (logs.none { it.contains(marker) } && steps < 40 && scheduler.pendingCount() > 0) {
            scheduler.runNext()
            steps++
        }
        assertTrue("Expected log marker not reached: $marker; recent logs=${logs.takeLast(12)}", logs.any { it.contains(marker) })
    }

    private fun runUntil(scheduler: FakeScheduler, maxSteps: Int, condition: () -> Boolean) {
        var steps = 0
        while (!condition() && steps < maxSteps && scheduler.pendingCount() > 0) {
            scheduler.runNext()
            steps++
        }
        assertTrue("Condition not reached after $steps scheduler steps", condition())
    }


    @Test
    fun privateOcrFailureAfterFastRetriesSchedulesFreshReadOnlyObservation() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            messageReadStatuses = List(12) { MessageReadStatus.OCR_FAILED }
            page = inbox
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 180) { logs.any { it.contains("READ_ONLY_RECHECK_SCHEDULED") } }
        assertEquals(0, port.navigateBackCalls)
        assertEquals(0, port.persistedChatSamples)
        assertTrue(logs.any { it.contains("OCR_READ_NOT_CONFIRMED") })
        assertTrue(scheduler.hasPendingDelay(500L))
        val earlierReads = port.messageReads
        runUntil(scheduler, 160) { port.messageReads > earlierReads }
        assertTrue(port.messageReads > earlierReads)
        assertEquals(0, port.navigateBackCalls)
        assertTrue(logs.none { it.contains("AI 发送成功记录") })
    }

    @Test
    fun privateTechnicalModelFailureWaitsForFreshComposerThenRestartsOcrWithoutBack() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = inbox
        }
        val logs = ArrayList<String>()
        var draftCalls = 0
        var emptyComposerVerified = false
        var verificationCalls = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onDraftConversationObserved = { _, _, _, _ -> draftCalls++; true },
            verifyPrivatePreSendModelFailure = { _, _ ->
                verificationCalls++
                emptyComposerVerified
            }
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 180) { logs.any { it.contains("聊天样本已保存") } }
        assertEquals(1, draftCalls)
        val originalChat = port.page
        val window = requireNotNull(originalChat.inputFieldTarget).window
        val conversation = requireNotNull(originalChat.conversationIdentityKey())
        val sessionKey = "${window.packageName}|${window.windowId}|${window.generation}|conversation=$conversation"
        val visit = AiReplyObservationRuntime::class.java.getDeclaredField("draftVisitToken")
            .apply { isAccessible = true }.get(runtime) as String
        runtime.onPreSendModelFailure(com.freedom.features.aireply.ai.AiReplyPreSendModelFailure(
            requestId = "private-technical-failure",
            requestKey = "private-failed-request-key",
            conversationKey = conversation,
            windowSessionKey = sessionKey,
            visitToken = visit,
            taskLease = null,
            reasonCode = "MODEL_DECISION_MODEL_CALL_FAILED",
            attempts = 2,
            retryDelayMs = 30_000L,
            readyToDefer = true
        ))
        // The legacy fake scheduler is FIFO, not deadline-prioritized. Deliver the
        // zero-delay model failure callback before the previously queued delayed BACK,
        // matching runtime scheduling without changing its actual delay algorithm.
        scheduler.runNextWithDelay(0L)
        runUntil(scheduler, 40) { logs.any { it.contains("PRE_SEND_FAILURE_RECORDED") } }
        assertEquals(0, port.navigateBackCalls)
        // A separate page read during cooldown must not bypass durable proof and
        // launch another draft before the private recovery verifier admits it.
        val readHandler = AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "handleMessageReadBatch", ChatMessageBatch::class.java, ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        readHandler.invoke(runtime, requireNotNull(port.lastPersistedBatch), port.page)
        assertTrue(logs.any { it.contains("MODEL_FAILURE_READ_HELD") })
        assertEquals(1, draftCalls)
        runUntil(scheduler, 60) { logs.any { it.contains("READ_ONLY_WAIT") } }
        assertEquals(1, draftCalls)
        assertTrue(verificationCalls > 0)
        assertEquals(0, port.navigateBackCalls)
        emptyComposerVerified = true
        runUntil(scheduler, 80) { logs.any { it.contains("RETRY_ADMITTED") } }
        runUntil(scheduler, 80) { draftCalls > 1 }
        assertEquals(2, draftCalls)
        assertTrue(logs.any { it.contains("DRAFT_RESTARTED") })
        assertEquals(0, port.navigateBackCalls)
        assertTrue(logs.none { it.contains("GREETING_REPLY_DEFERRED") })
    }

    @Test
    fun retryableOcrFailureAfterBoundedReadsNeverCompletesSessionOrNavigatesBack() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            messageReadStatuses = listOf(
                MessageReadStatus.OCR_FAILED,
                MessageReadStatus.OCR_FAILED,
                MessageReadStatus.OCR_FAILED
            )
            page = listPage
        }
        val logs = ArrayList<String>()
        val gesture = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.TAP) {
                    port.page = chatDetailSnapshot()
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = gesture,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 130) { logs.any { it.contains("OCR_READ_NOT_CONFIRMED") } }
        assertEquals(3, port.messageReads)
        assertEquals(0, port.persistedChatSamples)
        assertEquals(0, port.navigateBackCalls)
    }

    @Test
    fun confirmedPrivateSendReturnsEvenWhenSamplePersistenceFails() {
        for (confirmationId in listOf<String?>(null, "confirmed-private-send-id")) {
            val scheduler = FakeScheduler()
            val candidate = sessionCandidate(completeBoundaryEvidence = true)
            val inbox = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L
            )
            val port = FakeChatPort().apply {
                sessionRunEnabled = true
                resolvedTapBounds = candidate.rowBounds
                persistChatSampleResult = 0
                page = inbox
                pageAfterBack = inbox
            }
            val logs = ArrayList<String>()
            val runtime = AiReplyObservationRuntime(
                scheduler = scheduler,
                sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
                chatPagePort = port,
                chatMessagePort = port,
                chatSessionManagementPort = port,
                gesturePolicy = object : TestGestureApi {
                    override fun execute(
                        command: GestureIntentRequest,
                        listener: GestureExecutionListener
                    ): GestureSubmission {
                        if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                        listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                        return GestureSubmission.ACCEPTED
                    }
                },
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = logs::add,
                inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L,
                onDraftConversationObserved = { _, _, _, _ -> true }
            )
            runtime.scheduleInitialObservation()
            runUntil(scheduler, 180) {
                logs.any { it.contains("聊天样本保存失败或当前存储会话不可用") }
            }
            assertEquals(1, port.persistedChatSamples)
            assertEquals(0, port.navigateBackCalls)

            // Simulate the final outgoing-bubble receipt after the sample store failed.
            // The exact production confirmation handler must retain the stable-ID gate.
            val handler = AiReplyObservationRuntime::class.java.getDeclaredMethod(
                "handleSendConfirmationResult",
                com.freedom.features.aireply.ai.ReplySendTransactionOutcome::class.java,
                ChatMessageBatch::class.java,
                ChatPageSnapshot::class.java
            ).apply { isAccessible = true }
            val confirmed = com.freedom.features.aireply.ai.ReplySendTransactionOutcome(
                com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED,
                confirmationId
            )
            assertTrue(handler.invoke(runtime, confirmed, requireNotNull(port.lastPersistedBatch), port.page) as Boolean)
            if (confirmationId == null) {
                assertTrue(logs.any { it.contains("缺少稳定 sendConfirmationId") })
                assertEquals("Missing receipt must never authorize BACK", 0, port.navigateBackCalls)
                continue
            }
            assertTrue(logs.any { it.contains("CHAT_SAMPLE_NOT_PERSISTED") })
            runUntil(scheduler, 80) { port.navigateBackCalls == 1 }
            assertTrue(logs.any { it.contains("已提交系统返回动作") })
        }
    }

    @Test
    fun confirmedPrivateBackRecoversFromRejectedOrNoOpNavigationWithoutResending() {
        for (scenario in listOf("REJECTED", "ACCEPTED_NO_NAVIGATION")) {
            val scheduler = FakeScheduler()
            val candidate = sessionCandidate(completeBoundaryEvidence = true)
            val inbox = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.CHAT_LIST,
                scannedNodeCount = 80,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = true,
                messageListTarget = null,
                inputFieldTarget = null,
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate),
                windowGeneration = 1L
            )
            val port = FakeChatPort().apply {
                sessionRunEnabled = true
                resolvedTapBounds = candidate.rowBounds
                page = inbox
                pageAfterBack = inbox
                rejectBackAttempts = if (scenario == "REJECTED") 1 else 0
                backNoOpAttempts = if (scenario == "ACCEPTED_NO_NAVIGATION") 1 else 0
            }
            val logs = ArrayList<String>()
            val runtime = AiReplyObservationRuntime(
                scheduler = scheduler,
                sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
                chatPagePort = port,
                chatMessagePort = port,
                chatSessionManagementPort = port,
                gesturePolicy = object : TestGestureApi {
                    override fun execute(
                        command: GestureIntentRequest,
                        listener: GestureExecutionListener
                    ): GestureSubmission {
                        if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                        listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                        return GestureSubmission.ACCEPTED
                    }
                },
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = logs::add,
                inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L,
                onDraftConversationObserved = { _, _, _, _ -> true }
            )
            runtime.scheduleInitialObservation()
            runUntil(scheduler, 180) { logs.any { it.contains("聊天样本已保存") } }
            val handler = AiReplyObservationRuntime::class.java.getDeclaredMethod(
                "handleSendConfirmationResult",
                com.freedom.features.aireply.ai.ReplySendTransactionOutcome::class.java,
                ChatMessageBatch::class.java,
                ChatPageSnapshot::class.java
            ).apply { isAccessible = true }
            val confirmed = com.freedom.features.aireply.ai.ReplySendTransactionOutcome(
                com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED,
                "p1-back-recovery-send-id"
            )
            assertTrue(handler.invoke(runtime, confirmed, requireNotNull(port.lastPersistedBatch), port.page) as Boolean)
            runUntil(scheduler, 200) { port.navigateBackCalls >= 2 }
            assertEquals("scenario=$scenario only two safe BACK submissions", 2, port.navigateBackCalls)
            assertTrue("scenario=$scenario logs=$logs", logs.any { it.contains("DETAIL_STABILITY_WAIT") })
            assertTrue("scenario=$scenario logs=$logs", logs.any { it.contains("BACK_RETRY_ACCEPTED") })
            runUntil(scheduler, 80) { logs.any { it.contains("INBOX_VERIFIED") } }
            assertEquals("scenario=$scenario must not re-submit BACK after inbox", 2, port.navigateBackCalls)
            assertFalse("scenario=$scenario must not start an unsafe recovery", logs.any {
                it.contains("PRIVATE_SEND_RETURN_PAGE_UNVERIFIED")
            })
        }
    }

    @Test
    fun confirmedPrivateBackOnDifferentChatRoutesToCheckpointWithoutSecondBack() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val inbox = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            page = inbox
            pageAfterBack = chatDetailSnapshot().copy(chatTitleText = "与Another Contact对话")
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                    listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onDraftConversationObserved = { _, _, _, _ -> true }
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 180) { logs.any { it.contains("聊天样本已保存") } }
        val handler = AiReplyObservationRuntime::class.java.getDeclaredMethod(
            "handleSendConfirmationResult",
            com.freedom.features.aireply.ai.ReplySendTransactionOutcome::class.java,
            ChatMessageBatch::class.java,
            ChatPageSnapshot::class.java
        ).apply { isAccessible = true }
        assertTrue(handler.invoke(
            runtime,
            com.freedom.features.aireply.ai.ReplySendTransactionOutcome(
                com.freedom.features.aireply.ai.SendConfirmationResult.CONFIRMED,
                "p1-different-chat-send-id"
            ),
            requireNotNull(port.lastPersistedBatch),
            port.page
        ) as Boolean)
        runUntil(scheduler, 100) { logs.any { it.contains("PAGE_UNVERIFIED") } }
        assertEquals(1, port.navigateBackCalls)
        assertTrue(logs.any { it.contains("PAUSED_CHECKPOINT_SAVED") && it.contains("PRIVATE_SEND_RETURN_PAGE_UNVERIFIED") })
        assertFalse(logs.any { it.contains("BACK_RETRY_ACCEPTED") })
    }

    @Test
    fun delayedBackRechecksDraftTransactionBeforeDispatch() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(completeBoundaryEvidence = true)
        val listPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.CHAT_LIST,
            scannedNodeCount = 80,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = true,
            messageListTarget = null,
            inputFieldTarget = null,
            chatListViewport = ScreenRect(0, 120, 720, 1480),
            chatListCandidates = listOf(candidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            resolvedTapBounds = candidate.rowBounds
            messageDirection = ChatMessageDirection.OUTGOING
            page = listPage
        }
        var replyStillUnconfirmed = false
        val logs = ArrayList<String>()
        val gesture = object : TestGestureApi {
            override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                if (command.kind == GestureKind.TAP) port.page = chatDetailSnapshot()
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            sharedPageObservationCoordinator = PageObservationCoordinator(scheduler),
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = gesture,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            inspectAndroidRecoveryReadiness = ::testAndroidRecoveryReadiness,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isConversationDraftPending = { replyStillUnconfirmed }
        )
        runtime.scheduleInitialObservation()
        runUntil(scheduler, 130) {
            logs.any { it.contains("聊天样本已保存") } && scheduler.hasPendingDelay(1_500L)
        }
        replyStillUnconfirmed = true
        scheduler.runNextWithDelay(1_500L)
        assertEquals(0, port.navigateBackCalls)
        assertTrue(logs.any { it.contains("RECHECK_DRAFT_PENDING") })
    }

    private fun sessionCandidate(
        rowIndex: Int = 0,
        nameText: String = "Sample Contact",
        unreadStatus: com.freedom.features.aireply.ports.ChatUnreadStatus =
            com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
        completeBoundaryEvidence: Boolean = false,
        windowGeneration: Long = 1L,
        previewBoundsValid: Boolean = true
    ) = ChatListCandidateSnapshot(
        rowIndex = rowIndex,
        rowTarget = NodeTarget(
            WindowRef("com.immomo.momo", "com.immomo.momo.maintab.MaintabActivity", windowGeneration, 1),
            NodePath(listOf(0, rowIndex)),
            expectedClassName = "android.widget.RelativeLayout"
        ),
        rowBounds = ScreenRect(0, 200 + rowIndex * 150, 720, 350 + rowIndex * 150),
        rowVisibleToUser = completeBoundaryEvidence,
        intersectsViewport = true,
        fullyInsideViewport = true,
        rowBoundsValid = true,
        avatarBounds = ScreenRect(20, 220 + rowIndex * 150, 110, 310 + rowIndex * 150),
        avatarPath = NodePath(listOf(0, rowIndex, 0)),
        nameText = nameText,
        nameSource = com.freedom.features.aireply.ports.ChatNodeTextSource.NODE_TEXT,
        nameNodePath = NodePath(listOf(0, rowIndex, 1, 0)),
        nameBounds = ScreenRect(150, 215 + rowIndex * 150, 380, 250 + rowIndex * 150),
        nameNodeVisibleToUser = completeBoundaryEvidence,
        nameBoundsIntersectViewport = true,
        nameBoundsFullyInsideViewport = true,
        nameBoundsValid = true,
        previewText = "Preview",
        previewSource = com.freedom.features.aireply.ports.ChatNodeTextSource.NODE_TEXT,
        previewNodePath = null,
        previewBounds = ScreenRect(150, 255 + rowIndex * 150, 620, 290 + rowIndex * 150),
        previewNodeVisibleToUser = completeBoundaryEvidence,
        previewBoundsIntersectViewport = true,
        previewBoundsFullyInsideViewport = true,
        previewBoundsValid = previewBoundsValid,
        unreadStatus = unreadStatus,
        timestampText = "10:20",
        timestampSource = com.freedom.features.aireply.ports.ChatNodeTextSource.NODE_TEXT,
        timestampNodePath = null,
        timestampBounds = null,
        timestampNodeVisibleToUser = false
    )

    private fun chatDetailSnapshot(windowGeneration: Long = 1L) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "ChatActivity",
        pageKind = ChatPageKind.CHAT_DETAIL_CANDIDATE,
        scannedNodeCount = 40,
        editableInputCount = 1,
        messageListCount = 1,
        sendActionCount = 1,
        messageRelatedViewIdCount = 1,
        hasChatTab = false,
        messageListTarget = NodeTarget(WindowRef("com.immomo.momo", "ChatActivity", windowGeneration, 1), NodePath(listOf(1, 2))),
        inputFieldTarget = NodeTarget(WindowRef("com.immomo.momo", "ChatActivity", windowGeneration, 1), NodePath(listOf(1, 3))),
        // Production accepts message reads only when the chat partner identity is verified.
        chatTitleText = "与Sample Contact对话"
    )

    private fun completeMessageTabProbe(command: GestureIntentRequest, listener: GestureExecutionListener) {
        if (command.description.contains("点击底部消息标签")) {
            listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
        }
    }

    private fun runMessageTabProbeFlow(scheduler: FakeScheduler) {
        scheduler.runNextWithDelay(180L)
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(900L)
    }

    private fun eventWithOrigin(
        type: AccessibilityEventType,
        origin: InputOrigin
    ) = event(type).copy(
        inputOriginDecision = InputOriginDecision(
            origin = origin,
            evidence = when (origin) {
                InputOrigin.PHYSICAL_USER -> InputOriginEvidence.PRIVILEGED_PHYSICAL_INPUT_PROVENANCE
                InputOrigin.SELF_INJECTED -> InputOriginEvidence.FREEDOM_DISPATCH_GESTURE_LEDGER_MATCH
                InputOrigin.APPLICATION_INTERNAL -> InputOriginEvidence.TARGET_APPLICATION_CONTROL_FEEDBACK
                InputOrigin.EXTERNAL_INJECTED -> InputOriginEvidence.POSSIBLE_EXTERNAL_TOUCH_OVERLAP
                InputOrigin.UNKNOWN -> InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION
            },
            actionId = if (origin == InputOrigin.SELF_INJECTED) "test-self-action" else null,
            reason = "explicit test fixture provenance"
        )
    )

    private fun event(
        type: AccessibilityEventType,
        vararg signals: AccessibilityUiSignal
    ) = AccessibilityEventEnvelope(
        type = type,
        packageName = "com.immomo.momo",
        className = "",
        contentChanges = if (type == AccessibilityEventType.WINDOW_CONTENT_CHANGED) {
            setOf(AccessibilityContentChange.SUBTREE)
        } else emptySet(),
        eventTimeMillis = 1L,
        eventText = emptyList(),
        contentDescription = null,
        source = null,
        signals = signals.toSet()
    )

    private object NoOpGestureApi : TestGestureApi {
        override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission =
            GestureSubmission.ACCEPTED
    }

    private class FakeChatPort : ChatPagePort, ChatMessagePort, ChatSessionManagementPort {
        var page = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "MainActivity",
            pageKind = ChatPageKind.NON_CHAT,
            scannedNodeCount = 10,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = false,
            messageListTarget = null,
            inputFieldTarget = null
        )
        var regionResult = ChatRegionSnapshot(true, 3, 1, 1, 1, true)
        var fullInspections = 0
        var regionRefreshes = 0
        var lastRefreshInput: ChatPageSnapshot? = null
        var messageReads = 0
        var identityCaptureOutcomeOverride: ContactIdentityCaptureOutcome? = null
        var pendingIdentityReturnReinspection = false
        var simulateStructureThenOcr = false
        var ocrAsyncReadRequests = 0
        var deferNextOcrRead = false
        private var deferredOcrRead: Pair<ChatPageSnapshot, (ChatMessageBatch) -> Unit>? = null
        fun completeDeferredOcrRead() {
            val pending = requireNotNull(deferredOcrRead)
            deferredOcrRead = null
            pending.second(readRecentMessages(pending.first, 10))
        }
        var deferNextStructureRead = false
        private var deferredStructureRead: Pair<ChatPageSnapshot, (ChatMessageBatch) -> Unit>? = null
        fun hasDeferredStructureRead() = deferredStructureRead != null
        fun completeDeferredStructureRead() {
            val pending = requireNotNull(deferredStructureRead)
            deferredStructureRead = null
            pending.second(readRecentMessages(pending.first, 20))
        }
        var lastMessageLimit = 0
        var lastMessagePage: ChatPageSnapshot? = null
        var messageContentQuality = MessageContentQuality.VERIFIED
        var messageDirection = ChatMessageDirection.INCOMING
        var persistedChatSamples = 0
        var persistChatSampleResult = 1
        var lastPersistedChatSessionName: String? = "UNSET"
        var lastPersistedBatch: ChatMessageBatch? = null
        var throwOnMessageRead = false
        var messageReadStatuses: List<MessageReadStatus> = emptyList()
        var ocrDebugTextByRowIndex: Map<Int, String>? = null
        var simulateOcrWithoutDebugPayload: Boolean = false
        var inspectionPages: List<ChatPageSnapshot>? = null
        var failNextInspection = false
        var omitMessageTabPositionEvidence = false
        var provideMessageTabPositionEvidence = false
        var sessionRunEnabled = false
        var verifiedMessageWorkAreaNavigationSupported = false
        var messageWorkAreaEntryResult = true
        var messageWorkAreaEntryAttempts = 0
        var captureMessageWorkAreaGestureOutcome = false
        val messageWorkAreaOutcomeCallbacks = ArrayList<(MessageWorkAreaNavigationOutcome) -> Unit>()
        var pageAfterMessageWorkAreaEntry: ChatPageSnapshot? = null
        var resolvedTapBounds: ScreenRect? = null
        var resolvedTapBoundsSequence: List<ScreenRect?>? = null
        var resolveTapBoundsFromCandidate: Boolean = false
        var sessionSwipeViewportResults: List<ScreenRect?>? = null
        var sessionSwipeViewportResolutions = 0
        var sessionTapResolutions = 0
        var sessionListSamples = 0
        var headerValidationDiagnostics = 0
        var lastHeaderValidationDiagnostic: ChatHeaderNodeDiagnostic? = null
        var lastExpectedSessionCandidate: ChatListCandidateSnapshot? = null
        var pageAfterBack: ChatPageSnapshot? = null
        var navigateBackCalls: Int = 0
        var rejectBackAttempts = 0
        var backNoOpAttempts = 0
        var receivedGreetingsWorkflowEnabled: Boolean = false
        var receivedGreetingsIdleSteps: Int = 0
        var receivedGreetingsNavigationTaskStarts: Int = 0
        var receivedGreetingsCurrentListStartAttempts: Int = 0
        var beginCurrentListResult: Boolean = true
        var manualGreetingsArrivalStatus: ReceivedGreetingsPageArrivalStatus =
            ReceivedGreetingsPageArrivalStatus.NOT_TARGET
        var manualGreetingsArrivalObservations: Int = 0
        var manualGreetingsArrivalStatuses: List<ReceivedGreetingsPageArrivalStatus>? = null
        private var manualGreetingsArrivalIndex: Int = 0
        var receivedGreetingsPageObservations: Int = 0
        var receivedGreetingsPageObservationResult: Boolean = true
        var receivedGreetingsPriorityReturns: Int = 0
        var receivedGreetingsBackAttempts: Int = 0
        var receivedGreetingsConfirmedReturns: Int = 0
        var receivedGreetingsBackDeferred: Boolean = false
        var greetingDetailCompletionCalls = 0
        var greetingDetailSuccessfulCompletions = 0
        var greetingDetailReturnAccepted = true
        var greetingModelFailureDeferralCalls = 0
        var greetingModelFailureDeferralAccepted = true
        var greetingSendJournalAccepted = true
        var greetingSendJournalWrites = 0
        var receivedGreetingsTraversalStatusValue: ReceivedGreetingsTraversalStatus =
            ReceivedGreetingsTraversalStatus.UNKNOWN
        var greetingAwaitingSafeTargetEvidenceOnly = false
        var receivedGreetingsContactIdentity: ContactRowIdentity? = null
        var receivedGreetingsRowKeyHash: String? = null
        var receivedGreetingsDisplayName: String? = null
        var receivedGreetingsIdentityProfileProof = false
        var receivedGreetingsCheckpointFingerprint: String? = null
        var receivedGreetingsRecoverySyncAccepted: Boolean = false
        var pageAfterReceivedGreetingsReturn: ChatPageSnapshot? = null
        val resolvedTapCandidateNames = ArrayList<String?>()

        override fun beginSessionManagement(): String? = if (sessionRunEnabled) "test-run" else null
        override fun hasPendingIdentityReturnReinspectionRequest(): Boolean = pendingIdentityReturnReinspection
        override fun consumeIdentityReturnReinspectionRequest(): Boolean =
            pendingIdentityReturnReinspection.also { pendingIdentityReturnReinspection = false }
        override fun observeContactIdentityCapture(candidate: ChatListCandidateSnapshot): ContactIdentityCaptureOutcome =
            identityCaptureOutcomeOverride
                ?: super<ChatSessionManagementPort>.observeContactIdentityCapture(candidate)
        override fun finishSessionManagement(): String? = "test-run.zip"
        override fun captureSessionListSample(snapshot: ChatPageSnapshot, onComplete: (String?) -> Unit) {
            sessionListSamples++
            onComplete(null)
        }
        override fun resolveSessionTapBounds(snapshot: ChatPageSnapshot, candidate: ChatListCandidateSnapshot): ScreenRect? {
            sessionTapResolutions++
            resolvedTapCandidateNames += candidate.nameText
            val sequence = resolvedTapBoundsSequence
            return if (sequence != null) sequence.getOrNull(sessionTapResolutions - 1)
                else if (resolveTapBoundsFromCandidate) candidate.rowBounds else resolvedTapBounds
        }
        override fun resolveSessionListSwipeViewport(snapshot: ChatPageSnapshot): ScreenRect? {
            val sequence = sessionSwipeViewportResults
            val index = sessionSwipeViewportResolutions++
            return if (sequence == null) {
                snapshot.chatListViewport
            } else {
                sequence.getOrNull(index) ?: if (index >= sequence.size) sequence.lastOrNull() else null
            }
        }
        override fun persistChatSample(snapshot: ChatPageSnapshot, batch: ChatMessageBatch, sessionName: String?): Int {
            persistedChatSamples++
            lastPersistedChatSessionName = sessionName
            lastPersistedBatch = batch
            return persistChatSampleResult
        }
        override fun persistChatHeaderValidationDiagnostic(
            snapshot: ChatPageSnapshot,
            expectedSessionName: String?,
            matched: Boolean,
            expectedCandidate: ChatListCandidateSnapshot?
        ) {
            headerValidationDiagnostics++
            lastHeaderValidationDiagnostic = snapshot.chatHeaderDiagnostic
            lastExpectedSessionCandidate = expectedCandidate
        }
        override fun navigateBackToSessionList(): Boolean {
            navigateBackCalls++
            if (navigateBackCalls <= rejectBackAttempts) return false
            if (navigateBackCalls > backNoOpAttempts) pageAfterBack?.let { page = it }
            return true
        }

        override fun supportsTargetPackage(packageName: String): Boolean = packageName == "com.immomo.momo"
        override fun supportsVerifiedMessageWorkAreaNavigation(): Boolean =
            verifiedMessageWorkAreaNavigationSupported
        override fun ensureMessageWorkArea(): Boolean {
            messageWorkAreaEntryAttempts++
            if (messageWorkAreaEntryResult) pageAfterMessageWorkAreaEntry?.let { page = it }
            return messageWorkAreaEntryResult
        }
        override fun ensureMessageWorkArea(
            onGestureOutcome: (MessageWorkAreaNavigationOutcome) -> Unit
        ): Boolean {
            val accepted = ensureMessageWorkArea()
            if (accepted && captureMessageWorkAreaGestureOutcome) {
                messageWorkAreaOutcomeCallbacks += onGestureOutcome
            }
            return accepted
        }
        override fun supportsReceivedGreetingsIdleWorkflow(): Boolean = receivedGreetingsWorkflowEnabled
        override fun beginReceivedGreetingsNavigationTask() {
            receivedGreetingsNavigationTaskStarts++
        }
        override fun performReceivedGreetingsIdleStep(): Boolean {
            receivedGreetingsIdleSteps++
            return true
        }
        override fun observeManuallyOpenedReceivedGreetingsPage(): ReceivedGreetingsPageArrivalStatus {
            manualGreetingsArrivalObservations++
            val sequence = manualGreetingsArrivalStatuses
            return sequence?.getOrNull(manualGreetingsArrivalIndex++) ?: manualGreetingsArrivalStatus
        }
        override fun beginReceivedGreetingsTaskFromCurrentList(): Boolean {
            receivedGreetingsCurrentListStartAttempts++
            return beginCurrentListResult
        }
        override fun observeReceivedGreetingsPage(): Boolean {
            receivedGreetingsPageObservations++
            return receivedGreetingsPageObservationResult
        }
        override fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus =
            receivedGreetingsTraversalStatusValue
        override fun isReceivedGreetingsWaitingForSafeTargetEvidence(): Boolean =
            greetingAwaitingSafeTargetEvidenceOnly
        override fun receivedGreetingsCurrentContactIdentity(): ContactRowIdentity? =
            receivedGreetingsContactIdentity
        override fun receivedGreetingsCurrentRowKeyHash(): String? = receivedGreetingsRowKeyHash
        override fun receivedGreetingsCurrentDisplayName(): String? = receivedGreetingsDisplayName
        override fun persistReceivedGreetingConfirmedSend(confirmationId: String): Boolean {
            greetingSendJournalWrites++
            return greetingSendJournalAccepted && confirmationId.isNotBlank()
        }
        override fun deferReceivedGreetingAfterModelFailure(): Boolean {
            greetingModelFailureDeferralCalls++
            return greetingModelFailureDeferralAccepted && greetingDetailReturnAccepted
        }
        override fun completeReceivedGreetingDetailInspection(success: Boolean): Boolean {
            greetingDetailCompletionCalls++
            if (!greetingDetailReturnAccepted) return false
            if (success) greetingDetailSuccessfulCompletions++
            return true
        }
        override fun isReceivedGreetingsIdentityProfileNavigationActive(snapshot: ChatPageSnapshot): Boolean =
            receivedGreetingsIdentityProfileProof &&
                snapshot.packageName == "com.immomo.momo" &&
                snapshot.activityClass == "com.immomo.momo.personalprofile.presentation.activity.PersonalProfileActivityK" &&
                snapshot.pageKind == ChatPageKind.NON_CHAT
        override fun receivedGreetingsRecoveryFingerprint(): String? =
            receivedGreetingsCheckpointFingerprint
        override fun receivedGreetingsCompletionReturnSourceFingerprint(): String? =
            receivedGreetingsCheckpointFingerprint ?: page.takeIf {
                it.pageKind == ChatPageKind.NON_CHAT &&
                    it.activityClass.contains("NewSayHi")
            }?.let { "test-greeting-source:${it.windowGeneration}" }
        override fun synchronizeReceivedGreetingsRecoveryConfirmation(): Boolean =
            receivedGreetingsRecoverySyncAccepted
        override fun returnFromReceivedGreetingsForPriorityCheck(): ReceivedGreetingsBackResult {
            receivedGreetingsBackAttempts++
            if (receivedGreetingsBackDeferred) return ReceivedGreetingsBackResult.GESTURE_IN_FLIGHT
            if (receivedGreetingsPriorityReturns > receivedGreetingsConfirmedReturns) {
                return ReceivedGreetingsBackResult.WAITING_FOR_PAGE_CONFIRMATION
            }
            receivedGreetingsPriorityReturns++
            pageAfterReceivedGreetingsReturn?.let { page = it }
            return ReceivedGreetingsBackResult.BACK_SUBMITTED
        }
        override fun confirmReceivedGreetingsCompletionReturn(): Boolean {
            receivedGreetingsConfirmedReturns++
            return true
        }

        override fun inspectChatPage(): ChatPageSnapshot {
            fullInspections++
            if (failNextInspection) {
                failNextInspection = false
                error("test transient page inspection failure")
            }
            val snapshot = inspectionPages?.getOrNull(fullInspections - 1) ?: page
            if (!provideMessageTabPositionEvidence || omitMessageTabPositionEvidence ||
                !snapshot.hasChatTab ||
                snapshot.sessionListNavigationTarget != null
            ) return snapshot
            // The synthetic Message-tab anchor must belong to the exact same Android window
            // as the freshly extracted inbox row. Never invent a second window identity here.
            val navigationWindow = snapshot.chatListCandidates.firstOrNull()?.rowTarget?.window
                ?: snapshot.messageListTarget?.window
                ?: snapshot.inputFieldTarget?.window
                ?: return snapshot
            return snapshot.copy(
                screenBounds = snapshot.screenBounds ?: ScreenRect(0, 0, 720, 1600),
                sessionListNavigationTarget = SessionListNavigationTarget(
                    nodeTarget = NodeTarget(
                        window = navigationWindow,
                        path = NodePath(listOf(0, 2, 1)),
                        expectedViewId = "com.immomo.momo:id/maintab_layout_chat",
                        expectedClassName = "android.widget.FrameLayout"
                    ),
                    bounds = ScreenRect(288, 1486, 432, 1600)
                )
            )
        }

        override fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot {
            regionRefreshes++
            lastRefreshInput = previous
            return regionResult
        }

        override fun readRecentMessagesStructureAsync(
            page: ChatPageSnapshot,
            limit: Int,
            onResult: (ChatMessageBatch) -> Unit
        ) {
            if (deferNextStructureRead) {
                deferNextStructureRead = false
                deferredStructureRead = page to onResult
            } else if (simulateStructureThenOcr) {
                // Emulate Momo's pre-identity structure phase: OCR targets exist,
                // but the screenshot/recognizer must not be invoked yet.
                onResult(readRecentMessages(page, limit).copy(
                    ocrAttempted = false,
                    ocrRecognizedTargetCount = 0,
                    ocrFailedTargetCount = 0,
                    ocrDebugTextByRowIndex = null
                ))
            } else {
                super<ChatMessagePort>.readRecentMessagesStructureAsync(page, limit, onResult)
            }
        }

        override fun readRecentMessagesAsync(
            page: ChatPageSnapshot,
            limit: Int,
            onResult: (ChatMessageBatch) -> Unit
        ) {
            ocrAsyncReadRequests++
            if (deferNextOcrRead) {
                deferNextOcrRead = false
                deferredOcrRead = page to onResult
            } else {
                onResult(readRecentMessages(page, limit))
            }
        }

        override fun readRecentMessages(page: ChatPageSnapshot, limit: Int): ChatMessageBatch {
            messageReads++
            lastMessagePage = page
            lastMessageLimit = limit
            check(!throwOnMessageRead) { "test adapter failure" }
            val message = ChatMessage(
                id = "fake-message-id",
                direction = messageDirection,
                type = ChatMessageType.TEXT,
                content = "PRIVATE_CHAT_BODY_SENTINEL",
                bounds = ScreenRect(10, 20, 300, 80),
                directionEvidence = DirectionEvidence.BUBBLE_GEOMETRY,
                contentQuality = messageContentQuality
            )
            return ChatMessageBatch(
                status = messageReadStatuses.getOrNull(messageReads - 1) ?: MessageReadStatus.SUCCESS,
                windowSessionKey = page.messageListTarget?.window?.let { window ->
                    page.conversationIdentityKey()?.let { conversation ->
                        "${window.packageName}|${window.windowId}|${window.generation}|conversation=$conversation"
                    }
                },
                messages = if (messageReadStatuses.getOrNull(messageReads - 1) ==
                    MessageReadStatus.MESSAGE_LIST_UNAVAILABLE
                ) emptyList() else listOf(message),
                scannedRowCount = 3,
                candidateRowCount = 1,
                truncated = false,
                capturedAtMillis = 1L,
                ocrTargets = if (ocrDebugTextByRowIndex == null && !simulateOcrWithoutDebugPayload) emptyList() else listOf(
                    ChatMessageOcrTarget(
                        rowIndex = 1,
                        rowBounds = ScreenRect(10, 20, 300, 80),
                        bodyBounds = ScreenRect(20, 25, 280, 70),
                        bubbleBounds = ScreenRect(10, 20, 300, 80),
                        avatarBounds = ScreenRect(10, 20, 50, 60)
                    )
                ),
                ocrAttempted = ocrDebugTextByRowIndex != null || simulateOcrWithoutDebugPayload,
                ocrRecognizedTargetCount = ocrDebugTextByRowIndex?.values?.count(String::isNotBlank) ?: 0,
                ocrFailedTargetCount = if (ocrDebugTextByRowIndex == null && !simulateOcrWithoutDebugPayload) 0 else {
                    (1 - (ocrDebugTextByRowIndex?.values?.count(String::isNotBlank) ?: 0)).coerceAtLeast(0)
                },
                ocrDebugTextByRowIndex = ocrDebugTextByRowIndex
            )
        }
    }

    private class DeferredChatPort : ChatPagePort, ChatMessagePort, ChatSessionManagementPort {
        private val delegate = FakeChatPort()
        var page: ChatPageSnapshot
            get() = delegate.page
            set(value) { delegate.page = value }
        var fullInspections: Int = 0
            private set
        var ocrDebugTextByRowIndex: Map<Int, String>?
            get() = delegate.ocrDebugTextByRowIndex
            set(value) { delegate.ocrDebugTextByRowIndex = value }
        private data class PendingRead(
            val snapshot: ChatPageSnapshot,
            val onResult: (ChatMessageBatch) -> Unit
        )
        private val pendingReads = ArrayList<PendingRead>()
        val asyncReadRequests: Int get() = pendingReads.size

        override fun beginSessionManagement(): String? = delegate.beginSessionManagement()
        override fun finishSessionManagement(): String? = delegate.finishSessionManagement()
        override fun captureSessionListSample(snapshot: ChatPageSnapshot, onComplete: (String?) -> Unit) =
            delegate.captureSessionListSample(snapshot, onComplete)
        override fun resolveSessionTapBounds(snapshot: ChatPageSnapshot, candidate: ChatListCandidateSnapshot): ScreenRect? =
            delegate.resolveSessionTapBounds(snapshot, candidate)
        override fun persistChatSample(snapshot: ChatPageSnapshot, batch: ChatMessageBatch, sessionName: String?): Int =
            delegate.persistChatSample(snapshot, batch, sessionName)
        override fun navigateBackToSessionList(): Boolean = delegate.navigateBackToSessionList()

        override fun supportsTargetPackage(packageName: String): Boolean =
            delegate.supportsTargetPackage(packageName)

        override fun inspectChatPage(): ChatPageSnapshot {
            fullInspections++
            return delegate.inspectChatPage()
        }

        override fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot =
            delegate.refreshKnownChatRegions(previous)

        override fun readRecentMessages(page: ChatPageSnapshot, limit: Int): ChatMessageBatch =
            delegate.readRecentMessages(page, limit)

        override fun readRecentMessagesAsync(
            page: ChatPageSnapshot,
            limit: Int,
            onResult: (ChatMessageBatch) -> Unit
        ) {
            pendingReads += PendingRead(page, onResult)
        }

        fun completeRead(index: Int = pendingReads.lastIndex) {
            val pending = pendingReads.getOrNull(index) ?: error("No deferred message read at index=$index")
            pending.onResult(delegate.readRecentMessages(pending.snapshot, 10))
        }
    }

    private class FakeScheduler : TaskScheduler {
        private data class Entry(val delay: Long, val block: () -> Unit, var cancelled: Boolean = false)
        private val entries = ArrayList<Entry>()

        override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledTask {
            val entry = Entry(delayMillis, task)
            entries += entry
            return ScheduledTask { entry.cancelled = true }
        }

        fun pendingCount(): Int = entries.count { !it.cancelled }
        fun nextDelay(): Long = entries.firstOrNull { !it.cancelled }?.delay ?: -1L
        fun hasPendingDelay(delayMillis: Long): Boolean =
            entries.any { !it.cancelled && it.delay == delayMillis }

        fun runNext() {
            val entry = entries.firstOrNull { !it.cancelled } ?: error("No pending task")
            entry.cancelled = true
            entry.block()
        }

        /** New recovery tests model delay ordering; the legacy FIFO fixture remains unchanged. */
        fun runNextShortestDelay() {
            val entry = entries.filterNot { it.cancelled }.minByOrNull { it.delay }
                ?: error("No pending task")
            entry.cancelled = true
            entry.block()
        }

        fun runNextWithDelay(delayMillis: Long) {
            while (entries.none { !it.cancelled && it.delay == delayMillis }) {
                val prerequisite = entries.firstOrNull {
                    !it.cancelled && it.delay <= delayMillis && it.delay in setOf(0L, 180L)
                } ?: error("No pending task with delay=$delayMillis")
                prerequisite.cancelled = true
                prerequisite.block()
            }
            val entry = entries.firstOrNull { !it.cancelled && it.delay == delayMillis }
                ?: error("No pending task with delay=$delayMillis")
            entry.cancelled = true
            entry.block()
        }
    }
}
