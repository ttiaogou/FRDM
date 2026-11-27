package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.*
import com.freedom.features.aireply.policy.ConversationFilterEvidence
import com.freedom.features.aireply.policy.ConversationFilterPolicy
import com.freedom.features.aireply.policy.ConversationFilterSettings
import com.freedom.features.aireply.policy.ConversationFilterResult
import com.freedom.features.aireply.policy.ConversationFilterReason
import org.junit.Assert.*
import org.junit.Test

class MomoConversationFilterEvidenceParserTest {
    private fun node(path: List<Int>, id: String = "", text: String? = null,
                     type: String = "TextView", visible: Boolean = true) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = "android.widget.$type",
        viewId = if (id.isEmpty()) "" else "com.immomo.momo:id/$id",
        text = text, contentDescription = null, bounds = ScreenRect(0, 0, 100, 40),
        visible = visible, enabled = true, checkable = false, checked = false,
        clickable = false, longClickable = false, scrollable = false, editable = false,
        selected = false, focused = false, childCount = 0, packageName = "com.immomo.momo"
    )

    private fun profile(info: String, age: String, extra: List<AccessibilityNodeSnapshot> = emptyList(),
                        truncated: Boolean = false): AccessibilityTreeSnapshot {
        val nodes = listOf(
            node(listOf(0), "ll_userinfo_container", type = "LinearLayout"),
            node(listOf(0, 0), "badgeView", type = "LinearLayout"),
            node(listOf(0, 0, 0), type = "LinearLayout"),
            node(listOf(0, 0, 0, 0), type = "ImageView"),
            node(listOf(0, 0, 0, 1), text = age),
            node(listOf(0, 1), "tv_multi_info", info)
        ) + extra
        return AccessibilityTreeSnapshot(WindowRef("com.immomo.momo", "profile", 1),
            nodes, nodes.size, truncated, textCapture = NodeTextCapture.EPHEMERAL)
    }

    @Test fun sixObservedProfileFormats() {
        val cases = listOf(
            Triple("7.98km · 在线 · 128获赞 · 13粉丝", "38", ConversationFilterEvidence(0, 7.98, 38)),
            Triple("顺德区 · 15分钟前 · 54获赞 · 14粉丝", "80", ConversationFilterEvidence(15, null, 80)),
            Triple("0.49km · 38分钟前 · 23获赞 · 7粉丝", "100", ConversationFilterEvidence(38, 0.49, 100)),
            Triple("7.01km · 18分钟前 · 2036获赞 · 10粉丝\n使用 iPhone 14 Plus", "32", ConversationFilterEvidence(18, 7.01, 32)),
            Triple("0.44km · 2小时前 · 24获赞 · 3粉丝", "33", ConversationFilterEvidence(120, 0.44, 33)),
            Triple("广州市 · 9天前 · 434获赞 · 10粉丝", "33", ConversationFilterEvidence(12960, null, 33))
        )
        for ((info, age, expected) in cases) {
            assertEquals(expected, MomoConversationFilterEvidenceParser.fromProfile(profile(info, age)))
        }
    }

    @Test fun activityCompatibilityAndOverflow() {
        for (text in listOf("在线", "刚刚", "刚刚在线")) {
            assertEquals(0L, MomoConversationFilterEvidenceParser.parseActivityMinutes(text))
        }
        assertEquals(1L, MomoConversationFilterEvidenceParser.parseActivityMinutes("1分钟前"))
        assertEquals(60L, MomoConversationFilterEvidenceParser.parseActivityMinutes("1小时前"))
        assertNull(MomoConversationFilterEvidenceParser.parseActivityMinutes("你好，1分钟前"))
        assertNull(MomoConversationFilterEvidenceParser.parseActivityMinutes("9223372036854775807天前"))
    }

    @Test fun listEvidenceUsesOnlyObservedMetadataAndBadge() {
        val row = listOf(
            node(listOf(0), "userlist_item_layout_badgeContainer", type = "LinearLayout"),
            node(listOf(0, 0), "view_badge", type = "LinearLayout"),
            node(listOf(0, 0, 0), type = "LinearLayout"),
            node(listOf(0, 0, 0, 0), type = "ImageView"),
            node(listOf(0, 0, 0, 1), text = "32"),
            node(listOf(0, 0, 1), text = "财富等级2"),
            node(listOf(1), "profile_container", type = "LinearLayout"),
            node(listOf(1, 0), "hi_message_timestamp", "500m · 1分钟前"),
            node(listOf(2), "hilist_tv_distance", "01:18"),
            node(listOf(3), "hilist_tv_msgcontent", "在线 · 99岁 · 50km")
        )
        assertEquals(ConversationFilterEvidence(1, 0.5, 32),
            MomoConversationFilterEvidenceParser.fromListRow(row))
        assertEquals(ConversationFilterEvidence(null, null, 32),
            MomoConversationFilterEvidenceParser.fromListRow(row + node(listOf(4), "hilist_tv_distance", "2km")))
        assertEquals(ConversationFilterEvidence(),
            MomoConversationFilterEvidenceParser.fromListRow(listOf(row.last())))
    }

    private fun listActivityRow(text: String, visible: Boolean = true) = listOf(
        node(listOf(0), "profile_container", type = "LinearLayout"),
        node(listOf(0, 0), "hi_message_timestamp", text, visible = visible),
        node(listOf(1), "hi_message_msg_container", type = "LinearLayout"),
        node(listOf(1, 0), "hilist_tv_distance", "05:49"),
        node(listOf(1, 1), "hilist_tv_msgcontent", "20小时前 · 在线")
    )

    @Test fun observedGreetingRowsCanExcludeBeforeAvatarWithExistingPolicy() {
        val examples = listOf(
            Triple("0.60km · 7小时前", 420L, 0.60),
            Triple("0.85km · 14小时前", 840L, 0.85),
            Triple("64.80km · 12小时前", 720L, 64.80),
            Triple("0.47km · 6小时前", 360L, 0.47),
            Triple("5.40km · 15小时前", 900L, 5.40),
            Triple("4.33km · 13小时前", 780L, 4.33),
            Triple("37.82km · 3小时前", 180L, 37.82),
            Triple("0.42km · 14小时前", 840L, 0.42),
            Triple("2.18km · 20小时前", 1200L, 2.18),
            Triple("10.05km · 1分钟前", 1L, 10.05)
        )
        val settings = ConversationFilterSettings(maxLastActiveMinutes = 60)
        for ((text, minutes, distance) in examples) {
            val evidence = MomoConversationFilterEvidenceParser.fromListRow(listActivityRow(text))
            assertEquals(ConversationFilterEvidence(minutes, distance), evidence)
            val decision = ConversationFilterPolicy.evaluate(settings, evidence)
            assertEquals(if (minutes > 60L) ConversationFilterResult.EXCLUDE else ConversationFilterResult.ALLOW,
                decision.result)
            if (minutes > 60L) assertEquals(ConversationFilterReason.ACTIVE_TIME_EXCEEDED, decision.reason)
        }
    }

    @Test fun listActivityIsScopedAndUnknownFormatsFallBackToProfile() {
        val parser = MomoConversationFilterEvidenceParser
        val settings = ConversationFilterSettings(maxLastActiveMinutes = 60)
        val good = listActivityRow("0.60km · 7小时前")
        val invalid = listOf(
            listActivityRow("7小时前"),
            listActivityRow("05:49"),
            listActivityRow("0.60km · 05:49"),
            listActivityRow("0.60km · · 7小时前"),
            listActivityRow("0.60km · 7小时前 · 2小时前"),
            listActivityRow("广州市 · 7小时前"),
            listActivityRow("0.60km · 7小时前", visible = false),
            good.filterNot { it.viewId.endsWith("/profile_container") },
            good + node(listOf(2, 0), "hi_message_timestamp", "0.60km · 7小时前"),
            good + node(listOf(2), "profile_container", type = "LinearLayout"),
            good + node(listOf(2), "hilist_tv_distance", "2km")
        )
        for (row in invalid) {
            val evidence = parser.fromListRow(row)
            assertNull(evidence.lastActiveMinutes)
            assertEquals(ConversationFilterResult.UNKNOWN, ConversationFilterPolicy.evaluate(settings, evidence).result)
        }
        for (token in listOf("在线", "刚刚", "刚刚在线", "1分钟前", "1小时前")) {
            val evidence = parser.fromListRow(listActivityRow("0.60km · $token"))
            assertEquals(parser.parseActivityMinutes(token), evidence.lastActiveMinutes)
            assertEquals(ConversationFilterResult.ALLOW, ConversationFilterPolicy.evaluate(settings, evidence).result)
        }
    }

    @Test fun profileMissingConflictAndTruncationAreUnknown() {
        val parser = MomoConversationFilterEvidenceParser
        assertEquals(ConversationFilterEvidence(null, null, null),
            parser.fromProfile(profile("在线 · 2小时前 · 1km · 2km", "财富等级2")))
        assertEquals(ConversationFilterEvidence(),
            parser.fromProfile(profile("在线 · 1km", "33", truncated = true)))
        assertEquals(ConversationFilterEvidence(null, null, 33),
            parser.fromProfile(profile("在线 · 1km", "33",
                listOf(node(listOf(0, 2), "tv_multi_info", "2小时前 · 2km")))))
        assertEquals(ConversationFilterEvidence(null, null, 33),
            parser.fromProfile(profile("广州市", "33",
                listOf(node(listOf(1), "tv_multi_info", "在线 · 1km")))))
    }
}
