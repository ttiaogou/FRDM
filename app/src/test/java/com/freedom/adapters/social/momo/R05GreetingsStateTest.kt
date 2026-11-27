package com.freedom.adapters.social.momo

import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import org.junit.Assert.*
import org.junit.Test

class R05GreetingsStateTest {
    private class Repository : MomoSayHiTraversalCheckpointRepository {
        var value: MomoSayHiTraversalCheckpointRecord? = null
        override fun load(nowEpochMs: Long): MomoSayHiTraversalCheckpointRecord? = value
        override fun save(checkpoint: MomoSayHiTraversalCheckpointRecord) { value = checkpoint }
        override fun clear() { value = null }
    }

    @Test fun runReceiptCreditIsUniqueAndNeverPartOfTheDurableCheckpoint() {
        val repository = Repository()
        val state = MomoSayHiTraversalState(MomoSayHiTraversalCheckpointStore(repository))
        state.markProcessedRow("sent")
        state.deferRow("retry", "DRAFT_MODEL_FAILURE", 1_000L)
        val checkpoint = repository.value
        state.recordVerifiedReturnReceipt("historical")
        assertFalse(state.hasVerifiedReturnReceipts())
        state.recordRunSendReceipt("new-1")
        state.recordRunSendReceipt("new-1")
        state.recordRunSendReceipt("new-2")
        state.recordVerifiedReturnReceipt("new-2")
        state.recordVerifiedReturnReceipt("new-2")
        state.recordVerifiedReturnReceipt("new-1")
        assertTrue(state.hasVerifiedReturnReceipts())
        assertEquals(listOf("new-2", "new-1"), state.consumeVerifiedReturnReceipts())
        assertTrue(state.consumeVerifiedReturnReceipts().isEmpty())
        state.recordRunSendReceipt("late")
        state.clearRunReturnReceipts()
        state.recordVerifiedReturnReceipt("late")
        assertFalse(state.hasVerifiedReturnReceipts())
        assertEquals(checkpoint, repository.value)
        assertTrue(state.isProcessedRow("sent"))
        assertTrue(state.isDeferredRow("retry"))
    }

    @Test fun entryRuntimeResetDoesNotChangeNavigationPhaseOrExistingStabilityEvidence() {
        val nav = MomoSayHiNavigationFlowController()
        nav.beginEntryNavigation()
        nav.receivedGreetingsEntryTapPending = true
        nav.manualReceivedGreetingsArrivalProbeActive = true
        nav.sayHiCardListTapPending = true
        nav.receivedGreetingsEntryTapAtMs = 1_000L
        nav.sayHiCardListTapAtMs = 1_001L
        nav.lastSayHiCardListTapAtMs = 1_002L
        nav.lastReceivedGreetingsEntryTapAtMs = 1_003L
        nav.lastReceivedGreetingsEntryDiagnostic = "fixture"
        nav.lastReceivedGreetingsEntryDiagnosticAtMs = 1_004L
        nav.lastReceivedGreetingsNavigationWaitActivityClass = "fixture"
        assertFalse(nav.observeStableCardTarget("card-window", "target", 1_000L))
        nav.resetEntryNavigationRuntime()
        assertFalse(nav.receivedGreetingsEntryTapPending)
        assertFalse(nav.manualReceivedGreetingsArrivalProbeActive)
        assertFalse(nav.sayHiCardListTapPending)
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), listOf(
            nav.receivedGreetingsEntryTapAtMs, nav.sayHiCardListTapAtMs, nav.lastSayHiCardListTapAtMs,
            nav.lastReceivedGreetingsEntryTapAtMs, nav.lastReceivedGreetingsEntryDiagnosticAtMs))
        assertEquals("", nav.lastReceivedGreetingsEntryDiagnostic)
        assertEquals("", nav.lastReceivedGreetingsNavigationWaitActivityClass)
        assertEquals(MomoSayHiNavigationPhase.STABILIZING_PROFILE_CARD, nav.phase)
        assertTrue(nav.observeStableCardTarget("card-window", "target", 1_250L))
        assertTrue(nav.markCardListTapSubmitted())
        assertFalse(nav.observeStableReceivedList("list-window", "list", 2_000L))
        assertTrue(nav.observeStableReceivedList("list-window", "list", 2_250L))
    }
}
