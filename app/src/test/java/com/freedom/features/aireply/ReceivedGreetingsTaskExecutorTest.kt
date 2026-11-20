package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPagePort
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatRegionSnapshot
import com.freedom.features.aireply.ports.ReceivedGreetingsPageArrivalStatus
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivedGreetingsTaskExecutorTest {
    @Test
    fun mutatingTraversalStepIsRejectedWhenTaskDoesNotOwnUiOperations() {
        val port = FakePagePort()
        val executor = ReceivedGreetingsTaskExecutor(port) { false }

        assertFalse(executor.performIdleStep())
        assertEquals(ReceivedGreetingsTaskExecutor.StartResult.START_FAILED, executor.beginFromConfirmedList())
        assertFalse(executor.returnForPriorityCheck())
        assertFalse(executor.completeDetailInspection(success = true))
        assertEquals(0, port.idleSteps)
        assertEquals(0, port.currentListStarts)
        assertEquals(0, port.priorityReturns)
        assertEquals(0, port.detailCompletions)
    }

    @Test
    fun currentLeaseAllowsExactlyTheRequestedAdapterOperations() {
        val port = FakePagePort()
        val executor = ReceivedGreetingsTaskExecutor(port) { true }

        assertEquals(ReceivedGreetingsTaskExecutor.StartResult.STARTED, executor.beginNavigationTask())
        assertTrue(executor.performIdleStep())
        assertEquals(ReceivedGreetingsTaskExecutor.StartResult.STARTED, executor.beginFromConfirmedList())
        assertTrue(executor.returnForPriorityCheck())
        assertTrue(executor.completeDetailInspection(success = false))
        assertEquals(1, port.navigationStarts)
        assertEquals(1, port.idleSteps)
        assertEquals(1, port.currentListStarts)
        assertEquals(1, port.priorityReturns)
        assertEquals(false, port.lastDetailSuccess)
    }

    @Test
    fun pageProgressDecisionsSeparateDetailWaitFromSafeTraversalActions() {
        val port = FakePagePort()
        val executor = ReceivedGreetingsTaskExecutor(port) { false }

        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
        assertEquals(
            ReceivedGreetingsTaskExecutor.PageDecision.ENTRY_NOT_FOUND,
            executor.evaluatePageProgress(ChatPageKind.CHAT_LIST).decision
        )
        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        assertEquals(
            ReceivedGreetingsTaskExecutor.PageDecision.COMPLETE,
            executor.evaluatePageProgress(ChatPageKind.NON_CHAT).decision
        )
        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.DETAIL_READY
        assertEquals(
            ReceivedGreetingsTaskExecutor.PageDecision.WAIT_FOR_DETAIL_PAGE,
            executor.evaluatePageProgress(ChatPageKind.CHAT_LIST).decision
        )
        assertEquals(
            ReceivedGreetingsTaskExecutor.PageDecision.DETAIL_READY,
            executor.evaluatePageProgress(ChatPageKind.CHAT_DETAIL_CANDIDATE).decision
        )
        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.PROFILE_CARD
        assertEquals(
            ReceivedGreetingsTaskExecutor.PageDecision.ADVANCE_REQUIRED,
            executor.evaluatePageProgress(ChatPageKind.NON_CHAT).decision
        )
    }

    @Test
    fun boundedIdleStepReturnsOneCapturedTraversalDecision() {
        val port = FakePagePort()
        val executor = ReceivedGreetingsTaskExecutor(port) { true }

        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.SCANNING
        var observation = executor.performIdleStepAndInspect()
        assertTrue(observation.accepted)
        assertEquals(ReceivedGreetingsTaskExecutor.StepDecision.LIST_CONTINUES, observation.decision)
        assertEquals(1, port.statusReads)

        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.COMPLETE
        observation = executor.performIdleStepAndInspect()
        assertEquals(ReceivedGreetingsTaskExecutor.StepDecision.COMPLETE, observation.decision)

        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.PROFILE_CARD
        observation = executor.performIdleStepAndInspect()
        assertEquals(ReceivedGreetingsTaskExecutor.StepDecision.PROFILE_CARD_PENDING, observation.decision)

        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
        observation = executor.performIdleStepAndInspect()
        assertEquals(ReceivedGreetingsTaskExecutor.StepDecision.ENTRY_NOT_FOUND_RECHECK, observation.decision)

        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.ENTRY_NOT_FOUND
        port.stepAccepted = false
        observation = executor.performIdleStepAndInspect()
        assertEquals(ReceivedGreetingsTaskExecutor.StepDecision.RETRY_REQUIRED, observation.decision)

        port.traversalStatusValue = ReceivedGreetingsTraversalStatus.UNKNOWN
        observation = executor.performIdleStepAndInspect()
        assertEquals(ReceivedGreetingsTaskExecutor.StepDecision.RETRY_REQUIRED, observation.decision)

        val staleExecutor = ReceivedGreetingsTaskExecutor(port) { false }
        val staleObservation = staleExecutor.performIdleStepAndInspect()
        assertFalse(staleObservation.accepted)
        assertEquals(ReceivedGreetingsTaskExecutor.StepDecision.RETRY_REQUIRED, staleObservation.decision)
    }

    @Test
    fun readOnlyStatusAndManualPageObservationRemainAvailableWithoutUiLease() {
        val port = FakePagePort()
        val executor = ReceivedGreetingsTaskExecutor(port) { false }

        assertTrue(executor.supportsWorkflow())
        assertEquals(
            ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED,
            executor.observeManuallyOpenedPage()
        )
        assertTrue(executor.observePageReadOnly())
        assertEquals(ReceivedGreetingsTraversalStatus.SCANNING, executor.traversalStatus())
        assertEquals(1, port.manualObservations)
        assertEquals(1, port.readOnlyObservations)
    }

    private class FakePagePort : ChatPagePort {
        var navigationStarts = 0
        var idleSteps = 0
        var currentListStarts = 0
        var priorityReturns = 0
        var detailCompletions = 0
        var manualObservations = 0
        var readOnlyObservations = 0
        var lastDetailSuccess: Boolean? = null
        var traversalStatusValue: ReceivedGreetingsTraversalStatus = ReceivedGreetingsTraversalStatus.SCANNING
        var stepAccepted: Boolean = true
        var statusReads: Int = 0

        override fun supportsTargetPackage(packageName: String): Boolean = true
        override fun inspectChatPage(): ChatPageSnapshot = error("not used")
        override fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot = error("not used")
        override fun supportsReceivedGreetingsIdleWorkflow(): Boolean = true
        override fun beginReceivedGreetingsNavigationTask() { navigationStarts++ }
        override fun performReceivedGreetingsIdleStep(): Boolean { idleSteps++; return stepAccepted }
        override fun beginReceivedGreetingsTaskFromCurrentList(): Boolean { currentListStarts++; return true }
        override fun returnFromReceivedGreetingsForPriorityCheck(): Boolean { priorityReturns++; return true }
        override fun completeReceivedGreetingDetailInspection(success: Boolean): Boolean {
            detailCompletions++
            lastDetailSuccess = success
            return true
        }
        override fun observeManuallyOpenedReceivedGreetingsPage(): ReceivedGreetingsPageArrivalStatus {
            manualObservations++
            return ReceivedGreetingsPageArrivalStatus.LIST_CONFIRMED
        }
        override fun observeReceivedGreetingsPage(): Boolean { readOnlyObservations++; return true }
        override fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus {
            statusReads++
            return traversalStatusValue
        }
    }
}
