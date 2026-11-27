package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.TaskCoordinator
import com.freedom.core.runtime.UiOperationOwnershipCoordinator
import com.freedom.features.aireply.ports.ChatDraftInputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MomoImageSendEvidenceInspectorTest {
    private val pkg = "com.immomo.momo"
    private val original = WindowRef(pkg, MomoChatPageContract.CHAT_DETAIL_ACTIVITY, 1L, 7)
    private val lease = TaskCoordinator(UiOperationOwnershipCoordinator()).tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 1L)!!
    private val target = ChatDraftInputTarget(pkg, original.activityClass, "window", "conversation", "session",
        NodeTarget(original, NodePath(listOf(1))), "与测试对话", lease)
    private val screen = ScreenRect(0, 0, 720, 1600)
    private val listBounds = ScreenRect(0, 168, 720, 1359)
    private val rowBounds = ScreenRect(0, 959, 720, 1359)
    private val contentBounds = ScreenRect(243, 1029, 625, 1359)
    private val statusBounds = ScreenRect(252, 1311, 284, 1343)

    @Test
    fun newOutgoingImageMustBeNewTailOfOriginalChat() {
        val before = snapshot(original, 1)
        val baseline = MomoImageSendEvidenceInspector.baseline(before, target, "media-1")!!
        assertNull(MomoImageSendEvidenceInspector.newImage(before, baseline, target))
        val after = snapshot(original.copy(generation = 4L), 2)
        assertNotNull(MomoImageSendEvidenceInspector.newImage(after, baseline, target))
        assertNull(MomoImageSendEvidenceInspector.newImage(snapshot(original.copy(windowId = 8), 2), baseline, target))
        assertNull(MomoImageSendEvidenceInspector.newImage(snapshot(original, 3), baseline, target))
        assertNull(MomoImageSendEvidenceInspector.newImage(after.copy(nodes = after.nodes.map {
            if (it.viewId.endsWith(":id/message_layout_avatarcontainer")) it.copy(viewId = "$pkg:id/message_iv_avatar_container") else it
        }), baseline, target))
    }

    @Test
    fun baselineOnlyNeedsOptionalCountNotComposerEmotesOrVisibleTail() {
        val before = snapshot(original, 1)
        val countOnly = before.copy(nodes = before.nodes.filter {
            it.viewId.endsWith(":id/message_chat_recycler_view")
        })
        val baseline = MomoImageSendEvidenceInspector.baseline(countOnly, target, "media-1")
        assertEquals(1, baseline?.rowCount)
        assertNull(MomoImageSendEvidenceInspector.baseline(countOnly.copy(nodes = emptyList()), target, "media-1"))
    }

    @Test
    fun orangeUnreadAndGreenReadAreSuccessOnlyWithNonClickableStatus() {
        val orange = IntArray(1024) { 0xFFFFA020.toInt() }
        val green = IntArray(1024) { 0xFF20BB60.toInt() }
        val pink = IntArray(1024) { 0xFFFF306A.toInt() }
        val grey = IntArray(1024) { 0xFFAAAAAA.toInt() }
        assertEquals(MomoImageSendResult.SUCCESS, MomoImageSendEvidenceInspector.classifyPixels(orange, false))
        assertEquals(MomoImageSendResult.SUCCESS, MomoImageSendEvidenceInspector.classifyPixels(green, false))
        assertEquals(MomoImageSendResult.UNKNOWN, MomoImageSendEvidenceInspector.classifyPixels(orange, true))
        assertEquals(MomoImageSendResult.FAILED, MomoImageSendEvidenceInspector.classifyPixels(pink, true))
        assertEquals(MomoImageSendResult.UNKNOWN, MomoImageSendEvidenceInspector.classifyPixels(pink, false))
        assertEquals(MomoImageSendResult.UNKNOWN, MomoImageSendEvidenceInspector.classifyPixels(grey, false))
    }

    @Test
    fun capturedNodeStatesDistinguishSuccessFailureAndSending() {
        val baseline = MomoImageSendEvidenceInspector.baseline(snapshot(original, 30), target, "media-1")!!
        val sent = statusSnapshot(false)
        val failed = statusSnapshot(true)
        val sending = statusSnapshot(null)
        assertEquals(MomoImageSendResult.SUCCESS, MomoImageSendEvidenceInspector.newImage(sent, baseline, target)?.nodeResult)
        assertEquals(MomoImageSendResult.FAILED, MomoImageSendEvidenceInspector.newImage(failed, baseline, target)?.nodeResult)
        assertEquals(MomoImageSendResult.SENDING, MomoImageSendEvidenceInspector.newImage(sending, baseline, target)?.nodeResult)
        assertNull(MomoImageSendEvidenceInspector.newImage(failed.copy(nodes = failed.nodes.map {
            if (it.viewId.endsWith(":id/iv_message_status")) it.copy(actions = emptyList()) else it
        }), baseline, target))
        assertNull(MomoImageSendEvidenceInspector.newImage(failed.copy(nodes = failed.nodes +
            sending.nodes.single { it.viewId.endsWith(":id/progress_text") }), baseline, target))
        assertNull(MomoImageSendEvidenceInspector.newImage(failed.copy(nodes = failed.nodes.map {
            if (it.viewId.endsWith(":id/message_chat_recycler_view"))
                it.copy(collection = AccessibilityCollectionSnapshot(32, 1, false)) else it
        }), baseline, target))
    }

    @Test
    fun retryConfirmationNeedsExactDialogAndUniqueControls() {
        val window = original.copy(windowId = 9, generation = 5L)
        fun dialogNode(id: String, cls: String, path: List<Int>, text: String? = null,
                       button: Boolean = false) = AccessibilityNodeSnapshot(
            NodePath(path), cls, id, text, null, screen, true, true,
            false, false, button, false, false, false, false, false, 0,
            pkg, window.windowId, actions = if (button) listOf(AccessibilityActionSnapshot(16)) else emptyList()
        )
        val nodes = listOf(
            dialogNode("", "android.widget.FrameLayout", emptyList()),
            dialogNode("$pkg:id/parentPanel", "androidx.appcompat.widget.LinearLayoutCompat", listOf(0)),
            dialogNode("$pkg:id/alertTitle", "android.widget.TextView", listOf(0, 0), "提示"),
            dialogNode("android:id/message", "android.widget.TextView", listOf(0, 1), "重发该消息？"),
            dialogNode("android:id/button2", "android.widget.Button", listOf(0, 2, 0), "取消", true),
            dialogNode("android:id/button1", "android.widget.Button", listOf(0, 2, 1), "确认", true)
        )
        val dialog = AccessibilityTreeSnapshot(window, nodes, nodes.size, false, textCapture = NodeTextCapture.EPHEMERAL)
        assertNotNull(MomoImageSendEvidenceInspector.retryConfirmation(dialog))
        assertNull(MomoImageSendEvidenceInspector.retryConfirmation(dialog.copy(nodes = nodes.map {
            if (it.viewId == "android:id/message") it.copy(text = "删除该消息？") else it
        })))
        assertNull(MomoImageSendEvidenceInspector.retryConfirmation(dialog.copy(nodes = nodes + nodes.last().copy(path = NodePath(listOf(0, 3))))))
        assertNull(MomoImageSendEvidenceInspector.retryConfirmation(dialog.copy(truncated = true)))
    }

    @Test
    fun offlineBannerIsSeparateFromSendResult() {
        val failed = statusSnapshot(true)
        val banner = failed.nodes.first().copy(path = NodePath(listOf(8)),
            viewId = "$pkg:id/toptip_text", className = "android.widget.TextView", text = "当前没有网络，通讯已断开")
        assertEquals(false, MomoImageSendEvidenceInspector.networkDisconnected(failed))
        assertEquals(true, MomoImageSendEvidenceInspector.networkDisconnected(failed.copy(nodes = failed.nodes + banner)))
    }

    private fun statusSnapshot(clickable: Boolean?): AccessibilityTreeSnapshot {
        val base = snapshot(original.copy(generation = 4L), 31)
        val status = base.nodes.single { it.viewId.endsWith(":id/iv_message_status") }
        val body = base.nodes.single { it.viewId.endsWith(":id/image_content") }
        val container = base.nodes.single { it.viewId.endsWith(":id/message_status_view") }
        val rows = base.nodes.filter { it != status }.map {
            if (it == container) it.copy(childCount = if (clickable == null) 0 else 2) else it
        }
        val extra = if (clickable == null) listOf(body.copy(
            path = NodePath(body.path.childIndices.dropLast(1) + 2),
            viewId = "$pkg:id/progress_text", className = "android.widget.TextView", text = "0%",
            clickable = false, bounds = ScreenRect(423, 1171, 486, 1217)
        )) else listOf(
            status.copy(path = NodePath(container.path.childIndices + 1),
                clickable = clickable, focusable = clickable,
                actions = if (clickable) listOf(AccessibilityActionSnapshot(16)) else emptyList()),
            status.copy(viewId = "$pkg:id/iv_message_status_svga", bounds = container.bounds)
        )
        return base.copy(nodes = rows + extra)
    }

    private fun snapshot(window: WindowRef, count: Int): AccessibilityTreeSnapshot {
        fun node(id: String, cls: String, path: List<Int>, bounds: ScreenRect,
                 text: String? = null, clickable: Boolean = false) = AccessibilityNodeSnapshot(
            path = NodePath(path), className = cls, viewId = "$pkg:id/$id", text = text, contentDescription = null,
            bounds = bounds, visible = true, enabled = true, checkable = false, checked = false,
            clickable = clickable, longClickable = false, scrollable = false, editable = false,
            selected = false, focused = false, childCount = 0, packageName = pkg, windowId = window.windowId
        )
        val nodes = listOf(
            node("root", "android.widget.FrameLayout", emptyList(), screen),
            node("chat_user_name", "android.widget.TextView", listOf(0), ScreenRect(100, 70, 400, 120), "与测试对话"),
            node("message_ed_msgeditor", "android.widget.EditText", listOf(1), ScreenRect(100, 1360, 400, 1420)),
            node("message_btn_openemotes", "android.widget.ImageView", listOf(2), ScreenRect(410, 1360, 450, 1420)),
            node("message_chat_recycler_view", MomoChatPageContract.MESSAGE_LIST_CLASS_NAME, listOf(3), listBounds)
                .copy(collection = AccessibilityCollectionSnapshot(count, 1, false)),
            node("message_root", "android.widget.RelativeLayout", listOf(3, 0), rowBounds)
                .copy(collectionItem = AccessibilityCollectionItemSnapshot(count - 1, 1, 0, 1, false, false)),
            node("message_layout_avatarcontainer", "android.widget.RelativeLayout", listOf(3, 0, 0), ScreenRect(616, 1029, 706, 1119)),
            node("message_content_layout", "android.widget.LinearLayout", listOf(3, 0, 1), contentBounds),
            node("image_content", "android.widget.ImageView", listOf(3, 0, 1, 0), ScreenRect(299, 1044, 611, 1344), clickable = true),
            node("message_status_view", "android.widget.FrameLayout", listOf(3, 0, 1, 1), ScreenRect(243, 1302, 293, 1352)),
            node("iv_message_status", "android.widget.ImageView", listOf(3, 0, 1, 1, 0), statusBounds)
        )
        return AccessibilityTreeSnapshot(window, nodes, nodes.size, false, textCapture = NodeTextCapture.EPHEMERAL)
    }
}
