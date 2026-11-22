package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSendControlEligibilityTest {
    private fun accepts(
        viewId: String? = MomoSendControlEligibility.TEXT_SEND_VIEW_ID,
        className: String? = "android.widget.Button",
        label: String = "发送",
        visible: Boolean = true,
        enabled: Boolean = true,
        clickable: Boolean = true,
        sharesEditorParent: Boolean = true
    ): Boolean = MomoSendControlEligibility.accepts(
        viewId, className, label, visible, enabled, clickable, sharesEditorParent
    )

    @Test fun composerObservationClassifiesEmptyReadyPendingAndChangedWithoutUsingButtonAsEmptyProof() {
        assertEquals(MomoComposerSendState.EMPTY_CONFIRMED,
            MomoSendControlEligibility.observeComposer("", "草稿", false, 0))
        assertEquals(MomoComposerSendState.EMPTY_CONFIRMED,
            MomoSendControlEligibility.observeComposer("请输入消息...", "草稿", true, 0))
        assertEquals(MomoComposerSendState.TEXT_PRESENT_SEND_READY,
            MomoSendControlEligibility.observeComposer("草稿", "草稿", false, 1))
        assertEquals(MomoComposerSendState.TEXT_PRESENT_BUTTON_PENDING,
            MomoSendControlEligibility.observeComposer("草稿", "草稿", false, 0))
        assertEquals(MomoComposerSendState.INPUT_CHANGED,
            MomoSendControlEligibility.observeComposer("人工修改", "草稿", false, 1))
        assertEquals(MomoComposerSendState.INPUT_STATE_UNKNOWN,
            MomoSendControlEligibility.observeComposer("草稿", "草稿", false, 2))
    }

    @Test fun exactSendButtonInCurrentComposerIsAccepted() {
        assertTrue(accepts())
        assertTrue(accepts(label = ""))
        assertTrue(accepts(label = "send"))
    }

    @Test fun sameViewIdOutsideCurrentComposerIsRejected() {
        assertFalse(accepts(sharesEditorParent = false))
        assertFalse(accepts(label = "", sharesEditorParent = false))
    }

    @Test fun wrongTypeOrNonSendLabelIsRejected() {
        assertFalse(accepts(className = "android.widget.ImageView"))
        assertFalse(accepts(label = "关注"))
    }

    @Test fun invisibleDisabledOrNonClickableNodesAreRejected() {
        assertFalse(accepts(visible = false))
        assertFalse(accepts(enabled = false))
        assertFalse(accepts(clickable = false))
    }

    @Test fun legacyButtonRequiresSendLabelAndNoUnknownUnlabeledControlIsAccepted() {
        val oldId = "com.immomo.momo:id/message_btn_send"
        assertTrue(accepts(viewId = oldId, sharesEditorParent = false))
        assertFalse(accepts(viewId = oldId, label = "", sharesEditorParent = false))
        assertFalse(accepts(viewId = "com.immomo.momo:id/topbar_button", label = "", sharesEditorParent = false))
    }
}
