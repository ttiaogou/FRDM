package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.*
import com.freedom.core.accessibility.query.NodeQueryBudget
import org.junit.Assert.*
import org.junit.Test

class MomoSayHiAccessibilityInspectorTest {
    private fun node(
        path: List<Int>, id: String = "", text: String? = null,
        visible: Boolean = true, children: Int = 0, scrollable: Boolean = false,
        className: String = if (id.substringAfterLast('/').substringAfterLast(':') == "listview") {
            "androidx.recyclerview.widget.RecyclerView"
        } else "android.widget.TextView",
        clickable: Boolean = false
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = className, viewId = id,
        text = text, contentDescription = null,
        bounds = ScreenRect(0, 0, 100, 40), visible = visible, enabled = true,
        checkable = false, checked = false, clickable = clickable, longClickable = false,
        scrollable = scrollable, editable = false, selected = false, focused = false,
        childCount = children,
        packageName = MomoAppDescriptor.PACKAGE_NAME
    )

    /** Adds the observed fixed toolbar/content shell around an existing captured list fixture. */
    private fun withReceivedListShell(tree: AccessibilityTreeSnapshot): AccessibilityTreeSnapshot {
        val root = tree.nodes.firstOrNull { it.path.childIndices.isEmpty() } ?: node(emptyList(), className = "android.widget.FrameLayout")
        val shifted = tree.nodes.filter { it.path.childIndices.isNotEmpty() }.map { existing ->
            val oldPath = existing.path.childIndices
            val newPath = if (oldPath.firstOrNull() == 0) {
                listOf(1, 0) + oldPath.drop(1)
            } else {
                listOf(2) + oldPath.drop(1)
            }
            if (existing.viewId.substringAfterLast('/').substringAfterLast(':') == "listview") {
                existing.copy(
                    path = NodePath(newPath),
                    className = "androidx.recyclerview.widget.RecyclerView",
                    scrollable = true,
                    enabled = true,
                    bounds = ScreenRect(0, 180, 720, 1580),
                    visible = true,
                    childCount = existing.childCount + 1
                )
            } else existing.copy(path = NodePath(newPath))
        }
        // The captured list layout has a stable “全部招呼” section heading. Include it in every
        // list fixture so row-extraction tests don't accidentally model a structurally incomplete
        // page just because their focus is a specific row field.
        val shiftedList = shifted.singleOrNull {
            it.viewId.substringAfterLast('/').substringAfterLast(':') == "listview"
        }
        val listTitle = shiftedList?.let { list ->
            val listPath = list.path.childIndices
            val nextChild = (shifted.asSequence()
                .map { it.path.childIndices }
                .filter { it.size > listPath.size && it.take(listPath.size) == listPath }
                .map { it[listPath.size] }
                .maxOrNull() ?: -1) + 1
            node(listPath + nextChild, "com.immomo.momo:id/title", "全部招呼")
        }
        val appbar = node(listOf(0), "com.immomo.momo:id/appbar_id", children = 1,
            className = "android.widget.RelativeLayout")
        val toolbar = node(listOf(0, 0), "com.immomo.momo:id/toolbar_id", children = 2,
            className = "android.view.ViewGroup")
        val unread = node(listOf(0, 0, 0), "com.immomo.momo:id/ic_toolbar_all_unread",
            className = "android.widget.ImageView", clickable = true)
        val harass = node(listOf(0, 0, 1), "com.immomo.momo:id/ic_toolbar_all_harass",
            className = "android.widget.ImageView", clickable = true)
        val content = node(listOf(1), "com.immomo.momo:id/layout_content", children = 1,
            className = "android.widget.FrameLayout")
        return tree.copy(nodes = listOf(root, appbar, toolbar, unread, harass, content) + shifted + listOfNotNull(listTitle))
    }


    private fun fingerprintFact(
        id: String,
        text: String = "",
        className: String = "android.widget.TextView",
        enabled: Boolean = true,
        scrollable: Boolean = false,
        boundsEmpty: Boolean = false,
        children: Int = 0,
        ancestors: List<String> = emptyList()
    ) = MomoReceivedGreetingsFingerprintFact(
        id = id,
        text = text,
        className = className,
        enabled = enabled,
        scrollable = scrollable,
        boundsEmpty = boundsEmpty,
        childCount = children,
        ancestors = ancestors
    )

    private fun minimumGreetingListShell() = listOf(
        fingerprintFact("layout_content"),
        fingerprintFact(
            "listview",
            className = "androidx.recyclerview.widget.RecyclerView",
            enabled = true,
            scrollable = true,
            children = 1,
            ancestors = listOf("layout_content")
        )
    )

