package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.runtime.RunMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoAlbumGesturePermitTest {
    private val album = WindowRef("com.immomo.momo", "AlbumActivity", 10L, 3)

    @Test
    fun onlyExactCommandCanUseThePermit() {
        val permit = MomoAlbumGesturePermit("media-1", album, { album }, { true }, "selector-1")
        assertTrue(permit.matchesCommand("selector-1"))
        assertFalse(permit.matchesCommand("media-1"))
        assertFalse(permit.matchesCommand("other-gesture"))
        assertFalse(permit.matchesCommand(null))
    }

    @Test
    fun staleWindowOwnerOrModeCannotDispatch() {
        var current = album
        var owns = true
        val permit = MomoAlbumGesturePermit("media-1", album, { current }, { owns }, "selector-1")
        fun allowed() = permit.permits(RunMode.AI_REPLY, album.packageName, album.windowId, album.activityClass)
        assertTrue(allowed())
        assertFalse(permit.permits(RunMode.ACCOUNT_BROWSE, album.packageName, album.windowId, album.activityClass))
        assertFalse(permit.permits(RunMode.AI_REPLY, album.packageName, 4, album.activityClass))
        assertFalse(permit.permits(RunMode.AI_REPLY, album.packageName, album.windowId, "OtherActivity"))
        current = album.copy(generation = 11L)
        assertFalse(allowed())
        current = album
        owns = false
        assertFalse(allowed())
    }
}
