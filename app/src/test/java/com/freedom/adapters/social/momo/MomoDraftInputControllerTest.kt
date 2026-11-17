package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.*
import com.freedom.features.aireply.ports.*
import org.junit.Assert.*
import org.junit.Test

class MomoDraftInputControllerTest {
    private class Field(var text: String = "") : MomoDraftInputController.Field {
        var focused = true
        var acceptsWrite = true
        var writes = 0
        var onFocus: () -> Unit = {}
        override fun readText() = text
        override fun focus(): Boolean { onFocus(); return focused }
        override fun setText(text: String): Boolean {
            writes++
            if (acceptsWrite) this.text = text
            return acceptsWrite
        }
    }
    // 故意使用旧窗口、无效标题元数据和不存在的路径；当前正确输入框仍应可写。
    private val target = ChatDraftInputTarget("old", "oldActivity", "", "", "",
        NodeTarget(WindowRef("old", "oldActivity", -10, -1), NodePath(listOf(99))))

    @Test fun correctLiveComposerWorksWithStaleMetadata() {
        val field = Field()
        val controller = MomoDraftInputController { operation -> operation(field) }
        assertEquals(ChatDraftInputStatus.WRITTEN, controller.writeDraft(target, "", "你好").status)
        assertEquals(ChatDraftInputStatus.VERIFIED, controller.verifyDraft(target, "你好").status)
        assertEquals("你好", field.text)
    }

    @Test fun focusReplacementUsesFreshComposer() {
        val before = Field(); val after = Field(); var current = before
        before.onFocus = { current = after }
        val controller = MomoDraftInputController { operation -> operation(current) }
        assertEquals(ChatDraftInputStatus.WRITTEN, controller.writeDraft(target, "", "新草稿").status)
        assertEquals(0, before.writes)
        assertEquals("新草稿", after.text)
    }

    @Test fun userEditDuringFocusIsPreserved() {
        val field = Field()
        field.onFocus = { field.text = "人工输入" }
        val controller = MomoDraftInputController { operation -> operation(field) }
        assertEquals(ChatDraftInputStatus.INPUT_NOT_EMPTY, controller.writeDraft(target, "", "草稿").status)
        assertEquals("人工输入", field.text)
        assertEquals(0, field.writes)
    }

    @Test fun noCorrectComposerReturnsFailureWithoutWrite() {
        val controller = MomoDraftInputController {
            ChatDraftInputResult(ChatDraftInputStatus.INPUT_NOT_FOUND, "CURRENT_WRITABLE_INPUT_NOT_FOUND")
        }
        assertEquals(ChatDraftInputStatus.INPUT_NOT_FOUND, controller.writeDraft(target, "", "草稿").status)
    }

    @Test fun rejectedWriteAndVerificationMismatchRemainFailures() {
        val field = Field(); field.acceptsWrite = false
        val controller = MomoDraftInputController { operation -> operation(field) }
        assertEquals(ChatDraftInputStatus.ACTION_REJECTED, controller.writeDraft(target, "", "草稿").status)
        assertEquals(ChatDraftInputStatus.TEXT_MISMATCH, controller.verifyDraft(target, "草稿").status)
    }
}
