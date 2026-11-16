package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoSayHiRowClickHitPolicyTest {
    private val safeArea = ScreenRect(180, 720, 620, 780)

    @Test
    fun clickableRowContainerContainingSafeAreaIsAccepted() {
        val result = MomoSayHiRowClickHitPolicy.evaluate(
            safeArea,
            "com.immomo.momo:id/item_layout",
            null,
            ScreenRect(0, 700, 720, 810),
            true
        )
        assertTrue(result is MomoSayHiRowClickHitResult.Accepted)
    }

    @Test
    fun avatarHitInsideRowCandidateIsRecognizedForProfileReturn() {
        val avatarBounds = ScreenRect(340, 720, 460, 780)
        val result = MomoSayHiRowClickHitPolicy.evaluate(
            safeArea,
            "com.immomo.momo:id/userlist_item_iv_face",
            null,
            avatarBounds,
            true
        )
        assertTrue(result is MomoSayHiRowClickHitResult.Accepted)
        assertEquals(true, (result as MomoSayHiRowClickHitResult.Accepted).avatarHit)
    }

    @Test
    fun avatarOutsideRowCandidateIsRejected() {
        val result = MomoSayHiRowClickHitPolicy.evaluate(
            safeArea,
            "com.immomo.momo:id/userlist_item_iv_face",
            null,
            ScreenRect(20, 710, 130, 800),
            true
        )
        assertEquals(
            "AVATAR_SOURCE_OUTSIDE_ROW_CANDIDATE",
            (result as MomoSayHiRowClickHitResult.Rejected).reason
        )
    }

    @Test
    fun vipAndWealthLevelHitsAreRejected() {
        listOf("vip_badge", "svip_tag", "wealth_level_icon").forEach { id ->
            val result = MomoSayHiRowClickHitPolicy.evaluate(
                safeArea, "com.immomo.momo:id/$id", null,
                ScreenRect(0, 700, 720, 810), true
            )
            assertEquals(
                "INDEPENDENT_ROW_CONTROL",
                (result as MomoSayHiRowClickHitResult.Rejected).reason
            )
        }
    }

    @Test
    fun greenChildControlCannotStandInForRowHit() {
        val result = MomoSayHiRowClickHitPolicy.evaluate(
            safeArea, "com.immomo.momo:id/green_widget", null,
            ScreenRect(560, 720, 630, 780), true
        )
        assertEquals(
            "INDEPENDENT_ROW_CONTROL",
            (result as MomoSayHiRowClickHitResult.Rejected).reason
        )
    }

    @Test
    fun nonClickableBadgeAndStatusComponentsAreNeverAcceptedAsRowClickEvidence() {
        listOf(
            "userlist_item_layout_badgeContainer",
            "view_badge",
            "img_online_status_dot",
            "green_widget",
            "hilist_tv_red",
            "real_man_auth_icon"
        ).forEach { id ->
            val result = MomoSayHiRowClickHitPolicy.evaluate(
                safeArea,
                "com.immomo.momo:id/$id",
                null,
                ScreenRect(0, 700, 720, 810),
                true
            )
            assertEquals(
                "INDEPENDENT_ROW_CONTROL",
                (result as MomoSayHiRowClickHitResult.Rejected).reason
            )
        }
    }

    @Test
    fun incompleteOrNonClickableEventSourceIsRejected() {
        val missing = MomoSayHiRowClickHitPolicy.evaluate(safeArea, null, null, null, false)
        assertEquals("CLICK_SOURCE_UNAVAILABLE", (missing as MomoSayHiRowClickHitResult.Rejected).reason)
        val notClickable = MomoSayHiRowClickHitPolicy.evaluate(
            safeArea, "row", null, ScreenRect(0, 700, 720, 810), false
        )
        assertEquals("ROW_CLICK_SOURCE_NOT_CLICKABLE", (notClickable as MomoSayHiRowClickHitResult.Rejected).reason)
    }
}
