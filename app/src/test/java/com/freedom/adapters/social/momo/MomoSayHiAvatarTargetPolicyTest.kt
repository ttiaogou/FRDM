package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.AccessibilityTreeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.accessibility.query.NodeTreeIndex
import org.junit.Assert.*
import org.junit.Test

class MomoSayHiAvatarTargetPolicyTest {
    private fun node(
        path: List<Int>,
        id: String = "",
        bounds: ScreenRect,
        clickable: Boolean = false,
        text: String? = null,
        contentDescription: String? = null,
        children: Int = 0,
        className: String = "android.view.ViewGroup",
        packageName: String = MomoAppDescriptor.PACKAGE_NAME
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = className, viewId = id,
        text = text, contentDescription = contentDescription, bounds = bounds, visible = true,
        enabled = true, checkable = false, checked = false, clickable = clickable,
        longClickable = false, scrollable = false, editable = false, selected = false,
        focused = false, childCount = children, packageName = packageName
    )

    /**
     * The production inspector deliberately requires the confirmed page shell before extracting
     * rows. These tests focus on row tap targets, so add the minimum complete shell evidence while
     * preserving the row paths under test.
     */
    private fun confirmedReceivedGreetingsList(tree: AccessibilityTreeSnapshot): AccessibilityTreeSnapshot {
        val nodes = tree.nodes.map { current ->
            when {
                current.path.childIndices.isEmpty() -> current.copy(
                    viewId = "com.immomo.momo:id/layout_content",
                    childCount = maxOf(current.childCount, 2),
                    packageName = MomoAppDescriptor.PACKAGE_NAME
                )
                current.path.childIndices == listOf(0) &&
                    current.viewId == MomoSayHiPageContract.LIST_VIEW_ID -> current.copy(
                    className = "androidx.recyclerview.widget.RecyclerView",
                    enabled = true,
                    scrollable = true,
                    childCount = maxOf(current.childCount, 2),
                    packageName = MomoAppDescriptor.PACKAGE_NAME
                )
                else -> current.copy(packageName = MomoAppDescriptor.PACKAGE_NAME)
            }
        }
        // The production page fingerprint requires one of its two stable routes. Keep this
        // tap-policy fixture valid with the observed fixed section title instead of relying on
        // the older, weaker list-container-only shape.
        return tree.copy(nodes = nodes + listOf(
            node(
                listOf(0, 1), "com.immomo.momo:id/title",
                bounds = ScreenRect(24, 170, 220, 210), text = "全部招呼"
            ),
            node(
                listOf(1), "com.immomo.momo:id/appbar_id",
                bounds = ScreenRect(0, 0, 720, 150), children = 1
            ),
            node(
                listOf(1, 0), "com.immomo.momo:id/toolbar_id",
                bounds = ScreenRect(0, 0, 720, 150), children = 1
            )
        ))
    }

