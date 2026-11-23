package com.freedom.features.aireply.policy

import com.freedom.features.aireply.ai.AiDecisionTaskIds
import com.freedom.features.aireply.ai.AiReplyModelContracts
import com.freedom.features.aireply.ai.ModelContextScope

/**
 * Stranger greeting: only current-window verified messages may enter the shared draft flow.
 * No history preload/lookup, send, navigation or recovery action is owned by this policy.
 */
object ReceivedGreetingsPolicy : TaskPolicy {
    override val taskId: String = AiDecisionTaskIds.RECEIVED_GREETINGS
    private val context = ContextPolicy(ModelContextScope.CURRENT_WINDOW_ONLY)

    override fun evaluateTrigger(evidence: TaskTriggerEvidence): TaskTriggerDecision =
        AiReplyTaskPolicyDefaults.trigger(taskId, evidence)

    override fun perceptionConfig(): TaskPerceptionConfig = AiReplyTaskPolicyDefaults.perception
    override fun contextPolicy(): ContextPolicy = context
    override fun modelContract() = AiReplyModelContracts.currentOutput()
    override fun recoveryDirective(failure: TaskFailure): TaskRecoveryDirective =
        AiReplyTaskPolicyDefaults.recovery(failure)
}
