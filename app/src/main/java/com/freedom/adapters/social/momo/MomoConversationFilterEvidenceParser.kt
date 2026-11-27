package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.features.aireply.policy.ConversationFilterEvidence

/**
 * Filter-only evidence from existing snapshots. No new acquisition, raw-text retention,
 * identity fingerprint changes, or page-admission authority.
 */
internal object MomoConversationFilterEvidenceParser {
    private const val PREFIX = "com.immomo.momo:id/"
    private val ageDigits = Regex("[0-9]{1,3}")
    private val activityPattern = Regex("([0-9]+)\\s*(分钟|小时|天)前")
    private val separators = Regex("[·\\r\\n]+")

    fun fromListRow(nodes: List<AccessibilityNodeSnapshot>): ConversationFilterEvidence {
        val visible = nodes.filter { it.visible && !it.bounds.isEmpty }
        val badgeContainer = visible.singleId("userlist_item_layout_badgeContainer")
        val badge = badgeContainer?.let { container ->
            visible.filter { it.isWithin(container) }.singleId("view_badge")
        }
        val distance = listOf("hi_message_timestamp", "hilist_tv_distance").flatMap { id ->
            visible.filter { it.viewId == PREFIX + id }.flatMap { tokens(it.text) }
        }.mapNotNull(MomoSayHiProfileParser::parseDistanceToken).distinct().singleOrNull()
        // Device cross-check: profile_container/hi_message_timestamp has the same
        // distance + activity pair as the verified profile. The greeting clock instead
        // belongs to hi_message_msg_container/hilist_tv_distance. Do not infer presence
        // from arbitrary row text, a bare relative timestamp or an unverified format.
        val profile = visible.singleId("profile_container")
        val timestamp = visible.singleId("hi_message_timestamp")?.takeIf { node ->
            profile != null && node.isWithin(profile) && node.className == "android.widget.TextView"
        }
        val pair = timestamp?.text?.split('·')?.map(String::trim)
        val activeMinutes = pair?.takeIf { it.size == 2 && distance != null &&
            MomoSayHiProfileParser.parseDistanceToken(it[0]) == distance
        }?.get(1)?.let(::parseActivityMinutes)
        return ConversationFilterEvidence(
            lastActiveMinutes = activeMinutes,
            distanceKm = distance,
            age = readAge(visible, badge)
        )
    }

    /** Caller must already own the current verified profile transaction and snapshot. */
    fun fromProfile(tree: AccessibilityTreeSnapshot): ConversationFilterEvidence {
        if (tree.truncated || tree.window.packageName != "com.immomo.momo")
            return ConversationFilterEvidence()
        val visible = tree.nodes.filter { it.visible && !it.bounds.isEmpty }
        val container = visible.singleId("ll_userinfo_container") ?: return ConversationFilterEvidence()
        val scoped = visible.filter { it.isWithin(container) }
        val badge = scoped.singleId("badgeView")
        val info = scoped.singleId("tv_multi_info")
        val fields = tokens(info?.text)
        return ConversationFilterEvidence(
            lastActiveMinutes = fields.mapNotNull(::parseActivityMinutes).distinct().singleOrNull(),
            distanceKm = fields.mapNotNull(MomoSayHiProfileParser::parseDistanceToken).distinct().singleOrNull(),
            age = readAge(scoped, badge)
        )
    }

    fun parseActivityMinutes(text: String): Long? {
        val token = text.trim()
        if (token in setOf("在线", "刚刚", "刚刚在线")) return 0L
        val match = activityPattern.matchEntire(token) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier = when (match.groupValues[2]) {
            "小时" -> 60L
            "天" -> 1_440L
            else -> 1L
        }
        return amount.takeIf { it <= Long.MAX_VALUE / multiplier }?.times(multiplier)
    }

    private fun tokens(text: String?): List<String> =
        text.orEmpty().split(separators).map(String::trim).filter(String::isNotEmpty)

    private fun List<AccessibilityNodeSnapshot>.singleId(id: String): AccessibilityNodeSnapshot? =
        singleOrNull { it.viewId == PREFIX + id }

    private fun AccessibilityNodeSnapshot.isWithin(root: AccessibilityNodeSnapshot): Boolean =
        path.childIndices.size > root.path.childIndices.size &&
            path.childIndices.take(root.path.childIndices.size) == root.path.childIndices

    private fun readAge(nodes: List<AccessibilityNodeSnapshot>, badge: AccessibilityNodeSnapshot?): Int? {
        if (badge == null) return null
        val descendants = nodes.filter { it.isWithin(badge) }
        // Observed age badge: anonymous LinearLayout containing sibling ImageView and numeric
        // TextView. Wealth-level labels are separate and must not supply an age.
        val numeric = descendants.filter { node ->
            node.className == "android.widget.TextView" && node.viewId.isBlank() &&
                ageDigits.matches(node.text.orEmpty().trim()) &&
                descendants.any { sibling ->
                    sibling.className == "android.widget.ImageView" &&
                        sibling.path.childIndices.dropLast(1) == node.path.childIndices.dropLast(1)
                }
        }.singleOrNull() ?: return null
        return numeric.text?.trim()?.toIntOrNull()?.takeIf { it in 1..120 }
    }
}
