package com.freedom.adapters.social.momo

import android.graphics.Bitmap
import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityNodeField
import com.freedom.core.accessibility.api.AccessibilityFieldCaptureState
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTextCapture
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.runtime.TaskLease
import com.freedom.features.aireply.ports.ChatDraftInputTarget

internal enum class MomoImageSendResult { SUCCESS, FAILED, SENDING, UNKNOWN }
internal data class MomoImageSendBaseline(
    val transactionId: String,
    val originalWindow: WindowRef,
    val conversationKey: String,
    val lease: TaskLease,
    val rowCount: Int
) {
    fun belongsTo(target: ChatDraftInputTarget, id: String): Boolean =
        transactionId.isNotBlank() && transactionId == id &&
            originalWindow == target.nodeTarget.window && conversationKey == target.conversationIdentityKey &&
            lease == target.taskLease
}
/** Immutable dispatch provenance, retained only by the caller's media transaction. */
internal data class MomoImageSendAttempt(
    val baseline: MomoImageSendBaseline?,
    val commandId: String,
    val dispatchedAtUptimeMs: Long
)
internal data class MomoImageSendProof(
    val window: WindowRef,
    val rowCount: Int,
    val rootBounds: ScreenRect,
    val imageBounds: ScreenRect,
    val statusBounds: ScreenRect,
    val statusClickable: Boolean,
    val nodeResult: MomoImageSendResult = MomoImageSendResult.UNKNOWN
)

/** Positive append evidence; a returned chat or completed tap alone cannot confirm a send. */
internal object MomoImageSendEvidenceInspector {
    private const val SCROLL_FORWARD = 4096

    fun baseline(snapshot: AccessibilityTreeSnapshot, target: ChatDraftInputTarget, transactionId: String): MomoImageSendBaseline? {
        val lease = target.taskLease ?: return null
        if (transactionId.isBlank() || snapshot.window != target.nodeTarget.window ||
            target.conversationIdentityKey.isBlank()) return null
        // Best-effort evidence only. Missing title/composer/emotes or a non-visible tail
        // must not prevent collecting the count from the already admitted parent chat.
        if (snapshot.truncated || snapshot.timeBudgetExceeded || snapshot.textCapture != NodeTextCapture.EPHEMERAL)
            return null
        val list = scopedNodes(snapshot).singleOrNull {
            it.viewId == MomoChatPageContract.MESSAGE_LIST_VIEW_ID &&
                it.className == MomoChatPageContract.MESSAGE_LIST_CLASS_NAME
        } ?: return null
        val count = list.collection?.rowCount?.takeIf { it >= 0 } ?: return null
        return MomoImageSendBaseline(transactionId, snapshot.window, target.conversationIdentityKey,
            lease, count)
    }

