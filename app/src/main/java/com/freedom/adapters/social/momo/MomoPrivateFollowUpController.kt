package com.freedom.adapters.social.momo

import android.os.SystemClock
import com.freedom.core.accessibility.api.AccessibilityGateway
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.runtime.TaskLease
import com.freedom.features.aireply.ports.ChatDraftInputTarget
import com.freedom.features.aireply.ports.PrivateChatFollowUpProgress

// The existing Momo adapter owns this short-lived platform navigation transaction.
// The AI runtime owns scheduling/admission; TaskCoordinator remains the only lease owner.
internal enum class PrivateFollowUpStage {
    OPEN_ALBUM, WAIT_ALBUM, WAIT_DIRECTORY, WAIT_FREEDOM, WAIT_SELECTION, WAIT_SEND_RESULT,
    IMAGE_RETRY_OPEN, IMAGE_RETRY_CONFIRM,
    INTERCEPTION_OPEN_SETTINGS, INTERCEPTION_OPEN_CONTROL, INTERCEPTION_TAP_SWITCH,
    INTERCEPTION_DISMISS, INTERCEPTION_BACK, INTERCEPTION_RETURN, TERMINAL
}

internal class PrivateFollowUpTransaction(
    val target: ChatDraftInputTarget,
    val id: String,
    val owner: () -> Boolean,
    val interception: Boolean,
    val beforeStep: (com.freedom.features.aireply.domain.contact.ContactFollowUpStep) -> Boolean
) {
    val logPrefix = if (interception) "PRIVATE_INTERCEPTION" else "PRIVATE_IMAGE_SEND"
    val image = MomoImageSendState()
    val interceptionState = MomoInterceptionState()
    @Volatile var succeeded = false
    @Volatile var stage = if (interception) PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS else PrivateFollowUpStage.OPEN_ALBUM
    @Volatile var inFlight = false
    @Volatile var stageStartedAt = SystemClock.uptimeMillis()
    var lastWaitReason: String? = null
    var waitObservationCount = 0L
}

