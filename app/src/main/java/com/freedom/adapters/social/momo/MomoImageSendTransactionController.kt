package com.freedom.adapters.social.momo

import android.os.SystemClock
import com.freedom.core.accessibility.api.AccessibilityGateway
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.features.aireply.ports.ChatDraftInputTarget

/** Per-child image state; the existing parent transaction still owns the lease and lifetime. */
internal class MomoImageSendState {
    var baseline: MomoImageSendBaseline? = null
    @Volatile var sendAttempt: MomoImageSendAttempt? = null
    // The original failed-row tap must really have been dispatched by this transaction.
    @Volatile var imageRetryDialogRequested = false
    var imageRetryDialogWindowId: Int? = null
    var imageRetryAttempt = 0
    @Volatile var imageRetryNotBefore = 0L
    var albumWindow: WindowRef? = null
    var directoryRejectedRetries = 0
}

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
    @Volatile var switchVerifiedOn = false
    @Volatile var succeeded = false
    @Volatile var stage = if (interception) PrivateFollowUpStage.INTERCEPTION_OPEN_SETTINGS else PrivateFollowUpStage.OPEN_ALBUM
    @Volatile var inFlight = false
    @Volatile var stageStartedAt = SystemClock.uptimeMillis()
    var interceptionSettingsObserved = false
    var interceptionLastAction: MomoInterceptionRecoveryPolicy.Action? = null
    var interceptionRetryAttempt = 0
    @Volatile var interceptionRetryNotBefore = 0L
    var lastWaitReason: String? = null
    var waitObservationCount = 0L
}