    fun newImage(snapshot: AccessibilityTreeSnapshot, baseline: MomoImageSendBaseline, target: ChatDraftInputTarget): MomoImageSendProof? {
        if (!baseline.belongsTo(target, baseline.transactionId) ||
            snapshot.window.windowId != baseline.originalWindow.windowId ||
            snapshot.window.activityClass != baseline.originalWindow.activityClass ||
            snapshot.window.generation < baseline.originalWindow.generation) return null
        val list = confirmedTailList(snapshot, target) ?: return null
        val count = list.collection!!.rowCount
        if (count.toLong() != baseline.rowCount.toLong() + 1L) return null
        val nodes = scopedNodes(snapshot)
        val root = nodes.singleOrNull { it.path.childIndices.isEmpty() && it.usable() } ?: return null
        val row = nodes.singleOrNull {
            it.path.parent() == list.path && it.viewId == "${target.packageName}:id/message_root" &&
                it.className == "android.widget.RelativeLayout" &&
                it.collectionItem?.rowIndex == count - 1 && it.usable() && list.bounds.contains(it.bounds)
        } ?: return null
        val rowNodes = nodes.filter { it.path.isDescendantOf(row.path) }
        val sender = rowNodes.singleOrNull {
            it.viewId == "${target.packageName}:id/message_layout_avatarcontainer" &&
                it.className == "android.widget.RelativeLayout" && it.path.parent() == row.path && it.usable()
        } ?: return null
        if (rowNodes.any { it.viewId == "${target.packageName}:id/message_iv_avatar_container" } ||
            !row.bounds.contains(sender.bounds)) return null
        val content = rowNodes.singleOrNull {
            it.viewId == "${target.packageName}:id/message_content_layout" &&
                it.path.parent() == row.path && it.usable()
        } ?: return null
        val body = rowNodes.singleOrNull {
            it.viewId == "${target.packageName}:id/image_content" &&
                it.className == "android.widget.ImageView" && it.path.isDescendantOf(content.path) &&
                it.usable() && it.clickable && content.bounds.contains(it.bounds)
        } ?: return null
        val statusContainer = rowNodes.singleOrNull {
            it.viewId == "${target.packageName}:id/message_status_view" &&
                it.path.parent() == content.path && it.usable()
        } ?: return null
        if (!list.bounds.contains(body.bounds) || !root.bounds.contains(statusContainer.bounds)) return null
        val progressNodes = rowNodes.filter { it.viewId == "${target.packageName}:id/progress_text" }
        val statusNodes = rowNodes.filter { it.viewId == "${target.packageName}:id/iv_message_status" }
        val animationNodes = rowNodes.filter { it.viewId == "${target.packageName}:id/iv_message_status_svga" }
        // A progress indicator is positive evidence of an unfinished upload, never a retry target.
        if (progressNodes.isNotEmpty()) {
            val progress = progressNodes.singleOrNull()?.takeIf {
                it.className == "android.widget.TextView" && it.usable() &&
                    it.path.parent() == body.path.parent() && body.bounds.contains(it.bounds) &&
                    it.text?.trim()?.matches(Regex("(100|[0-9]{1,2})%")) == true
            } ?: return null
            if (statusNodes.isNotEmpty() || animationNodes.isNotEmpty() || statusContainer.childCount != 0 ||
                !content.bounds.contains(progress.bounds)) return null
            return MomoImageSendProof(snapshot.window, count, root.bounds, body.bounds,
                statusContainer.bounds, false, MomoImageSendResult.SENDING)
        }
        val status = statusNodes.singleOrNull()?.takeIf {
            it.className == "android.widget.ImageView" && it.path.parent() == statusContainer.path &&
                it.usable() && statusContainer.bounds.contains(it.bounds)
        } ?: return null
        if (!root.bounds.contains(status.bounds) || !list.bounds.contains(status.bounds)) return null
        val animation = animationNodes.singleOrNull()?.takeIf {
            it.className == "android.widget.ImageView" && it.path.parent() == statusContainer.path &&
                it.usable() && statusContainer.bounds.contains(it.bounds) && !it.clickable
        }
        val actionsRead = status.captureState(AccessibilityNodeField.ACTIONS, snapshot.textCapture) in
            setOf(AccessibilityFieldCaptureState.VALUE_PRESENT, AccessibilityFieldCaptureState.EMPTY)
        if (!actionsRead) return null
        val clickAction = status.actions.any { it.id == 16 }
        // Conflicting interaction flags must not be turned into a success or resend.
        if (status.clickable != clickAction || status.focusable != status.clickable) return null
        val result = if (animation != null && statusContainer.childCount == 2)
            if (status.clickable) MomoImageSendResult.FAILED else MomoImageSendResult.SUCCESS
        else MomoImageSendResult.UNKNOWN
        return MomoImageSendProof(snapshot.window, count, root.bounds, body.bounds, status.bounds, status.clickable, result)
    }

    /** Exact app-provided offline banner; not a substitute for the target row's result. */
    fun networkDisconnected(snapshot: AccessibilityTreeSnapshot): Boolean =
        scopedNodes(snapshot).any {
            it.viewId == "${snapshot.window.packageName}:id/toptip_text" &&
                it.usable() && it.text?.trim() == "当前没有网络，通讯已断开"
        }

