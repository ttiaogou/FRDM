package com.freedom.features.aireply.policy

import com.freedom.features.aireply.PrivateMessagesTaskController
import com.freedom.features.aireply.ReceivedGreetingsTaskController
import com.freedom.features.aireply.ai.AiReplyModelContracts
import com.freedom.features.aireply.ai.ModelContextScope
import com.freedom.features.aireply.ai.VerifiedDraftHistory
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import org.junit.Assert.*
import org.junit.Test

class AiReplyBusinessPoliciesTest {
    private val verified = ContactRowIdentity(
        "snapshot", "temporary", "contact-verified", ContactIdentityConfidence.PLATFORM_ID_VERIFIED
    )
    private val provisional = ContactRowIdentity(
        "snapshot", "temporary", null, ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL
    )
    private val missingId = ContactRowIdentity(
        "snapshot", "temporary", null, ContactIdentityConfidence.PLATFORM_ID_VERIFIED
    )
    private val candidateHistory = VerifiedDraftHistory(
        accountKey = "account", contactId = "contact-verified",
        conversationId = "momo-direct-contact-verified", messages = emptyList()
    )

    @Test fun taskIdentityAndModelContractMatchTheExistingBusinessOwners() {
        assertEquals(ReceivedGreetingsTaskController.TASK_ID, ReceivedGreetingsPolicy.taskId)
        assertEquals(PrivateMessagesTaskController.TASK_ID, PrivateMessagesPolicy.taskId)
        assertEquals(AiReplyModelContracts.currentOutput(), ReceivedGreetingsPolicy.modelContract())
        assertEquals(AiReplyModelContracts.currentOutput(), PrivateMessagesPolicy.modelContract())
    }

    @Test fun receivedGreetingsCannotUseHistoryEvenWithAPlatformVerifiedIdentity() {
        val context = ReceivedGreetingsPolicy.contextPolicy()
        assertEquals(ModelContextScope.CURRENT_WINDOW_ONLY, context.scope)
        assertNull(context.verifiedHistoryIdentity(verified))
        assertNull(context.selectHistory(candidateHistory))
        assertNull(context.selectHistory(null))
    }

    @Test fun privateMessagesOnlyPreloadForARealPlatformVerifiedContact() {
        val context = PrivateMessagesPolicy.contextPolicy()
        assertEquals(ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW, context.scope)
        assertSame(verified, context.verifiedHistoryIdentity(verified))
        assertNull(context.verifiedHistoryIdentity(provisional))
        assertNull(context.verifiedHistoryIdentity(missingId))
        assertNull(context.verifiedHistoryIdentity(null))
    }

    @Test fun privateMessagesPreserveVerifiedHistoryOrCurrentWindowFallback() {
        val context = PrivateMessagesPolicy.contextPolicy()
        assertSame(candidateHistory, context.selectHistory(candidateHistory))
        assertNull(context.selectHistory(null))
    }

    @Test fun bothStrategiesAreDeclarativeAndFailClosedOnWrongTaskOrMissingLease() {
        val policies = listOf(ReceivedGreetingsPolicy, PrivateMessagesPolicy)
        for (policy in policies) {
            assertEquals(
                TaskTriggerDecision.REJECT,
                policy.evaluateTrigger(TaskTriggerEvidence("UNKNOWN", true, true, false))
            )
            assertEquals(
                TaskTriggerDecision.DEFER,
                policy.evaluateTrigger(TaskTriggerEvidence(policy.taskId, true, false, false))
            )
            assertEquals(
                TaskTriggerDecision.DEFER,
                policy.evaluateTrigger(TaskTriggerEvidence(policy.taskId, true, true, true))
            )
            assertEquals(
                TaskTriggerDecision.ELIGIBLE,
                policy.evaluateTrigger(TaskTriggerEvidence(policy.taskId, true, true, false))
            )
            assertEquals(TaskPerceptionConfig(10, 10, true), policy.perceptionConfig())
        }
    }

    @Test fun recoveryDirectivesNeverAuthorizeBlindSendOrReuseOfStaleCallback() {
        for (policy in listOf(ReceivedGreetingsPolicy, PrivateMessagesPolicy)) {
            assertEquals(TaskRecoveryDirective.VERIFY_SEND_WITHOUT_REPLAY,
                policy.recoveryDirective(TaskFailure(TaskFailureCode.SEND_UNCONFIRMED)))
            assertEquals(TaskRecoveryDirective.DROP_STALE_RESULT,
                policy.recoveryDirective(TaskFailure(TaskFailureCode.STALE_CALLBACK)))
            assertEquals(TaskRecoveryDirective.KEEP_EXISTING_RECOVERY_TRANSACTION,
                policy.recoveryDirective(TaskFailure(TaskFailureCode.PAGE_UNKNOWN)))
        }
    }
}
