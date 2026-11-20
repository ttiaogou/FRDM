package com.freedom.features.aireply

import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatRegionSnapshot
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsTaskFlowControllerTest {
    @Test
    fun navigationStartAndConfirmedListStartKeepLifecycleTransitionsInOneOwner() {
        val port = FakePagePort()
        val flow = createFlow(port)

        assertTrue(flow.claimOwnership("start greetings"))
        assertEquals(ReceivedGreetingsTaskFlowController.StartResult.STARTED, flow.beginNavigationTask())
        assertTrue(flow.isActive)
        assertEquals(1, port.navigationStarts)

        flow.reset()
        assertTrue(flow.claimOwnership("confirmed list"))
        assertEquals(ReceivedGreetingsTaskFlowController.StartResult.STARTED, flow.beginFromConfirmedList())
        assertTrue(flow.isActive)
        assertEquals(1, port.currentListStarts)
    }

    @Test
    fun unsupportedCapabilitiesAreReportedBeforeAdapterStart() {
        val port = FakePagePort().apply { workflowSupported = false }
        val flow = createFlow(port)

        assertTrue(flow.claimOwnership("start greetings"))
        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.NOT_SUPPORTED,
            flow.beginNavigationTask()
        )
        assertFalse(flow.isActive)
        assertEquals(0, port.navigationStarts)
    }

    @Test
    fun failedConfirmedListStartDoesNotMarkTaskStarted() {
        val port = FakePagePort().apply { currentListStartAccepted = false }
        val flow = createFlow(port)

        assertTrue(flow.claimOwnership("confirmed list"))
        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.START_FAILED,
            flow.beginFromConfirmedList()
        )
        assertFalse(flow.isActive)
        assertEquals(1, port.currentListStarts)
    }

    @Test
    fun classifiedPageSnapshotProducesStableProgressDecisions() {
        val port = FakePagePort()
        val flow = createFlow(port)

        port.status = ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.ENTRY_NOT_FOUND,
            flow.evaluatePageSnapshot(ChatPageKind.CHAT_LIST).kind
        )

        port.status = ReceivedGreetingsTraversalStatus.COMPLETE
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.COMPLETE,
            flow.evaluatePageSnapshot(ChatPageKind.NON_CHAT).kind
        )

        port.status = ReceivedGreetingsTraversalStatus.DETAIL_READY
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.DETAIL_READY,
            flow.evaluatePageSnapshot(ChatPageKind.CHAT_DETAIL_CANDIDATE).kind
        )
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.WAIT_FOR_DETAIL_PAGE,
            flow.evaluatePageSnapshot(ChatPageKind.CHAT_LIST).kind
        )
    }

    @Test
    fun classifiedPageStepMapsAdapterOutcomesAndResetsSuccessfulRetryEpisode() {
        val port = FakePagePort()
        val flow = createFlow(port)
        assertTrue(flow.claimOwnership("test"))

        assertEquals(1, flow.nextRecoveryRetryPlan().retryCount)
        port.status = ReceivedGreetingsTraversalStatus.SCANNING
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.LIST_CONTINUES,
            flow.evaluatePageSnapshot(ChatPageKind.CHAT_LIST).kind
        )
        assertEquals(1, flow.nextRecoveryRetryPlan().retryCount)

        port.status = ReceivedGreetingsTraversalStatus.PROFILE_CARD
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.PROFILE_CARD_PENDING,
            flow.evaluatePageSnapshot(ChatPageKind.CHAT_LIST).kind
        )

        port.status = ReceivedGreetingsTraversalStatus.UNKNOWN
        port.stepAccepted = false
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.RETRY_REQUIRED,
            flow.evaluatePageSnapshot(ChatPageKind.NON_CHAT).kind
        )
    }

    @Test
    fun classifiedMissingEntryAndUnclassifiedMissingEntryKeepTheirDifferentSemantics() {
        val port = FakePagePort()
        val flow = createFlow(port)
        assertTrue(flow.claimOwnership("test"))

        port.status = ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
        port.stepAccepted = false
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.ENTRY_NOT_FOUND,
            flow.evaluatePageSnapshot(ChatPageKind.CHAT_LIST).kind
        )
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.RETRY_REQUIRED,
            flow.evaluateUnclassifiedPageSnapshot().kind
        )
    }

    @Test
    fun unclassifiedListProgressNeverExecutesBusinessStepAndKeepsRecoveryRetryState() {
        val port = FakePagePort()
        val flow = createFlow(port)
        assertTrue(flow.claimOwnership("test"))

        flow.nextRecoveryRetryPlan()
        port.status = ReceivedGreetingsTraversalStatus.SCANNING
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.RETRY_REQUIRED,
            flow.evaluateUnclassifiedPageSnapshot().kind
        )
        assertEquals(2, flow.nextRecoveryRetryPlan().retryCount)

        port.status = ReceivedGreetingsTraversalStatus.PROFILE_CARD
        assertEquals(
            ReceivedGreetingsTaskFlowController.DecisionKind.RETRY_REQUIRED,
            flow.evaluateUnclassifiedPageSnapshot().kind
        )
    }

    @Test
    fun retryPlanUsesExistingProgressiveBoundedScheduleAndLifecycleOwnsExitLatch() {
        val port = FakePagePort()
        val flow = createFlow(port)
        assertEquals(500L, flow.nextRecoveryRetryPlan().delayMs)
        assertEquals(1_000L, flow.nextRecoveryRetryPlan().delayMs)

        flow.markCompletedAwaitingUserExit()
        assertTrue(flow.completedAwaitingUserExit)
        flow.acknowledgeCompletedTaskExit()
        assertFalse(flow.completedAwaitingUserExit)

        flow.markAwaitingNormalInboxAfterReturn()
        assertTrue(flow.awaitingNormalInboxAfterReturn)
        flow.markTraversalReturnedToInbox()
        assertFalse(flow.awaitingNormalInboxAfterReturn)
    }

    private fun createFlow(port: FakePagePort): ReceivedGreetingsTaskFlowController {
        val ownership = AiReplyTaskOwnership(
            coordinator = TaskCoordinator(UiOperationOwnershipCoordinator()),
            currentRunGeneration = { 1L },
            onLog = {}
        )
        ownership.beginRun(1L)
        return ReceivedGreetingsTaskFlowController(ownership, port)
    }

    private class FakePagePort : ChatPagePort {
        var navigationStarts = 0
        var idleSteps = 0
        var currentListStarts = 0
        var status = ReceivedGreetingsTraversalStatus.SCANNING
        var stepAccepted = true
        var workflowSupported = true
        var currentListStartAccepted = true

        override fun supportsTargetPackage(packageName: String): Boolean = true
        override fun inspectChatPage(): ChatPageSnapshot = error("not used")
        override fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot = error("not used")
        override fun supportsReceivedGreetingsIdleWorkflow(): Boolean = workflowSupported
        override fun beginReceivedGreetingsNavigationTask() { navigationStarts++ }
        override fun performReceivedGreetingsIdleStep(): Boolean { idleSteps++; return stepAccepted }
        override fun beginReceivedGreetingsTaskFromCurrentList(): Boolean {
            currentListStarts++
            return currentListStartAccepted
        }
        override fun returnFromReceivedGreetingsForPriorityCheck(): Boolean = true
        override fun completeReceivedGreetingDetailInspection(success: Boolean): Boolean = true
        override fun observeManuallyOpenedReceivedGreetingsPage(): ReceivedGreetingsPageArrivalStatus =
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
        override fun observeReceivedGreetingsPage(): Boolean = true
        override fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus = status
    }
}
