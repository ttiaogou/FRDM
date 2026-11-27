package com.freedom.features.aireply.domain.contact

import org.junit.Assert.assertEquals
import org.junit.Test

/** Real ledger and repository; fixed confirmation IDs, no network or platform UI. */
class R02MediaLedgerTraceTest {
    @Test
    fun sameThresholdRequiresImageThenInterceptionAndDurableConfirmation() {
        val repo = InMemoryContactMessageLedgerRepository()
        var ledger = ContactMessageLedger(repo)
        val trace = mutableListOf<String>()
        fun record(id: String) {
            val result = ledger.recordSuccessfulSend("fixture-a", id, 2, 2)
            trace += "${result.status}:${result.count}:${ledger.pendingRecoveryAction("fixture-a")}"
        }
        record("one")
        record("one")
        record("two")
        trace += "early-interception:${ledger.confirmInterceptionEnabled("fixture-a", ledger.followUpCheckpoint("fixture-a"))}"
        ledger = ContactMessageLedger(repo)
        trace += "restart:${ledger.successfulSendCount("fixture-a")}:${ledger.pendingRecoveryAction("fixture-a")}"
        trace += "wrong-contact:${ledger.confirmImageSent("fixture-b", ledger.followUpCheckpoint("fixture-b"))}"
        trace += "image:${ledger.confirmImageSent("fixture-a", ledger.followUpCheckpoint("fixture-a"))}:${ledger.pendingRecoveryAction("fixture-a")}"
        ledger = ContactMessageLedger(repo)
        trace += "interception:${ledger.confirmInterceptionEnabled("fixture-a", ledger.followUpCheckpoint("fixture-a"))}"
        ledger = ContactMessageLedger(repo)
        trace += "final:${ledger.successfulSendCount("fixture-a")}:${ledger.pendingRecoveryAction("fixture-a")}"
        assertEquals(listOf("RECORDED:1:NONE", "DUPLICATE:1:NONE", "RECORDED:2:SEND_IMAGE",
            "early-interception:false", "restart:2:SEND_IMAGE", "wrong-contact:false",
            "image:true:ENABLE_INTERCEPTION", "interception:true", "final:2:NONE"), trace)
    }
}
