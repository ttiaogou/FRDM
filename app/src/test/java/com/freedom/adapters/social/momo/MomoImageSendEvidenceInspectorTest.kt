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
