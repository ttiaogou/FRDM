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
import com.freedom.core.accessibility.gesture.VerticalSwipeDirection
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

        runtime.scheduleInitialObservation()
        assertEquals("AI_REPLY_PRIVATE_MESSAGES", taskCoordinator.activeTaskId())
        runtime.schedule(eventWithOrigin(AccessibilityEventType.VIEW_SCROLLED, InputOrigin.UNKNOWN))
        scheduler.runNextWithDelay(300L)

        assertEquals(1, port.manualGreetingsArrivalObservations)
        assertEquals(0, gestureCount)
        assertEquals(0, port.receivedGreetingsCurrentListStartAttempts)

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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = {},
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
    fun unknownStartupPageEventsDoNotLatchManualReview() {
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

        assertEquals(2, bottomSwipes) // Admission swipe, then stop after the stable read-only suffix is confirmed.
        assertEquals(1, upwardSwipes) // The first upward swipe exposes the middle page.
        assertEquals(
            listOf(
                GestureKind.SWIPE, GestureKind.SWIPE, // Startup: establish list top.
                GestureKind.SWIPE, GestureKind.SWIPE, GestureKind.SWIPE,
                GestureKind.SWIPE, GestureKind.SWIPE, GestureKind.TAP
            ),
            commands.map { it.kind }
        ) // Middle-page unread is tapped before another upward swipe can be submitted.
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
        val submittedCommands = ArrayList<GestureIntentRequest>()
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
            fullInspectionDebounceMs = 300L,
            regionRefreshDebounceMs = 500L,
            chooseRandomInt = { minimum, _ -> minimum }
        )

        runtime.scheduleInitialObservation()
        runUntilLog(scheduler, logs, "AI Reply：切换收到的招呼只读 OCR 任务")

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
        assertTrue(logs.any { it.contains("保持当前页面") })
        assertFalse(logs.any { it.contains("已请求返回普通消息页") })
        assertFalse(logs.any { it.contains("模拟回复人数") })
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
            chatPagePort = port,
            chatMessagePort = port,
            chatSessionManagementPort = port,
            gesturePolicy = NoOpGestureApi,
            targetPackage = { "com.immomo.momo" },
            isAiReplyActive = { true },
            onLog = logs::add,
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
        var receivedGreetingsPageObservations: Int = 0
        var receivedGreetingsPageObservationResult: Boolean = true
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
            receivedGreetingsPageObservations++
            return receivedGreetingsPageObservationResult
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
