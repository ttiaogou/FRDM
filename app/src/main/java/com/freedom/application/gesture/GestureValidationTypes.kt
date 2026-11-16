package com.freedom.application.gesture

import com.freedom.core.accessibility.gesture.GestureCommand
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.api.GestureSubmission

/**
 * Runtime-only request token. Its constructor is private and the issuance hook is internal to
 * :gesture-runtime; production issuance is owned by GesturePolicyValidator after raw input checks.
 */
internal class ValidatedGestureRequest private constructor(
    val request: GestureIntentRequest
) {
    internal companion object {
        /** Call only after GesturePolicyValidator.validateRequestFields has succeeded. */
        internal fun issueAfterValidation(request: GestureIntentRequest): ValidatedGestureRequest =
            ValidatedGestureRequest(request)
    }
}

internal sealed interface GestureRequestValidationResult {
    data class Accepted(val request: ValidatedGestureRequest) : GestureRequestValidationResult
    data class Rejected(val reason: String) : GestureRequestValidationResult
}

/**
 * Runtime-only capability proving that one immutable compiled command passed geometry policy.
 * The command itself is immutable and cannot be constructed/copied from outside :gesture-runtime.
 */
internal class ApprovedGestureCommand private constructor(
    val command: GestureCommand
) {
    internal companion object {
        /** Production call site is restricted to GesturePolicyValidator.validateAndApprove. */
        internal fun issueAfterValidation(command: GestureCommand): ApprovedGestureCommand =
            ApprovedGestureCommand(command)
    }
}

internal sealed interface GestureCommandValidationResult {
    data class Approved(val command: ApprovedGestureCommand) : GestureCommandValidationResult
    data class Rejected(val reason: String) : GestureCommandValidationResult
}

/** Runtime SPI: only an approved capability can be passed to the serial coordinator. */
internal interface GestureExecutor {
    fun execute(
        approvedCommand: ApprovedGestureCommand,
        listener: GestureExecutionListener
    ): GestureSubmission
}

/** Narrow interruption capability held only by Runtime orchestration. */
internal interface InterruptibleGestureExecutor : GestureExecutor {
    fun cancelActive(
        commandId: String? = null,
        reason: String = "preempted_by_user"
    ): Boolean
}
