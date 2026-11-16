package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoMessageTabAnchorResolverTest {
    @Test
    fun resolvesMessageBubbleByBottomNavigationParentRatherThanGlobalResourceIdUniqueness() {
        val resolved = MomoMessageTabAnchorResolver.resolve(
            nodes = listOf(
                node("0/0/2", "com.immomo.momo:id/maintab_bottom_container"),
                node("0/0/2/0", "com.immomo.momo:id/tabwidget", selected = false),
                node("0/0/2/0/0", "com.immomo.momo:id/maintab_layout_nearby", clickable = true),
                node("0/0/2/0/0/0/0", "com.immomo.momo:id/fl_bubble_container"),
                node("0/0/2/0/1", "com.immomo.momo:id/maintab_layout_live", clickable = true),
                node("0/0/2/0/1/0/0", "com.immomo.momo:id/fl_bubble_container"),
                node("0/0/2/0/2", "com.immomo.momo:id/maintab_layout_chat", clickable = true, selected = true),
                node("0/0/2/0/2/0/0", "com.immomo.momo:id/fl_bubble_container", selected = true),
                node("0/0/2/0/4", "com.immomo.momo:id/maintab_layout_profile", clickable = true),
                node("0/0/2/0/4/0/0", "com.immomo.momo:id/fl_bubble_container")
            ),
            targetPackage = PACKAGE
        )

        assertEquals(listOf(0, 0, 2, 0, 2), resolved?.clickableTab?.path?.childIndices)
        assertEquals(listOf(0, 0, 2, 0, 2, 0, 0), resolved?.bubble?.path?.childIndices)
        assertTrue(resolved?.isSelected == true)
    }

    @Test
    fun resolvesUnselectedMessageTabAsTheNavigationTarget() {
        val resolved = MomoMessageTabAnchorResolver.resolve(
            nodes = listOf(
                node("0/2", "com.immomo.momo:id/maintab_bottom_container"),
                node("0/2/0", "com.immomo.momo:id/tabwidget"),
                node("0/2/0/2", "com.immomo.momo:id/maintab_layout_chat", clickable = true),
                node("0/2/0/2/0/0", "com.immomo.momo:id/fl_bubble_container")
            ),
            targetPackage = PACKAGE
        )

        assertFalse(resolved?.isSelected ?: true)
    }

    @Test
    fun rejectsMultipleBubbleAnchorsUnderTheSameChatTab() {
        val resolved = MomoMessageTabAnchorResolver.resolve(
            nodes = listOf(
                node("0/2", "com.immomo.momo:id/maintab_bottom_container"),
                node("0/2/0", "com.immomo.momo:id/tabwidget"),
                node("0/2/0/2", "com.immomo.momo:id/maintab_layout_chat", clickable = true),
                node("0/2/0/2/0/0", "com.immomo.momo:id/fl_bubble_container"),
                node("0/2/0/2/1/0", "com.immomo.momo:id/fl_bubble_container")
            ),
            targetPackage = PACKAGE
        )

        assertNull(resolved)
    }

    @Test
    fun rejectsMismatchedSelectedStateBetweenBubbleAndClickableTab() {
        val resolved = MomoMessageTabAnchorResolver.resolve(
            nodes = listOf(
                node("0/2", "com.immomo.momo:id/maintab_bottom_container"),
                node("0/2/0", "com.immomo.momo:id/tabwidget"),
                node("0/2/0/2", "com.immomo.momo:id/maintab_layout_chat", clickable = true, selected = true),
                node("0/2/0/2/0/0", "com.immomo.momo:id/fl_bubble_container", selected = false)
            ),
            targetPackage = PACKAGE
        )

        assertNull(resolved)
    }

    @Test
    fun confirmsPrivateMessageWorkAreaFromTopMessageTabAndSessionListRoute() {
        val nodes = listOf(
            workAreaNode("0", "appbar_id", className = "android.widget.RelativeLayout"),
            workAreaNode("0/0", "tablayout_id", className = "android.widget.HorizontalScrollView"),
            workAreaNode("0/0/0/0", "tab_title", text = "消息", selected = true, className = "android.widget.TextView"),
            workAreaNode("0/1", "session_global_search", clickable = false),
            workAreaNode("0/2", "action_jump_contact", clickable = false),
            workAreaNode("1", "pagertabcontent", className = "androidx.viewpager.widget.ViewPager"),
            workAreaNode("1/0", "session_list_page_layout"),
            workAreaNode("1/0/0", "session_list_inner_container"),
            workAreaNode("1/0/0/0", "recyclerview", scrollable = true, className = "androidx.recyclerview.widget.RecyclerView"),
            workAreaNode("2", "maintab_bottom_container"),
            workAreaNode("2/0", "tabwidget"),
            workAreaNode("2/0/0", "maintab_layout_chat", clickable = true, selected = true),
            workAreaNode("2/0/0/0/0", "fl_bubble_container", selected = true)
        )

        assertTrue(MomoMessageTabAnchorResolver.isMessageWorkArea(nodes, PACKAGE))
    }

    @Test
    fun topMessageTabAndSessionListRemainSufficientWhenBottomNavigationIsObscured() {
        val nodes = listOf(
            workAreaNode("0", "appbar_id"),
            workAreaNode("0/0", "tablayout_id"),
            workAreaNode("0/0/0", "tab_title", text = "消息", selected = true,
                className = "android.widget.TextView"),
            workAreaNode("1", "pagertabcontent"),
            workAreaNode("1/0", "session_list_page_layout"),
            workAreaNode("1/0/0", "session_list_inner_container"),
            workAreaNode("1/0/0/0", "recyclerview", scrollable = true,
                className = "androidx.recyclerview.widget.RecyclerView")
        )
        assertTrue(MomoMessageTabAnchorResolver.isMessageWorkArea(nodes, PACKAGE))
    }

    @Test
    fun rejectsMessageWorkAreaWhenAllIndependentRoutesFail() {
        val nodes = listOf(
            workAreaNode("0", "appbar_id", className = "android.widget.RelativeLayout"),
            workAreaNode("0/0", "tablayout_id"),
            workAreaNode("0/0/0/0", "tab_title", text = "消息", selected = false, className = "android.widget.TextView"),
            workAreaNode("0/1", "session_global_search", clickable = false),
            workAreaNode("0/2", "action_jump_contact", clickable = false),
            workAreaNode("1", "pagertabcontent"),
            workAreaNode("1/0", "session_list_page_layout"),
            workAreaNode("1/0/0", "session_list_inner_container"),
            workAreaNode("1/0/0/0", "recyclerview", scrollable = true, className = "androidx.recyclerview.widget.RecyclerView"),
            workAreaNode("2", "maintab_bottom_container"),
            workAreaNode("2/0", "tabwidget"),
            workAreaNode("2/0/0", "maintab_layout_chat", clickable = true, selected = false),
            workAreaNode("2/0/0/0/0", "fl_bubble_container", selected = false)
        )

        assertFalse(MomoMessageTabAnchorResolver.isMessageWorkArea(nodes, PACKAGE))
    }

    @Test
    fun bottomMessageTabAndSessionListFallbackWhenTopTabIsMissing() {
        val nodes = listOf(
            workAreaNode("1", "pagertabcontent"),
            workAreaNode("1/0", "session_list_page_layout"),
            workAreaNode("1/0/0", "session_list_inner_container"),
            workAreaNode("1/0/0/0", "recyclerview", scrollable = true,
                className = "androidx.recyclerview.widget.RecyclerView"),
            workAreaNode("2", "maintab_bottom_container"),
            workAreaNode("2/0", "tabwidget"),
            workAreaNode("2/0/0", "maintab_layout_chat", clickable = true, selected = true),
            workAreaNode("2/0/0/0/0", "fl_bubble_container", selected = true)
        )
        assertTrue(MomoMessageTabAnchorResolver.isMessageWorkArea(nodes, PACKAGE))
    }

    @Test
    fun appbarActionsAndSessionListFallbackWhenBothTabLabelsAreUnavailable() {
        val nodes = listOf(
            workAreaNode("0", "appbar_id"),
            workAreaNode("0/1", "session_global_search", clickable = true),
            workAreaNode("0/2", "action_jump_contact", clickable = true),
            workAreaNode("1", "pagertabcontent"),
            workAreaNode("1/0", "session_list_page_layout"),
            workAreaNode("1/0/0", "session_list_inner_container"),
            workAreaNode("1/0/0/0", "recyclerview", scrollable = true,
                className = "androidx.recyclerview.widget.RecyclerView")
        )
        assertTrue(MomoMessageTabAnchorResolver.isMessageWorkArea(nodes, PACKAGE))
    }

    private fun workAreaNode(
        path: String,
        id: String,
        text: String? = null,
        clickable: Boolean = false,
        selected: Boolean = false,
        scrollable: Boolean = false,
        className: String = "android.widget.FrameLayout"
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path.split('/').map(String::toInt)),
        className = className,
        viewId = "com.immomo.momo:id/$id",
        text = text,
        contentDescription = null,
        bounds = ScreenRect(0, 0, 720, 1600),
        visible = true,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = clickable,
        longClickable = false,
        scrollable = scrollable,
        editable = false,
        selected = selected,
        focused = false,
        childCount = 1,
        packageName = PACKAGE
    )

    private fun node(
        path: String,
        viewId: String,
        clickable: Boolean = false,
        selected: Boolean = false
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path.split('/').map(String::toInt)),
        className = if (viewId.endsWith("fl_bubble_container")) "android.widget.RelativeLayout" else "android.widget.FrameLayout",
        viewId = viewId,
        text = null,
        contentDescription = null,
        bounds = ScreenRect(0, 100, 144, 214),
        visible = true,
        enabled = true,
        checkable = false,
        checked = false,
        clickable = clickable,
        longClickable = false,
        scrollable = false,
        editable = false,
        selected = selected,
        focused = false,
        childCount = 1,
        packageName = PACKAGE
    )

    private companion object {
        const val PACKAGE = "com.immomo.momo"
    }
}
