package com.freedom.features.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 regression baseline for the in-memory recovery context.
 *
 * These tests cover context proof invalidation and transaction identity. Actual fresh page reads
 * and UI-lease transfer remain integration responsibilities.
 */
class RecoveryContextTest {
    private fun requestedContext(): RecoveryContext = RecoveryContext().apply {
        request(
            taskId = "AI_REPLY_PRIVATE_MESSAGES",
            recoveryReason = "PAGE_ROUTE_CHANGED",
            routeId = "AI_REPLY_PRIVATE_MESSAGES",
            generation = 7L,
            recoveryCheckpointId = "checkpoint-7",
            pageObservationGeneration = 11L
        )
    }

    @Test
    fun newlyCreatedContextHasNoPendingRecoveryOrOperationAuthority() {
        val context = RecoveryContext()

        assertEquals(RecoveryContext.State.IDLE, context.state)
        assertEquals(RecoveryContext.PauseDomain.NONE, context.pauseDomain)
        assertFalse(context.pending)
        assertFalse(context.fingerprintVerified)
        assertFalse(context.uiOwnershipTransferred)
        assertEquals(0, context.verificationSamples)
        assertNull(context.sourceTaskId)
        assertNull(context.routeId)
    }

    @Test
    fun requestAndCheckpointTransitionsKeepAnImmutableDiagnosticSnapshot() {
        val context = requestedContext()
        assertEquals(RecoveryContext.State.REQUESTED, context.state)
        assertEquals(RecoveryContext.PauseDomain.RECOVERY_TRANSACTION, context.pauseDomain)

        context.markCheckpointSaved()
        assertEquals(RecoveryContext.State.CHECKPOINT_SAVED, context.state)
        val saved = context.snapshot()

        context.markCheckpointLoaded()
        assertEquals(RecoveryContext.State.CHECKPOINT_LOADED, context.state)
        assertTrue(context.checkpointSaved)
        assertTrue(context.checkpointLoaded)
        assertEquals(RecoveryContext.State.CHECKPOINT_SAVED, saved.state)
        assertFalse(saved.checkpointLoaded)
        assertEquals("checkpoint-7", saved.checkpointId)
        assertEquals(7L, saved.runGeneration)
        assertEquals(11L, saved.observationGeneration)
    }

    @Test
    fun equalFingerprintNeedsTwoSamplesAndChangedFingerprintRevokesVerification() {
        val context = requestedContext()
        val route = "AI_REPLY_PRIVATE_MESSAGES"

        assertFalse(context.observeTarget(route, "route|structure-a"))
        assertEquals(RecoveryContext.State.VERIFYING, context.state)
        assertEquals(1, context.verificationSamples)
        assertFalse(context.fingerprintVerified)

        assertTrue(context.observeTarget(route, "route|structure-a"))
        assertEquals(RecoveryContext.State.VERIFIED, context.state)
        assertEquals(2, context.verificationSamples)

        assertFalse(context.observeTarget(route, "route|structure-b"))
        assertEquals(RecoveryContext.State.VERIFYING, context.state)
        assertEquals(1, context.verificationSamples)
        assertFalse(context.fingerprintVerified)
        assertTrue(context.observeTarget(route, "route|structure-b"))
        assertEquals(RecoveryContext.State.VERIFIED, context.state)
    }

