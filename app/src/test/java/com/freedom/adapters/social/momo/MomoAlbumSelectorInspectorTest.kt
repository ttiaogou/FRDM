package com.freedom.adapters.social.momo

import org.junit.Assert.*
import org.junit.Test

class MomoAlbumSelectorInspectorTest {
    @Test fun requiresUniqueUsableSelector() {
        fun resolve(title: String = "相册", titles: Int = 1, arrows: Int = 1,
                    hierarchy: Boolean = true, usable: Boolean = true, right: Int = 420) =
            MomoAlbumSelectorInspector.resolve(title, titles, arrows, hierarchy, usable, 315, 887, right, 975)
        assertEquals("相册", resolve()?.title)
        assertEquals("Freedom", resolve(title = "Freedom")?.title)
        assertNull(resolve(title = ""))
        assertNull(resolve(titles = 2))
        assertNull(resolve(arrows = 0))
        assertNull(resolve(hierarchy = false))
        assertNull(resolve(usable = false))
        assertNull(resolve(right = 315))
    }
}
