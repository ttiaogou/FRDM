package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.freedom.adapters.social.momo.MomoInterceptionRecoveryPolicy.Action
import com.freedom.adapters.social.momo.MomoInterceptionRecoveryPolicy.Page

class MomoInterceptionRecoveryPolicyTest {
    private val policy = MomoInterceptionRecoveryPolicy

    @Test fun capturedSettingsStatusIsExact() {
        assertEquals(false, policy.settingsChecked(""))
        assertEquals(true, policy.settingsChecked("已开启"))
        assertNull(policy.settingsChecked("未开启"))
        assertNull(policy.settingsChecked("正在开启"))
    }

    @Test fun alreadyEnabledSettingsSkipsPopupAndToggle() {
        val result = policy.reconcile(Page.SETTINGS_ON, false)
        assertEquals(Action.BACK, result.action)
        assertTrue(result.verifiedOn)
    }

    @Test fun offAfterRejectedOrAmbiguousTapReacquiresInsteadOfTerminating() {
        assertEquals(Action.OPEN_SETTINGS, policy.reconcile(Page.ORIGINAL_CHAT, false).action)
        assertEquals(Action.OPEN_CONTROL, policy.reconcile(Page.SETTINGS_OFF, false).action)
        val result = policy.reconcile(Page.POPUP_OFF, false)
        assertEquals(Action.ENABLE, result.action)
        assertFalse(result.verifiedOn)
    }

    @Test fun positiveSwitchAndReturnNeverToggleAgain() {
        val popup = policy.reconcile(Page.POPUP_ON, false)
        assertEquals(Action.DISMISS, popup.action)
        val settings = policy.reconcile(Page.SETTINGS_ON, popup.verifiedOn)
        assertEquals(Action.BACK, settings.action)
        assertEquals(Action.COMPLETE, policy.reconcile(Page.ORIGINAL_CHAT, settings.verifiedOn).action)
    }

    @Test fun partialSettingsAndPopupEvidenceNeverAuthorizeMutation() {
        for (page in listOf(Page.SETTINGS_IDENTITY_UNCONFIRMED, Page.SETTINGS_STATE_UNKNOWN,
            Page.POPUP_SOURCE_UNCONFIRMED, Page.POPUP_STATE_UNKNOWN)) {
            assertEquals(Action.WAIT, policy.reconcile(page, false).action)
            assertFalse(policy.reconcile(page, false).verifiedOn)
            assertEquals(Action.WAIT, policy.reconcile(page, true).action)
            assertTrue(policy.reconcile(page, true).verifiedOn)
        }
    }

    @Test fun unknownHoldsProofWithoutAuthorizingAnyGesture() {
        val waiting = policy.reconcile(Page.UNKNOWN, true)
        assertEquals(Action.WAIT, waiting.action)
        assertTrue(waiting.verifiedOn)
        assertEquals(Action.WAIT, policy.reconcile(Page.UNKNOWN, false).action)
        assertEquals(Action.ENABLE, policy.reconcile(Page.POPUP_OFF, waiting.verifiedOn).action)
        assertFalse(policy.reconcile(Page.POPUP_OFF, waiting.verifiedOn).verifiedOn)
    }
}
