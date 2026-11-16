package com.freedom.adapters.accessibility.android

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.core.accessibility.events.AccessibilitySourceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityEventFormatterTest {
    @Test
    fun missingSourceKeepsTheExistingFallbackText() {
        assertEquals(
            "控件来源节点=系统未提供；控件坐标=系统未提供",
            AccessibilityEventFormatter.sourceDetails(null)
        )
    }

    @Test
    fun emptyBoundsAndMissingTraitsKeepTheExistingDetailsText() {
        val source = source(bounds = ScreenRect(5, 8, 5, 18))
        assertEquals(
            "控件坐标范围=系统未提供；注意：这是控件边界，不是手指触摸坐标",
            AccessibilityEventFormatter.sourceDetails(source)
        )
    }

    @Test
    fun sourceBoundsViewIdAndTraitsKeepTheirExistingOrderAndWording() {
        val source = source(
            viewId = "com.immomo.momo:id/send",
            bounds = ScreenRect(10, 20, 110, 70),
            clickable = true,
            longClickable = true,
            scrollable = true,
            editable = true
        )
        assertEquals(
            "控件坐标范围=[10,20][110,70]；控件 ViewId=com.immomo.momo:id/send；" +
                "控件能力=可点击、可长按、可滚动、可编辑；注意：这是控件边界，不是手指触摸坐标",
            AccessibilityEventFormatter.sourceDetails(source)
        )
    }

    private fun source(
        viewId: String = "",
        bounds: ScreenRect? = null,
        clickable: Boolean = false,
        longClickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false
    ) = AccessibilitySourceSnapshot(
        className = "android.view.View",
        viewId = viewId,
        text = null,
        contentDescription = null,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        scrollable = scrollable,
        editable = editable
    )
}
