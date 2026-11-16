package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath

/**
 * Resolves Momo's message tab by its actual bottom-navigation hierarchy.
 * fl_bubble_container is repeated under several tabs, so global ID uniqueness is invalid.
 */
internal data class MomoMessageTabAnchor(
    val clickableTab: AccessibilityNodeSnapshot,
    val bubble: AccessibilityNodeSnapshot,
    val isSelected: Boolean
)

internal object MomoMessageTabAnchorResolver {
    private const val TAB_WIDGET_ID = "com.immomo.momo:id/tabwidget"
    private const val MESSAGE_TAB_ID = "com.immomo.momo:id/maintab_layout_chat"
    private const val BUBBLE_ID = "com.immomo.momo:id/fl_bubble_container"

    fun resolve(
        nodes: List<AccessibilityNodeSnapshot>,
        targetPackage: String
    ): MomoMessageTabAnchor? {
        val bottomContainers = nodes.filter { node ->
            node.viewId == "com.immomo.momo:id/maintab_bottom_container" &&
                node.packageName == targetPackage && node.visible && node.enabled && !node.bounds.isEmpty
        }
        if (bottomContainers.size != 1) return null
        val bottomContainerPath = bottomContainers.single().path
        val tabWidgets = nodes.filter { node ->
            node.viewId == TAB_WIDGET_ID && node.packageName == targetPackage &&
                node.visible && node.enabled && !node.bounds.isEmpty &&
                isDescendant(node.path, bottomContainerPath)
        }
        if (tabWidgets.size != 1) return null
        val tabWidgetPath = tabWidgets.single().path

        val messageTabs = nodes.filter { node ->
            node.viewId == MESSAGE_TAB_ID && node.packageName == targetPackage &&
                node.visible && node.enabled && node.clickable && !node.bounds.isEmpty &&
                isDirectChild(node.path, tabWidgetPath)
        }
        if (messageTabs.size != 1) return null
        val messageTab = messageTabs.single()

        val bubbles = nodes.filter { node ->
            node.viewId == BUBBLE_ID && node.packageName == targetPackage &&
                node.visible && node.enabled && !node.bounds.isEmpty &&
                isDescendant(node.path, messageTab.path)
        }
        if (bubbles.size != 1) return null
        val bubble = bubbles.single()

        // Transitional snapshots can briefly disagree; do not click from an ambiguous state.
        if (messageTab.selected != bubble.selected) return null
        return MomoMessageTabAnchor(
            clickableTab = messageTab,
            bubble = bubble,
            isSelected = messageTab.selected && bubble.selected
        )
    }

    /**
     * Confirms the actual private-message work area, not merely the persistent bottom tab.
     * Each path is self-contained and page-local: top Message tab + session list, selected bottom
     * Message tab + session list, or appbar Search/Contacts + session list. Paths are alternatives,
     * so a temporary banner covering one anchor does not invalidate another complete route.
     */
    fun isMessageWorkArea(
        nodes: List<AccessibilityNodeSnapshot>,
        targetPackage: String
    ): Boolean {
        val visible = nodes.filter {
            it.packageName == targetPackage && it.visible && it.enabled && !it.bounds.isEmpty
        }
        fun localId(node: AccessibilityNodeSnapshot): String =
            node.viewId.substringAfterLast(':').substringAfterLast('/')
        fun descendants(id: String, ancestor: AccessibilityNodeSnapshot): List<AccessibilityNodeSnapshot> =
            visible.filter { localId(it) == id && isDescendant(it.path, ancestor.path) }

        // Resolve the central session-list structure once. It is the stable workspace body;
        // individual session rows, names, previews and unread counts are deliberately ignored.
        val pager = visible.singleOrNull { localId(it) == "pagertabcontent" }
        val sessionPage = pager?.let { descendants("session_list_page_layout", it).singleOrNull() }
        val inner = sessionPage?.let { descendants("session_list_inner_container", it).singleOrNull() }
        val recycler = inner?.let {
            descendants("recyclerview", it).singleOrNull { node ->
                node.className.endsWith("RecyclerView") && node.scrollable &&
                    node.bounds.width > 0 && node.bounds.height > 0
            }
        }
        val hasSessionList = recycler != null
        if (!hasSessionList) return false

        val appbar = visible.singleOrNull { localId(it) == "appbar_id" }
        val tabLayout = appbar?.let { descendants("tablayout_id", it).singleOrNull() }
        val topMessageSelected = tabLayout?.let { tabs ->
            descendants("tab_title", tabs).any {
                it.className.endsWith("TextView") && it.text?.trim() == "消息" && it.selected
            }
        } == true

        // Alternative path A: the business sub-tab itself is selected and owns the session list.
        // Alternative path B: persistent bottom Message tab is selected and owns the session list;
        // this path survives a banner/dialog temporarily obscuring the top tab label.
        // Alternative path C: the appbar's Search + Contacts actions coexist with the session list;
        // this path does not depend on either tab's visible text/selected state.
        val bottomMessageSelected = resolve(nodes, targetPackage)?.isSelected == true
        val searchAndContacts = appbar != null &&
            descendants("session_global_search", appbar).any { it.clickable && it.visible } &&
            descendants("action_jump_contact", appbar).any { it.clickable && it.visible }

        return MomoAlternativeFingerprintPolicy.matchesAny(
            listOf(
                MomoFingerprintPath("TOP_MESSAGE_TAB_AND_SESSION_LIST", listOf(topMessageSelected, hasSessionList)),
                MomoFingerprintPath("BOTTOM_MESSAGE_TAB_AND_SESSION_LIST", listOf(bottomMessageSelected, hasSessionList)),
                MomoFingerprintPath("APPBAR_ACTIONS_AND_SESSION_LIST", listOf(searchAndContacts, hasSessionList))
            )
        )
    }

    private fun isDescendant(candidate: NodePath, ancestor: NodePath): Boolean {
        val child = candidate.childIndices
        val parent = ancestor.childIndices
        return child.size > parent.size && child.take(parent.size) == parent
    }

    private fun isDirectChild(candidate: NodePath, parent: NodePath): Boolean {
        val child = candidate.childIndices
        val parentIndices = parent.childIndices
        return child.size == parentIndices.size + 1 && child.take(parentIndices.size) == parentIndices
    }
}
