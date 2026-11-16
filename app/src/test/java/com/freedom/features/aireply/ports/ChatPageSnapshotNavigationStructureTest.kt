package com.freedom.features.aireply.ports

import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatPageSnapshotNavigationStructureTest {
    @Test
    fun volatileListRowChangesDoNotChangeNavigationStructureKey() {
        val before = snapshot(
            listOf(
                node(path = "0", insideList = false, bounds = ScreenRect(0, 0, 720, 1600)),
                node(path = "0/2", insideList = true, bounds = ScreenRect(0, 200, 720, 340), textLength = 8)
            )
        )
        val after = snapshot(
            listOf(
                node(path = "0", insideList = false, bounds = ScreenRect(0, 0, 720, 1600)),
                node(path = "0/2", insideList = true, bounds = ScreenRect(0, 200, 720, 360), textLength = 42)
            )
        )

        assertEquals(before.navigationStructureKey(), after.navigationStructureKey())
    }

    @Test
    fun stableNavigationNodeGeometryChangeChangesStructureKey() {
        val before = snapshot(listOf(node("0/1", false, ScreenRect(0, 1400, 720, 1500))))
        val after = snapshot(listOf(node("0/1", false, ScreenRect(0, 1380, 720, 1500))))

        assertNotEquals(before.navigationStructureKey(), after.navigationStructureKey())
    }

    private fun snapshot(evidence: List<ChatPageNodeEvidence>) = ChatPageSnapshot(
        packageName = "com.immomo.momo",
        activityClass = "com.immomo.momo.maintab.MaintabActivity",
        pageKind = ChatPageKind.CHAT_LIST,
        scannedNodeCount = evidence.size,
        editableInputCount = 0,
        messageListCount = 1,
        sendActionCount = 0,
        messageRelatedViewIdCount = 1,
        hasChatTab = true,
        messageListTarget = null,
        inputFieldTarget = null,
        structureEvidence = evidence,
        chatListViewport = ScreenRect(0, 120, 720, 1450),
        screenBounds = ScreenRect(0, 0, 720, 1600)
    )

    private fun node(
        path: String,
        insideList: Boolean,
        bounds: ScreenRect,
        textLength: Int = 0
    ) = ChatPageNodeEvidence(
        path = path,
        className = if (insideList) "android.widget.TextView" else "androidx.recyclerview.widget.RecyclerView",
        viewId = if (insideList) "row_title" else "message_list",
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        childCount = if (insideList) 0 else 3,
        isEditable = false,
        isScrollable = !insideList,
        isClickable = insideList,
        hasText = textLength > 0,
        insideListCandidate = insideList,
        depth = if (insideList) 2 else 1,
        parentPath = if (insideList) "0/1" else "0",
        isVisibleToUser = true,
        isListProbeRoot = !insideList,
        textLength = textLength,
        boundsValid = true
    )
}
