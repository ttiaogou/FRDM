package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import java.util.Locale

/** Result of verifying that a click event came from the row container, not a row sub-control. */
internal sealed interface MomoSayHiRowClickHitResult {
    data class Accepted(
        val sourceViewId: String,
        /** True when the tap landed on the avatar; the Activity guard must handle profile return. */
        val avatarHit: Boolean = false
    ) : MomoSayHiRowClickHitResult
    data class Rejected(val reason: String, val sourceViewId: String) : MomoSayHiRowClickHitResult
}

/**
 * Fail-closed validation for a tap dispatched into the safe area of one received-greeting row.
 * The event source must be the clickable row (or an ancestor covering the row hit region).
 * VIP/SVIP, wealth-level and other independent child controls are rejected. Avatar hits are
 * separately recognized because they can open the profile page, which the adapter safely returns
 * from without attempting chat OCR.
 */
internal object MomoSayHiRowClickHitPolicy {
    fun evaluate(
        safeTapBounds: ScreenRect,
        sourceViewId: String?,
        sourceContentDescription: String?,
        sourceBounds: ScreenRect?,
        sourceClickable: Boolean
    ): MomoSayHiRowClickHitResult {
        val localId = sourceViewId.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast(':')
            .lowercase(Locale.ROOT)
        val label = sourceContentDescription.orEmpty().lowercase(Locale.ROOT)
        if (isAvatarSource(localId, label)) {
            if (sourceBounds == null || sourceBounds.isEmpty) {
                return MomoSayHiRowClickHitResult.Rejected("AVATAR_CLICK_SOURCE_UNAVAILABLE", localId)
            }
            if (!overlaps(sourceBounds, safeTapBounds)) {
                return MomoSayHiRowClickHitResult.Rejected("AVATAR_SOURCE_OUTSIDE_ROW_CANDIDATE", localId)
            }
            if (!sourceClickable) {
                return MomoSayHiRowClickHitResult.Rejected("AVATAR_CLICK_SOURCE_NOT_CLICKABLE", localId)
            }
            // An avatar is a valid hit within the overall row candidate, but not proof of chat
            // navigation. The adapter waits for the resulting Activity and safely backs out of
            // the profile page instead of treating it as a chat detail.
            return MomoSayHiRowClickHitResult.Accepted(localId, avatarHit = true)
        }
        if (isIndependentControl(localId, label)) {
            return MomoSayHiRowClickHitResult.Rejected("INDEPENDENT_ROW_CONTROL", localId)
        }
        if (sourceBounds == null || sourceBounds.isEmpty) {
            return MomoSayHiRowClickHitResult.Rejected("CLICK_SOURCE_UNAVAILABLE", localId)
        }
        if (!contains(sourceBounds, safeTapBounds)) {
            return MomoSayHiRowClickHitResult.Rejected("CLICK_SOURCE_DOES_NOT_CONTAIN_SAFE_ROW_AREA", localId)
        }
        if (!sourceClickable) {
            return MomoSayHiRowClickHitResult.Rejected("ROW_CLICK_SOURCE_NOT_CLICKABLE", localId)
        }
        return MomoSayHiRowClickHitResult.Accepted(localId)
    }

    private fun isAvatarSource(id: String, label: String): Boolean =
        id in AVATAR_IDS || id.contains("avatar") || label.contains("avatar") || label.contains("头像")

    private fun isIndependentControl(id: String, label: String): Boolean =
        id.contains("vip") || id.contains("wealth") || id.contains("rich_level") ||
            id.contains("level_badge") || id == "userlist_item_layout_badgecontainer" ||
            id == "view_badge" || id == "img_online_status_dot" ||
            id == "green_widget" || id == "hilist_tv_red" || id == "real_man_auth_icon" ||
            label.contains("svip") || label.contains("vip") ||
            label.contains("财富") || label.contains("等级")

    private fun overlaps(first: ScreenRect, second: ScreenRect): Boolean =
        !first.isEmpty && !second.isEmpty && first.left < second.right &&
            first.right > second.left && first.top < second.bottom && first.bottom > second.top

    private fun contains(outer: ScreenRect, inner: ScreenRect): Boolean =
        !outer.isEmpty && !inner.isEmpty && inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom

    private val AVATAR_IDS = setOf(
        "userlist_item_iv_face", "chatlist_item_iv_face", "img_avatar", "icon",
        "avatar_container", "avatar_layout"
    )
}
