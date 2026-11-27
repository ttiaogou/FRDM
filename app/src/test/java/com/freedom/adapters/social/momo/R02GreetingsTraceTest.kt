package com.freedom.adapters.social.momo

import com.freedom.features.aireply.policy.ConversationFilterDecision
import com.freedom.features.aireply.policy.ConversationFilterEvidence
import com.freedom.features.aireply.policy.ConversationFilterReason
import com.freedom.features.aireply.policy.ConversationFilterResult
import com.freedom.features.aireply.policy.ConversationFilterSettings
import com.freedom.features.aireply.ports.MomoSayHiDeferredRowCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class R02GreetingsTraceTest {
    @Test
    fun visitFilterNeverErasesRecoveryOrConfirmedWorkAcrossReentry() {
        val repository = InMemoryRepository()
        val state = MomoSayHiTraversalState(MomoSayHiTraversalCheckpointStore(repository))
        val settings = ConversationFilterSettings(maxLastActiveMinutes = 60)
        val evidence = ConversationFilterEvidence(lastActiveMinutes = 240)
        val decision = ConversationFilterDecision(ConversationFilterResult.EXCLUDE, ConversationFilterReason.ACTIVE_TIME_EXCEEDED)
        val keys = listOf("a".repeat(64), "b".repeat(64))
        val trace = mutableListOf<String>()
        state.markProcessedRow("sent")
        val deferred = state.deferRow("recover", "DRAFT_MODEL_FAILURE", 1_000L)
        trace += "retry:${deferred.delayMs}"
        state.observeVerifiedViewport(keys)
        state.updateFilterSettings(settings)
        val checkpoint = repository.value
        trace += "filter:${state.recordListFilterDecision("excluded", evidence, decision)}:${state.isFilterSkipped("excluded", evidence)}"
        trace += "durable-unchanged:${checkpoint == repository.value}"
        state.suspendTraversal(preserveTraversal = true)
        state.updateFilterSettings(settings)
        trace += "reentry:${state.isFilterSkipped("excluded", evidence)}:${state.observeVerifiedViewport(keys)}"
        trace += "retained:${state.isProcessedRow("sent")}:${state.isDeferredRow("recover")}"
        trace += "due:${state.isDeferredRetryDue("recover", 2_499L)}:${state.isDeferredRetryDue("recover", 2_500L)}"
        assertEquals(listOf("retry:1500", "filter:true:true", "durable-unchanged:true",
            "reentry:false:CONTINUE", "retained:true:true", "due:false:true"), trace)
    }

    private class InMemoryRepository : MomoSayHiTraversalCheckpointRepository {
        var value: MomoSayHiTraversalCheckpointRecord? = null
        override fun load(nowEpochMs: Long): MomoSayHiTraversalCheckpointRecord? = value
        override fun save(checkpoint: MomoSayHiTraversalCheckpointRecord) {
            value = checkpoint
        }
        override fun clear() {
            value = null
        }
    }
}