    @Test
    fun differentRouteAndStructuralKeyBeginANewVerificationSequence() {
        val context = requestedContext()
        assertFalse(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|structure"))
        assertFalse(context.observeTarget("AI_REPLY_RECEIVED_GREETINGS", "greetings|structure"))
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", context.routeId)
        assertEquals(1, context.verificationSamples)
        assertFalse(context.fingerprintVerified)

        assertTrue(context.observeTarget("AI_REPLY_RECEIVED_GREETINGS", "greetings|structure"))
        assertEquals(RecoveryContext.State.VERIFIED, context.state)
    }


    @Test
    fun identicalFingerprintOnDifferentRouteCannotReuseEarlierStableSamples() {
        val context = requestedContext()
        assertFalse(context.observeTarget("MESSAGE_LIST", "same-structure"))
        assertTrue(context.observeTarget("MESSAGE_LIST", "same-structure"))

        assertFalse(context.observeTarget("RECEIVED_GREETINGS", "same-structure"))
        assertEquals(1, context.verificationSamples)
        assertFalse(context.fingerprintVerified)
        assertThrows(IllegalStateException::class.java) {
            context.markUiOwnershipTransferred()
        }

        assertTrue(context.observeTarget("RECEIVED_GREETINGS", "same-structure"))
    }

    @Test
    fun invalidatedRouteProofRequiresTwoNewSamplesWithoutLosingRecoveryCheckpoint() {
        val context = requestedContext()
        context.markCheckpointSaved()
        context.markCheckpointLoaded()
        assertFalse(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|stable"))
        assertTrue(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|stable"))
        val before = context.snapshot()

        context.invalidateFingerprint()

        assertTrue(context.pending)
        assertEquals(before.taskId, context.sourceTaskId)
        assertEquals(before.checkpointId, context.checkpointId)
        assertEquals(before.runGeneration, context.runGeneration)
        assertEquals(before.observationGeneration, context.observationGeneration)
        assertTrue(context.checkpointSaved)
        assertTrue(context.checkpointLoaded)
        assertEquals(RecoveryContext.PauseDomain.RECOVERY_TRANSACTION, context.pauseDomain)
        assertEquals(RecoveryContext.State.CHECKPOINT_LOADED, context.state)
        assertEquals(0, context.verificationSamples)
        assertFalse(context.fingerprintVerified)
        assertFalse(context.uiOwnershipTransferred)
        assertThrows(IllegalStateException::class.java) {
            context.markUiOwnershipTransferred()
        }
        assertFalse(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|stable"))
        assertTrue(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|stable"))
        context.markUiOwnershipTransferred()
        assertTrue(context.uiOwnershipTransferred)
    }

    @Test
    fun invalidationAfterOneSampleAlsoPreventsCrossGapVerification() {
        val context = requestedContext()
        assertFalse(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "same-key"))
        context.invalidateFingerprint()
        context.invalidateFingerprint()

        assertEquals(0, context.verificationSamples)
        assertFalse(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "same-key"))
        assertEquals(1, context.verificationSamples)
        assertTrue(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "same-key"))
    }

    @Test
    fun ownershipTransferCannotBeMarkedBeforeFingerprintVerification() {
        val context = requestedContext()
        assertThrows(IllegalStateException::class.java) {
            context.markUiOwnershipTransferred()
        }
        assertFalse(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|structure"))
        assertThrows(IllegalStateException::class.java) {
            context.markUiOwnershipTransferred()
        }
        assertFalse(context.uiOwnershipTransferred)

        assertTrue(context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|structure"))
        context.markUiOwnershipTransferred()
        assertTrue(context.uiOwnershipTransferred)
        assertEquals(RecoveryContext.State.UI_OWNERSHIP_TRANSFERRED, context.state)
    }

    @Test
    fun verifiedCheckpointProofIsRecordedWithoutASecondSampleCounter() {
        val context = requestedContext()
        context.markCheckpointLoaded()

        context.acknowledgeVerifiedCheckpoint(
            routeId = "AI_REPLY_PRIVATE_MESSAGES",
            proof = RecoveryCheckpointVerificationResult.ResumeReady("WAITING_FOR_SESSION_LIST")
        )

        assertEquals(RecoveryContext.REQUIRED_STABLE_SAMPLES, context.verificationSamples)
        assertTrue(context.fingerprintVerified)
        assertFalse(context.uiOwnershipTransferred)
        assertEquals(RecoveryContext.State.VERIFIED, context.state)
    }

    @Test
    fun identityMatchingRejectsOlderGenerationOrDifferentTransactionWithoutMutation() {
        val context = requestedContext()
        val matching = RecoveryEventIdentity(
            taskId = "AI_REPLY_PRIVATE_MESSAGES",
            routeId = "AI_REPLY_PRIVATE_MESSAGES",
            runGeneration = 7L,
            checkpointId = "checkpoint-7",
            observationGeneration = 11L
        )
        val before = context.snapshot()

        assertTrue(context.matches(matching))
        assertTrue(context.matches(matching.copy(observationGeneration = 12L)))
        assertFalse(context.matches(matching.copy(observationGeneration = 10L)))
        assertFalse(context.matches(matching.copy(runGeneration = 6L)))
        assertFalse(context.matches(matching.copy(taskId = "OTHER_TASK")))
        assertFalse(context.matches(matching.copy(routeId = "OTHER_ROUTE")))
        assertFalse(context.matches(matching.copy(checkpointId = "OTHER_CHECKPOINT")))
        assertEquals(before, context.snapshot())
    }

    @Test
    fun startingAnotherRecoveryClearsPreviousProofAndRetainsOnlyNewIdentity() {
        val context = requestedContext()
        context.markCheckpointSaved()
        context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|structure")
        context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|structure")
        context.markUiOwnershipTransferred()

        context.request(
            taskId = "AI_REPLY_RECEIVED_GREETINGS",
            recoveryReason = "UNKNOWN_PAGE",
            routeId = "AI_REPLY_RECEIVED_GREETINGS",
            generation = 8L,
            recoveryCheckpointId = "checkpoint-8",
            pageObservationGeneration = 1L
        )

        assertEquals(RecoveryContext.State.REQUESTED, context.state)
        assertEquals("AI_REPLY_RECEIVED_GREETINGS", context.sourceTaskId)
        assertEquals("checkpoint-8", context.checkpointId)
        assertEquals(8L, context.runGeneration)
        assertEquals(0, context.verificationSamples)
        assertFalse(context.checkpointSaved)
        assertFalse(context.checkpointLoaded)
        assertFalse(context.fingerprintVerified)
        assertFalse(context.uiOwnershipTransferred)
    }

    @Test
    fun pauseDomainsDistinguishIndependentSafetyPauseFromRecoveryTransaction() {
        val context = RecoveryContext()

        context.recordPauseState(isPaused = true)
        assertEquals(RecoveryContext.PauseDomain.BUSINESS_SAFETY, context.pauseDomain)

        context.request("TASK", "UNKNOWN_PAGE")
        context.recordPauseState(isPaused = true)
        assertEquals(RecoveryContext.PauseDomain.RECOVERY_TRANSACTION, context.pauseDomain)

        context.recordPauseState(isPaused = false)
        assertEquals(RecoveryContext.PauseDomain.NONE, context.pauseDomain)
        assertTrue(context.pending)
    }

    @Test
    fun invalidRequestArgumentsDoNotReplaceExistingRecovery() {
        val context = requestedContext()
        val before = context.snapshot()

        assertThrows(IllegalArgumentException::class.java) {
            context.request("", "UNKNOWN_PAGE", "ROUTE", 8L, "checkpoint-8", 12L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.request("TASK", "UNKNOWN_PAGE", "ROUTE", -1L, "checkpoint-8", 12L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            context.request("TASK", "UNKNOWN_PAGE", "ROUTE", 8L, "checkpoint-8", -1L)
        }
        assertEquals(before, context.snapshot())
    }

    @Test
    fun clearingContextRemovesCheckpointProofAndPreviousTransactionIdentity() {
        val context = requestedContext()
        context.markCheckpointSaved()
        context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|structure")
        context.observeTarget("AI_REPLY_PRIVATE_MESSAGES", "inbox|structure")
        context.markUiOwnershipTransferred()

        context.clear()

        assertEquals(RecoveryContext.State.IDLE, context.state)
        assertEquals(RecoveryContext.PauseDomain.NONE, context.pauseDomain)
        assertFalse(context.pending)
        assertFalse(context.checkpointSaved)
        assertFalse(context.checkpointLoaded)
        assertFalse(context.fingerprintVerified)
        assertFalse(context.uiOwnershipTransferred)
        assertEquals(0, context.verificationSamples)
        assertEquals(0L, context.runGeneration)
        assertEquals(0L, context.observationGeneration)
        assertNull(context.checkpointId)
        assertNull(context.routeId)
        assertNull(context.reason)
        assertThrows(IllegalStateException::class.java) {
            context.markCheckpointSaved()
        }
    }
}