    @Test fun sharedFingerprintPolicyAcceptsOnlyTheTwoStrictPageRoutes() {
        val shell = minimumGreetingListShell()

        // A: list + stable section heading.
        assertTrue(MomoReceivedGreetingsFingerprintPolicy.matches(
            shell + fingerprintFact("title", text = "全部招呼", ancestors = listOf("layout_content", "listview"))
        ))

        // B: list + actual greeting-row schema.
        assertTrue(MomoReceivedGreetingsFingerprintPolicy.matches(
            shell + listOf(
                fingerprintFact("userlist_item_iv_face", ancestors = listOf("layout_content", "listview")),
                fingerprintFact("userlist_item_tv_name", ancestors = listOf("layout_content", "listview")),
                fingerprintFact("hilist_tv_msgcontent", ancestors = listOf("layout_content", "listview"))
            )
        ))

        // C (toolbar + list) is intentionally not a page identity route.
        assertFalse(MomoReceivedGreetingsFingerprintPolicy.matches(
            shell + listOf(
                fingerprintFact("appbar_id"),
                fingerprintFact("toolbar_id", ancestors = listOf("appbar_id"))
            )
        ))
    }

    @Test fun sharedFingerprintPolicyRejectsAmbiguousOrUnusableListShells() {
        val shell = minimumGreetingListShell()
        val toolbar = listOf(
            fingerprintFact("appbar_id"),
            fingerprintFact("toolbar_id", ancestors = listOf("appbar_id"))
        )
        assertFalse(MomoReceivedGreetingsFingerprintPolicy.matches(
            shell + shell.last() + toolbar
        ))
        assertFalse(MomoReceivedGreetingsFingerprintPolicy.matches(
            listOf(shell.first(), shell.last().copy(enabled = false)) + toolbar
        ))
        assertFalse(MomoReceivedGreetingsFingerprintPolicy.matches(
            listOf(shell.first().copy(boundsEmpty = true), shell.last()) + toolbar
        ))
        assertFalse(MomoReceivedGreetingsFingerprintPolicy.matches(
            shell.map { if (it.id == "listview") it.copy(childCount = 0) else it } +
                fingerprintFact("title", text = "全部招呼", ancestors = listOf("layout_content", "listview"))
        ))
    }

    @Test fun recommendationCarouselCannotSupplyGreetingTitleOrRowEvidence() {
        val shell = minimumGreetingListShell()
        val carouselAncestors = listOf("layout_content", "listview", "card_list")
        val facts = shell + listOf(
            fingerprintFact("title", text = "全部招呼", ancestors = carouselAncestors),
            fingerprintFact("userlist_item_iv_face", ancestors = carouselAncestors),
            fingerprintFact("userlist_item_tv_name", ancestors = carouselAncestors),
            fingerprintFact("hilist_tv_msgcontent", ancestors = carouselAncestors)
        )
        assertFalse(MomoReceivedGreetingsFingerprintPolicy.matches(facts))
    }

    @Test fun confirmsReceivedGreetingsListFromStableTitleAndOuterListStructure() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef(MomoAppDescriptor.PACKAGE_NAME, "UnknownActivity", 12L, 44),
            nodes = listOf(
                node(emptyList(), className = "android.widget.FrameLayout"),
                node(listOf(0), "com.immomo.momo:id/appbar_id", className = "android.widget.RelativeLayout"),
                node(listOf(0, 0), "com.immomo.momo:id/toolbar_id", className = "android.view.ViewGroup"),
                node(listOf(0, 0, 0), "com.immomo.momo:id/ic_toolbar_all_unread", clickable = true, className = "android.widget.ImageView"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/ic_toolbar_all_harass", clickable = true, className = "android.widget.ImageView"),
                node(listOf(1), "com.immomo.momo:id/layout_content", className = "android.widget.FrameLayout"),
                node(listOf(1, 0), "com.immomo.momo:id/listview", children = 2, scrollable = true,
                    className = "androidx.recyclerview.widget.RecyclerView"),
                node(listOf(1, 0, 0), "com.immomo.momo:id/title", "全部招呼"),
                node(listOf(1, 0, 1), "com.immomo.momo:id/userlist_item_iv_face", className = "android.widget.ImageView")
            ),
            visitedNodeCount = 9,
            truncated = false
        )