    /** The resend confirmation is a separate active window. Generic button1 alone is never proof. */
    fun retryConfirmation(snapshot: AccessibilityTreeSnapshot): ScreenRect? {
        if (snapshot.truncated || snapshot.timeBudgetExceeded || snapshot.textCapture != NodeTextCapture.EPHEMERAL ||
            snapshot.window.packageName != "com.immomo.momo" || snapshot.window.windowId < 0) return null
        val nodes = scopedNodes(snapshot)
        val root = nodes.singleOrNull { it.path.childIndices.isEmpty() && it.usable() } ?: return null
        val panel = nodes.singleOrNull {
            it.viewId == "com.immomo.momo:id/parentPanel" &&
                it.className == "androidx.appcompat.widget.LinearLayoutCompat" && it.usable() &&
                root.bounds.contains(it.bounds)
        } ?: return null
        fun exact(id: String, cls: String, text: String, button: Boolean = false): AccessibilityNodeSnapshot? =
            nodes.singleOrNull {
                it.viewId == id && it.className == cls && it.text?.trim() == text &&
                    it.usable() && it.path.isDescendantOf(panel.path) && panel.bounds.contains(it.bounds) &&
                    (!button || (it.clickable && it.actions.any { action -> action.id == 16 }))
            }
        exact("com.immomo.momo:id/alertTitle", "android.widget.TextView", "提示") ?: return null
        exact("android:id/message", "android.widget.TextView", "重发该消息？") ?: return null
        val cancel = exact("android:id/button2", "android.widget.Button", "取消", true) ?: return null
        val confirm = exact("android:id/button1", "android.widget.Button", "确认", true) ?: return null
        if (cancel.path.parent() != confirm.path.parent()) return null
        // The captured modal contains only these four visible labels and two clickable buttons.
        // Reject extra controls or text rather than accepting another overlay.
        if (nodes.any { it.visible && it.text.orEmpty().trim().let { value ->
                value.isNotEmpty() && value !in setOf("提示", "重发该消息？", "取消", "确认")
            } } || nodes.filter { it.usable() && it.clickable }.map { it.path }.toSet() != setOf(cancel.path, confirm.path))
            return null
        return confirm.bounds
    }

    private fun confirmedTailList(snapshot: AccessibilityTreeSnapshot, target: ChatDraftInputTarget): AccessibilityNodeSnapshot? {
        if (snapshot.truncated || snapshot.timeBudgetExceeded || snapshot.textCapture != NodeTextCapture.EPHEMERAL ||
            snapshot.window.packageName != target.packageName || target.packageName != "com.immomo.momo" ||
            snapshot.window.windowId < 0 || snapshot.window.activityClass.isBlank()) return null
        val nodes = scopedNodes(snapshot)
        val title = nodes.singleOrNull {
            it.viewId == MomoChatPageContract.CHAT_TITLE_VIEW_ID && it.usable()
        } ?: return null
        val expected = target.expectedChatTitle?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val observedTitle = title.text.orEmpty().ifBlank { title.contentDescription.orEmpty() }.trim()
        if (observedTitle != expected) return null
        if (nodes.none { it.viewId == MomoChatPageContract.CHAT_COMPOSER_VIEW_ID && it.usable() } ||
            nodes.none { it.viewId == MomoChatPageContract.CHAT_EMOTES_VIEW_ID && it.usable() }) return null
        val list = nodes.singleOrNull {
            it.viewId == MomoChatPageContract.MESSAGE_LIST_VIEW_ID &&
                it.className == MomoChatPageContract.MESSAGE_LIST_CLASS_NAME && it.usable()
        } ?: return null
        val count = list.collection?.rowCount?.takeIf { it >= 0 } ?: return null
        if (list.captureState(AccessibilityNodeField.ACTIONS, snapshot.textCapture) !in
            setOf(AccessibilityFieldCaptureState.VALUE_PRESENT, AccessibilityFieldCaptureState.EMPTY) ||
            list.actions.any { it.id == SCROLL_FORWARD }) return null
        if (count == 0) {
            if (nodes.any { it.path.parent() == list.path && it.viewId == MomoChatPageContract.CHAT_MESSAGE_ROOT_VIEW_ID }) return null
        } else if (nodes.none {
            it.path.parent() == list.path && it.viewId == MomoChatPageContract.CHAT_MESSAGE_ROOT_VIEW_ID &&
                it.collectionItem?.rowIndex == count - 1 && it.usable() && list.bounds.contains(it.bounds)
        }) return null
        return list
    }

