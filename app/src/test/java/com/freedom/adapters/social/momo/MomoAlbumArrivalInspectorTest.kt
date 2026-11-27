package com.freedom.adapters.social.momo

import org.junit.Assert.*
import org.junit.Test

class MomoAlbumArrivalInspectorTest {
    private val anchors = mapOf("tv_navigation" to 1, "tab_title" to 1, "finish" to 1, "rl_recycler" to 1)
    @Test fun requiresCompleteUniqueAlbumShell() {
        assertTrue(MomoAlbumArrivalInspector.matches("com.immomo.momo", anchors))
        assertFalse(MomoAlbumArrivalInspector.matches("com.immomo.momo", anchors - "finish"))
        assertFalse(MomoAlbumArrivalInspector.matches("com.immomo.momo", anchors + ("tab_title" to 2)))
        assertFalse(MomoAlbumArrivalInspector.matches("other", anchors))
    }
}
