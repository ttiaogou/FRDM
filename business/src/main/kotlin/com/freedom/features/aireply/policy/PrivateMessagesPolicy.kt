package com.freedom.features.aireply.policy

import com.freedom.features.aireply.ai.AiDecisionTaskIds
import com.freedom.features.aireply.ai.AiReplyModelContracts
import com.freedom.features.aireply.ai.ModelContextScope

/**
 * Private messages: verified contact history is allowed, never required.
 * If identity/history are unavailable, existing validated current-window fallback remains.
 * This policy does not resolve contacts, perform OCR or control task leases.
 */
object PrivateMessagesPolicy : TaskPolicy {
    override val taskId: String = AiDecisionTaskIds.PRIVATE_MESSAGES
    private val context = ContextPolicy(ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW)

    override fun evaluateTrigger(evidence: TaskTriggerEvidence): TaskTriggerDecision =
        AiReplyTaskPolicyDefaults.trigger(taskId, evidence)

    override fun perceptionConfig(): TaskPerceptionConfig = AiReplyTaskPolicyDefaults.perception
    override fun contextPolicy(): ContextPolicy = context
    override fun modelContract() = AiReplyModelContracts.currentOutput()
    override fun recoveryDirective(failure: TaskFailure): TaskRecoveryDirective =
        AiReplyTaskPolicyDefaults.recovery(failure)
}