    private fun scopedNodes(snapshot: AccessibilityTreeSnapshot) = snapshot.nodes.filter {
        it.packageName == snapshot.window.packageName && it.windowId == snapshot.window.windowId
    }

    /**
     * Node flags do not expose the icon state. Require a local status crop with positive color
     * evidence; orange (unread) or green (read) plus a non-clickable icon is success.
     * A pink/red error icon plus its clickable retry control is failure; all else is unknown.
     */
    fun classifyStatus(bitmap: Bitmap, proof: MomoImageSendProof, windowCapture: Boolean): MomoImageSendResult {
        if (proof.nodeResult == MomoImageSendResult.SENDING) return MomoImageSendResult.SENDING
        val root = proof.rootBounds
        val bounds = proof.statusBounds
        val offsetX = if (windowCapture) root.left else 0
        val offsetY = if (windowCapture) root.top else 0
        if (windowCapture && (bitmap.width != root.width || bitmap.height != root.height)) return MomoImageSendResult.UNKNOWN
        val left = bounds.left - offsetX
        val top = bounds.top - offsetY
        val right = bounds.right - offsetX
        val bottom = bounds.bottom - offsetY
        if (left < 0 || top < 0 || right > bitmap.width || bottom > bitmap.height ||
            right <= left || bottom <= top || (right - left) > 160 || (bottom - top) > 160)
            return MomoImageSendResult.UNKNOWN
        val pixels = IntArray((right - left) * (bottom - top))
        bitmap.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        return classifyPixels(pixels, proof.statusClickable)
    }

    fun classifyPixels(pixels: IntArray, clickable: Boolean): MomoImageSendResult {
        if (pixels.isEmpty()) return MomoImageSendResult.UNKNOWN
        var success = 0
        var failure = 0
        var colored = 0
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max < 100 || max - min < 55) continue
            colored++
            // Strongly saturated orange and green; avoid pale background and grey spinners.
            if ((r >= 150 && g >= 70 && r >= g + 25 && g >= b + 35) ||
                (g >= 110 && g >= r + 35 && g >= b + 20)) success++
            else if (r >= 150 && r >= g + 45 && b >= g + 20) failure++
        }
        val minimum = maxOf(8, pixels.size / 20)
        if (colored < minimum) return MomoImageSendResult.UNKNOWN
        return when {
            !clickable && success >= minimum && success * 10 >= colored * 8 && failure == 0 -> MomoImageSendResult.SUCCESS
            clickable && failure >= minimum && failure * 10 >= colored * 8 && success == 0 -> MomoImageSendResult.FAILED
            else -> MomoImageSendResult.UNKNOWN
        }
    }

    private fun AccessibilityNodeSnapshot.usable(): Boolean = visible && enabled && !bounds.isEmpty
    private fun NodePath.parent(): NodePath? = childIndices.takeIf { it.isNotEmpty() }?.dropLast(1)?.let(::NodePath)
    private fun NodePath.isDescendantOf(other: NodePath): Boolean =
        childIndices.size > other.childIndices.size && childIndices.take(other.childIndices.size) == other.childIndices
    private fun ScreenRect.contains(other: ScreenRect): Boolean =
        !isEmpty && !other.isEmpty && other.left >= left && other.right <= right &&
            other.top >= top && other.bottom <= bottom
}
