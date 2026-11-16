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
import com.freedom.core.accessibility.events.InputOriginDecision
import com.freedom.core.accessibility.events.InputOriginEvidence
import com.freedom.core.runtime.ScheduledTask
import com.freedom.core.runtime.TaskScheduler
import com.freedom.core.runtime.TaskCoordinator
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
import com.freedom.features.aireply.ports.ChatHeaderNodeDiagnostic
import com.freedom.features.aireply.ports.ChatMessagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageNodeEvidence
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPort
import com.freedom.features.aireply.ports.ChatListCandidateSnapshot
import com.freedom.features.aireply.ports.ChatListUnreadDiagnostics
import com.freedom.features.aireply.ports.ChatRegionSnapshot
import com.freedom.features.aireply.ports.SessionListNavigationTarget
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

class AiReplyObservationRuntimeTest {
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            taskCoordinator = taskCoordinator,
            currentRunGeneration = { 5L }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", taskCoordinator.activeTaskId())
        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_START, InputOrigin.PHYSICAL_USER))
        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_END, InputOrigin.PHYSICAL_USER))
        scheduler.runNextWithDelay(180L)

        assertEquals(0, gestureCount)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertTrue(logs.any { it.contains("收到的招呼页面监听[WAITING]") })

        port.manualGreetingsArrivalStatuses = listOf(
            ReceivedGreetingsPageArrivalStatus.LIST_STABILIZING,
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
        )
        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_START, InputOrigin.PHYSICAL_USER))
        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_END, InputOrigin.PHYSICAL_USER))
        scheduler.runNextWithDelay(180L)
        assertEquals(0, gestureCount)
        scheduler.runNextWithDelay(350L)

        assertEquals(0, gestureCount)
        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertEquals(0, port.receivedGreetingsNavigationTaskStarts)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", taskCoordinator.activeTaskId())
        assertTrue(logs.any { it.contains("收到的招呼人工任务[STARTED]") })

        // Option A: completion releases automation ownership but never navigates away.
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        scheduler.runNextWithDelay(1_300L)
        assertEquals(0, port.receivedGreetingsPriorityReturns)
        assertNull(taskCoordinator.activeTaskId())
        assertTrue(logs.any { it.contains("收到的招呼任务[COMPLETE_STAY_ON_PAGE]") })
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        runtime.schedule(
            eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER)
                .copy(signals = setOf(AccessibilityUiSignal.CHAT_NAVIGATION))
        )
        scheduler.runNextWithDelay(300L) // A debounced fresh page snapshot probes the manual arrival.

        assertTrue(logs.any { it.contains("收到的招呼页面监听[WAITING]") })
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(0, gestures)
        scheduler.runNextWithDelay(350L) // A second fresh same-window list snapshot confirms arrival.

        assertEquals(1, port.receivedGreetingsCurrentListStartAttempts)
        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertEquals(0, port.receivedGreetingsNavigationTaskStarts)
        assertEquals(0, gestures)
        assertTrue(logs.any { it.contains("收到的招呼人工任务[STARTED]") })
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
                chatPagePort = port,
                chatMessagePort = port,
                chatSessionManagementPort = port,
                gesturePolicy = NoOpGestureApi,
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = logs::add,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L,
                chooseRandomInt = { minimum, _ -> minimum }
            )

            runtime.scheduleInitialObservation(passiveOnly = false)
            runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, origin))
            scheduler.runNextWithDelay(300L)

            assertEquals("First read-only probe must run for $origin", 1, port.manualGreetingsArrivalObservations)
            assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)
            assertTrue(logs.any { it.contains("收到的招呼页面监听[WAITING]") })

            scheduler.runNextWithDelay(350L)

            assertEquals("Stable list must start the task for $origin", 1, port.receivedGreetingsCurrentListStartAttempts)
            assertEquals(1, port.receivedGreetingsIdleSteps)
            assertTrue(logs.any { it.contains("收到的招呼人工任务[STARTED]") })
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
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
        assertTrue(logs.any { it.contains("收到的招呼页面监听[LOW_FREQUENCY_FALLBACK]") })
        assertTrue(logs.any { it.contains("收到的招呼人工任务[STARTED]") })
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.UNKNOWN))
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED))
        runtime.schedule(eventWithOrigin(AccessibilityEventType.WINDOW_CONTENT_CHANGED, InputOrigin.UNKNOWN))

        scheduler.runNextWithDelay(300L)

        assertEquals(1, port.manualGreetingsArrivalObservations)
        assertTrue(scheduler.hasPendingDelay(60_000L))
    }

    @Test
    fun passiveAiModeArchivesManualChatWithoutGesturesOrDirectionPromotion() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            messageDirection = ChatMessageDirection.UNKNOWN
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
                chatListCandidates = listOf(sessionCandidate())
            )
        }
        var gestureCount = 0
        var observedBatch: ChatMessageBatch? = null
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onMessagesObserved = { observedBatch = it }
        )

        runtime.scheduleInitialObservation(passiveOnly = true)
        scheduler.runNext() // Read the list currently displayed by the user.
        scheduler.runNext() // Complete the passive list sample callback.

        assertEquals(1, port.sessionListSamples)
        assertEquals(0, gestureCount)
        assertEquals(0, port.sessionTapResolutions)

        // Simulate the user's manual entry into a one-to-one chat detail.
        port.page = chatDetailSnapshot()
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNext()

        assertEquals(1, port.messageReads)
        assertEquals(1, port.persistedChatSamples)
        assertEquals(null, port.lastPersistedChatSessionName)
        assertEquals(ChatMessageDirection.UNKNOWN, port.lastPersistedBatch?.messages?.single()?.direction)
        assertTrue(observedBatch?.messages.isNullOrEmpty())
        assertEquals(0, gestureCount)
        assertEquals(0, port.sessionTapResolutions)
        assertTrue(logs.any { it.contains("人工打开的聊天详情已归档") })

        // A repeated content event may reread the same visible batch, but must not duplicate
        // the same archived sample for this window generation.
        runtime.schedule(event(AccessibilityEventType.VIEW_TEXT_CHANGED))
        scheduler.runNext()
        assertEquals(2, port.messageReads)
        assertEquals(1, port.persistedChatSamples)
        assertEquals(0, gestureCount)
    }

    @Test
    fun passiveModeInspectsNonChatPagesAfterManualScrollWithoutRunningGestures() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 30,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null,
                sessionListNavigationTarget = testNavigationTarget("com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity"),
            )
        }
        var gestureCount = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
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
            onLog = {},
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = true)
        scheduler.runNext()
        assertEquals(1, port.passiveSayHiObservations)

        val manualScroll = eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER)
        runtime.onPreemptiveTargetInteraction(manualScroll)
        runtime.schedule(manualScroll)
        scheduler.runNextWithDelay(180L) // The passive Message-tab guard runs before quiet verification.
        scheduler.runNextWithDelay(5_000L) // UNKNOWN scroll is a temporary manual hand-off.
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // Three stable snapshots resume passive page observation only.
        assertEquals(2, port.passiveSayHiObservations)
        assertTrue(port.fullInspections >= 2)
        assertEquals(0, gestureCount)
        assertEquals(0, port.sessionTapResolutions)
    }

    @Test
    fun userTouchYieldsAutomationThenResumesAfterStablePageVerificationAndInboxReturn() {
        val scheduler = FakeScheduler()
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
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(sessionCandidate())
            )
        }
        var gestureCount = 0
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = true)
        scheduler.runNext() // Initial passive recognition must not issue a managed swipe.
        assertEquals(0, gestureCount)

        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_START, InputOrigin.PHYSICAL_USER))
        port.page = chatDetailSnapshot()
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        assertEquals(0, port.messageReads) // No read against a page while the user is touching.

        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_END, InputOrigin.PHYSICAL_USER))
        scheduler.runNextWithDelay(5_000L) // Shared manual-handoff quiet period.
        assertEquals(0, port.messageReads)
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // Three matching snapshots confirm the page.

        assertEquals(1, port.messageReads)
        assertEquals(1, port.persistedChatSamples)
        assertEquals(null, port.lastPersistedChatSessionName)
        assertEquals(0, gestureCount) // A manually opened detail remains read-only.
        assertTrue(logs.any { it.contains("让出界面控制权") })
        assertTrue(logs.any { it.contains("被动样本归档") && it.contains("不执行自动返回") })
        assertFalse(logs.any { it.contains("自动接管已恢复") })

        // Returning to a freshly recognized inbox keeps passive collection passive.
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
            chatListCandidates = listOf(sessionCandidate())
        )
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNextWithDelay(180L)
        assertEquals(0, gestureCount)
        assertTrue(logs.any { it.contains("PASSIVE_ONLY") && it.contains("MESSAGE_PAGE") })
    }

    @Test
    fun unresolvedManualHandoffEscalatesFiveFifteenSixtySecondsThenLatchesAndTurnsScreenOff() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "MainActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 20,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null,
                sessionListNavigationTarget = testNavigationTarget("MainActivity"),
            )
        }
        val logs = ArrayList<String>()
        var persistedReviewReason: String? = null
        var screenOffRequests = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission =
                    GestureSubmission.ACCEPTED
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            onManualReviewRequired = { persistedReviewReason = it },
            onRequestScreenOff = { screenOffRequests++; true }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext() // Start autonomous session management.
        runtime.onGlobalTouchEvent(
            eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_START, InputOrigin.PHYSICAL_USER)
        )
        runtime.onGlobalTouchEvent(
            eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_END, InputOrigin.PHYSICAL_USER)
        )

        scheduler.runNextWithDelay(5_000L)
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // First stable verification fails: not the session list.
        assertNull(persistedReviewReason)
        scheduler.runNextWithDelay(180L) // The second-stage check first re-runs the Message-tab guard.
        assertTrue(scheduler.hasPendingDelay(15_000L))

        scheduler.runNextWithDelay(15_000L)
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // Second stable verification also fails.
        assertNull(persistedReviewReason)
        scheduler.runNextWithDelay(180L)
        assertTrue(scheduler.hasPendingDelay(60_000L))

        scheduler.runNextWithDelay(60_000L)
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // Third failure persists the hold and requests screen-off directly.

        assertTrue(persistedReviewReason != null)
        assertTrue(persistedReviewReason!!.contains("第三轮静默 60 秒"))
        assertEquals(1, screenOffRequests)
        assertTrue(logs.any { it.contains("MANUAL_REVIEW_LATCHED") && it.contains("已请求息屏=true") })
        assertEquals(0L, scheduler.pendingCount().toLong())
    }

    @Test
    fun stableNonChatPageKeepsResumePendingUntilTheInboxReturns() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate()
        val chatListPage = ChatPageSnapshot(
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
            chatListCandidates = listOf(candidate)
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiCardActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 30,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null,
                sessionListNavigationTarget = testNavigationTarget("com.immomo.momo.message.sayhi.activity.reflect.NewSayHiCardActivity"),
            )
        }
        var gestureCount = 0
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext() // Initial observation of the current non-chat page.
        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_START, InputOrigin.PHYSICAL_USER))
        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_END, InputOrigin.PHYSICAL_USER))
        scheduler.runNextWithDelay(5_000L)
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // Stable NON_CHAT is confirmed; safe navigation is unavailable.

        assertEquals(0, gestureCount)
        assertTrue(logs.any { it.contains("页面=NON_CHAT") && it.contains("本轮未满足自主恢复条件") })
        assertTrue(logs.any { it.contains("开始第2轮静默 15 秒") })
        assertFalse(logs.any { it.contains("自动接管已恢复") })

        // A later Activity transition back to the inbox must trigger a fresh stability check.
        port.page = chatListPage
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runMessageTabProbeFlow(scheduler) // Tap Message and compare the fresh inbox position.

        assertTrue("expected inbox resume log; logs=$logs", logs.any {
            (it.contains("消息会话列表") && it.contains("自动接管已恢复")) ||
                it.contains("启动仲裁[INITIALIZATION_RESUMED]")
        })
        assertTrue(gestureCount >= 1) // The Message-tab probe was accepted before re-arming.
    }

    @Test
    fun autonomousRunResumesAfterManualHandoffAndVerifiedInboxReturn() {
        val scheduler = FakeScheduler()
        val chatListPage = ChatPageSnapshot(
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
            chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ))
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = chatListPage
            pageAfterBack = chatListPage
        }
        var gestureCount = 0
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext() // Autonomous mode must submit its initial inbox-positioning swipe.
        val autonomousGestureCount = gestureCount
        assertTrue(autonomousGestureCount > 0)

        runtime.onPreemptiveTargetInteraction(eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER))
        port.page = chatDetailSnapshot()
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNextWithDelay(5_000L)
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // Stable detail is verified, then a safe Back is submitted.

        assertTrue(logs.any { it.contains("让出界面控制权") })
        assertFalse(logs.any { it.contains("自动接管已恢复") })
        assertEquals(1, port.messageReads) // The manually opened detail is observed read-only.
        assertEquals(1, port.navigateBackCalls)
        scheduler.runNextWithDelay(900L) // Wait for the Back transition, then verify the inbox.
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L)
        runMessageTabProbeFlow(scheduler)
        assertTrue(logs.any { it.contains("启动仲裁[INITIALIZATION_RESUMED]") })
        assertTrue(gestureCount > autonomousGestureCount)
    }

    @Test
    fun startupVisibleUnreadStillPositionsAtBottomBeforeBottomToTopTraversal() {
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
            chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD))
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        val submittedKinds = mutableListOf<GestureKind>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    submittedKinds += command.kind
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        runUntil(scheduler, 30) { submittedKinds.contains(GestureKind.SWIPE) }

        assertTrue(logs.any { it.contains("首次向上滑动") })
        assertTrue("首次向上准入滑动必须先于未读判断", submittedKinds.contains(GestureKind.SWIPE))
    }

    @Test
    fun startupUnknownUnreadStateIsCheckedOnlyAfterTheMandatoryFirstSwipe() {
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
            chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNKNOWN))
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        val submittedKinds = mutableListOf<GestureKind>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    submittedKinds += command.kind
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()

        assertTrue("UNKNOWN 不能阻止首次准入滑动；必须在该手势成功后的新快照中再判断", submittedKinds.contains(GestureKind.SWIPE))
    }

    @Test
    fun startupNoUnreadStillSubmitsExactlyTheMandatoryAdmissionSwipeFirst() {
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
            chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ))
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        val submittedKinds = mutableListOf<GestureKind>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    submittedKinds += command.kind
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()

        assertTrue(logs.any { it.contains("首次向上滑动") })
        assertTrue("消息工作区首次必须执行向上准入滑动", submittedKinds.contains(GestureKind.SWIPE))
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestures += command.kind
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
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
        assertTrue(logs.any { it.contains("首次向上滑动") })
        assertTrue("通过稳定复核和工作区确认后执行首次向上准入滑动", gestures.contains(GestureKind.SWIPE))
    }

    @Test
    fun unknownCounterClearsOnlyAfterTenSecondsAndThreeStableFreshSnapshots() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.maintab.MaintabActivity",
                pageKind = ChatPageKind.NON_CHAT,
                scannedNodeCount = 30,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null,
                windowGeneration = 4L
            )
        }
        val logs = mutableListOf<String>()
        var clock = 1_000L
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            uptimeMillis = { clock }
        )
        val firstUnknown = event(AccessibilityEventType.VIEW_SCROLLED).copy(
            windowId = 41,
            pageGeneration = 4L,
            source = AccessibilitySourceSnapshot(
                className = "androidx.recyclerview.widget.RecyclerView",
                viewId = "com.immomo.momo:id/active_rv",
                text = null,
                contentDescription = null,
                bounds = ScreenRect(0, 120, 720, 1480),
                clickable = false,
                longClickable = false,
                scrollable = true,
                editable = false
            ),
            inputOriginDecision = InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "test unmatched scroll"
            )
        )

        runtime.onPreemptiveTargetInteraction(firstUnknown)
        assertEquals(1, logs.count { it.contains("未知交互仲裁[BEHAVIOUR]") })
        assertTrue(scheduler.hasPendingDelay(10_000L))

        clock = 11_000L
        scheduler.runNext() // Ten seconds have elapsed; first fresh snapshot.
        scheduler.runNext() // Second matching fresh snapshot, 300 ms later.
        scheduler.runNext() // Third matching fresh snapshot clears the sub-threshold count.

        assertTrue(logs.any { it.contains("未知交互仲裁[COUNTER_RESET]") })
        assertTrue(logs.any { it.contains("连续10秒无新UNKNOWN") })

        val nextPageUnknown = firstUnknown.copy(
            windowId = 42,
            pageGeneration = 5L,
            source = firstUnknown.source?.copy(viewId = "com.immomo.momo:id/new_popup")
        )
        runtime.onPreemptiveTargetInteraction(nextPageUnknown)
        val behaviourLogs = logs.filter { it.contains("未知交互仲裁[BEHAVIOUR]") }
        assertEquals(2, behaviourLogs.size)
        assertTrue(behaviourLogs.all { it.contains("count=1/3") })
    }

    @Test
    fun singleUnknownGlobalTouchBoundaryPairDoesNotYieldAutomation() {
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
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()
        val beforeTouch = gestureCount
        runtime.onGlobalTouchEvent(event(AccessibilityEventType.TOUCH_INTERACTION_START))
        runtime.onGlobalTouchEvent(event(AccessibilityEventType.TOUCH_INTERACTION_END))
        assertFalse(persistedReviewRequired)
        assertTrue(logs.any { it.contains("未知交互仲裁[BEHAVIOUR]") && it.contains("count=1/3") })
        assertTrue(logs.any { it.contains("UNKNOWN_CONTINUES") })
        assertFalse(logs.any { it.contains("PROTECTION_TRIGGERED") })
        assertEquals(beforeTouch, gestureCount)
    }

    @Test
    fun globalTouchEndRestartsQuietPeriodWhenThirdUnknownTargetEventAlreadyEnteredSettling() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort()
        val logs = mutableListOf<String>()
        val runtime = runtime(scheduler, port, logs::add)

        fun unknownClick(windowId: Int, viewId: String) = event(AccessibilityEventType.VIEW_CLICKED).copy(
            windowId = windowId,
            pageGeneration = windowId.toLong(),
            source = AccessibilitySourceSnapshot(
                className = "android.widget.Button",
                viewId = "com.immomo.momo:id/$viewId",
                text = null,
                contentDescription = null,
                bounds = ScreenRect(10, 10, 110, 60),
                clickable = true,
                longClickable = false,
                scrollable = false,
                editable = false
            ),
            inputOriginDecision = InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "unmatched target interaction"
            )
        )

        runtime.onPreemptiveTargetInteraction(unknownClick(11, "popup_one"))
        runtime.onPreemptiveTargetInteraction(unknownClick(12, "popup_two"))
        runtime.onPreemptiveTargetInteraction(unknownClick(13, "popup_three"))
        assertTrue(logs.any { it.contains("未知交互仲裁[PROTECTION_TRIGGERED]") })

        runtime.onGlobalTouchEvent(event(AccessibilityEventType.TOUCH_INTERACTION_END))

        assertTrue(logs.any { it.contains("PROTECTION_SETTLING") && it.contains("静默计时从触摸结束重新起算") })
    }

    @Test
    fun unknownClickDuringStartupDefersInitializationAndResumesOnStableInbox() {
        val scheduler = FakeScheduler()
        val startupPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.UNKNOWN,
            scannedNodeCount = 13,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = false,
            messageListTarget = null,
            inputFieldTarget = null
        )
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
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = startupPage
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext() // First Activity frame is not yet a recognized inbox.
        val startupAdDismissClick = event(AccessibilityEventType.VIEW_CLICKED).copy(
            windowId = 19,
            pageGeneration = 1L,
            source = AccessibilitySourceSnapshot(
                className = "android.widget.Button",
                viewId = "com.immomo.momo:id/popup-one",
                text = null,
                contentDescription = null,
                bounds = ScreenRect(100, 100, 300, 180),
                clickable = true,
                longClickable = false,
                scrollable = false,
                editable = false
            ),
            inputOriginDecision = InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "click feedback has no Freedom action correlation during startup"
            )
        )
        runtime.onPreemptiveTargetInteraction(startupAdDismissClick)
        runtime.schedule(startupAdDismissClick)
        assertFalse("A single UNKNOWN startup event must not pause the task", persistedReviewRequired)
        assertTrue(logs.any { it.contains("CONTINUE_AUTONOMOUS") && it.contains("count=1/3") })
        assertFalse(logs.any { it.contains("PROTECTION_TRIGGERED") })

        listOf("popup-two", "popup-three").forEachIndexed { index, viewId ->
            val next = startupAdDismissClick.copy(
                windowId = 20 + index,
                pageGeneration = 2L + index,
                source = AccessibilitySourceSnapshot(
                    className = "android.widget.Button",
                    viewId = "com.immomo.momo:id/$viewId",
                    text = null, contentDescription = null,
                    bounds = ScreenRect(100, 100, 300, 180),
                    clickable = true, longClickable = false, scrollable = false, editable = false
                )
            )
            runtime.onPreemptiveTargetInteraction(next)
            runtime.schedule(next)
        }
        assertFalse(persistedReviewRequired)
        assertTrue(logs.any { it.contains("PROTECTION_TRIGGERED") && it.contains("3/3") })

        port.page = inbox
        runUntilLog(scheduler, logs, "启动仲裁[INITIALIZATION_RESUMED]")
        assertFalse(persistedReviewRequired)
        assertTrue(logs.any { it.contains("启动仲裁[INITIALIZATION_RESUMED]") })
        assertTrue(gestureCount > 0)
    }

    @Test
    fun unknownGlobalTouchBoundariesDuringStartupDoNotLatchPersistentReview() {
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
            chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ))
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        runtime.onGlobalTouchEvent(event(AccessibilityEventType.TOUCH_INTERACTION_START))
        runtime.onGlobalTouchEvent(event(AccessibilityEventType.TOUCH_INTERACTION_END))
        assertFalse(persistedReviewRequired)
        assertTrue(logs.any { it.contains("count=1/3") })
        assertFalse(logs.any { it.contains("PROTECTION_TRIGGERED") })
        runUntil(scheduler, 12) { gestureCount > 0 }
        assertTrue(gestureCount > 0)
    }

    @Test
    fun startupUnknownClickOnNonChatWaitsForInboxReturnWithoutLatchingReview() {
        val scheduler = FakeScheduler()
        val startupPage = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.UNKNOWN,
            scannedNodeCount = 13,
            editableInputCount = 0,
            messageListCount = 0,
            sendActionCount = 0,
            messageRelatedViewIdCount = 0,
            hasChatTab = false,
            messageListTarget = null,
            inputFieldTarget = null
        )
        val nonChat = startupPage.copy(
            activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiCardActivity",
            pageKind = ChatPageKind.NON_CHAT,
            scannedNodeCount = 40
        )
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
            chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ))
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = startupPage
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()
        val unknownClick = event(AccessibilityEventType.VIEW_CLICKED).copy(
            inputOriginDecision = InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "startup dialog click without source bounds"
            )
        )
        runtime.onPreemptiveTargetInteraction(unknownClick)
        runtime.schedule(unknownClick)
        port.page = nonChat
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runUntilLog(scheduler, logs, "NewSayHiCardActivity")

        assertFalse(persistedReviewRequired)
        assertEquals(0, gestureCount)

        port.page = inbox
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runUntil(scheduler, 20) { gestureCount > 0 } // Returning to the inbox resumes startup inspection without a manual hand-off.
        assertFalse(persistedReviewRequired)
        assertFalse(logs.any { it.contains("未知交互仲裁[PROTECTION_TRIGGERED]") })
        assertTrue(logs.any { it.contains("首次向上滑动") })
        assertTrue(gestureCount > 0)
    }

    @Test
    fun threeIndependentUnknownClicksTriggerProtectionAndResumeAfterVerifiedInboxReturn() {
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
            chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ))
        )
        val nonChat = inbox.copy(
            activityClass = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
            pageKind = ChatPageKind.NON_CHAT,
            hasChatTab = false,
            chatListViewport = null,
            chatListCandidates = emptyList(),
            sessionListNavigationTarget = testNavigationTarget(
                "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity",
                generation = 1L,
                expectedViewId = "com.immomo.momo:id/new_sayhi_session_list",
                expectedClassName = "androidx.recyclerview.widget.RecyclerView",
                bounds = ScreenRect(0, 120, 720, 1480)
            )
        )
        val port = FakeChatPort().apply {
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        runUntil(scheduler, 12) { gestureCount > 0 } // Initial autonomous positioning gesture is accepted.
        val initialGestureCount = gestureCount
        assertTrue(initialGestureCount > 0)

        val unknownManualClick = event(AccessibilityEventType.VIEW_CLICKED).copy(
            source = AccessibilitySourceSnapshot(
                className = "android.widget.RelativeLayout",
                viewId = "com.immomo.momo:id/item_layout",
                text = null,
                contentDescription = null,
                bounds = ScreenRect(0, 838, 720, 985),
                clickable = true,
                longClickable = true,
                scrollable = false,
                editable = false
            ),
            windowId = 1,
            pageGeneration = 1L,
            inputOriginDecision = InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "unmatched inbox row click"
            )
        )
        port.page = nonChat
        runtime.onPreemptiveTargetInteraction(unknownManualClick)
        runtime.schedule(unknownManualClick)
        listOf(2, 3).forEachIndexed { index, _ ->
            val next = unknownManualClick.copy(
                windowId = 1,
                pageGeneration = 1L,
                source = unknownManualClick.source?.copy(
                    viewId = "com.immomo.momo:id/popup-${index + 2}"
                )
            )
            runtime.onPreemptiveTargetInteraction(next)
            runtime.schedule(next)
        }
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNextWithDelay(5_000L)
        scheduler.runNextWithDelay(300L)
        scheduler.runNextWithDelay(300L) // Stable NON_CHAT remains observation-only.

        assertFalse(persistedReviewRequired)
        assertTrue(logs.any { it.contains("未知交互仲裁[PROTECTION_TRIGGERED]") && it.contains("3/3") })
        assertTrue(logs.any { it.contains("页面=NON_CHAT") && it.contains("本轮未满足自主恢复条件") })
        assertTrue(logs.any { it.contains("开始第2轮静默 15 秒") })
        assertEquals(initialGestureCount, gestureCount)

        // Returning to the inbox is a page observation, not another input signal. Tap Message
        // and compare a fresh snapshot without restarting the quiet period.
        port.page = inbox
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runMessageTabProbeFlow(scheduler)

        assertFalse(persistedReviewRequired)
        assertTrue(logs.any { it.contains("自动接管已恢复") || it.contains("启动仲裁[INITIALIZATION_RESUMED]") })
        assertTrue(gestureCount > initialGestureCount)
    }

    @Test
    fun singleUnknownScrollDoesNotHandOff() {
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
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext() // Start the autonomous inbox positioning gesture.
        val beforeUnknownInput = gestureCount
        val unknownScroll = event(AccessibilityEventType.VIEW_SCROLLED).copy(
            inputOriginDecision = InputOriginDecision(
                InputOrigin.UNKNOWN,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "unmatched list scroll"
            )
        )
        runtime.onPreemptiveTargetInteraction(unknownScroll)
        runtime.schedule(unknownScroll)
        assertFalse(persistedReviewRequired)
        assertTrue(logs.any { it.contains("CONTINUE_AUTONOMOUS") && it.contains("count=1/3") })
        assertFalse(logs.any { it.contains("PROTECTION_TRIGGERED") })
        assertEquals(beforeUnknownInput, gestureCount)
    }

    @Test
    fun messageTabPositionGuardConfirmsAfterTapAndPageDataUpdatesDoNotStartQuietPeriod() {
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
            chatListViewport = ScreenRect(0, 156, 720, 1486),
            screenBounds = ScreenRect(0, 0, 720, 1600),
            sessionListNavigationTarget = SessionListNavigationTarget(
                nodeTarget = NodeTarget(
                    window = WindowRef(
                        packageName = "com.immomo.momo",
                        activityClass = "com.immomo.momo.maintab.MaintabActivity",
                        generation = 5L,
                        windowId = 41
                    ),
                    path = NodePath(listOf(0, 2, 1)),
                    expectedViewId = "com.immomo.momo:id/maintab_layout_chat",
                    expectedClassName = "android.widget.FrameLayout"
                ),
                bounds = ScreenRect(288, 1486, 432, 1600)
            )
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        var messageTabTaps = 0
        var messageTabFeedbackIntent = GestureFeedbackIntent.STANDARD
        val executor = object : TestGestureApi {
            override fun execute(
                command: GestureIntentRequest,
                listener: GestureExecutionListener
            ): GestureSubmission {
                if (command.kind == GestureKind.TAP && command.description.contains("点击底部消息标签")) {
                    messageTabTaps++
                    messageTabFeedbackIntent = command.feedbackIntent
                }
                listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                return GestureSubmission.ACCEPTED
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        val unknownScroll = eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER)
        runtime.onPreemptiveTargetInteraction(unknownScroll)
        runtime.schedule(unknownScroll)

        // Burst notifications while the guard is coalescing: none is a new touch signal.
        runtime.schedule(
            event(AccessibilityEventType.WINDOW_CONTENT_CHANGED).copy(
                source = AccessibilitySourceSnapshot(
                    className = "android.widget.TextView",
                    viewId = "com.immomo.momo:id/chatlist_item_tv_content",
                    text = null,
                    contentDescription = null,
                    bounds = ScreenRect(157, 240, 570, 278),
                    clickable = false,
                    longClickable = false,
                    scrollable = false,
                    editable = false
                )
            )
        )
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        runtime.schedule(
            event(AccessibilityEventType.WINDOW_CONTENT_CHANGED).copy(
                source = AccessibilitySourceSnapshot(
                    className = "android.widget.FrameLayout",
                    viewId = "com.immomo.momo:id/native_ad_container",
                    text = null,
                    contentDescription = null,
                    bounds = ScreenRect(0, 300, 720, 500),
                    clickable = false,
                    longClickable = false,
                    scrollable = false,
                    editable = false
                )
            )
        )

        scheduler.runNextWithDelay(180L) // Fresh pre-tap message-tab and viewport snapshot.
        scheduler.runNextWithDelay(0L) // Gesture completion callback.
        // Selecting Message may refresh the adjacent RecyclerView and emit a scroll event.
        // The ledger's explicit NAVIGATION_TAB intent must classify that as self feedback.
        val protectionCountBeforeSelfFeedback = logs.count { it.contains("PROTECTION_TRIGGERED") }
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED))
        scheduler.runNextWithDelay(900L) // Fresh post-tap snapshot; positions remain unchanged.

        assertEquals(1, messageTabTaps)
        assertEquals(GestureFeedbackIntent.NAVIGATION_TAB, messageTabFeedbackIntent)
        assertTrue(logs.any { it.contains("消息标签保护[CONFIRMED]") })
        assertTrue(logs.any { it.contains("MESSAGE_DATA_UPDATE") })
        assertTrue(logs.any { it.contains("PAGE_NAVIGATION_UPDATE") })
        assertTrue(logs.any { it.contains("AD_CONTENT_UPDATE") })
        assertTrue(logs.any {
            it.contains("HANDOFF_EVENT") && it.contains("事件=VIEW_SCROLLED") && it.contains("人工输入=false")
        })
        assertEquals(
            protectionCountBeforeSelfFeedback,
            logs.count { it.contains("PROTECTION_TRIGGERED") }
        )
        assertFalse(logs.any { it.contains("第1轮静默") })
        assertFalse(scheduler.hasPendingDelay(5_000L))
        assertFalse(scheduler.hasPendingDelay(15_000L))
        assertFalse(scheduler.hasPendingDelay(60_000L))
    }

    @Test
    fun messageTabProbeDoesNotResumeWhenTapChangesPageContext() {
        val scheduler = FakeScheduler()
        val tabTarget = SessionListNavigationTarget(
            nodeTarget = NodeTarget(
                window = WindowRef("com.immomo.momo", "com.immomo.momo.maintab.MaintabActivity", 5L, 41),
                path = NodePath(listOf(0, 2, 1)),
                expectedViewId = "com.immomo.momo:id/maintab_layout_chat",
                expectedClassName = "android.widget.FrameLayout"
            ),
            bounds = ScreenRect(288, 1486, 432, 1600)
        )
        val otherTab = ChatPageSnapshot(
            packageName = "com.immomo.momo",
            activityClass = "com.immomo.momo.maintab.MaintabActivity",
            pageKind = ChatPageKind.NON_CHAT,
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
            sessionListNavigationTarget = tabTarget
        )
        val inbox = otherTab.copy(pageKind = ChatPageKind.CHAT_LIST)
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = otherTab
        }
        val logs = mutableListOf<String>()
        var messageTabTaps = 0
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    if (command.description.contains("点击底部消息标签")) {
                        messageTabTaps++
                        port.page = inbox // The click changes page context: it was not already the active tab.
                        listener.onResult(GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED))
                    }
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()
        val manualScroll = eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER)
        runtime.onPreemptiveTargetInteraction(manualScroll)
        runtime.schedule(manualScroll)
        scheduler.runNextWithDelay(180L)
        scheduler.runNextWithDelay(0L)
        scheduler.runNextWithDelay(900L)

        assertEquals(1, messageTabTaps)
        assertFalse(logs.any { it.contains("消息标签保护[CONFIRMED]") })
        assertFalse(logs.any { it.contains("自动接管已恢复") })
        assertTrue(logs.any { it.contains("MESSAGE_PAGE_AWAITING_POSITION") })
        assertFalse(scheduler.hasPendingDelay(5_000L))
        assertTrue(scheduler.hasPendingDelay(30_000L))
    }

    @Test
    fun recoveryDoesNotResumeWhenMessageTabPositionEvidenceIsMissing() {
        val scheduler = FakeScheduler()
        var fakeUptimeMs = 10_000L
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            omitMessageTabPositionEvidence = true
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
                chatListViewport = ScreenRect(0, 156, 720, 1486)
            )
        }
        val logs = mutableListOf<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            uptimeMillis = { fakeUptimeMs }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()
        val manualScroll = eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER)
        runtime.onPreemptiveTargetInteraction(manualScroll)
        runtime.schedule(manualScroll)
        runUntilLog(scheduler, logs, "MESSAGE_PAGE_AWAITING_POSITION")

        assertFalse(logs.any { it.contains("自动接管已恢复") })
        assertFalse(scheduler.hasPendingDelay(5_000L))
        assertTrue(scheduler.hasPendingDelay(30_000L))
        assertTrue(logs.any { it.contains("30S_MESSAGE_TAB_WATCH_SCHEDULED") })
        fakeUptimeMs += 35_000L
        scheduler.runNextWithDelay(30_000L)
        assertTrue(logs.any { it.contains("30S_MESSAGE_TAB_WATCH_FIRED") && it.contains("lateByMs=5000") })
    }

    @Test
    fun passiveOnlyMessageTabGuardNeverExecutesNavigationGesture() {
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
            chatListViewport = ScreenRect(0, 156, 720, 1486),
            screenBounds = ScreenRect(0, 0, 720, 1600),
            sessionListNavigationTarget = SessionListNavigationTarget(
                nodeTarget = NodeTarget(
                    window = WindowRef(
                        packageName = "com.immomo.momo",
                        activityClass = "com.immomo.momo.maintab.MaintabActivity",
                        generation = 5L,
                        windowId = 41
                    ),
                    path = NodePath(listOf(0, 2, 1)),
                    expectedViewId = "com.immomo.momo:id/maintab_layout_chat",
                    expectedClassName = "android.widget.FrameLayout"
                ),
                bounds = ScreenRect(288, 1486, 432, 1600)
            )
        )
        val port = FakeChatPort().apply { sessionRunEnabled = true; page = inbox }
        var gestureCount = 0
        val logs = mutableListOf<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = true)
        scheduler.runNext()
        val manualScroll = eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.PHYSICAL_USER)
        runtime.onPreemptiveTargetInteraction(manualScroll)
        runtime.schedule(manualScroll)
        scheduler.runNextWithDelay(180L)

        assertEquals(0, gestureCount)
        assertTrue(logs.any { it.contains("消息标签保护[PASSIVE_ONLY]") })
    }

    @Test
    fun singleExternalInjectedClickDoesNotImmediatelyHandOff() {
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
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()
        val beforeExternalInput = gestureCount
        val externalClick = event(AccessibilityEventType.VIEW_CLICKED).copy(
            inputOriginDecision = InputOriginDecision(
                InputOrigin.EXTERNAL_INJECTED,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "external input provider"
            )
        )
        runtime.onPreemptiveTargetInteraction(externalClick)
        runtime.schedule(externalClick)
        assertFalse(persistedReviewRequired)
        assertEquals(beforeExternalInput, gestureCount)
        assertTrue(logs.any { it.contains("CONTINUE_AUTONOMOUS") && it.contains("origin=EXTERNAL_INJECTED") })
        assertFalse(logs.any { it.contains("PROTECTION_TRIGGERED") })
    }

    @Test
    fun externalInjectedNonClickEventAlsoYieldsWithoutPersistentReviewLatch() {
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
            provideMessageTabPositionEvidence = true
            sessionRunEnabled = true
            page = inbox
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var persistedReviewRequired = false
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    completeMessageTabProbe(command, listener)
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            isInputManualReviewRequired = { persistedReviewRequired }
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        scheduler.runNext()
        val beforeExternalEvent = gestureCount
        val externalFocusEvent = event(AccessibilityEventType.VIEW_FOCUSED).copy(
            inputOriginDecision = InputOriginDecision(
                InputOrigin.EXTERNAL_INJECTED,
                InputOriginEvidence.NO_MATCHING_FREEDOM_ACTION,
                reason = "external injected focus event"
            )
        )
        runtime.schedule(externalFocusEvent)

        assertFalse(persistedReviewRequired)
        assertEquals(beforeExternalEvent, gestureCount)
        assertFalse(logs.any { it.contains("EXTERNAL_INJECTED_HANDOFF") })
    }

    @Test
    fun recoveryLocatesAndTapsMessageTabBeforeResumingSessionTraversal() {
        val scheduler = FakeScheduler()
        val messageTabTarget = SessionListNavigationTarget(
            nodeTarget = NodeTarget(
                window = WindowRef("com.immomo.momo", "com.immomo.momo.maintab.MaintabActivity", 1L, 41),
                path = NodePath(listOf(0, 3)),
                expectedViewId = "com.immomo.momo:id/maintab_layout_chat",
                expectedClassName = "android.widget.FrameLayout"
            ),
            bounds = ScreenRect(0, 1400, 180, 1600)
        )
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
            screenBounds = ScreenRect(0, 0, 720, 1600),
            chatListCandidates = listOf(sessionCandidate()),
            sessionListNavigationTarget = messageTabTarget
        )
        val mainPageOutsideInbox = inbox.copy(
            pageKind = ChatPageKind.NON_CHAT,
            chatListViewport = null,
            chatListCandidates = emptyList()
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = mainPageOutsideInbox
        }
        val logs = mutableListOf<String>()
        var gestureCount = 0
        var tabTapCount = 0
        var tabTapCommand: GestureIntentRequest? = null
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(command: GestureIntentRequest, listener: GestureExecutionListener): GestureSubmission {
                    gestureCount++
                    if (command.description.contains("点击底部消息标签")) {
                        tabTapCount++
                        tabTapCommand = command
                        port.page = inbox
                        listener.onResult(
                            GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED)
                        )
                    }
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = false)
        // Initial page is NON_CHAT, so no traversal gesture is allowed.
        scheduler.runNext()
        val beforeHandoff = gestureCount
        val firstUnknownClick = eventWithOrigin(
            AccessibilityEventType.VIEW_CLICKED,
            InputOrigin.UNKNOWN
        ).copy(
            windowId = 31,
            pageGeneration = 1L,
            source = AccessibilitySourceSnapshot(
                className = "android.widget.Button",
                viewId = "com.immomo.momo:id/popup-one",
                text = null,
                contentDescription = null,
                bounds = ScreenRect(100, 100, 300, 180),
                clickable = true,
                longClickable = false,
                scrollable = false,
                editable = false
            )
        )
        listOf(firstUnknownClick, firstUnknownClick.copy(
            windowId = 32,
            pageGeneration = 2L,
            source = firstUnknownClick.source?.copy(viewId = "com.immomo.momo:id/popup-two")
        ), firstUnknownClick.copy(
            windowId = 33,
            pageGeneration = 3L,
            source = firstUnknownClick.source?.copy(viewId = "com.immomo.momo:id/popup-three")
        )).forEach { click ->
            runtime.onPreemptiveTargetInteraction(click)
            runtime.schedule(click)
        }
        runMessageTabProbeFlow(scheduler)

        assertEquals(1, tabTapCount)
        val submittedTap = tabTapCommand?.intent as? GestureIntent.Tap
        assertTrue("Business code must submit only the semantic Message-tab target", submittedTap != null)
        assertEquals(messageTabTarget.bounds, submittedTap?.preferredBounds)
        assertEquals(messageTabTarget.bounds, submittedTap?.targetBounds)
        assertEquals(inbox.screenBounds, submittedTap?.displayBounds)
        assertEquals(GestureFeedbackIntent.NAVIGATION_TAB, submittedTap?.feedbackIntent)
        assertTrue(logs.any { it.contains("消息标签保护[CONFIRMED]") })
        assertFalse(logs.any { it.contains("第1轮静默") })

        assertTrue(logs.any { it.contains("自动接管已恢复") || it.contains("启动仲裁[INITIALIZATION_RESUMED]") })
        assertTrue(gestureCount > beforeHandoff)
    }

    @Test
    fun delayedScrollEventAfterOwnGestureCallbackDoesNotYieldToUser() {
        val scheduler = FakeScheduler()
        val candidate = sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ)
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
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(candidate)
            )
        }
        val logs = ArrayList<String>()
        var pendingGestureListener: GestureExecutionListener? = null
        var pendingGestureIntentRequestId = ""
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    pendingGestureListener = listener
                    pendingGestureIntentRequestId = command.id
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntil(scheduler, 12) { pendingGestureListener != null } // Initial page inspection submits our own list swipe.
        assertTrue(pendingGestureListener != null)

        pendingGestureListener!!.onResult(
            GestureExecutionResult(
                pendingGestureIntentRequestId,
                GestureExecutionStatus.COMPLETED
            )
        )
        scheduler.runNext() // Gesture callback switches state to WAITING_FOR_SESSION_LIST.

        // Android can deliver TYPE_VIEW_SCROLLED after the gesture callback. The pending
        // viewport baseline is still the ownership marker until the refreshed snapshot arrives.
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.SELF_INJECTED))

        assertTrue(logs.none { it.contains("检测到目标页面滚动事件") })
        assertTrue(logs.none { it.contains("已让出界面控制权") })

        // Explicit user touch remains authoritative and revokes the pending self-scroll marker.
        runtime.onGlobalTouchEvent(eventWithOrigin(AccessibilityEventType.TOUCH_INTERACTION_START, InputOrigin.PHYSICAL_USER))
        assertTrue(logs.any { it.contains("PROTECTION_TRIGGERED") && it.contains("PHYSICAL_USER") })
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
    fun manualClickPreemptsAnActiveAutonomousScrollBeforeTheDebouncedPageEvent() {
        val scheduler = FakeScheduler()
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
                chatListViewport = ScreenRect(0, 120, 720, 1480),
                chatListCandidates = listOf(sessionCandidate(unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.READ))
            )
        }
        val logs = ArrayList<String>()
        var pendingCommandId: String? = null
        var pendingListener: GestureExecutionListener? = null
        var cancelledCommandId: String? = null
        val executor = object : TestGestureApiWithCancellation {
            override fun execute(
                command: GestureIntentRequest,
                listener: GestureExecutionListener
            ): GestureSubmission {
                pendingCommandId = command.id
                pendingListener = listener
                return GestureSubmission.ACCEPTED
            }

            override fun cancelActive(commandId: String, reason: String): Boolean {
                if (pendingCommandId == null || commandId != pendingCommandId) return false
                cancelledCommandId = pendingCommandId
                pendingListener?.onResult(
                    GestureExecutionResult(
                        commandId = pendingCommandId!!,
                        status = GestureExecutionStatus.CANCELLED,
                        reason = reason
                    )
                )
                return true
            }
        }
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext() // Initial CHAT_LIST inspection submits an autonomous list swipe.
        val autonomousCommandId = pendingCommandId
        assertTrue(autonomousCommandId != null)

        val manualClick = eventWithOrigin(AccessibilityEventType.VIEW_CLICKED, InputOrigin.PHYSICAL_USER).copy(
            source = AccessibilitySourceSnapshot(
                className = "android.widget.RelativeLayout",
                viewId = "com.immomo.momo:id/item_layout",
                text = null,
                contentDescription = null,
                bounds = ScreenRect(0, 600, 720, 750),
                clickable = true,
                longClickable = true,
                scrollable = false,
                editable = false
            )
        )
        runtime.onPreemptiveTargetInteraction(manualClick)

        assertEquals(autonomousCommandId, cancelledCommandId)
        assertTrue(logs.any { it.contains("未知交互仲裁[PROTECTION_TRIGGERED]") })
        assertTrue(logs.any { it.contains("ACTIVE_GESTURE_INVALIDATED") && it.contains("逻辑取消=true") })

        // The cancellation callback belongs to the pre-handoff generation and must not schedule
        // a retry/continuation after the human click has already taken control.
        scheduler.runNext()
        assertTrue(logs.any { it.contains("已忽略过期自主滑动回调") })
        assertTrue(logs.none { it.contains("已完成一次底部定位方向的列表滑动") })
    }

    @Test
    fun clickLongClickContextClickAndSelectionPreemptEvenWithoutSampleCapture() {
        val interactionTypes = listOf(
            AccessibilityEventType.VIEW_CLICKED,
            AccessibilityEventType.VIEW_LONG_CLICKED,
            AccessibilityEventType.VIEW_CONTEXT_CLICKED,
            AccessibilityEventType.VIEW_SELECTED
        )

        interactionTypes.forEach { type ->
            val logs = mutableListOf<String>()
            val runtime = AiReplyObservationRuntime(
                scheduler = FakeScheduler(),
                chatPagePort = FakeChatPort(),
                chatMessagePort = FakeChatPort(),
                chatSessionManagementPort = FakeChatPort(),
                gesturePolicy = NoOpGestureApi,
                targetPackage = { "com.immomo.momo" },
                isAiReplyActive = { true },
                onLog = logs::add,
                fullInspectionDebounceMs = 300L,
                regionRefreshDebounceMs = 500L
            )

            runtime.onPreemptiveTargetInteraction(eventWithOrigin(type, InputOrigin.PHYSICAL_USER))

            assertTrue("$type must yield control even when sample capture is inactive", logs.any {
                it.contains("未知交互仲裁[PROTECTION_TRIGGERED]")
            })
        }
    }

    @Test
    fun unidentifiedPagePausesOnlyItsCollectorAndLaterRecognizedPageResumesSampling() {
        val scheduler = FakeScheduler()
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            passiveSayHiObservationResult = false
            page = ChatPageSnapshot(
                packageName = "com.immomo.momo",
                activityClass = "com.immomo.momo.unknown.TransientActivity",
                pageKind = ChatPageKind.UNKNOWN,
                scannedNodeCount = 5,
                editableInputCount = 0,
                messageListCount = 0,
                sendActionCount = 0,
                messageRelatedViewIdCount = 0,
                hasChatTab = false,
                messageListTarget = null,
                inputFieldTarget = null
            )
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation(passiveOnly = true)
        scheduler.runNext()
        assertTrue(logs.any { it.contains("仅暂停当前页面业务采集") })
        assertEquals(0, port.messageReads)

        port.page = chatDetailSnapshot(windowGeneration = 9L)
        runtime.schedule(event(AccessibilityEventType.WINDOW_STATE_CHANGED))
        scheduler.runNext() // The recognized chat detail is inspected without restarting the run.
        assertEquals(1, port.messageReads)
        assertEquals(1, port.persistedChatSamples)
        assertTrue(logs.none { it.contains("AI 样本归档：运行时重置") })
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        scheduler.runNext() // Empty first snapshot schedules a short stability retry.
        assertTrue(logs.any { it.contains("第 1/3 次短间隔复核") })
        assertEquals(350L, scheduler.nextDelay())

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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntil(scheduler, 60) { port.messageReads == 1 }

        assertEquals(4, bottomSwipes) // Admission swipe, one bottom movement, and two fresh no-progress confirmations.
        assertEquals(1, upwardSwipes) // The first upward swipe exposes the middle page.
        assertEquals(
            listOf(
                GestureKind.SWIPE, GestureKind.SWIPE, GestureKind.SWIPE,
                GestureKind.SWIPE, GestureKind.SWIPE, GestureKind.TAP
            ),
            commands.map { it.kind }
        ) // Middle-page unread is tapped before another upward swipe can be submitted.
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
            // The mandatory startup-admission swipe adds a fresh pre-dispatch inspection.
            // Keep the late viewport change for the delayed boundary confirmation itself.
            inspectionPages = listOf(
                firstPage, firstPage, firstPage, firstPage,
                firstPage, firstPage, firstPage, latePage
            )
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
        val incompleteCandidate = sessionCandidate(
            nameText = "Partially Observed Contact",
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD
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
            chatListCandidates = listOf(incompleteCandidate),
            windowGeneration = 1L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = listPage
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
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
            chatListCandidates = listOf(visibleCandidate),
            windowGeneration = 0L
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            page = listPage
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI 会话管理已暂停：延迟边界复核未能确认同一会话列表页面")

        assertTrue(logs.none { it.contains("底部边界经延迟独立快照复核通过") })
    }

    @Test
    fun aiSessionManagerRepositionsAtBottomWhenListViewportChangesOutsideItsOwnSwipe() {
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L
        )

        runtime.scheduleInitialObservation()
        runUntil(scheduler, 60) { port.messageReads == 1 }
        runUntilLog(scheduler, logs, "未发出滚动手势时可见会话身份序列发生变化；当前列表位置不可信")

        assertEquals(1, commands.count { it.kind == GestureKind.TAP })
        assertEquals(GestureKind.SWIPE, commands.last().kind)
        assertTrue(commands.last().description.contains("启动定位底部"))
        assertTrue(logs.any { it.contains("保留本轮已处理身份，重新定位底部后继续审视") })
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNKNOWN
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
            )
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
        val gestures = ArrayList<GestureKind>()
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = object : TestGestureApi {
                override fun execute(
                    command: GestureIntentRequest,
                    listener: GestureExecutionListener
                ): GestureSubmission {
                    gestures += command.kind
                    listener.onResult(
                        GestureExecutionResult(command.id, GestureExecutionStatus.COMPLETED)
                    )
                    return GestureSubmission.ACCEPTED
                }
            },
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼只读 OCR 任务")

        assertEquals(1, port.receivedGreetingsIdleSteps)
        assertEquals("No-unread admission must perform exactly one swipe", listOf(GestureKind.SWIPE), gestures)
        assertTrue(logs.any { it.contains("首次成功向上滑动后的视口没有明确未读") })
        assertFalse(logs.any { it.contains("底部边界经延迟独立快照复核通过") })
        assertFalse(logs.any { it.contains("顶部边界经延迟独立快照复核通过") })
        assertFalse(logs.any { it.contains("随机待机") || it.contains("模拟回复人数") || it.contains("模拟处理目标") })
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼只读 OCR 任务")
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼只读 OCR 任务")
        port.page = profileCard
        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.PROFILE_CARD
        runUntilLog(scheduler, logs, "等待 NewSayHiSessionListActivity")

        assertTrue(logs.any { it.contains("已识别资料卡") && it.contains("顶部‘列表’控件") })
        assertFalse(logs.any { it.contains("暂停：资料卡顶部‘列表’控件未能切换") })
        assertTrue(scheduler.hasPendingDelay(1_300L))
    }

    @Test
    fun receivedGreetingsListTraversalCompletesAndStaysOnCurrentPage() {
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
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼只读 OCR 任务")
        port.page = nonChat
        scheduler.runNext()
        assertTrue(logs.any { it.contains("优先采集未读行头像身份和聊天详情 OCR，完成后再滚动；不回复") })
        assertTrue(scheduler.hasPendingDelay(1_300L))

        port.receivedGreetingsTraversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        runUntilLog(scheduler, logs, "收到的招呼任务[COMPLETE_STAY_ON_PAGE]")
        assertEquals(0, port.receivedGreetingsPriorityReturns)
        assertEquals(nonChat, port.page)
        assertTrue(logs.any { it.contains("保持当前收到的招呼页面") })
        assertFalse(logs.any { it.contains("已请求返回普通消息页") })
        assertFalse(logs.any { it.contains("模拟回复人数") })
    }

    @Test
    fun unknownUnreadRowsPauseInsteadOfBeingReportedAsNoUnread() {
        val scheduler = FakeScheduler()
        val unknownCandidate = sessionCandidate(
            unreadStatus = com.freedom.features.aireply.ports.ChatUnreadStatus.UNKNOWN
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
            chatListCandidates = listOf(unknownCandidate)
        )
        val port = FakeChatPort().apply {
            sessionRunEnabled = true
            receivedGreetingsWorkflowEnabled = true
            page = listPage
        }
        val logs = ArrayList<String>()
        val runtime = AiReplyObservationRuntime(
            scheduler = scheduler,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = executor,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.other.social.app" },
            isAiReplyActive = { true },
            onLog = {},
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
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

    private fun runtime(
        scheduler: FakeScheduler,
        port: FakeChatPort,
        onLog: (String) -> Unit = {}
    ) = AiReplyObservationRuntime(
        scheduler = scheduler,
        chatPagePort = port,
        chatMessagePort = port,
        chatSessionManagementPort = port,
        gesturePolicy = NoOpGestureApi,
        targetPackage = { "com.immomo.momo" },
        isAiReplyActive = { true },
        onLog = onLog,
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

    private fun sessionCandidate(
        rowIndex: Int = 0,
        nameText: String = "Sample Contact",
        unreadStatus: com.freedom.features.aireply.ports.ChatUnreadStatus =
            com.freedom.features.aireply.ports.ChatUnreadStatus.UNREAD,
        completeBoundaryEvidence: Boolean = false,
        windowGeneration: Long = 1L
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
        previewBoundsValid = true,
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
        var lastMessageLimit = 0
        var lastMessagePage: ChatPageSnapshot? = null
        var messageContentQuality = MessageContentQuality.VERIFIED
        var messageDirection = ChatMessageDirection.INCOMING
        var persistedChatSamples = 0
        var lastPersistedChatSessionName: String? = "UNSET"
        var lastPersistedBatch: ChatMessageBatch? = null
        var throwOnMessageRead = false
        var messageReadStatuses: List<MessageReadStatus> = emptyList()
        var ocrDebugTextByRowIndex: Map<Int, String>? = null
        var simulateOcrWithoutDebugPayload: Boolean = false
        var inspectionPages: List<ChatPageSnapshot>? = null
        var omitMessageTabPositionEvidence = false
        var provideMessageTabPositionEvidence = false
        var sessionRunEnabled = false
        var verifiedMessageWorkAreaNavigationSupported = false
        var messageWorkAreaEntryResult = true
        var messageWorkAreaEntryAttempts = 0
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
        var passiveSayHiObservations: Int = 0
        var passiveSayHiObservationResult: Boolean = true
        var receivedGreetingsPriorityReturns: Int = 0
        var receivedGreetingsTraversalStatusValue: ReceivedGreetingsTraversalStatus =
            ReceivedGreetingsTraversalStatus.UNKNOWN
        var pageAfterReceivedGreetingsReturn: ChatPageSnapshot? = null
        val resolvedTapCandidateNames = ArrayList<String?>()

        override fun beginSessionManagement(): String? = if (sessionRunEnabled) "test-run" else null
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
            return 1
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
            pageAfterBack?.let { page = it }
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
            passiveSayHiObservations++
            return passiveSayHiObservationResult
        }
        override fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus =
            receivedGreetingsTraversalStatusValue
        override fun returnFromReceivedGreetingsForPriorityCheck(): Boolean {
            receivedGreetingsPriorityReturns++
            pageAfterReceivedGreetingsReturn?.let { page = it }
            return true
        }

        override fun inspectChatPage(): ChatPageSnapshot {
            fullInspections++
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
