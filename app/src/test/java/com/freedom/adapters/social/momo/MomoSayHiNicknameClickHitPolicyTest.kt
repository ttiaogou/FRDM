package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiNicknameClickHitPolicyTest {
    private val nicknameBounds = ScreenRect(170, 710, 300, 750)

    @Test
    fun authenticationIconIsRejectedEvenWhenItsBoundsOverlapNickname() {
        val result = MomoSayHiNicknameClickHitPolicy.evaluate(
            targetBounds = nicknameBounds,
            sourceViewId = "com.immomo.momo:id/real_man_auth_icon",
            sourceContentDescription = null,
            sourceBounds = ScreenRect(200, 703, 244, 747),
            sourceClickable = true
        )
        assertTrue(result is MomoSayHiNicknameClickHitResult.Rejected)
        assertEquals(
            "INDEPENDENT_FUNCTION_CONTROL",
            (result as MomoSayHiNicknameClickHitResult.Rejected).reason
        )
    }

    @Test
    fun genericSiblingClickableControlIsRejected() {
        val result = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, "com.immomo.momo:id/row_action_icon", null,
            ScreenRect(295, 710, 340, 750), true
        )
        assertEquals(
            "SIBLING_CLICKABLE_CONTROL",
            (result as MomoSayHiNicknameClickHitResult.Rejected).reason
        )
    }

    @Test
    fun unknownClickableControlWithNicknameSizedBoundsIsRejected() {
        val result = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, "com.immomo.momo:id/unknown_action", null,
            nicknameBounds, true
        )
        assertEquals(
            "SIBLING_CLICKABLE_CONTROL",
            (result as MomoSayHiNicknameClickHitResult.Rejected).reason
        )
    }

    @Test
    fun nonClickableNicknameOrDelegatingRowAncestorCanBeAccepted() {
        val direct = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, "com.immomo.momo:id/userlist_item_tv_name", null,
            nicknameBounds, false
        )
        assertTrue(direct is MomoSayHiNicknameClickHitResult.Accepted)

        val delegated = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, "com.immomo.momo:id/user_row", null,
            ScreenRect(0, 690, 720, 790), true
        )
        assertTrue(delegated is MomoSayHiNicknameClickHitResult.Accepted)
    }

    @Test
    fun sourceOutsideNicknameIsRejected() {
        val result = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, "com.immomo.momo:id/other_action", null,
            ScreenRect(400, 710, 450, 750), true
        )
        assertEquals(
            "CLICK_SOURCE_OUTSIDE_NICKNAME_BOUNDS",
            (result as MomoSayHiNicknameClickHitResult.Rejected).reason
        )
    }

    @Test
    fun authenticationDescriptionIsRejectedWithoutResourceId() {
        val result = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, null, "实名认证",
            ScreenRect(180, 710, 260, 750), true
        )
        assertEquals(
            "INDEPENDENT_FUNCTION_CONTROL",
            (result as MomoSayHiNicknameClickHitResult.Rejected).reason
        )
    }
    @Test
    fun overlappingNonClickableUnexpectedNodeIsRejected() {
        val result = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, "com.immomo.momo:id/decorative_badge", null,
            ScreenRect(180, 710, 250, 745), false
        )
        assertEquals(
            "UNEXPECTED_CLICK_SOURCE",
            (result as MomoSayHiNicknameClickHitResult.Rejected).reason
        )
    }

    @Test
    fun onlyKnownNicknameTextNodeOrContainingClickableAncestorIsAccepted() {
        val result = MomoSayHiNicknameClickHitPolicy.evaluate(
            nicknameBounds, "com.immomo.momo:id/userlist_item_tv_name", null,
            ScreenRect(175, 715, 295, 745), false
        )
        assertTrue(result is MomoSayHiNicknameClickHitResult.Accepted)
    }

}
