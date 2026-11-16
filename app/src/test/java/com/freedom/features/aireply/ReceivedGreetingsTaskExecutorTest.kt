package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPagePort
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
        assertFalse(executor.beginFromConfirmedList())
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

        assertTrue(executor.beginNavigationTask())
        assertTrue(executor.performIdleStep())
        assertTrue(executor.beginFromConfirmedList())
        assertTrue(executor.returnForPriorityCheck())
        assertTrue(executor.completeDetailInspection(success = false))
        assertEquals(1, port.navigationStarts)
        assertEquals(1, port.idleSteps)
        assertEquals(1, port.currentListStarts)
        assertEquals(1, port.priorityReturns)
        assertEquals(false, port.lastDetailSuccess)
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

        override fun supportsTargetPackage(packageName: String): Boolean = true
        override fun inspectChatPage(): ChatPageSnapshot = error("not used")
        override fun refreshKnownChatRegions(previous: ChatPageSnapshot): ChatRegionSnapshot = error("not used")
        override fun supportsReceivedGreetingsIdleWorkflow(): Boolean = true
        override fun beginReceivedGreetingsNavigationTask() { navigationStarts++ }
        override fun performReceivedGreetingsIdleStep(): Boolean { idleSteps++; return true }
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
        override fun receivedGreetingsTraversalStatus(): ReceivedGreetingsTraversalStatus =
            ReceivedGreetingsTraversalStatus.SCANNING
    }
}
