package com.freedom.adapters.social.momo

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.freedom.adapters.accessibility.android.AndroidActiveWindowRootAccess
import com.freedom.adapters.social.momo.MomoSayHiIdentityFingerprintPolicy.normalizeIdentityDisplayName
import com.freedom.core.accessibility.api.GestureSubmission
import com.freedom.core.accessibility.api.NodeAction
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.gesture.GestureExecutionListener
import com.freedom.core.accessibility.gesture.GestureExecutionStatus
import com.freedom.core.accessibility.gesture.GestureIntent
import com.freedom.core.accessibility.gesture.GestureIntentRequest
import com.freedom.core.runtime.TaskLease
import com.freedom.features.aireply.ports.ChatDraftInputPort
import com.freedom.features.aireply.ports.ChatDraftInputResult
import com.freedom.features.aireply.ports.ChatDraftInputStatus
import com.freedom.features.aireply.ports.ChatDraftInputTarget
import com.freedom.features.aireply.ports.ChatDraftSendPort
import com.freedom.gesture.api.GestureApi

/** Android-side execution of an existing draft transaction; business confirmation remains upstream. */
internal class MomoDraftTransactionController(
    private val rootAccess: AndroidActiveWindowRootAccess,
    private val targetPackage: String,
    private val activityClass: () -> String,
    private val onLog: (String) -> Unit,
    private val gesturePolicy: GestureApi,
    private val isAiReplyUiOperationOwned: () -> Boolean,
    private val isBoundAiReplyUiOperationOwned: (TaskLease?) -> Boolean,
    private val sendTapDurationMs: Long
) : ChatDraftInputPort, ChatDraftSendPort {
    private fun draftInputController(target: ChatDraftInputTarget) = MomoDraftInputController { operation ->
        withCurrentDraftInput { node ->
            operation(object : MomoDraftInputController.Field {
                override fun readText(): String = draftInputText(node)

                // Recheck the exact window, title, task lease and run generation before EVERY
                // mutation, including after focusing rebuilds the composer node.
                override fun focus(): Boolean =
                    isCurrentDraftTarget(target) && isAiReplyUiOperationOwned() &&
                        isBoundAiReplyUiOperationOwned(target.taskLease) &&
                        rootAccess.performVerifiedChatInputAction(node, NodeAction.FOCUS, taskLease = target.taskLease)

                override fun setText(text: String): Boolean =
                    isCurrentDraftTarget(target) && isAiReplyUiOperationOwned() &&
                        isBoundAiReplyUiOperationOwned(target.taskLease) &&
                        rootAccess.performVerifiedChatInputAction(node, NodeAction.SET_TEXT, text, target.taskLease)
            })
        }
    }

    override fun writeDraft(target: ChatDraftInputTarget, expectedText: String, text: String): ChatDraftInputResult {
        if (!isCurrentDraftTarget(target)) {
            return ChatDraftInputResult(ChatDraftInputStatus.STALE_CHAT, "CHAT_CONTEXT_CHANGED")
        }
        return draftInputController(target).writeDraft(target, expectedText, text)
    }

    override fun verifyDraft(target: ChatDraftInputTarget, text: String): ChatDraftInputResult {
        if (!isCurrentDraftTarget(target)) {
            return ChatDraftInputResult(ChatDraftInputStatus.STALE_CHAT, "CHAT_CONTEXT_CHANGED")
        }
        return draftInputController(target).verifyDraft(target, text)
    }

    /** Stage-one gate: the live root must still be the window captured for this chat. */
    private fun isCurrentDraftTarget(target: ChatDraftInputTarget): Boolean {
        if (target.packageName != targetPackage || activityClass() != target.activityClass) return false
        return rootAccess.withActiveTargetRoot { root, window ->
            if (window != target.nodeTarget.window) return@withActiveTargetRoot false
            val titleNodes = root.findAccessibilityNodeInfosByViewId(MomoChatPageContract.CHAT_TITLE_VIEW_ID).orEmpty()
            try {
                val currentTitle = titleNodes.firstOrNull { it.isVisibleToUser }
                    ?.let { it.text?.toString().orEmpty().ifBlank { it.contentDescription?.toString().orEmpty() } }
                    ?.trim()
                    .orEmpty()
                val expectedTitle = target.expectedChatTitle?.trim().orEmpty()
                expectedTitle.isNotBlank() && normalizeIdentityDisplayName(currentTitle) ==
                    normalizeIdentityDisplayName(expectedTitle)
            } finally {
                titleNodes.forEach { node -> runCatching { node.recycle() } }
            }
        } ?: false
    }

    override fun sendDraft(target: ChatDraftInputTarget, expectedText: String): ChatDraftInputResult =
        sendDraft(target, expectedText) {}

    override fun sendDraft(
        target: ChatDraftInputTarget,
        expectedText: String,
        onGestureCompleted: () -> Unit
    ): ChatDraftInputResult {
        if (!isAiReplyUiOperationOwned() || !isBoundAiReplyUiOperationOwned(target.taskLease)) {
            return ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "TASK_OWNERSHIP_LOST")
        }
        if (!isCurrentDraftTarget(target)) {
            return ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "CHAT_IDENTITY_CONFLICT")
        }
        if (target.packageName != targetPackage || activityClass() != target.activityClass) {
            return ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "CHAT_CONTEXT_CHANGED")
        }
        return try {
            // Use the target application's root so an auxiliary dialog/card window does not
            // replace the chat controls used by this already-validated send operation.
            rootAccess.withActiveTargetRoot { root, _ ->
                val inputs = root.findAccessibilityNodeInfosByViewId(MomoChatPageContract.CHAT_COMPOSER_VIEW_ID).orEmpty()
                val writableInputs = inputs.filter { node ->
                    node.viewIdResourceName == MomoChatPageContract.CHAT_COMPOSER_VIEW_ID &&
                        node.isEditable && node.isEnabled && node.isVisibleToUser && !node.isPassword
                }
                if (writableInputs.size != 1) {
                    inputs.forEach { node -> runCatching { node.recycle() } }
                    return@withActiveTargetRoot ChatDraftInputResult(
                        ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "INPUT_IDENTITY_CHANGED"
                    )
                }
                val input = writableInputs.single()
                val inputText = draftInputText(input)
                if (inputText != expectedText) {
                    val state = MomoSendControlEligibility.observeComposer(
                        inputText, expectedText, input.isShowingHintText, eligibleButtonCount = 0
                    )
                    onLog("AI 输入状态观察：state=${state.name}；inputMatched=false；不覆盖当前输入")
                    inputs.forEach { node -> runCatching { node.recycle() } }
                    return@withActiveTargetRoot ChatDraftInputResult(
                        ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "DRAFT_CHANGED_BEFORE_SEND"
                    )
                }
                // Momo displays message_btn_sendtext only when the composer contains text.
                // Absence is not proof of an empty composer: the verified input above is authoritative.
                val sendCandidates = SEND_CONTROL_VIEW_ID_SUFFIXES.flatMap { suffix ->
                    root.findAccessibilityNodeInfosByViewId("$targetPackage:id/$suffix").orEmpty()
                }
                val composerParent = input.parent
                val bounds = try {
                    val sendNodes = try {
                        sendCandidates.distinctBy { node ->
                            "${node.viewIdResourceName}|${node.hashCode()}"
                        }.filter { node ->
                            val parent = if (node.viewIdResourceName == MomoSendControlEligibility.TEXT_SEND_VIEW_ID) {
                                node.parent
                            } else null
                            try {
                                val sharesEditorParent = parent != null && composerParent != null &&
                                    parent == composerParent &&
                                    parent.viewIdResourceName == MomoSendControlEligibility.EDITOR_LAYOUT_VIEW_ID
                                val label = node.text?.toString().orEmpty() + " " +
                                    node.contentDescription?.toString().orEmpty()
                                MomoSendControlEligibility.accepts(
                                    viewId = node.viewIdResourceName,
                                    className = node.className?.toString(),
                                    label = label,
                                    visible = node.isVisibleToUser,
                                    enabled = node.isEnabled,
                                    clickable = node.isClickable,
                                    sharesEditorParent = sharesEditorParent
                                )
                            } finally {
                                runCatching { parent?.recycle() }
                            }
                        }
                    } finally {
                        runCatching { composerParent?.recycle() }
                    }
                    val observedState = MomoSendControlEligibility.observeComposer(
                        inputText, expectedText, input.isShowingHintText, sendNodes.size
                    )
                    onLog("AI 输入状态观察：state=${observedState.name}；inputMatched=true；" +
                        "validSendControls=${sendNodes.size}；不记录输入正文")
                    if (sendNodes.isEmpty()) {
                        return@withActiveTargetRoot ChatDraftInputResult(
                            ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND, "SEND_CONTROL_NOT_READY"
                        )
                    }
                    if (sendNodes.size != 1) {
                        return@withActiveTargetRoot ChatDraftInputResult(
                            ChatDraftInputStatus.SEND_CONTROL_AMBIGUOUS, "SEND_CONTROL_AMBIGUOUS"
                        )
                    }
                    val rect = Rect()
                    sendNodes.single().getBoundsInScreen(rect)
                    ScreenRect(rect.left, rect.top, rect.right, rect.bottom)
                } finally {
                    inputs.forEach { node -> runCatching { node.recycle() } }
                    sendCandidates.forEach { node -> runCatching { node.recycle() } }
                }
                if (bounds.isEmpty) {
                    return@withActiveTargetRoot ChatDraftInputResult(
                        ChatDraftInputStatus.SEND_CONTROL_NOT_FOUND, "SEND_CONTROL_BOUNDS_INVALID"
                    )
                }
                val executionContext = gesturePolicy.captureContext(target.nodeTarget.window)
                    ?: return@withActiveTargetRoot ChatDraftInputResult(
                        ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "CHAT_WINDOW_NOT_CURRENT"
                    )
                if (executionContext.targetWindow != target.nodeTarget.window) {
                    return@withActiveTargetRoot ChatDraftInputResult(
                        ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "CHAT_WINDOW_GENERATION_CHANGED"
                    )
                }
                val request = GestureIntentRequest(
                    id = "momo-send-${System.nanoTime()}",
                    intent = GestureIntent.Tap(
                        preferredBounds = bounds,
                        targetBounds = bounds,
                        durationMs = sendTapDurationMs,
                        feedbackIntent = com.freedom.core.accessibility.api.GestureFeedbackIntent.STANDARD
                    ),
                    description = "Tap verified Momo send control after draft revalidation",
                    executionContext = executionContext
                )
                if (!isBoundAiReplyUiOperationOwned(target.taskLease)) {
                    return@withActiveTargetRoot ChatDraftInputResult(
                        ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "TASK_OWNERSHIP_LOST"
                    )
                }
                val submission = gesturePolicy.execute(request, GestureExecutionListener { result ->
                    onLog("AI 发送手势回调：status=${result.status}; reason=${result.reason}")
                    if (result.status == GestureExecutionStatus.COMPLETED) {
                        onGestureCompleted()
                    }
                })
                if (submission == GestureSubmission.ACCEPTED) {
                    ChatDraftInputResult(ChatDraftInputStatus.SEND_SUBMITTED)
                } else {
                    ChatDraftInputResult(ChatDraftInputStatus.SEND_GESTURE_REJECTED, submission.name)
                }
            } ?: ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "ACTIVE_WINDOW_UNAVAILABLE")
        } catch (failure: Exception) {
            onLog("AI 发送前校验异常：${failure.javaClass.simpleName}")
            ChatDraftInputResult(ChatDraftInputStatus.SEND_PRECONDITION_FAILED, "SEND_PRECONDITION_EXCEPTION")
        }
    }

    private fun draftInputText(input: AccessibilityNodeInfo): String =
        if (input.isShowingHintText) "" else input.text?.toString().orEmpty()

    /** 节点仅在本次回调中使用并释放；不依赖全树快照、旧路径或子树快照。 */
    private fun withCurrentDraftInput(
        block: (AccessibilityNodeInfo) -> ChatDraftInputResult
    ): ChatDraftInputResult {
        return try {
            rootAccess.withActiveWindowRoot { root ->
                val candidates = root.findAccessibilityNodeInfosByViewId(MomoChatPageContract.CHAT_COMPOSER_VIEW_ID).orEmpty()
                try {
                    val writable = candidates.filter { node ->
                        node.viewIdResourceName == MomoChatPageContract.CHAT_COMPOSER_VIEW_ID &&
                            node.isEditable && node.isEnabled && node.isVisibleToUser && !node.isPassword
                    }
                    when (writable.size) {
                        1 -> block(writable.single())
                        0 -> {
                            onLog("AI 草稿输入定位失败：CURRENT_WRITABLE_INPUT_NOT_FOUND；精确ID候选=${candidates.size}")
                            ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_FOUND, "CURRENT_WRITABLE_INPUT_NOT_FOUND")
                        }
                        else -> {
                            onLog("AI 草稿输入定位失败：CURRENT_INPUT_AMBIGUOUS；可写候选=${writable.size}")
                            ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_FOUND, "CURRENT_INPUT_AMBIGUOUS")
                        }
                    }
                } finally {
                    candidates.forEach { node -> runCatching { node.recycle() } }
                }
            } ?: ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_FOUND, "ACTIVE_INPUT_ROOT_UNAVAILABLE")
        } catch (failure: Exception) {
            onLog("AI 草稿输入操作异常：INPUT_NODE_ACCESS_FAILED；exception=${failure.javaClass.simpleName}")
            ChatDraftInputResult(ChatDraftInputStatus.ACTION_REJECTED, "INPUT_NODE_ACCESS_FAILED")
        }
    }


    private companion object {
        private val SEND_CONTROL_VIEW_ID_SUFFIXES = setOf(
            "send", "btn_send", "message_btn_send", "submit_message", "message_btn_sendtext"
        )
    }
}