    @Test fun currentCardListIconUsesItsOwnImageBoundsWithoutClickingWholeRow() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 9),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/card_container", ScreenRect(0, 220, 720, 390), clickable = true, children = 2),
                node(listOf(0, 0, 0), "com.immomo.momo:id/icon", ScreenRect(24, 250, 144, 370)),
                node(listOf(0, 0, 1), "com.immomo.momo:id/tv_name", ScreenRect(170, 260, 360, 310), text = "目标用户")
            ),
            visitedNodeCount = 5,
            truncated = false
        )

        val target = MomoSayHiAvatarTargetPolicy.resolveAvatarTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0))
        )

        assertEquals(listOf(0, 0, 0), target?.path?.childIndices)
        assertEquals(ScreenRect(24, 250, 144, 370), target?.targetBounds)
        assertEquals(target?.targetBounds, target?.preferredBounds)
    }

    @Test fun avatarTargetUsesExactUserlistFaceControlAndKeepsRowTapSeparate() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 1),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 4),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/userlist_item_iv_face", ScreenRect(24, 210, 134, 320), clickable = true, children = 1),
                node(listOf(0, 0, 1, 0), "com.immomo.momo:id/img_avatar", ScreenRect(24, 210, 134, 320)),
                node(listOf(0, 0, 2), "com.immomo.momo:id/hilist_tv_msgcontent", ScreenRect(150, 260, 560, 300), text = "你好"),
                node(listOf(0, 0, 3), "com.immomo.momo:id/hilist_tv_red", ScreenRect(680, 210, 700, 230))
            ),
            visitedNodeCount = 8,
            truncated = false
        )

        val inspection = MomoSayHiAccessibilityInspector().inspect(
            confirmedReceivedGreetingsList(tree)
        )
        val row = inspection.listRows.single()

        assertEquals(listOf(0, 0, 1), row.avatarTapTarget?.path?.childIndices)
        assertEquals(ScreenRect(24, 210, 134, 320), row.avatarTapTarget?.preferredBounds)
        assertEquals(listOf(0, 0), row.rowTapTarget?.path?.childIndices)
        // The row remains the click target, but the recognized status marker is excluded
        // from the safe tap rectangle before a point is chosen.
        assertEquals(ScreenRect(0, 200, 680, 360), row.rowTapTarget?.targetBounds)
        assertEquals(row.rowTapTarget?.targetBounds, row.rowTapTarget?.preferredBounds)
        assertTrue(row.rowTapTarget!!.excludedBounds.contains(ScreenRect(680, 210, 700, 230)))
        assertTrue(overlaps(row.rowTapTarget!!.targetBounds, ScreenRect(24, 210, 134, 320)))
        assertNotEquals(row.avatarTapTarget?.path, row.rowTapTarget?.path)
    }

    @Test fun refusesToUseWholeClickableRowAsAvatarFallback() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 2),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 3),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/userlist_item_iv_face", ScreenRect(24, 210, 134, 320), children = 1),
                node(listOf(0, 0, 1, 0), "com.immomo.momo:id/img_avatar", ScreenRect(24, 210, 134, 320)),
                node(listOf(0, 0, 2), "com.immomo.momo:id/hilist_tv_red", ScreenRect(680, 210, 700, 230))
            ),
            visitedNodeCount = 7,
            truncated = false
        )

        val row = MomoSayHiAccessibilityInspector().inspect(
            confirmedReceivedGreetingsList(tree)
        ).listRows.single()
        assertNull(row.avatarTapTarget)
        assertNotNull(row.rowTapTarget)
    }


    @Test fun rowTapUsesWideSafeAreaAndExcludesOverlappingAuthenticationIcon() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 4),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 2),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/real_man_auth_icon", ScreenRect(310, 218, 350, 246), clickable = true)
            ),
            visitedNodeCount = 5,
            truncated = false
        )

        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
        )
        assertNotNull(target)
        val safeTarget = target ?: error("row safe target not resolved")
        assertNotEquals(listOf(0, 0, 0), safeTarget.path.childIndices)
        assertFalse(overlaps(safeTarget.targetBounds, ScreenRect(310, 218, 350, 246)))
        assertTrue(safeTarget.targetBounds.width >= 24 && safeTarget.targetBounds.height >= 20)
    }

    @Test fun rowTapUsesSafeRowAreaWhenIndependentControlDoesNotOverlapName() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 5),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 2),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/real_man_auth_icon", ScreenRect(370, 218, 410, 246), clickable = true)
            ),
            visitedNodeCount = 5,
            truncated = false
        )

        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
        )
        assertEquals(listOf(0, 0), target?.path?.childIndices)
        assertEquals(ScreenRect(0, 246, 720, 360), target?.targetBounds)
        assertEquals(target?.targetBounds, target?.preferredBounds)
    }

    @Test fun rowSafeAreaDoesNotDependOnNicknameBoundsBeingLarge() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 6),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 1),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 155, 215), text = "小")
            ),
            visitedNodeCount = 4,
            truncated = false
        )
        assertNotNull(
            MomoSayHiAvatarTargetPolicy.resolveRowTarget(
                NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
            )
        )
    }

    @Test fun rowSafeAreaExcludesAuthenticationDescriptionControl() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 7),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 2),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨"),
                node(listOf(0, 0, 1), "", ScreenRect(310, 218, 350, 246), contentDescription = "实名认证", clickable = true)
            ),
            visitedNodeCount = 5,
            truncated = false
        )
        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
        )
        assertNotNull(target)
        val safeTarget = target ?: error("row safe target not resolved")
        assertFalse(overlaps(safeTarget.targetBounds, ScreenRect(310, 218, 350, 246)))
    }

    @Test fun sharedResolverSupportsMainInboxAvatarResourceId() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 3),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), "row", ScreenRect(0, 200, 720, 360), clickable = true, children = 2),
                node(listOf(0, 0), "com.immomo.momo:id/chatlist_item_iv_face", ScreenRect(20, 210, 130, 320), clickable = true),
                node(listOf(0, 1), "com.immomo.momo:id/chatlist_item_tv_name", ScreenRect(150, 220, 320, 260), text = "甲")
            ),
            visitedNodeCount = 4,
            truncated = false
        )
        val index = NodeTreeIndex(tree)
        val target = MomoSayHiAvatarTargetPolicy.resolveAvatarTarget(index, NodePath(listOf(0)))
        assertEquals(listOf(0, 0), target?.path?.childIndices)
    }

    @Test fun safeTapAreaExcludesVipWealthAndUnknownClickableWidgetBounds() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 8),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 4),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/svip_badge", ScreenRect(340, 210, 390, 245)),
                node(listOf(0, 0, 2), "com.immomo.momo:id/wealth_level_badge", ScreenRect(400, 210, 450, 245)),
                node(listOf(0, 0, 3), "com.immomo.momo:id/green_widget", ScreenRect(610, 215, 690, 285), clickable = true)
            ),
            visitedNodeCount = 7,
            truncated = false
        )
        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
        )
        assertNotNull(target)
        val safeTarget = target ?: error("row safe target not resolved")
        assertFalse(overlaps(safeTarget.targetBounds, ScreenRect(340, 210, 390, 245)))
        assertFalse(overlaps(safeTarget.targetBounds, ScreenRect(400, 210, 450, 245)))
        assertFalse(overlaps(safeTarget.targetBounds, ScreenRect(610, 215, 690, 285)))
    }

    @Test fun rowTapExcludesNonClickableBadgeStripAndGreenOnlineDotInsideAvatar() {
        val badgeBounds = ScreenRect(164, 240, 352, 272)
        val onlineDotBounds = ScreenRect(112, 300, 136, 324)
        val vipBounds = ScreenRect(400, 240, 462, 272)
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 13),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 3),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 235), text = "目标用户"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/avatar_container", ScreenRect(30, 210, 140, 320), children = 2),
                node(listOf(0, 0, 1, 0), "com.immomo.momo:id/userlist_item_iv_face", ScreenRect(30, 210, 140, 320), clickable = true),
                node(listOf(0, 0, 1, 1), "com.immomo.momo:id/img_online_status_dot", onlineDotBounds, className = "android.view.View"),
                node(listOf(0, 0, 2), "com.immomo.momo:id/userlist_item_layout_badgeContainer", badgeBounds, children = 1),
                node(listOf(0, 0, 2, 0), "com.immomo.momo:id/view_badge", badgeBounds, children = 2),
                node(listOf(0, 0, 2, 0, 0), "com.immomo.momo:id/wealth_level_badge", ScreenRect(238, 240, 352, 272), text = "财富等级1"),
                node(listOf(0, 0, 2, 0, 1), "com.immomo.momo:id/svip_badge", vipBounds)
            ),
            visitedNodeCount = 11,
            truncated = false
        )

        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
        )

        assertNotNull(target)
        val safeTarget = target ?: error("row safe target not resolved")
        assertEquals(listOf(0, 0), safeTarget.path.childIndices)
        assertFalse(overlaps(safeTarget.targetBounds, badgeBounds))
        assertFalse(overlaps(safeTarget.targetBounds, onlineDotBounds))
        assertFalse(overlaps(safeTarget.targetBounds, vipBounds))
        assertTrue(safeTarget.excludedBounds.contains(badgeBounds))
        assertTrue(safeTarget.excludedBounds.contains(onlineDotBounds))
        assertTrue(safeTarget.excludedBounds.contains(vipBounds))
    }

    @Test fun rejectsRowTapOnlyWhenProtectedComponentsLeaveNoMinimumSafeRectangle() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 14),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 2),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 235), text = "目标用户"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/userlist_item_layout_badgeContainer", ScreenRect(0, 200, 720, 360))
            ),
            visitedNodeCount = 5,
            truncated = false
        )

        assertNull(
            MomoSayHiAvatarTargetPolicy.resolveRowTarget(
                NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
            )
        )
    }

    @Test fun resolvesClickableContainerAncestorWhenRecognizedRowNodeIsNested() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 9),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 2),
                node(listOf(0, 0, 0), "com.immomo.momo:id/row_content", ScreenRect(0, 200, 720, 360), children = 1),
                node(listOf(0, 0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(160, 220, 340, 260), text = "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/userlist_item_iv_face", ScreenRect(24, 210, 134, 320))
            ),
            visitedNodeCount = 6,
            truncated = false
        )
        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0, 0)), NodePath(listOf(0, 0, 0, 0))
        )
        assertNotNull(target)
        val safeTarget = target ?: error("row safe target not resolved")
        assertEquals(listOf(0, 0), safeTarget.path.childIndices)
        assertTrue(overlaps(safeTarget.targetBounds, ScreenRect(24, 210, 134, 320)))
    }

    @Test fun secondPhaseRowTapKeepsIdlessImageInsideRecognizedAvatarContainerEligible() {
        val avatarBounds = ScreenRect(20, 210, 132, 322)
        val avatarImageBounds = ScreenRect(44, 234, 108, 298)
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 12),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 3),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 220, 330, 260), text = "小雨"),
                node(listOf(0, 0, 1), "com.immomo.momo:id/userlist_item_iv_face", avatarBounds, children = 1),
                node(listOf(0, 0, 1, 0), "", avatarImageBounds, className = "android.widget.ImageView"),
                node(listOf(0, 0, 2), "com.immomo.momo:id/green_widget", ScreenRect(620, 215, 690, 285), clickable = true)
            ),
            visitedNodeCount = 7,
            truncated = false
        )

        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
        )

        assertNotNull(target)
        val safeTarget = target ?: error("row safe target not resolved")
        assertTrue(overlaps(safeTarget.targetBounds, avatarImageBounds))
        assertFalse(overlaps(safeTarget.targetBounds, ScreenRect(620, 215, 690, 285)))
    }

    @Test fun refusesRowTapWhenNoClickableRowContainerExists() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 10),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), children = 1),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨")
            ),
            visitedNodeCount = 4,
            truncated = false
        )
        assertNull(
            MomoSayHiAvatarTargetPolicy.resolveRowTarget(
                NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
            )
        )
    }

    @Test fun compactImageShortcutWithoutIdOrClickableFlagIsExcluded() {
        val tree = AccessibilityTreeSnapshot(
            window = WindowRef("com.immomo.momo", MomoSayHiPageContract.LIST_ACTIVITY, 11),
            nodes = listOf(
                node(emptyList(), bounds = ScreenRect(0, 0, 720, 1600), children = 1),
                node(listOf(0), MomoSayHiPageContract.LIST_VIEW_ID, ScreenRect(0, 150, 720, 1500), children = 1),
                node(listOf(0, 0), "com.immomo.momo:id/user_row", ScreenRect(0, 200, 720, 360), clickable = true, children = 2),
                node(listOf(0, 0, 0), "com.immomo.momo:id/userlist_item_tv_name", ScreenRect(150, 210, 330, 250), text = "小雨"),
                node(listOf(0, 0, 1), "", ScreenRect(340, 215, 366, 241), className = "android.widget.ImageView")
            ),
            visitedNodeCount = 5,
            truncated = false
        )
        val target = MomoSayHiAvatarTargetPolicy.resolveRowTarget(
            NodeTreeIndex(tree), NodePath(listOf(0, 0)), NodePath(listOf(0, 0, 0))
        )
        assertNotNull(target)
        val safeTarget = target ?: error("row safe target not resolved")
        assertFalse(overlaps(safeTarget.targetBounds, ScreenRect(340, 215, 366, 241)))
    }

    private fun overlaps(first: ScreenRect, second: ScreenRect): Boolean =
        first.left < second.right && first.right > second.left &&
            first.top < second.bottom && first.bottom > second.top

}
