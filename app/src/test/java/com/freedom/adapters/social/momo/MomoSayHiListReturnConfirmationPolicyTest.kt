package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiListReturnConfirmationPolicyTest {
    private val policy = MomoSayHiListReturnConfirmationPolicy(
        requiredStableSamples = 2,
        minimumSampleSpacingMs = 500L
    )

    @Test
    fun confirmsOnlyAfterTwoFreshSamplesOfSameWindowGeneration() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertEquals(1, policy.stableSampleCount)

        assertTrue(policy.observe("list:window-7:generation-12", 1_750L))
        assertEquals(2, policy.stableSampleCount)
    }

    @Test
    fun windowOrGenerationChangeRestartsConfirmation() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertFalse(policy.observe("list:window-8:generation-12", 1_750L))
        assertEquals(1, policy.stableSampleCount)

        assertFalse(policy.observe("list:window-8:generation-13", 2_500L))
        assertEquals(1, policy.stableSampleCount)
        assertTrue(policy.observe("list:window-8:generation-13", 3_250L))
    }

    @Test
    fun missingEvidenceResetsConfirmation() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertFalse(policy.observe(null, 1_750L))
        assertEquals(0, policy.stableSampleCount)
        assertFalse(policy.observe("list:window-7:generation-12", 2_500L))
    }

    @Test
    fun observationsTooCloseTogetherCannotConfirmReturn() {
        assertFalse(policy.observe("list:window-7:generation-12", 1_000L))
        assertFalse(policy.observe("list:window-7:generation-12", 1_200L))
        assertEquals(1, policy.stableSampleCount)
        assertTrue(policy.observe("list:window-7:generation-12", 1_800L))
    }

    @Test
    fun durableSendAndVerifiedReturnRemainSeparateAcrossInterruptedBack() {
        val storage = object : com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnRepository {
            val saved = LinkedHashMap<String, com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnRecord>()
            override fun loadAll() = saved.values.toList()
            override fun save(
                record: com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnRecord
            ): Boolean {
                saved[record.rowKeyHash] = record
                return true
            }
        }
        val journal = com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnJournal(storage)
        assertTrue(journal.recordConfirmedSend("row-hash", "contact-hash", "send-hash"))

        val afterProcessRestart = com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnJournal(storage)
        val returnCheck = MomoSayHiListReturnConfirmationPolicy()
        assertEquals(
            com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnPhase.SEND_CONFIRMED,
            afterProcessRestart.findByRow("row-hash")?.phase
        )
        assertFalse(returnCheck.observe("list:window-7:gen-12", 1_000L))
        assertEquals(1, afterProcessRestart.pendingReturns().size)
        assertFalse(returnCheck.observe("list:window-8:gen-12", 1_800L))
        assertEquals(1, afterProcessRestart.pendingReturns().size)
        assertTrue(returnCheck.observe("list:window-8:gen-12", 2_400L))
        assertTrue(afterProcessRestart.confirmVerifiedReturn("row-hash", "contact-hash"))
        assertEquals(
            com.freedom.features.aireply.ports.ReceivedGreetingsSendReturnPhase.RETURN_VERIFIED,
            journal.findByRow("row-hash")?.phase
        )
        assertTrue(afterProcessRestart.pendingReturns().isEmpty())
        assertTrue(afterProcessRestart.hasConfirmedSendForContact("contact-hash"))
    }

}
