package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused lease/startup regression coverage for the received-greetings handoff. */
class TaskLeaseLifecycleVerificationTest {
    @Test
    fun unsupportedAdapterStopsBeforeNavigationAndDoesNotStartTask() {
        val port = FakePagePort().apply { workflowSupported = false }
        val flow = flow(port)
        assertTrue(flow.claimOwnership("test"))

        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.NOT_SUPPORTED,
            flow.beginNavigationTask()
        )
        assertFalse(flow.isActive)
        assertEquals(0, port.navigationStarts)
    }

    @Test
    fun automaticNavigationFailureDoesNotMarkTaskStarted() {
        val port = FakePagePort().apply { navigationThrows = true }
        val flow = flow(port)
        assertTrue(flow.claimOwnership("test"))

        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.START_FAILED,
            flow.beginNavigationTask()
        )
        assertFalse(flow.isActive)
        assertEquals(1, port.navigationStarts)
    }

    @Test
    fun manualTakeoverFailureDoesNotMarkTaskStarted() {
        val port = FakePagePort().apply { currentListStartAccepted = false }
        val flow = flow(port)
        assertTrue(flow.claimOwnership("test"))

        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.START_FAILED,
            flow.beginFromConfirmedList()
        )
        assertFalse(flow.isActive)
        assertEquals(1, port.currentListStarts)
    }

    @Test
    fun firstTraversalFailureKeepsTheLeaseForRecoveryWithoutMarkingCompletion() {
        val port = FakePagePort().apply { idleStepAccepted = false }
        val flow = flow(port)
        assertTrue(flow.claimOwnership("test"))
        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.STARTED,
            flow.beginFromConfirmedList()
        )

        assertFalse(flow.performInitialIdleStep())
        assertTrue(flow.isActive)
        assertTrue(flow.ownsTask())
    }

    @Test
    fun normalCompletionReleasesTheExactGreetingsLease() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ownership = AiReplyTaskOwnership(coordinator, { 1L }, {})
        ownership.beginRun(1L)
        val flow = ReceivedGreetingsTaskFlowController(ownership, FakePagePort())
        assertTrue(flow.claimOwnership("test"))
        flow.markStarted()
        flow.markTraversalCompleted()
        assertTrue(flow.isActive)
        assertTrue(flow.awaitingNormalInboxAfterReturn)
        assertTrue(flow.ownsTask())
        assertEquals(ReceivedGreetingsTaskController.TASK_ID, coordinator.activeTaskId())

        assertTrue(flow.returnForPriorityCheck())
        flow.markReturnRequested()
        assertTrue(flow.isActive)
        assertTrue(flow.returnRequested)
        assertTrue(flow.ownsTask())

        flow.markTraversalReturnedToInbox()
        assertFalse(flow.isActive)
        assertFalse(flow.awaitingNormalInboxAfterReturn)
        assertFalse(flow.returnRequested)

        assertTrue(flow.releaseOwnership("completed"))
        assertFalse(flow.ownsTask())
        assertNull(coordinator.activeTaskId())
    }

    @Test
    fun staleLeaseFromAnOlderGenerationCannotAdvanceTheNewTask() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val old = requireNotNull(coordinator.tryAcquire("old", 1L))
        val current = requireNotNull(coordinator.tryAcquire("new", 2L))

        assertFalse(coordinator.isCurrent(old))
        assertTrue(coordinator.isCurrent(current))
        assertEquals("new", coordinator.activeTaskId())
    }

    @Test
    fun releasedLeaseRejectsLateEventFromTheOldTask() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ownership = AiReplyTaskOwnership(coordinator, { 1L }, {})
        ownership.beginRun(1L)
        val flow = ReceivedGreetingsTaskFlowController(ownership, FakePagePort())
        assertTrue(flow.claimOwnership("test"))
        assertTrue(flow.releaseOwnership("old task stopped"))

        assertFalse(flow.ownsTask())
        assertFalse(flow.performInitialIdleStep())
        assertNull(coordinator.activeTaskId())
    }

    @Test
    fun duplicateStartDuringRecoveryIsIdempotent() {
        val port = FakePagePort()
        val flow = flow(port)
        assertTrue(flow.claimOwnership("test"))
        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.STARTED,
            flow.beginNavigationTask()
        )

        assertEquals(
            ReceivedGreetingsTaskFlowController.StartResult.STARTED,
            flow.beginNavigationTask()
        )
        assertEquals(1, port.navigationStarts)
    }

    @Test
    fun transferredLeaseAllowsPrivateMessagesToResumeAfterGreetingsReturn() {
        val coordinator = TaskCoordinator(UiOperationOwnershipCoordinator())
        val ownership = AiReplyTaskOwnership(coordinator, { 1L }, {})
        ownership.beginRun(1L)
        val greetings = ReceivedGreetingsTaskController(ownership)
        val privateMessages = PrivateMessagesTaskController(ownership)
        assertTrue(greetings.claimOwnership("greetings"))

        assertTrue(privateMessages.claimOwnership("returned normal inbox"))
        assertFalse(greetings.ownsTask())
        assertTrue(privateMessages.ownsTask())
        assertEquals(PrivateMessagesTaskController.TASK_ID, coordinator.activeTaskId())
    }

    private fun flow(port: FakePagePort): ReceivedGreetingsTaskFlowController {
        val ownership = AiReplyTaskOwnership(
            TaskCoordinator(UiOperationOwnershipCoordinator()),
            { 1L },
            {}
        )
        ownership.beginRun(1L)
        return ReceivedGreetingsTaskFlowController(ownership, port)
    }

    private class FakePagePort : ChatPagePort {
        var workflowSupported = true
        var navigationThrows = false
        var currentListStartAccepted = true
        var idleStepAccepted = true
        var navigationStarts = 0
        var currentListStarts = 0

        override fun supportsTargetPackage(packageName: String): Boolean = true
        override fun inspectChatPage(): ChatPageSnapshot = error("not used")
        override fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot = error("not used")
        override fun supportsReceivedGreetingsIdleWorkflow(): Boolean = workflowSupported
        override fun beginReceivedGreetingsNavigationTask() {
            navigationStarts++
            if (navigationThrows) error("navigation failure")
        }
        override fun performReceivedGreetingsIdleStep(): Boolean = idleStepAccepted
        override fun beginReceivedGreetingsTaskFromCurrentList(): Boolean {
            currentListStarts++
            return currentListStartAccepted
        }
        override fun observeManuallyOpenedReceivedGreetingsPage(): ReceivedGreetingsPageArrivalStatus =
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
        override fun observeReceivedGreetingsPage(): Boolean = true
        override fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus =
            ReceivedGreetingsTraversalStatus.SCANNING
        override fun returnFromReceivedGreetingsForPriorityCheck(): Boolean = true
        override fun completeReceivedGreetingDetailInspection(success: Boolean): Boolean = true
    }
}
