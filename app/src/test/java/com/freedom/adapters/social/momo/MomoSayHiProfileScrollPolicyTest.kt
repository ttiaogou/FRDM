package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiProfileScrollPolicyTest {
    private fun node(
        path: List<Int>,
        className: String,
        viewId: String = "",
        text: String? = null,
        bounds: ScreenRect = ScreenRect(0, 1065, 720, 1600),
        visible: Boolean = true,
        scrollable: Boolean = false,
        childCount: Int = 1
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path),
        className = className,
        viewId = viewId,
        text = text,
        contentDescription = null,
        bounds = bounds,
        visible = visible,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = false,
        longClickable = false,
        scrollable = scrollable,
        editable = false,
        selected = false,
        focused = false,
        childCount = childCount
    )

    private fun observedProfileNodes(
        includeHeading: Boolean = true,
        includeRecyclerView: Boolean = true
    ): List<AccessibilityNodeSnapshot> = buildList {
        // Independent profile-page fingerprint group A.
        add(node(
            path = listOf(0), className = "android.widget.TextView",
            viewId = "com.immomo.momo:id/personal_profile_name",
            text = "苏苏", bounds = ScreenRect(30, 758, 130, 817), childCount = 0
        ))
        add(node(
            path = listOf(1), className = "android.widget.TextView",
            viewId = "com.immomo.momo:id/tv_multi_info",
            text = "顺德区 · 2小时前 · 33获赞 · 3粉丝",
            bounds = ScreenRect(30, 879, 690, 915), childCount = 0
        ))

        // Independent viewport fingerprint group B: ViewPager -> RecyclerView.
        add(node(
            path = listOf(2), className = "androidx.viewpager.widget.ViewPager",
            viewId = "com.immomo.momo:id/pagertabcontent",
            bounds = ScreenRect(0, 1065, 720, 1600), scrollable = true, childCount = 1
        ))
        if (includeRecyclerView) {
            add(node(
                path = listOf(2, 0),
                className = "androidx.recyclerview.widget.RecyclerView",
                bounds = ScreenRect(0, 1065, 720, 1600),
                scrollable = true,
                childCount = if (includeHeading) 1 else 0
            ))
            if (includeHeading) {
                add(node(
                    path = listOf(2, 0, 0), className = "android.widget.TextView",
                    text = "个人信息", bounds = ScreenRect(30, 1117, 158, 1160), childCount = 0
                ))
            }
        }
    }

    @Test fun pendingProfileScrollAcceptsFiveAnchorsWithoutDependingOnStaleActivityLabel() {
        val ids = listOf(
            "com.immomo.momo:id/root_layout",
            "com.immomo.momo:id/personal_profile_name",
            "com.immomo.momo:id/toolbar_id",
            "com.immomo.momo:id/tabs",
            "com.immomo.momo:id/pagertabcontent"
        )
        assertTrue(MomoSayHiProfileScrollPolicy.permitsPendingProfileScroll(
            mode = com.freedom.core.runtime.RunMode.AI_REPLY,
            pendingOnCurrentWindow = true,
            activeWindowId = 10,
            currentRootViewIds = ids
        ))
    }

    @Test fun profilePermitStillRequiresExactPendingWindowModeAndAllFiveAnchors() {
        val ids = setOf(
            "root_layout", "personal_profile_name", "toolbar_id", "tabs", "pagertabcontent"
        )
        val policy = MomoSayHiProfileScrollPolicy
        assertTrue(policy.matchesProfileSurface(ids))
        assertTrue(!policy.permitsPendingProfileScroll(
            com.freedom.core.runtime.RunMode.AI_REPLY, false, 10, ids
        ))
        assertTrue(!policy.permitsPendingProfileScroll(
            com.freedom.core.runtime.RunMode.ACCOUNT_BROWSE, true, 10, ids
        ))
        assertTrue(!policy.permitsPendingProfileScroll(
            com.freedom.core.runtime.RunMode.AI_REPLY, true, -1, ids
        ))
        assertTrue(!policy.permitsPendingProfileScroll(
            com.freedom.core.runtime.RunMode.AI_REPLY, true, 10, ids - "tabs"
        ))
    }

    @Test fun identitySwipePermitRequiresExactTransactionWindowAndModeWithoutProfileAnchors() {
        val policy = MomoSayHiProfileScrollPolicy
        val mode = com.freedom.core.runtime.RunMode.AI_REPLY
        assertTrue(policy.permitsIdentityProfileScroll(mode, true, 10, 10))
        assertTrue(!policy.permitsIdentityProfileScroll(mode, true, 10, 11))
        assertTrue(!policy.permitsIdentityProfileScroll(mode, false, 10, 10))
        assertTrue(!policy.permitsIdentityProfileScroll(mode, true, -1, -1))
        assertTrue(!policy.permitsIdentityProfileScroll(com.freedom.core.runtime.RunMode.ACCOUNT_BROWSE, true, 10, 10))
    }

    @Test fun identityViewportCanUseUniqueVerticalContentWithoutAnyProfileAnchors() {
        val vertical = node(listOf(0), "android.widget.ScrollView",
            bounds = ScreenRect(0, 478, 720, 1600), scrollable = true)
        val horizontal = node(listOf(1), "androidx.viewpager.widget.ViewPager",
            bounds = ScreenRect(0, 478, 720, 1600), scrollable = true)
        val decision = MomoSayHiProfileScrollPolicy.resolveIdentityViewport(listOf(vertical, horizontal), 720, 1554)
        assertEquals(ScreenRect(0, 478, 720, 1554), decision.viewport)
        assertEquals(listOf(0), decision.targetPath)
        assertNull(MomoSayHiProfileScrollPolicy.resolveIdentityViewport(listOf(horizontal), 720, 1554).viewport)
        assertNull(MomoSayHiProfileScrollPolicy.resolveIdentityViewport(
            listOf(vertical, vertical.copy(path = NodePath(listOf(2)))), 720, 1554
        ).viewport)
        assertNull(MomoSayHiProfileScrollPolicy.resolveIdentityViewport(
            listOf(vertical.copy(bounds = ScreenRect(0, 1500, 720, 1600))), 720, 1554
        ).viewport)
    }

    @Test fun selectsObservedProfileRecyclerViewAndClipsAccessibilityBoundsToDisplay() {
        val decision = MomoSayHiProfileScrollPolicy.resolveViewport(
            nodes = observedProfileNodes(),
            displayWidth = 720,
            displayHeight = 1554
        )
        assertEquals(ScreenRect(0, 1065, 720, 1554), decision.viewport)
        assertEquals(1, decision.candidateCount)
        assertEquals("PROFILE_VIEWPORT_FINGERPRINT_CONFIRMED", decision.reason)
        assertEquals(listOf(2, 0), decision.targetPath)
    }

    @Test fun usesOnlyTheUniqueRecyclerViewUnderTheProfilePagerWhenHeadingIsNotVisible() {
        val decision = MomoSayHiProfileScrollPolicy.resolveViewport(
            nodes = observedProfileNodes(includeHeading = false),
            displayWidth = 720,
            displayHeight = 1554
        )
        assertEquals(ScreenRect(0, 1065, 720, 1554), decision.viewport)
        assertEquals("PROFILE_VIEWPORT_FINGERPRINT_FALLBACK", decision.reason)
    }

    @Test fun doesNotRequireProfileMetaNodeForIdentityRecoveryScroll() {
        val nodes = observedProfileNodes().filterNot {
            it.viewId.endsWith("/tv_multi_info")
        }
        val decision = MomoSayHiProfileScrollPolicy.resolveViewport(nodes, 720, 1554)
        assertEquals(ScreenRect(0, 1065, 720, 1554), decision.viewport)
        assertEquals("PROFILE_VIEWPORT_FINGERPRINT_CONFIRMED", decision.reason)
    }

    @Test fun refusesTheOuterViewPagerWhenItsContentRecyclerViewIsMissing() {
        val decision = MomoSayHiProfileScrollPolicy.resolveViewport(
            observedProfileNodes(includeRecyclerView = false), 720, 1554
        )
        assertNull(decision.viewport)
        assertEquals("PROFILE_CONTENT_RECYCLERVIEW_NOT_FOUND", decision.reason)
    }

    @Test fun refusesAmbiguousNestedProfileRecyclerViews() {
        val nodes = observedProfileNodes() + node(
            path = listOf(2, 1),
            className = "androidx.recyclerview.widget.RecyclerView",
            bounds = ScreenRect(0, 1065, 720, 1554),
            scrollable = true
        )
        val withTwoAnchors = nodes + node(
            path = listOf(2, 1, 0),
            className = "android.widget.TextView",
            text = "个人信息",
            bounds = ScreenRect(30, 1117, 158, 1160),
            childCount = 0
        )
        val decision = MomoSayHiProfileScrollPolicy.resolveViewport(withTwoAnchors, 720, 1554)
        assertNull(decision.viewport)
        assertEquals("AMBIGUOUS_PROFILE_CONTENT_RECYCLERVIEWS", decision.reason)
    }

    @Test fun rejectsAClippedViewportThatIsTooShortToSwipeSafely() {
        val nodes = observedProfileNodes().map { node ->
            if (node.path.childIndices == listOf(2, 0)) {
                node.copy(bounds = ScreenRect(0, 1500, 720, 1600))
            } else node
        }
        val decision = MomoSayHiProfileScrollPolicy.resolveViewport(nodes, 720, 1554)
        assertNull(decision.viewport)
        assertEquals("PROFILE_CONTENT_RECYCLERVIEW_BOUNDS_INVALID", decision.reason)
    }

    @Test fun viewPagerAloneIsNotAcceptedAsTheContentViewport() {
        val nodes = observedProfileNodes(includeRecyclerView = false) + node(
            path = listOf(3),
            className = "android.widget.ScrollView",
            bounds = ScreenRect(0, 0, 720, 1554),
            scrollable = true
        )
        val decision = MomoSayHiProfileScrollPolicy.resolveViewport(nodes, 720, 1554)
        assertNull(decision.viewport)
        assertTrue(decision.reason == "PROFILE_CONTENT_RECYCLERVIEW_NOT_FOUND")
    }
}
