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
    fun freshTargetEvidenceIsExplicitAndFailsClosed() {
        var current = true
        val permit = MomoAlbumGesturePermit("media-1", album, { album }, { true }, "selector-1", { current })
        assertTrue(permit.confirmsCurrentTarget())
        current = false
        assertFalse(permit.confirmsCurrentTarget())
        assertFalse(MomoAlbumGesturePermit("media-1", album, { album }, { true }).confirmsCurrentTarget())
    }

    @Test
    fun directoryPermitBindsActualBlankActivityWindowAndFreshTarget() {
        val child = WindowRef(album.packageName, "", 11L, 4)
        var live = child
        var owns = true
        var target = true
        val permit = MomoAlbumGesturePermit("media-1", child, { live }, { owns },
            "freedom-name", { target }, transactionChildAction = true)
        assertTrue(permit.transactionChildAction)
        assertTrue(permit.matchesCommand("freedom-name"))
        assertFalse(permit.matchesCommand("selector"))
        assertTrue(permit.permits(RunMode.AI_REPLY, child.packageName, child.windowId, ""))
        assertTrue(permit.confirmsCurrentTarget())
        target = false
        assertFalse(permit.confirmsCurrentTarget())
        target = true
        live = child.copy(generation = 12L)
        assertFalse(permit.permits(RunMode.AI_REPLY, child.packageName, child.windowId, ""))
        live = child
        owns = false
        assertFalse(permit.permits(RunMode.AI_REPLY, child.packageName, child.windowId, ""))
        assertFalse(MomoAlbumGesturePermit("media-1", child, { child }, { true }).transactionChildAction)
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