/** Image-only observation and verification; all writes still pass the shared checkpoint/tap gates. */
internal class MomoImageSendTransactionController(
    private val accessibilityGateway: AccessibilityGateway,
    private val targetPackage: String,
    private val onLog: (String) -> Unit,
    private val followUpCurrent: (PrivateFollowUpTransaction) -> Boolean,
    private val originalPrivateFollowUpChat: (PrivateFollowUpTransaction) -> Boolean,
    private val followUpWait: (PrivateFollowUpTransaction, String) -> Unit,
    private val followUpTerminal: (PrivateFollowUpTransaction, String, String) -> Unit,
    private val submitPrivateFollowUpStep: (PrivateFollowUpTransaction, PrivateFollowUpStage, ((GestureExecutionStatus) -> Unit) -> GestureSubmission) -> Unit,
    private val openImageAlbum: (ChatDraftInputTarget, String, () -> Boolean, (GestureExecutionStatus) -> Unit) -> GestureSubmission,
    private val openImageAlbumDirectory: (ChatDraftInputTarget, WindowRef, String, () -> Boolean, (GestureExecutionStatus) -> Unit) -> GestureSubmission,
    private val inspectFreedomDirectory: (WindowRef) -> MomoFreedomDirectoryTarget?,
    private val openFreedomAlbum: (ChatDraftInputTarget, WindowRef, String, () -> Boolean, (GestureExecutionStatus) -> Unit) -> GestureSubmission,
    private val selectFreedomImage: (ChatDraftInputTarget, WindowRef, String, () -> Boolean, (GestureExecutionStatus) -> Unit) -> GestureSubmission,
    private val sendFreedomImage: (ChatDraftInputTarget, WindowRef, String, () -> Boolean, MomoImageSendBaseline?, (MomoImageSendAttempt) -> Unit, (GestureExecutionStatus) -> Unit) -> GestureSubmission,
    private val freshMediaSnapshot: (WindowRef?) -> AccessibilityTreeSnapshot?,
    private val submitImageRetryOpen: (PrivateFollowUpTransaction, WindowRef) -> Unit,
    private val submitImageRetryConfirm: (PrivateFollowUpTransaction, WindowRef) -> Unit,
    private val inspectImageSendResult: (MomoImageSendAttempt, ChatDraftInputTarget, String, () -> Boolean, (MomoImageSendResult) -> Unit) -> Unit
) {
    /** Same append proof as initial send; never search globally for historical failure icons. */
    fun imageRetryProof(child: PrivateFollowUpTransaction, snapshot: AccessibilityTreeSnapshot): MomoImageSendProof? {
        val attempt = child.image.sendAttempt ?: return null
        val baseline = attempt.baseline ?: return null
        if (!attempt.commandId.startsWith("momo-album-send-image-") ||
            !baseline.belongsTo(child.target, child.id) || !followUpCurrent(child)) return null
        return MomoImageSendEvidenceInspector.newImage(snapshot, baseline, child.target)
    }

    private fun verifyPrivateImageSend(child: PrivateFollowUpTransaction) {
        val attempt = child.image.sendAttempt ?: run {
            followUpWait(child, "UNKNOWN_SEND_DISPATCH_EVIDENCE_MISSING")
            return
        }
        val snapshot = freshMediaSnapshot(null) ?: run {
            followUpWait(child, "UNKNOWN_IMAGE_PAGE_NOT_CONFIRMED")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (MomoImageSendEvidenceInspector.retryConfirmation(snapshot) != null) {
            if (!child.image.imageRetryDialogRequested ||
                (child.image.imageRetryDialogWindowId != null && child.image.imageRetryDialogWindowId != snapshot.window.windowId)) {
                followUpWait(child, "UNKNOWN_UNOWNED_RETRY_DIALOG")
                return
            }
            child.image.imageRetryDialogWindowId = snapshot.window.windowId
            if (now < child.image.imageRetryNotBefore) {
                followUpWait(child, "IMAGE_RETRY_BACKOFF")
                return
            }
            submitImageRetryConfirm(child, snapshot.window)
            return
        }
        val proof = imageRetryProof(child, snapshot)
        if (proof == null) {
            followUpWait(child, "UNKNOWN_IMAGE_RESULT_NOT_CONFIRMED")
            return
        }
        // The exact original append is visible again: any prior modal authorization is retired.
        // A new modal requires a new actual failure-control dispatch.
        child.image.imageRetryDialogRequested = false
        child.image.imageRetryDialogWindowId = null
        if (proof.nodeResult == MomoImageSendResult.FAILED) {
            if (MomoImageSendEvidenceInspector.networkDisconnected(snapshot)) {
                followUpWait(child, "IMAGE_NETWORK_DISCONNECTED")
                return
            }
            if (now < child.image.imageRetryNotBefore) {
                followUpWait(child, "IMAGE_RETRY_BACKOFF")
                return
            }
            submitImageRetryOpen(child, snapshot.window)
            return
        }
        if (proof.nodeResult == MomoImageSendResult.SENDING) {
            followUpWait(child, "IMAGE_SENDING")
            return
        }
        child.inFlight = true
        inspectImageSendResult(attempt, child.target, child.id, { followUpCurrent(child) }) { result ->
            if (followUpCurrent(child)) {
                child.inFlight = false
                when (result) {
                    MomoImageSendResult.SUCCESS -> followUpTerminal(child, "COMPLETED", "OUTGOING_IMAGE_CONFIRMED")
                    MomoImageSendResult.SENDING -> followUpWait(child, "IMAGE_SENDING")
                    MomoImageSendResult.FAILED,
                    MomoImageSendResult.UNKNOWN -> followUpWait(child, "UNKNOWN_IMAGE_RESULT_NOT_CONFIRMED")
                }
            }
        }
    }

    fun observe(child: PrivateFollowUpTransaction) {
        val owns = { followUpCurrent(child) }
        when (child.stage) {
            PrivateFollowUpStage.OPEN_ALBUM -> {
                if (!originalPrivateFollowUpChat(child)) {
                    followUpWait(child, "ORIGINAL_CHAT_NOT_CURRENT")
                    return
                }
                submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_ALBUM) { callback ->
                    openImageAlbum(child.target, child.id, owns, callback)
                }
            }
            PrivateFollowUpStage.WAIT_ALBUM -> {
                val window = accessibilityGateway.currentGestureWindow()
                if (window == null || window.packageName != targetPackage ||
                    window.generation < child.target.nodeTarget.window.generation) {
                    followUpWait(child, "ALBUM_SELECTOR_WINDOW_UNAVAILABLE")
                    return
                }
                child.image.albumWindow = window
                submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_DIRECTORY) { callback ->
                    openImageAlbumDirectory(child.target, window, child.id, owns, callback)
                }
            }
            PrivateFollowUpStage.WAIT_DIRECTORY -> {
                val window = child.image.albumWindow ?: return
                val directory = inspectFreedomDirectory(window)
                if (directory == null) {
                    followUpWait(child, "FREEDOM_NAME_NOT_FOUND_IN_CURRENT_CHILD_WINDOW")
                    return
                }
                onLog("PRIVATE_IMAGE_SEND DIRECTORY_NAME_FOUND windowId=${directory.window.windowId};" +
                    "generation=${directory.window.generation};bounds=${directory.nameBounds};activityRequired=false;name=Freedom")
                submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_FREEDOM) { callback ->
                    openFreedomAlbum(child.target, window, child.id, owns, callback)
                }
            }
            PrivateFollowUpStage.WAIT_FREEDOM -> {
                val window = accessibilityGateway.currentGestureWindow() ?: return
                if (window.packageName != targetPackage) return
                child.image.albumWindow = window
                submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_SELECTION) { callback ->
                    selectFreedomImage(child.target, window, child.id, owns, callback)
                }
            }
            PrivateFollowUpStage.WAIT_SELECTION -> {
                val selectedWindow = child.image.albumWindow ?: return
                val window = accessibilityGateway.currentGestureWindow() ?: return
                if (window.packageName != selectedWindow.packageName || window.windowId != selectedWindow.windowId ||
                    window.generation < selectedWindow.generation) {
                    followUpWait(child, "SELECTED_ALBUM_WINDOW_NOT_CURRENT")
                    return
                }
                submitPrivateFollowUpStep(child, PrivateFollowUpStage.WAIT_SEND_RESULT) { callback ->
                    sendFreedomImage(child.target, window, child.id, owns, child.image.baseline,
                        { attempt ->
                            if (followUpCurrent(child)) {
                                child.image.sendAttempt = attempt
                                onLog("${child.logPrefix} SEND_TAP_DISPATCHED")
                            }
                        },
                        callback)
                }
            }
            PrivateFollowUpStage.WAIT_SEND_RESULT,
            PrivateFollowUpStage.IMAGE_RETRY_OPEN,
            PrivateFollowUpStage.IMAGE_RETRY_CONFIRM -> verifyPrivateImageSend(child)
            else -> Unit // Non-image stages remain with the parent transaction.
        }
    }
}
