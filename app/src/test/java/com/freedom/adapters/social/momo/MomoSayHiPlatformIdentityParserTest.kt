package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.AccessibilityNodeSnapshot
import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.ScreenRect
import org.junit.Assert.*
import org.junit.Test

class MomoSayHiPlatformIdentityParserTest {
    private fun node(id: String = "", text: String?, visible: Boolean = true) =
        AccessibilityNodeSnapshot(
            path = NodePath(emptyList()), className = "android.widget.TextView", viewId = id,
            text = text, contentDescription = null, bounds = ScreenRect(0, 0, 200, 40),
            visible = visible, enabled = true, checkable = false, checked = false,
            clickable = false, longClickable = false, scrollable = false, editable = false,
            selected = false, focused = false, childCount = 0
        )

    @Test fun onlyExplicitlyLabelledMomoIdIsAccepted() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(node(text = "小雨"), node(text = "陌陌号：momo_123456"), node(text = "26岁"))
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.CONFIRMED, result.status)
        assertEquals("momo_123456", result.value)
    }

    @Test fun conflictingExplicitIdsAreAmbiguous() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(node(text = "陌陌号：10001"), node(text = "用户ID: 10002"))
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.AMBIGUOUS, result.status)
        assertNull(result.value)
        assertEquals(2, result.candidateCount)
    }

    @Test fun arbitraryNumbersAndDisplayNamesAreNotUniqueIdentityEvidence() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(node(text = "小雨"), node(text = "26岁"), node(text = "0.8km"), node(text = "10001"))
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.MISSING, result.status)
        assertNull(result.value)
    }

    @Test fun adjacentValueIsAcceptedOnlyAfterExplicitMomoIdLabel() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(
                node(text = "陌陌号："),
                node(text = "momo_7654321"),
                node(text = "26岁")
            ).mapIndexed { index, item -> item.copy(path = NodePath(listOf(index))) }
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.CONFIRMED, result.status)
        assertEquals("momo_7654321", result.value)
    }

    @Test fun adjacentNumberWithoutExplicitLabelIsNotAnIdentity() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(node(text = "26岁"), node(text = "7654321"))
                .mapIndexed { index, item -> item.copy(path = NodePath(listOf(index))) }
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.MISSING, result.status)
    }

    private fun structuralNode(
        path: List<Int>,
        className: String,
        id: String = "",
        text: String? = null,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        childCount: Int = 0,
        visible: Boolean = true
    ) = AccessibilityNodeSnapshot(
        path = NodePath(path), className = className, viewId = id,
        text = text, contentDescription = null, bounds = ScreenRect(0, 0, 240, 48),
        visible = visible, enabled = true, checkable = false, checked = false,
        clickable = clickable, longClickable = longClickable, scrollable = false,
        editable = false, selected = false, focused = false, childCount = childCount
    )

    @Test fun numericNameTextViewIsAcceptedOnlyInObservedMomoIdRowStructure() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(
                structuralNode(listOf(0), "android.widget.LinearLayout", clickable = true,
                    longClickable = true, childCount = 2),
                structuralNode(listOf(0, 0), "android.widget.ImageView", "com.immomo.momo:id/icon"),
                structuralNode(listOf(0, 1), "android.widget.TextView", "com.immomo.momo:id/name", "701402902")
            )
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.CONFIRMED, result.status)
        assertEquals("701402902", result.value)
        assertEquals(1, result.candidateCount)
        assertTrue(result.source.startsWith("MOMO_PROFILE_ID_EVIDENCE:"))
    }

    @Test fun numericNameTextViewWithoutClickableIconParentIsNotAnIdentity() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(
                structuralNode(listOf(0), "android.widget.LinearLayout", clickable = true,
                    longClickable = false, childCount = 2),
                structuralNode(listOf(0, 0), "android.widget.ImageView", "com.immomo.momo:id/icon"),
                structuralNode(listOf(0, 1), "android.widget.TextView", "com.immomo.momo:id/name", "701402902")
            )
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.MISSING, result.status)
    }

    @Test fun numericNameTextViewWithoutIconSiblingIsNotAnIdentity() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(
                structuralNode(listOf(0), "android.widget.LinearLayout", clickable = true,
                    longClickable = true, childCount = 2),
                structuralNode(listOf(0, 1), "android.widget.TextView", "com.immomo.momo:id/name", "701402902")
            )
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.MISSING, result.status)
    }

    @Test fun arbitraryPageNumbersRemainUntrustedWithoutTheNameIconRowStructure() {
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(
                node("com.immomo.momo:id/name", "175"),
                node(text = "701402902")
            )
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.MISSING, result.status)
    }


    @Test fun capturedProfileIdRowWithDeepPathIsRecognizedAlongsideOtherNameFields() {
        val parentPath = listOf(0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 5)
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(
                // Other profile metadata rows also use id=name, but their containers are not
                // the observed clickable + long-clickable Momo ID row.
                structuralNode(listOf(8), "android.widget.LinearLayout", clickable = false,
                    longClickable = false, childCount = 2),
                structuralNode(listOf(8, 0), "android.widget.ImageView", "com.immomo.momo:id/icon"),
                structuralNode(listOf(8, 1), "android.widget.TextView", "com.immomo.momo:id/name", "身高175cm"),
                structuralNode(parentPath, "android.widget.LinearLayout", clickable = true,
                    longClickable = true, childCount = 2),
                structuralNode(parentPath + 0, "android.widget.ImageView", "com.immomo.momo:id/icon"),
                structuralNode(parentPath + 1, "android.widget.TextView", "com.immomo.momo:id/name", "1171000256")
            )
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.CONFIRMED, result.status)
        assertEquals("1171000256", result.value)
        assertEquals(1, result.candidateCount)
    }

    @Test fun observedProfileLayoutRecognizesConfirmedMomoPlatformId830936401() {
        val idRowPath = listOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 6)
        val result = MomoSayHiPlatformIdentityParser.parse(
            listOf(
                // Demographic rows also use TextView[id=name], but lack the verified ID-row
                // parent contract and therefore must not become platform identity candidates.
                structuralNode(idRowPath.dropLast(1), "android.widget.LinearLayout", clickable = true,
                    longClickable = true, childCount = 2),
                structuralNode(idRowPath.dropLast(1) + 0, "android.widget.ImageView", "com.immomo.momo:id/icon"),
                structuralNode(idRowPath, "android.widget.TextView", "com.immomo.momo:id/name", "830936401"),
                structuralNode(listOf(8, 0), "android.widget.TextView", "com.immomo.momo:id/name", "身高176cm"),
                structuralNode(listOf(8, 1), "android.widget.TextView", "com.immomo.momo:id/name", "Lv29")
            )
        )

        assertEquals(MomoSayHiPlatformIdentityStatus.CONFIRMED, result.status)
        assertEquals("830936401", result.value)
        assertEquals(1, result.candidateCount)
    }

    @Test fun explicitIdResourceViewIsAcceptedButInvisibleNodeIsIgnored() {
        val visible = MomoSayHiPlatformIdentityParser.parse(
            listOf(node("com.immomo.momo:id/tv_user_id", "abc12345"))
        )
        val invisible = MomoSayHiPlatformIdentityParser.parse(
            listOf(node("com.immomo.momo:id/tv_user_id", "abc12345", visible = false))
        )
        assertEquals(MomoSayHiPlatformIdentityStatus.CONFIRMED, visible.status)
        assertEquals("abc12345", visible.value)
        assertEquals(MomoSayHiPlatformIdentityStatus.MISSING, invisible.status)
    }
}