/** Shared platform child lifecycle, not a task lease owner or an independent recovery scheduler. */
internal class MomoPrivateFollowUpController(
    private val accessibilityGateway: AccessibilityGateway,
    private val targetPackage: String,
    private val onLog: (String) -> Unit,
    private val isBoundAiReplyUiOperationOwned: (TaskLease?) -> Boolean,
    private val captureImageSendBaseline: (ChatDraftInputTarget, String, () -> Boolean) -> MomoImageSendBaseline?
) {
    @Volatile private var privateFollowUp: PrivateFollowUpTransaction? = null

    fun current(): PrivateFollowUpTransaction? = privateFollowUp

    fun progress(): PrivateChatFollowUpProgress = when (privateFollowUp?.stage) {
        null -> PrivateChatFollowUpProgress.IDLE
        PrivateFollowUpStage.TERMINAL -> PrivateChatFollowUpProgress.TERMINAL
        else -> PrivateChatFollowUpProgress.ACTIVE
    }

    fun followUpCurrent(child: PrivateFollowUpTransaction): Boolean =
        privateFollowUp === child && child.stage != PrivateFollowUpStage.TERMINAL &&
            runCatching(child.owner).getOrDefault(false)

    fun beginPrivateFollowUp(
        target: ChatDraftInputTarget,
        transactionId: String,
        interception: Boolean,
        beforeStep: (com.freedom.features.aireply.domain.contact.ContactFollowUpStep) -> Boolean,
        ownsTransaction: () -> Boolean
    ): Boolean {
        if (privateFollowUp != null || transactionId.isBlank() || target.packageName != targetPackage ||
            target.expectedChatTitle.isNullOrBlank() || target.conversationIdentityKey.isBlank() ||
            !runCatching(ownsTransaction).getOrDefault(false) ||
            !isBoundAiReplyUiOperationOwned(target.taskLease) ||
            accessibilityGateway.currentGestureWindow() != target.nodeTarget.window) return false
        val id = transactionId
        val child = PrivateFollowUpTransaction(target, id, ownsTransaction, interception, beforeStep)
        if (!interception) {
            // The normal send must have a baseline before leaving the original chat.
            child.image.baseline = captureImageSendBaseline(target, id, ownsTransaction) ?: return false
        }
        privateFollowUp = child
        onLog("${child.logPrefix} BEGIN parentLeaseInherited=true")
        return true
    }

    fun privateFollowUpSucceeded(): Boolean =
        privateFollowUp?.let { it.stage == PrivateFollowUpStage.TERMINAL && it.succeeded } == true

    fun followUpWait(child: PrivateFollowUpTransaction, reason: String) {
        if (child.lastWaitReason != reason) {
            child.lastWaitReason = reason
            child.waitObservationCount = 0L
        }
        val count = ++child.waitObservationCount
        if (count == 1L || count % 10L == 0L) {
            onLog("${child.logPrefix} WAIT stage=${child.stage};reason=$reason;observations=$count")
        }
    }

    fun followUpTerminal(child: PrivateFollowUpTransaction, result: String, reason: String) {
        if (privateFollowUp !== child || child.stage == PrivateFollowUpStage.TERMINAL) return
        val previous = child.stage
        child.succeeded = result == "COMPLETED"
        child.stage = PrivateFollowUpStage.TERMINAL
        child.inFlight = false
        onLog("${child.logPrefix} TERMINAL result=$result;stage=$previous;reason=$reason;retrySend=false")
    }

    fun abort(reason: String) {
        privateFollowUp?.let { followUpTerminal(it, "UNKNOWN", reason) }
    }

    fun reset(reason: String) {
        abort("RUNTIME_RESET")
        privateFollowUp = null
    }

    /** Persist before submission. Never replay an ambiguous album send; reconcile live results. */
    fun submitPrivateFollowUpStep(
        child: PrivateFollowUpTransaction,
        next: PrivateFollowUpStage,
        action: ((GestureExecutionStatus) -> Unit) -> GestureSubmission
    ) {
        if (!followUpCurrent(child) || child.inFlight || !isBoundAiReplyUiOperationOwned(child.target.taskLease)) {
            followUpWait(child, "PAGE_WRITE_LEASE_WAIT")
            return
        }
        val previous = child.stage
        val previousStartedAt = child.stageStartedAt
        val imageRetry = previous == PrivateFollowUpStage.IMAGE_RETRY_OPEN ||
            previous == PrivateFollowUpStage.IMAGE_RETRY_CONFIRM
        if (imageRetry) {
            child.image.imageRetryAttempt = (child.image.imageRetryAttempt + 1).coerceAtMost(6)
            child.image.imageRetryNotBefore = SystemClock.uptimeMillis() + maxOf(
                2_000L,
                com.freedom.features.recovery.RecoveryScheduler.readOnlyRetryDelayMs(child.image.imageRetryAttempt)
            )
        }
        if (child.interception) {
            child.interceptionState.interceptionRetryAttempt = (child.interceptionState.interceptionRetryAttempt + 1).coerceAtMost(6)
            child.interceptionState.interceptionRetryNotBefore = SystemClock.uptimeMillis() + maxOf(
                2_000L,
                com.freedom.features.recovery.RecoveryScheduler.readOnlyRetryDelayMs(child.interceptionState.interceptionRetryAttempt)
            )
        }
        if (!persistPrivateFollowUpStep(child, previous)) {
            if (child.interception || imageRetry) followUpWait(child, "UNKNOWN_CHECKPOINT_WRITE_RETRY")
            else followUpTerminal(child, "UNKNOWN", "CHECKPOINT_WRITE_NOT_CONFIRMED")
            return
        }
        child.stage = next
        child.stageStartedAt = SystemClock.uptimeMillis()
        child.inFlight = true
        child.lastWaitReason = null
        val submission = runCatching {
            action { status ->
                if (followUpCurrent(child)) {
                    if (child.interception) {
                        // A result is not page evidence. Wait for the settled live state,
                        // including CANCELLED/REJECTED; never roll back blindly to the tap.
                        child.interceptionState.interceptionRetryNotBefore = maxOf(
                            child.interceptionState.interceptionRetryNotBefore, SystemClock.uptimeMillis() + 2_000L
                        )
                        child.inFlight = false
                        onLog("${child.logPrefix} GESTURE_RESULT stage=$next;status=$status;reconcile=true")
                        return@action
                    }
                    if (imageRetry || (next == PrivateFollowUpStage.WAIT_SEND_RESULT && child.image.sendAttempt != null)) {
                        child.image.imageRetryNotBefore = maxOf(
                            child.image.imageRetryNotBefore, SystemClock.uptimeMillis() + 2_000L
                        )
                        child.inFlight = false
                        onLog("${child.logPrefix} GESTURE_RESULT stage=$next;status=$status;reconcile=true")
                        return@action
                    }
                    child.inFlight = false
                    onLog("${child.logPrefix} GESTURE_RESULT stage=$next;status=$status")
                    if (status == GestureExecutionStatus.DEFERRED) {
                        // No platform dispatch: keep the original stage and reacquire the target.
                        child.stage = previous
                        child.stageStartedAt = previousStartedAt
                    } else if (status == GestureExecutionStatus.REJECTED &&
                        previous == PrivateFollowUpStage.WAIT_DIRECTORY && child.image.directoryRejectedRetries == 0
                    ) {
                        // Directory navigation is not a send/toggle. Reacquire once on rejection.
                        child.image.directoryRejectedRetries++
                        child.stage = previous
                        child.stageStartedAt = previousStartedAt
                        followUpWait(child, "DIRECTORY_TAP_REJECTED_REACQUIRE")
                    } else if (status != GestureExecutionStatus.COMPLETED) {
                        // An ambiguous send/toggle is never replayed.
                        followUpTerminal(child, "UNKNOWN", "GESTURE_NOT_CONFIRMED")
                    }
                }
            }
        }.getOrDefault(GestureSubmission.REJECTED)
        onLog("${child.logPrefix} SUBMISSION from=$previous;to=$next;submission=$submission")
        // A synchronous result may have completed or rolled back the stage already.
        if (!followUpCurrent(child) || child.stage != next) return
        if (child.interception) {
            if (submission != GestureSubmission.ACCEPTED) {
                child.inFlight = false
                followUpWait(child, "UNKNOWN_GESTURE_ADMISSION_RECHECK")
            }
            return
        }
        if (imageRetry || (next == PrivateFollowUpStage.WAIT_SEND_RESULT && child.image.sendAttempt != null)) {
            if (submission != GestureSubmission.ACCEPTED) {
                child.inFlight = false
                followUpWait(child, "UNKNOWN_IMAGE_GESTURE_ADMISSION_RECHECK")
            }
            return
        }
        when (submission) {
            GestureSubmission.ACCEPTED -> Unit
            GestureSubmission.DEFERRED_NOT_READY -> {
                child.inFlight = false
                child.stage = previous
                child.stageStartedAt = previousStartedAt
                followUpWait(child, "FRESH_ADMISSION_REQUIRED")
            }
            GestureSubmission.REJECTED -> {
                // Directory navigation is not a send/toggle. A rejected submission may be
                // retried once, only after the observer reacquires the current directory.
                if (previous == PrivateFollowUpStage.WAIT_DIRECTORY &&
                    child.image.directoryRejectedRetries == 0 && followUpCurrent(child)
                ) {
                    child.image.directoryRejectedRetries++
                    child.inFlight = false
                    child.stage = previous
                    child.stageStartedAt = previousStartedAt
                    followUpWait(child, "DIRECTORY_TAP_REJECTED_REACQUIRE")
                } else followUpTerminal(child, "UNKNOWN", "GESTURE_SUBMISSION_REJECTED")
            }
            else -> followUpTerminal(child, "UNKNOWN", "GESTURE_SUBMISSION_REJECTED")
        }
    }

    private fun persistPrivateFollowUpStep(child: PrivateFollowUpTransaction, stage: PrivateFollowUpStage): Boolean {
        val callback = child.beforeStep
        val step = when (stage) {
            PrivateFollowUpStage.OPEN_ALBUM -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_OPEN_ALBUM
            PrivateFollowUpStage.WAIT_ALBUM -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_OPEN_DIRECTORY
            PrivateFollowUpStage.WAIT_DIRECTORY -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_OPEN_FREEDOM
            PrivateFollowUpStage.WAIT_FREEDOM -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_SELECT
            PrivateFollowUpStage.WAIT_SELECTION -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_SEND
            PrivateFollowUpStage.WAIT_SEND_RESULT -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_VERIFY
            PrivateFollowUpStage.IMAGE_RETRY_OPEN -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_RETRY_OPEN
            PrivateFollowUpStage.IMAGE_RETRY_CONFIRM -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.IMAGE_RETRY_CONFIRM
            PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.INTERCEPTION_OPEN_SETTINGS
            PrivateFollowUpStage.INTERCEPTION_OPEN_CONTROL -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.INTERCEPTION_OPEN_CONTROL
            PrivateFollowUpStage.INTERCEPTION_TAP_SWITCH -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.INTERCEPTION_SWITCH
            PrivateFollowUpStage.INTERCEPTION_DISMISS -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.INTERCEPTION_DISMISS
            PrivateFollowUpStage.INTERCEPTION_BACK -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.INTERCEPTION_BACK
            PrivateFollowUpStage.INTERCEPTION_RETURN -> com.freedom.features.aireply.domain.contact.ContactFollowUpStep.INTERCEPTION_RETURN
            PrivateFollowUpStage.TERMINAL -> return false
        }
        return followUpCurrent(child) && runCatching { callback(step) }.getOrDefault(false)
    }

    fun observe(
        reconcileInterception: (PrivateFollowUpTransaction) -> Boolean,
        observeImage: (PrivateFollowUpTransaction) -> Unit,
        observeInterception: (PrivateFollowUpTransaction) -> Unit
    ) {
        val child = privateFollowUp ?: return
        if (child.stage == PrivateFollowUpStage.TERMINAL) return
        if (!followUpCurrent(child)) {
            followUpTerminal(child, "UNKNOWN", "EXACT_TRANSACTION_OWNERSHIP_LOST")
            return
        }
        if (SystemClock.uptimeMillis() - child.stageStartedAt > 45_000L) {
            if (!child.interception && child.stage !in setOf(
                    PrivateFollowUpStage.WAIT_SEND_RESULT, PrivateFollowUpStage.IMAGE_RETRY_OPEN,
                    PrivateFollowUpStage.IMAGE_RETRY_CONFIRM
                )) {
                followUpTerminal(child, "UNKNOWN", "STAGE_OBSERVATION_TIMEOUT")
                return
            }
            // Keep the exact transaction/lease; observation can continue indefinitely.
            // A pending command still blocks all writes until its result is delivered.
            onLog("${child.logPrefix} UNKNOWN stage=${child.stage};reason=STAGE_OBSERVATION_TIMEOUT;retainCheckpoint=true")
            child.stageStartedAt = SystemClock.uptimeMillis()
        }
        if (child.inFlight) {
            followUpWait(child, "GESTURE_RESULT_PENDING")
            return
        }
        if (child.interception && !reconcileInterception(child)) return
        when (child.stage) {
            PrivateFollowUpStage.OPEN_ALBUM, PrivateFollowUpStage.WAIT_ALBUM,
            PrivateFollowUpStage.WAIT_DIRECTORY, PrivateFollowUpStage.WAIT_FREEDOM,
            PrivateFollowUpStage.WAIT_SELECTION, PrivateFollowUpStage.WAIT_SEND_RESULT,
            PrivateFollowUpStage.IMAGE_RETRY_OPEN, PrivateFollowUpStage.IMAGE_RETRY_CONFIRM ->
                observeImage(child)
            PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS, PrivateFollowUpStage.INTERCEPTION_OPEN_CONTROL,
            PrivateFollowUpStage.INTERCEPTION_TAP_SWITCH, PrivateFollowUpStage.INTERCEPTION_DISMISS,
            PrivateFollowUpStage.INTERCEPTION_BACK, PrivateFollowUpStage.INTERCEPTION_RETURN ->
                observeInterception(child)
            PrivateFollowUpStage.TERMINAL -> Unit
        }
    }

}
