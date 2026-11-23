package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.*
import com.freedom.core.runtime.*
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


    @Test fun rejectedFocusNeverCallsSetText() {
        val field = Field()
        field.focused = false
        val controller = MomoDraftInputController { operation -> operation(field) }
        assertEquals(ChatDraftInputStatus.ACTION_REJECTED, controller.writeDraft(target, "", "草稿").status)
        assertEquals("", field.text)
        assertEquals(0, field.writes)
    }

    @Test fun permissionRevokedBetweenFocusAndWriteNeverMutatesComposer() {
        val field = Field()
        var authorized = true
        val controller = MomoDraftInputController { operation ->
            operation(object : MomoDraftInputController.Field {
                override fun readText() = field.text
                override fun focus(): Boolean {
                    authorized = false
                    return true
                }
                override fun setText(text: String): Boolean {
                    if (!authorized) return false
                    field.text = text
                    return true
                }
            })
        }
        assertEquals(ChatDraftInputStatus.ACTION_REJECTED, controller.writeDraft(target, "", "草稿").status)
        assertEquals("", field.text)
    }


    @Test fun validatedGreetingOwnerCanInputWithoutPrivateComposerScope() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val privateLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 1L))
        val greetingLease = requireNotNull(tasks.transfer(privateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        val gate = UiWriteAuthorization(tasks, { 1L }, { RunMode.AI_REPLY })
        var liveChatMatches = true
        val field = Field()
        val controller = MomoDraftInputController { operation ->
            operation(object : MomoDraftInputController.Field {
                override fun readText() = field.text
                override fun focus(): Boolean = liveChatMatches &&
                    (gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, greetingLease) { true } ?: false)
                override fun setText(text: String): Boolean {
                    if (!liveChatMatches) return false
                    return gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, greetingLease) {
                        field.text = text
                        true
                    } ?: false
                }
            })
        }
        assertFalse(gate.permits(UiWriteScope.AI_COMPOSER))
        assertEquals(ChatDraftInputStatus.WRITTEN, controller.writeDraft(target, "", "你好").status)
        assertEquals("你好", field.text)
        liveChatMatches = false
        assertEquals(ChatDraftInputStatus.ACTION_REJECTED, controller.writeDraft(target, "你好", "你好呀").status)
        assertEquals("你好", field.text)
    }

    @Test fun greetingCallbackCannotReuseLeaseAfterTaskSwitchDuringFocus() {
        val tasks = TaskCoordinator(UiOperationOwnershipCoordinator())
        val privateLease = requireNotNull(tasks.tryAcquire("AI_REPLY_PRIVATE_MESSAGES", 2L))
        val greetingsLease = requireNotNull(tasks.transfer(privateLease, "AI_REPLY_RECEIVED_GREETINGS"))
        val gate = UiWriteAuthorization(tasks, { 2L }, { RunMode.AI_REPLY })
        val field = Field()
        val controller = MomoDraftInputController { operation ->
            operation(object : MomoDraftInputController.Field {
                override fun readText() = field.text
                override fun focus(): Boolean {
                    val focused = gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, greetingsLease) {
                        true
                    } ?: false
                    if (focused) tasks.transfer(greetingsLease, "AI_REPLY_PRIVATE_MESSAGES")
                    return focused
                }
                override fun setText(text: String): Boolean =
                    gate.withBoundAuthorizedWrite(UiWriteScope.VERIFIED_GREETING_COMPOSER, greetingsLease) {
                        field.text = text
                        true
                    } ?: false
            })
        }
        assertEquals(ChatDraftInputStatus.ACTION_REJECTED, controller.writeDraft(target, "", "草稿").status)
        assertEquals("", field.text)
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