        assertTrue(MomoSayHiAccessibilityInspector().hasReceivedGreetingsListFingerprint(tree))
        assertEquals(
            MomoSayHiPageKind.RECEIVED_GREETINGS_LIST,
            MomoSayHiAccessibilityInspector().inspect(tree).kind
        )
    }

    @Test fun confirmedListviewSubtreeIsRevalidatedWithoutMissingLayoutContentAncestor() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef(
                MomoAppDescriptor.PACKAGE_NAME,
                MomoSayHiPageContract.LIST_ACTIVITY,
                77L,
                2582
            ),
            nodes = listOf(
                // inspectNodeSubtree() returns listview as the new root; layout_content is absent.
                node(emptyList(), MomoSayHiPageContract.LIST_VIEW_ID, children = 1, scrollable = true),
                node(listOf(0), "com.immomo.momo:id/userlist_item_iv_face", className = "android.widget.ImageView"),
                node(listOf(0, 0), "com.immomo.momo:id/userlist_item_tv_name", "测试用户"),
                node(listOf(0, 1), "com.immomo.momo:id/hi_message_timestamp", "刚刚"),
                node(listOf(0, 2), "com.immomo.momo:id/hilist_tv_msgcontent", "你好")
            ),
            visitedNodeCount = 5,
            truncated = false
        )
        val inspector = MomoSayHiAccessibilityInspector()

        assertTrue(inspector.hasReceivedGreetingsListSubtreeFingerprint(tree))
        // A subtree must not self-authorize; the adapter supplies this only after full-page
        // confirmation and exact window/NodeTarget continuity.
        assertEquals(MomoSayHiPageKind.UNKNOWN, inspector.inspect(tree).kind)
        val inspection = inspector.inspect(tree, receivedGreetingsListSubtreeConfirmed = true)
        assertEquals(MomoSayHiPageKind.RECEIVED_GREETINGS_LIST, inspection.kind)
        assertTrue(inspection.containerFound)
    }

    @Test fun confirmedListviewSubtreeRejectsRecommendationCarouselEvenWithStaleChatActivityHint() {
        val nodes = listOf(
            node(emptyList(), MomoSayHiPageContract.LIST_VIEW_ID, children = 1, scrollable = true),
            node(listOf(0), "com.immomo.momo:id/card_list", children = 1, scrollable = true,
                className = "androidx.recyclerview.widget.RecyclerView"),
            node(listOf(0, 0), "com.immomo.momo:id/userlist_item_iv_face", className = "android.widget.ImageView"),
            node(listOf(0, 1), "com.immomo.momo:id/userlist_item_tv_name", "推荐用户")
        )
        val recommendationOnly = AccessibilityTreeSnapshot(
            window = WindowRef(MomoAppDescriptor.PACKAGE_NAME, MomoSayHiPageContract.LIST_ACTIVITY, 78L, 2583),
            nodes = nodes, visitedNodeCount = nodes.size, truncated = false
        )
        val staleChatHint = recommendationOnly.copy(
            window = recommendationOnly.window.copy(activityClass = MomoChatPageContract.CHAT_DETAIL_ACTIVITY)
        )
        val inspector = MomoSayHiAccessibilityInspector()

        assertFalse(inspector.hasReceivedGreetingsListSubtreeFingerprint(recommendationOnly))
        assertFalse(inspector.hasReceivedGreetingsListSubtreeFingerprint(staleChatHint))
        assertEquals(
            MomoSayHiPageKind.UNKNOWN,
            inspector.inspect(staleChatHint, receivedGreetingsListSubtreeConfirmed = true).kind
        )
    }

    @Test fun confirmedGreetingSubtreeSurvivesStaleChatActivityHintAfterBack() {
        val nodes = listOf(
            node(emptyList(), MomoSayHiPageContract.LIST_VIEW_ID, children = 3, scrollable = true),
            node(listOf(0), "com.immomo.momo:id/userlist_item_iv_face", className = "android.widget.ImageView"),
            node(listOf(0, 0), "com.immomo.momo:id/userlist_item_tv_name", "测试用户"),
            node(listOf(0, 1), "com.immomo.momo:id/hi_message_timestamp", "刚刚"),
            node(listOf(0, 2), "com.immomo.momo:id/hilist_tv_msgcontent", "你好")
        )
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef(
                MomoAppDescriptor.PACKAGE_NAME,
                MomoChatPageContract.CHAT_DETAIL_ACTIVITY,
                79L,
                2686
            ),
            nodes = nodes, visitedNodeCount = nodes.size, truncated = false
        )
        val inspector = MomoSayHiAccessibilityInspector()

        assertTrue(inspector.hasReceivedGreetingsListSubtreeFingerprint(tree))
        assertEquals(MomoSayHiPageKind.UNKNOWN, inspector.inspect(tree).kind)
        val inspection = inspector.inspect(tree, receivedGreetingsListSubtreeConfirmed = true)
        assertEquals(MomoSayHiPageKind.RECEIVED_GREETINGS_LIST, inspection.kind)
        assertTrue(inspection.containerFound)
        assertEquals(1, inspection.listRows.size)
    }

    @Test fun snapshotRejectsToolbarListWithoutAStablePageSpecificMarker() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef(MomoAppDescriptor.PACKAGE_NAME, "UnknownActivity", 13L, 46),
            nodes = listOf(
                node(emptyList(), className = "android.widget.FrameLayout"),
                node(listOf(0), "com.immomo.momo:id/appbar_id", className = "android.widget.RelativeLayout"),
                node(listOf(0, 0), "com.immomo.momo:id/toolbar_id", className = "android.view.ViewGroup"),
                node(listOf(1), "com.immomo.momo:id/layout_content", className = "android.widget.FrameLayout"),
                node(listOf(1, 0), "com.immomo.momo:id/listview", children = 0, scrollable = true,
                    className = "androidx.recyclerview.widget.RecyclerView")
            ),
            visitedNodeCount = 5,
            truncated = false
        )

        val inspector = MomoSayHiAccessibilityInspector()
        assertFalse(inspector.hasReceivedGreetingsListFingerprint(tree))
        assertEquals(MomoSayHiPageKind.UNKNOWN, inspector.inspect(tree).kind)
    }

    @Test fun activityNameAndListIdAloneCannotConfirmReceivedGreetingsList() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef(MomoAppDescriptor.PACKAGE_NAME, MomoSayHiPageContract.LIST_ACTIVITY, 12L, 45),
            nodes = listOf(
                node(emptyList(), className = "android.widget.FrameLayout"),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, children = 0, scrollable = true,
                    className = "androidx.recyclerview.widget.RecyclerView")
            ),
            visitedNodeCount = 2,
            truncated = false
        )

        assertFalse(MomoSayHiAccessibilityInspector().hasReceivedGreetingsListFingerprint(tree))
        assertEquals(MomoSayHiPageKind.UNKNOWN, MomoSayHiAccessibilityInspector().inspect(tree).kind)
    }

    @Test fun isolatesVerticalGreetingRowsFromNestedHorizontalRecommendationCarousel() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 91),
            nodes = listOf(
                node(emptyList()),
                // The real page exposes listview as the outer, vertically scrolling RecyclerView.
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, children = 4, scrollable = true),
                // Real header signature: all content below these labels and inside card_list is excluded.
                node(listOf(0, 0), "com.immomo.momo:id/header", children = 4),
                node(listOf(0, 0, 0), MomoSayHiPageContract.RECOMMENDATION_CAROUSEL_VIEW_ID, children = 4),
                node(listOf(0, 0, 0, 0), "com.immomo.momo:id/card_container", children = 2),
                node(listOf(0, 0, 0, 0, 0), "com.immomo.momo:id/icon"),
                node(listOf(0, 0, 0, 0, 1), "com.immomo.momo:id/tv_name", "推荐用户甲"),
                node(listOf(0, 0, 0, 1), "com.immomo.momo:id/card_container", children = 2),
                node(listOf(0, 0, 0, 1, 0), "com.immomo.momo:id/icon"),
                node(listOf(0, 0, 0, 1, 1), "com.immomo.momo:id/tv_name", "推荐用户乙"),
                node(listOf(0, 0, 0, 2), "com.immomo.momo:id/card_container", children = 2),
                node(listOf(0, 0, 0, 2, 0), "com.immomo.momo:id/icon"),
                node(listOf(0, 0, 0, 2, 1), "com.immomo.momo:id/tv_name", "推荐用户丙"),
                node(listOf(0, 0, 0, 3), "com.immomo.momo:id/card_container", children = 2),
                node(listOf(0, 0, 0, 3, 0), "com.immomo.momo:id/icon"),
                node(listOf(0, 0, 0, 3, 1), "com.immomo.momo:id/tv_name", "推荐用户丁"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/title_unread", "未读招呼"),
                node(listOf(0, 0, 2), "com.immomo.momo:id/title_check", "查看更多"),
                node(listOf(0, 0, 3), "com.immomo.momo:id/hilist_tv_count", "35"),
                // The real page has a section title between its header and user rows.
                node(listOf(0, 1), "com.immomo.momo:id/section_title"),
                node(listOf(0, 2), "com.immomo.momo:id/user_row", children = 4),
                node(listOf(0, 2, 0), "com.immomo.momo:id/userlist_item_iv_face"),
                node(listOf(0, 2, 1), "com.immomo.momo:id/userlist_item_tv_name", "真实招呼用户甲"),
                node(listOf(0, 2, 2), "com.immomo.momo:id/hilist_tv_distance", "0.8km"),
                node(listOf(0, 2, 3), "com.immomo.momo:id/hilist_tv_msgcontent", "你好"),
                node(listOf(0, 3), "com.immomo.momo:id/user_row", children = 4),
                node(listOf(0, 3, 0), "com.immomo.momo:id/userlist_item_iv_face"),
                node(listOf(0, 3, 1), "com.immomo.momo:id/userlist_item_tv_name", "真实招呼用户乙"),
                node(listOf(0, 3, 2), "com.immomo.momo:id/hilist_tv_distance", "1.2km"),
                node(listOf(0, 3, 3), "com.immomo.momo:id/hilist_tv_msgcontent", "在吗")
            ),
            visitedNodeCount = 30,
            truncated = false
        )

        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))

        assertEquals(MomoSayHiPageKind.RECEIVED_GREETINGS_LIST, result.kind)
        assertTrue(result.containerFound)
        assertEquals(2, result.listRows.size)
        assertEquals(setOf("真实招呼用户甲", "真实招呼用户乙"), result.listRows.mapNotNull { it.displayName.value }.toSet())
        assertEquals(setOf("0.8km", "1.2km"), result.listRows.mapNotNull { it.distanceText.value }.toSet())
        assertEquals(4, result.excludedRecommendationCardCount)
        assertFalse(result.listRows.any { it.displayName.value?.startsWith("推荐用户") == true })
        assertFalse(tree.nodes.single { it.viewId == MomoSayHiPageContract.RECOMMENDATION_CAROUSEL_VIEW_ID }.scrollable)
        assertTrue(tree.nodes.single { it.viewId == MomoSayHiPageContract.LIST_VIEW_ID }.scrollable)
    }


    @Test fun horizontalRecommendationCarouselAloneCannotAuthorizeGreetingListScan() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 92),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), "com.immomo.momo:id/header", children = 1),
                node(listOf(0, 0), MomoSayHiPageContract.RECOMMENDATION_CAROUSEL_VIEW_ID, children = 1),
                node(listOf(0, 0, 0), "com.immomo.momo:id/card_container", children = 1),
                node(listOf(0, 0, 0, 0), "com.immomo.momo:id/tv_name", "仅推荐卡片")
            ),
            visitedNodeCount = 5,
            truncated = false
        )

        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))

        assertEquals(MomoSayHiPageKind.UNKNOWN, result.kind)
        assertFalse(result.containerFound)
        assertTrue(result.listRows.isEmpty())
    }

    @Test fun readsOnlyTextInsideConfirmedProfileContentContainer() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 1),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, children = 4),
                node(listOf(0, 0), text = "萧荧"),
                node(listOf(0, 1), text = "在线 · 0.59km"),
                node(listOf(0, 2), text = "♀30"),
                node(listOf(0, 3), text = "他月收入1万元，身高165cm，旅游四方"),
                node(listOf(1), text = "列表外的31岁")
            ),
            visitedNodeCount = 7,
            truncated = false
        )
        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        assertEquals(MomoSayHiPageKind.GREETING_PROFILE_CARD, result.kind)
        assertTrue(result.containerFound)
        assertEquals(5, result.containerNodeCount)
        assertEquals(4, result.textNodeCount)
        assertEquals(30, result.profile?.age?.value)
        assertEquals(Gender.FEMALE, result.profile?.gender?.value)
        assertEquals(0.59, result.profile?.distanceKm?.value!!, 0.0001)
        assertEquals(true, result.profile?.online?.value)
        assertFalse(result.profile?.age?.rawText.orEmpty().contains("31岁"))
    }

    @Test fun keepsProfileTextByExactResourceIdAndDistinguishesMissingEmptyAndMultipleMatches() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 20),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, children = 5),
                node(listOf(0, 0), "com.immomo.momo:id/tv_name", "真实昵称"),
                node(listOf(0, 1), "com.immomo.momo:id/tv_plain_text", "一段真实简介文本"),
                node(listOf(0, 2), "com.immomo.momo:id/location_distance", ""),
                node(listOf(0, 3), "com.immomo.momo:id/tv_recommend_text", "第一句招呼"),
                node(listOf(0, 4), "com.immomo.momo:id/tv_recommend_text", "第二句招呼")
            ),
            visitedNodeCount = 7,
            truncated = false
        )

        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        val fields = result.profile?.nodeTextFields.orEmpty()

        assertEquals(EvidenceStatus.CONFIRMED, fields.getValue("tv_name").evidence.status)
        assertEquals("真实昵称", fields.getValue("tv_name").evidence.value)
        assertEquals("真实昵称", result.profile?.displayName?.value)
        assertEquals(EvidenceStatus.CONFIRMED, fields.getValue("tv_plain_text").evidence.status)
        assertEquals("一段真实简介文本", fields.getValue("tv_plain_text").evidence.value)
        assertEquals(EvidenceStatus.EMPTY, fields.getValue("location_distance").evidence.status)
        assertEquals(EvidenceStatus.MULTIPLE_MATCHES, fields.getValue("tv_recommend_text").evidence.status)
        assertEquals(2, fields.getValue("tv_recommend_text").candidateCount)
        assertEquals(EvidenceStatus.MISSING, fields.getValue("user_online_status_container").evidence.status)
        assertEquals(fields, result.profile?.nodeTextFields)
    }

    @Test fun extractsVisibleGreetingRowsBySharedAccessibilityAncestor() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 4),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, children = 2),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", children = 4),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/hilist_tv_distance", "0.8km"),
                node(listOf(0, 0, 2), "com.immomo.momo:id/hilist_tv_msgcontent", "你好呀"),
                node(listOf(0, 0, 3), "com.immomo.momo:id/hi_message_timestamp", "刚刚"),
                node(listOf(0, 1), "com.immomo.momo:id/user_row", children = 2),
                node(listOf(0, 1, 0), "com.immomo.momo:id/userlist_item_tv_name", "晚风"),
                node(listOf(0, 1, 1), "com.immomo.momo:id/hilist_tv_msgcontent", "在吗"),
                node(listOf(1), "", "页面外文本")
            ),
            visitedNodeCount = 11,
            truncated = false
        )
        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        assertEquals(MomoSayHiPageKind.RECEIVED_GREETINGS_LIST, result.kind)
        assertEquals(2, result.listRows.size)
        val first = result.listRows.first { it.displayName.value == "小雨" }
        assertEquals("0.8km", first.distanceText.value)
        assertEquals("你好呀", first.greetingText.value)
        assertEquals("刚刚", first.timestampText.value)
        val second = result.listRows.first { it.displayName.value == "晚风" }
        assertEquals("在吗", second.greetingText.value)
        assertEquals(EvidenceStatus.UNKNOWN, second.distanceText.status)
    }

    @Test fun inspectsListviewSubtreeWhenItsSnapshotPathsRemainAbsolute() {
        val listPath = listOf(0, 0, 2, 1)
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 40, 891),
            nodes = listOf(
                node(listPath, MomoSayHiPageContract.LIST_VIEW_ID, children = 1),
                node(listPath + 0, "com.immomo.momo:id/user_row", children = 3),
                node(listPath + listOf(0, 0), "com.immomo.momo:id/userlist_item_tv_name", "列表内用户"),
                node(listPath + listOf(0, 1), "com.immomo.momo:id/hilist_tv_msgcontent", "你好"),
                node(listPath + listOf(0, 2), "com.immomo.momo:id/hilist_tv_red")
            ),
            visitedNodeCount = 5,
            truncated = false,
            windowId = 891
        )

        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))

        assertEquals(MomoSayHiPageKind.RECEIVED_GREETINGS_LIST, result.kind)
        assertTrue(result.containerFound)
        assertEquals(1, result.listRows.size)
        assertEquals("列表内用户", result.listRows.single().displayName.value)
        assertEquals(true, result.listRows.single().unread.value)
        assertEquals(listOf(1, 0) + listPath.drop(1), result.listRows.single().rowPath.dropLast(1))
    }

    @Test fun extractsAgeGenderDistanceAndOnlineStateWithinEachGreetingRow() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 6),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, children = 2),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", children = 6),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/hilist_tv_distance", "0.8km"),
                node(listOf(0, 0, 2), "com.immomo.momo:id/hilist_tv_msgcontent", "你好呀"),
                node(listOf(0, 0, 3), "com.immomo.momo:id/hi_message_timestamp", "刚刚"),
                node(listOf(0, 0, 4), text = "♀26"),
                node(listOf(0, 0, 5), text = "在线"),
                node(listOf(0, 1), "com.immomo.momo:id/user_row", children = 5),
                node(listOf(0, 1, 0), "com.immomo.momo:id/userlist_item_tv_name", "晚风"),
                node(listOf(0, 1, 1), "com.immomo.momo:id/hilist_tv_distance", "500m"),
                node(listOf(0, 1, 2), "com.immomo.momo:id/hilist_tv_msgcontent", "在吗"),
                node(listOf(0, 1, 3), text = "♂31"),
                node(listOf(0, 1, 4), text = "离线")
            ),
            visitedNodeCount = 15,
            truncated = false
        )

        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        val first = result.listRows.first { it.displayName.value == "小雨" }
        val second = result.listRows.first { it.displayName.value == "晚风" }

        assertEquals(26, first.age.value)
        assertEquals(Gender.FEMALE, first.gender.value)
        assertEquals(0.8, first.distanceKm.value!!, 0.0001)
        assertEquals(true, first.online.value)
        assertEquals(31, second.age.value)
        assertEquals(Gender.MALE, second.gender.value)
        assertEquals(0.5, second.distanceKm.value!!, 0.0001)
        assertEquals(false, second.online.value)
        assertFalse(first.age.rawText.orEmpty().contains("31"))
        assertFalse(second.age.rawText.orEmpty().contains("26"))
    }


    @Test fun hilistTvRedInsideTheExactUserRowConfirmsUnreadGreeting() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 21),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, children = 2),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", children = 3),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/hilist_tv_distance", "0.8km"),
                node(listOf(0, 0, 2), "com.immomo.momo:id/hilist_tv_red", visible = false),
                node(listOf(0, 1), "com.immomo.momo:id/user_row", children = 2),
                node(listOf(0, 1, 0), "com.immomo.momo:id/userlist_item_tv_name", "晚风"),
                node(listOf(0, 1, 1), "com.immomo.momo:id/hilist_tv_distance", "500m")
            ),
            visitedNodeCount = 9,
            truncated = false
        )

        val rows = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree)).listRows
        val unread = rows.first { it.displayName.value == "小雨" }
        val read = rows.first { it.displayName.value == "晚风" }

        assertEquals(true, unread.unread.value)
        assertEquals(EvidenceStatus.CONFIRMED, unread.unread.status)
        assertEquals("ACCESSIBILITY_VIEW_ID:hilist_tv_red", unread.unread.source)
        assertEquals(false, read.unread.value)
        assertEquals(EvidenceStatus.CONFIRMED, read.unread.status)
    }

    @Test fun redMarkerIsScopedToItsOwnRowAndNeverBecomesOnlineEvidence() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 22),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, children = 2),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", children = 3),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", "甲"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/hilist_tv_distance", "1km"),
                node(listOf(0, 0, 2), "com.immomo.momo:id/hilist_tv_red"),
                node(listOf(0, 1), "com.immomo.momo:id/user_row", children = 2),
                node(listOf(0, 1, 0), "com.immomo.momo:id/userlist_item_tv_name", "乙"),
                node(listOf(0, 1, 1), "com.immomo.momo:id/hilist_tv_distance", "2km")
            ),
            visitedNodeCount = 9,
            truncated = false
        )
        val rows = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree)).listRows
        assertEquals(true, rows.first { it.displayName.value == "甲" }.unread.value)
        assertEquals(false, rows.first { it.displayName.value == "乙" }.unread.value)
        assertEquals(EvidenceStatus.UNKNOWN, rows.first { it.displayName.value == "甲" }.online.status)
    }

    @Test fun unreadInvitationMarkerTextDoesNotImplyOnlineStatus() {
        val metadata = MomoSayHiProfileParser.parseListMetadata(listOf("小雨", "红点", "0.8km", "刚刚"))
        assertEquals(EvidenceStatus.UNKNOWN, metadata.online.status)
        assertNull(metadata.online.value)
    }

    @Test fun doesNotTreatRowsWithoutExplicitNameAsContacts() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 5),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/section_header", children = 1),
                node(listOf(0, 0, 0), "com.immomo.momo:id/hilist_tv_msgcontent", "分组标题")
            ),
            visitedNodeCount = 4,
            truncated = false
        )
        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        assertTrue(result.listRows.isEmpty())
    }

    @Test fun rejectsActivityWithoutItsObservedContainer() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 2),
            nodes = listOf(node(emptyList()), node(listOf(0), text = "30岁")),
            visitedNodeCount = 2,
            truncated = false
        )
        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        assertEquals(MomoSayHiPageKind.UNKNOWN, result.kind)
        assertFalse(result.containerFound)
        assertNull(result.profile)
    }

    @Test fun keepsUnknownFieldsUnknownAndReportsTruncation() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 3),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, children = 1),
                node(listOf(0, 0), text = "30岁")
            ),
            visitedNodeCount = 1_200,
            truncated = true
        )
        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        assertTrue(result.truncated)
        assertFalse(result.containerFound)
        assertNull(result.profile)
        assertTrue(result.diagnostic.contains("节点树采集不完整"))
    }
    @Test fun rejectsMultipleVisibleProfileContainersWithoutMixingTheirFields() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 9),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, children = 1),
                node(listOf(0, 0), text = "甲 30岁"),
                node(listOf(1), MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, children = 1),
                node(listOf(1, 0), text = "乙 28岁")
            ),
            visitedNodeCount = 5,
            truncated = false
        )

        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))

        assertEquals(MomoSayHiPageKind.GREETING_PROFILE_CARD, result.kind)
        assertFalse(result.containerFound)
        assertTrue(result.containerAmbiguous)
        assertTrue(result.containerMatchCount >= 2)
        assertNull(result.profile)
        assertTrue(result.diagnostic.contains("多个容器候选"))
    }

    @Test fun refusesPageClassificationWhenVisibleQueryBudgetIsExhausted() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 10),
            nodes = listOf(
                node(emptyList()),
                node(listOf(0), MomoSayHiPageContract.CARD_CONTENT_VIEW_ID, children = 1),
                node(listOf(0, 0), text = "甲 30岁")
            ),
            visitedNodeCount = 3,
            truncated = false
        )

        val result = MomoSayHiAccessibilityInspector(
            queryBudget = NodeQueryBudget(maxVisitedNodes = 1, timeoutMs = 1_000L)
        ).inspect(tree)

        assertEquals(MomoSayHiPageKind.UNKNOWN, result.kind)
        assertTrue(result.queryBudgetExceeded)
        assertFalse(result.containerFound)
        assertNull(result.profile)
    }

    @Test fun resolvesForegroundProfileFromStackedCardsUsingUniqueLargestPopulatedCard() {
        fun cardNode(
            path: List<Int>, className: String, id: String = "", text: String? = null,
            bounds: ScreenRect = ScreenRect(0, 0, 100, 40), children: Int = 0
        ) = AccessibilityNodeSnapshot(
            path = NodePath(path), className = className, viewId = id,
            text = text, contentDescription = null, bounds = bounds,
            visible = true, enabled = true, checkable = false, checked = false,
            clickable = false, longClickable = false, scrollable = false,
            editable = false, selected = false, focused = false, childCount = children,
            packageName = MomoAppDescriptor.PACKAGE_NAME
        )

        val cardId = MomoSayHiPageContract.CARD_CONTENT_VIEW_ID
        val hostId = "com.immomo.momo:id/slidecard_layout"
        val nodes = mutableListOf(cardNode(emptyList(), "android.widget.FrameLayout"))
        val cards = listOf(
            Triple(0, ScreenRect(63, 267, 657, 1164), 3),
            Triple(1, ScreenRect(47, 238, 674, 1184), 3),
            Triple(2, ScreenRect(30, 208, 690, 1204), 3),
            Triple(3, ScreenRect(63, 267, 657, 1164), 0)
        )
        cards.forEach { (slot, bounds, childCount) ->
            nodes += cardNode(listOf(slot), "android.widget.FrameLayout", hostId,
                bounds = ScreenRect(bounds.left, bounds.top - 20, bounds.right, bounds.bottom + 194), children = 6)
            nodes += cardNode(listOf(slot, 0), "androidx.recyclerview.widget.RecyclerView", cardId,
                bounds = bounds, children = childCount)
            if (slot == 2) {
                nodes += cardNode(listOf(slot, 0, 0), "android.widget.TextView", text = "前景资料卡")
                nodes += cardNode(listOf(slot, 0, 1), "android.widget.TextView", text = "30岁")
                nodes += cardNode(listOf(slot, 0, 2), "android.widget.TextView", text = "在线")
            }
        }
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 5),
            nodes = nodes, visitedNodeCount = nodes.size, truncated = false
        )

        val result = MomoSayHiAccessibilityInspector().inspect(tree)

        assertTrue(result.containerFound)
        assertTrue(result.containerAmbiguous)
        assertEquals(4, result.containerMatchCount)
        assertEquals("前景资料卡", result.profile?.displayName?.value)
        assertEquals(30, result.profile?.age?.value)
        assertTrue(result.diagnostic.contains("唯一最大卡片边界"))
    }

    @Test fun refusesStackedCardsWhenLargestBoundsAreTied() {
        fun item(path: List<Int>, className: String, id: String = "", bounds: ScreenRect,
                 children: Int = 0) = AccessibilityNodeSnapshot(
            path = NodePath(path), className = className, viewId = id,
            text = null, contentDescription = null, bounds = bounds, visible = true,
            enabled = true, checkable = false, checked = false, clickable = false,
            longClickable = false, scrollable = false, editable = false, selected = false,
            focused = false, childCount = children,
            packageName = MomoAppDescriptor.PACKAGE_NAME
        )
        val id = MomoSayHiPageContract.CARD_CONTENT_VIEW_ID
        val host = "com.immomo.momo:id/slidecard_layout"
        val rect = ScreenRect(20, 200, 700, 1200)
        val nodes = mutableListOf(item(emptyList(), "android.widget.FrameLayout", bounds = ScreenRect(0,0,720,1600)))
        repeat(2) { slot ->
            nodes += item(listOf(slot), "android.widget.FrameLayout", host, rect, 1)
            nodes += item(listOf(slot, 0), "androidx.recyclerview.widget.RecyclerView", id, rect, 1)
            nodes += item(listOf(slot, 0, 0), "android.widget.TextView", bounds = rect)
        }
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.CARD_ACTIVITY, 6),
            nodes = nodes, visitedNodeCount = nodes.size, truncated = false
        )
        val result = MomoSayHiAccessibilityInspector().inspect(withReceivedListShell(tree))
        assertFalse(result.containerFound)
        assertNull(result.profile)
        assertTrue(result.diagnostic.contains("无法唯一确认前景资料卡"))
    }

}
