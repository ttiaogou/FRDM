package com.freedom.features.aireply.policy

import com.freedom.features.aireply.ai.ModelContextScope
import com.freedom.features.aireply.ai.ModelOutputContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.test.assertFailsWith

class TaskPolicyContractTest {
    private val greetings = object : TaskPolicy {
        override val taskId = "AI_REPLY_RECEIVED_GREETINGS"
        override fun evaluateTrigger(evidence: TaskTriggerEvidence): TaskTriggerDecision =
            if (evidence.taskId == taskId && evidence.pageConfirmed &&
                evidence.taskLeaseCurrent && !evidence.recoveryPending
            ) TaskTriggerDecision.ELIGIBLE else TaskTriggerDecision.DEFER

        override fun perceptionConfig() = TaskPerceptionConfig(20, 10, true)
        override fun contextPolicy() = ContextPolicy(ModelContextScope.CURRENT_WINDOW_ONLY)
        override fun modelContract() = ModelOutputContract("ai_reply_decision", """{"type":"object"}""")
        override fun recoveryDirective(failure: TaskFailure): TaskRecoveryDirective = when (failure.code) {
            TaskFailureCode.SEND_UNCONFIRMED -> TaskRecoveryDirective.VERIFY_SEND_WITHOUT_REPLAY
            TaskFailureCode.STALE_CALLBACK -> TaskRecoveryDirective.DROP_STALE_RESULT
            else -> TaskRecoveryDirective.KEEP_EXISTING_RECOVERY_TRANSACTION
        }
    }

    @Test fun unknownTaskDoesNotImplicitlyResolveToGreetings() {
        val registry = object : PolicyRegistry {
            override fun resolve(taskId: String): TaskPolicy? =
                greetings.takeIf { it.taskId == taskId }
        }
        assertEquals(greetings, registry.resolve("AI_REPLY_RECEIVED_GREETINGS"))
        assertNull(registry.resolve("AI_REPLY_PRIVATE_MESSAGES"))
        assertNull(registry.resolve("UNKNOWN_TASK"))
    }

    @Test fun policyExpressesIndependentContextAndAdvisoryRecovery() {
        assertEquals(ModelContextScope.CURRENT_WINDOW_ONLY, greetings.contextPolicy().scope)
        assertEquals(TaskTriggerDecision.DEFER, greetings.evaluateTrigger(
            TaskTriggerEvidence(greetings.taskId, pageConfirmed = true,
                taskLeaseCurrent = false, recoveryPending = false)
        ))
        assertEquals(TaskRecoveryDirective.VERIFY_SEND_WITHOUT_REPLAY,
            greetings.recoveryDirective(TaskFailure(TaskFailureCode.SEND_UNCONFIRMED)))
        assertEquals(TaskRecoveryDirective.DROP_STALE_RESULT,
            greetings.recoveryDirective(TaskFailure(TaskFailureCode.STALE_CALLBACK)))
    }

    @Test fun perceptionLimitsAndTaskIdentityRequireValidValues() {
        assertFailsWith<IllegalArgumentException> { TaskPerceptionConfig(0, 10, true) }
        assertFailsWith<IllegalArgumentException> { TaskPerceptionConfig(20, 0, true) }
        assertFailsWith<IllegalArgumentException> {
            TaskTriggerEvidence("", pageConfirmed = true,
                taskLeaseCurrent = true, recoveryPending = false)
        }
    }
}
