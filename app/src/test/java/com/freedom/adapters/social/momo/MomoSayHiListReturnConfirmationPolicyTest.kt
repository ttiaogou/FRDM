package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TargetAppReadinessController
import com.freedom.core.runtime.TargetAppReadinessEvidence
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
    fun structurallyVerifiedReturnDoesNotAuthorizeActionUntilFreshActivityAndReady() {
        val pkg = "com.immomo.momo"
        val listActivity = "com.immomo.momo.message.sayhi.activity.reflect.NewSayHiSessionListActivity"
        val unknownWindow = WindowRef(pkg, "", 24L, 3083)
        val restoredWindow = unknownWindow.copy(activityClass = listActivity)
        val listKey = requireNotNull(MomoWindowPageFingerprint.windowIdentity(
            unknownWindow, pkg, "RECEIVED_GREETINGS_LIST"
        ))
        assertEquals(listKey, MomoWindowPageFingerprint.windowIdentity(
            restoredWindow, pkg, "RECEIVED_GREETINGS_LIST"
        ))
        assertFalse(policy.observe(listKey, 1_000L))
        assertTrue(policy.observe(listKey, 1_500L))

        val readiness = TargetAppReadinessController(foregroundQuietPeriodMs = 0L)
        val evidence = TargetAppReadinessEvidence(
            targetPackage = pkg, foregroundPackage = pkg, activityClass = "",
            runMode = RunMode.AI_REPLY, windowId = 3083, pageGeneration = 24L,
            activeWindowAvailable = true, businessPageReady = true
        )
        assertFalse(readiness.evaluate(evidence, 1_500L).isReadyForWindow(unknownWindow, RunMode.AI_REPLY))
        val ready = readiness.evaluate(evidence.copy(activityClass = listActivity), 1_850L)
        assertTrue(ready.isReadyForWindow(restoredWindow, RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(unknownWindow, RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(restoredWindow.copy(generation = 25L), RunMode.AI_REPLY))
        assertFalse(ready.isReadyForWindow(restoredWindow.copy(windowId = 3097), RunMode.AI_REPLY))
        readiness.invalidate("stop", 2_000L)
        assertFalse(readiness.current().isReadyForWindow(restoredWindow, RunMode.AI_REPLY))
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
