package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.ScreenRect
import java.util.Locale

/** Result of checking the node that Android reports for a nickname tap. */
internal sealed interface MomoSayHiNicknameClickHitResult {
    data class Accepted(val sourceViewId: String) : MomoSayHiNicknameClickHitResult
    data class Rejected(val reason: String, val sourceViewId: String) : MomoSayHiNicknameClickHitResult
}

/**
 * Fail-closed interpretation of TYPE_VIEW_CLICKED for a received-greeting nickname target.
 * A row ancestor may be the event source when the app delegates clicks to its row; independent
 * action icons and sibling controls are never accepted as a nickname hit.
 */
internal object MomoSayHiNicknameClickHitPolicy {
    private val nicknameViewIds = setOf("userlist_item_tv_name", "tv_name")

    fun evaluate(
        targetBounds: ScreenRect,
        sourceViewId: String?,
        sourceContentDescription: String?,
        sourceBounds: ScreenRect?,
        sourceClickable: Boolean
    ): MomoSayHiNicknameClickHitResult {
        val localId = sourceViewId.orEmpty().substringAfterLast('/').lowercase(Locale.ROOT)
        val label = sourceContentDescription.orEmpty().lowercase(Locale.ROOT)
        val independentAction = localId.contains("real_man_auth") ||
            localId.contains("auth_icon") || localId.contains("verify") ||
            localId.contains("cert") || localId.contains("vip") ||
            label.contains("认证") || label.contains("实名") || label.contains("验证")
        if (independentAction) {
            return MomoSayHiNicknameClickHitResult.Rejected(
                "INDEPENDENT_FUNCTION_CONTROL", localId
            )
        }
        if (sourceBounds == null || sourceBounds.isEmpty) {
            return MomoSayHiNicknameClickHitResult.Rejected("CLICK_SOURCE_UNAVAILABLE", localId)
        }
        val intersectsTarget = sourceBounds.left < targetBounds.right &&
            sourceBounds.right > targetBounds.left &&
            sourceBounds.top < targetBounds.bottom &&
            sourceBounds.bottom > targetBounds.top
        if (!intersectsTarget) {
            return MomoSayHiNicknameClickHitResult.Rejected(
                "CLICK_SOURCE_OUTSIDE_NICKNAME_BOUNDS", localId
            )
        }
        val sourceContainsTarget = sourceBounds.left <= targetBounds.left &&
            sourceBounds.top <= targetBounds.top &&
            sourceBounds.right >= targetBounds.right &&
            sourceBounds.bottom >= targetBounds.bottom
        // Only the actual nickname TextView, or a clickable ancestor that fully contains the
        // nickname (the app's delegated row-click pattern), is an expected event source. A random
        // overlapping ImageView/non-clickable control must not pass merely because its bounds
        // intersect the intended tap area.
        if (localId in nicknameViewIds) {
            return MomoSayHiNicknameClickHitResult.Accepted(localId)
        }
        if (sourceClickable) {
            if (sourceContainsTarget && sourceBounds != targetBounds) {
                return MomoSayHiNicknameClickHitResult.Accepted(localId)
            }
            return MomoSayHiNicknameClickHitResult.Rejected("SIBLING_CLICKABLE_CONTROL", localId)
        }
        return MomoSayHiNicknameClickHitResult.Rejected("UNEXPECTED_CLICK_SOURCE", localId)
    }
}
