package com.freedom.features.aireply.policy

/**
 * Shared conservative declarations, not a second task scheduler or recovery executor.
 * OCR limits mirror the existing 10-message ChatMessagePort call sites but are not yet wired
 * to the reader; that ownership remains with AiReplyObservationRuntime during P2.
 */
internal object AiReplyTaskPolicyDefaults {
    val perception = TaskPerceptionConfig(
        structuralMessageLimit = 10,
        ocrMessageLimit = 10,
        ocrEnabled = true
    )

    fun trigger(taskId: String, evidence: TaskTriggerEvidence): TaskTriggerDecision = when {
        evidence.taskId != taskId -> TaskTriggerDecision.REJECT
        !evidence.pageConfirmed || !evidence.taskLeaseCurrent || evidence.recoveryPending ->
            TaskTriggerDecision.DEFER
        else -> TaskTriggerDecision.ELIGIBLE
    }

    /** Advisory only: the existing business state owner must independently authorize actions. */
    fun recovery(failure: TaskFailure): TaskRecoveryDirective = when (failure.code) {
        TaskFailureCode.STALE_CALLBACK -> TaskRecoveryDirective.DROP_STALE_RESULT
        TaskFailureCode.SEND_UNCONFIRMED -> TaskRecoveryDirective.VERIFY_SEND_WITHOUT_REPLAY
        TaskFailureCode.PAGE_UNKNOWN -> TaskRecoveryDirective.KEEP_EXISTING_RECOVERY_TRANSACTION
        TaskFailureCode.IDENTITY_CONFLICT -> TaskRecoveryDirective.DEFER
        TaskFailureCode.MESSAGE_EVIDENCE_UNAVAILABLE,
        TaskFailureCode.MODEL_CALL_FAILED,
        TaskFailureCode.MODEL_OUTPUT_INVALID -> TaskRecoveryDirective.REOBSERVE
    }
}
