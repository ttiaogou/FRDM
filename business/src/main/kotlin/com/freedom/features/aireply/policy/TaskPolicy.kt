package com.freedom.features.aireply.policy

import com.freedom.features.aireply.ai.AiModelCallDiagnostics
import com.freedom.features.aireply.ai.ModelContextScope
import com.freedom.features.aireply.ai.ModelOutputContract
import com.freedom.features.aireply.ai.VerifiedDraftHistory
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactRowIdentity

/**
 * Declarative, side-effect-free business contract.
 *
 * The existing page recognizer, task coordinator, UI lease gate, send coordinator and
 * UnifiedRecoveryTransactionManager remain authoritative. An ELIGIBLE answer or a recovery
 * directive never grants permission to click, navigate, send or replay an unknown gesture.
 */
interface TaskPolicy {
    val taskId: String

    fun evaluateTrigger(evidence: TaskTriggerEvidence): TaskTriggerDecision
    fun perceptionConfig(): TaskPerceptionConfig
    fun contextPolicy(): ContextPolicy
    fun modelContract(): ModelOutputContract
    fun recoveryDirective(failure: TaskFailure): TaskRecoveryDirective
}

/** Already-classified evidence; the policy must not read Android pages or raw OCR callbacks. */
data class TaskTriggerEvidence(
    val taskId: String,
    val pageConfirmed: Boolean,
    val taskLeaseCurrent: Boolean,
    val recoveryPending: Boolean
) {
    init { require(taskId.isNotBlank()) }
}

enum class TaskTriggerDecision { ELIGIBLE, DEFER, REJECT }

/**
 * Describes the intended perception profile without changing the existing runtime constants.
 * No implementation is registered during P0.
 */
data class TaskPerceptionConfig(
    val structuralMessageLimit: Int,
    val ocrMessageLimit: Int,
    val ocrEnabled: Boolean
) {
    init {
        require(structuralMessageLimit > 0)
        require(ocrMessageLimit > 0)
    }
}

/**
 * Declares which context a business may use. Identity admission mirrors the existing
 * private-message preload check; current-window-only strategies never read history.
 * This does not resolve contacts or inspect pages, and cannot grant a task lease.
 */
data class ContextPolicy(val scope: ModelContextScope) {
    fun verifiedHistoryIdentity(identity: ContactRowIdentity?): ContactRowIdentity? =
        if (scope == ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW) {
            identity?.takeIf {
                it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                    !it.contactId.isNullOrBlank()
            }
        } else {
            null
        }

    fun selectHistory(history: VerifiedDraftHistory?): VerifiedDraftHistory? =
        if (scope == ModelContextScope.VERIFIED_HISTORY_WITH_CURRENT_WINDOW) history else null
}

/** Safe failure taxonomy; no raw message, exception text, URL or credentials. */
enum class TaskFailureCode {
    MESSAGE_EVIDENCE_UNAVAILABLE,
    MODEL_CALL_FAILED,
    MODEL_OUTPUT_INVALID,
    PAGE_UNKNOWN,
    SEND_UNCONFIRMED,
    STALE_CALLBACK,
    IDENTITY_CONFLICT
}

data class TaskFailure(
    val code: TaskFailureCode,
    val modelDiagnostics: AiModelCallDiagnostics? = null
)

/** Advisory only. The existing state owner validates and executes any recovery. */
enum class TaskRecoveryDirective {
    REOBSERVE,
    KEEP_EXISTING_RECOVERY_TRANSACTION,
    VERIFY_SEND_WITHOUT_REPLAY,
    DROP_STALE_RESULT,
    DEFER
}
