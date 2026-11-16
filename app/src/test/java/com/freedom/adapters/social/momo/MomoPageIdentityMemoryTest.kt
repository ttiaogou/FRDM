package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.*
import org.junit.Test

class MomoPageIdentityMemoryTest {
    @Test fun retainsOnlySameUnderlyingWindowAndGenerationWhileDialogWindowExists() {
        val window = WindowRef("com.immomo.momo", "Main", 4L, 12)
        val memory = MomoPageIdentityMemory()
        memory.remember(window, "DYNAMIC_FEED", "feed-v2", 1_000L)
        assertEquals("DYNAMIC_FEED", memory.retainForDialog(window, 13, 2_000L)?.pageKind)
        assertNull(memory.retainForDialog(window, 12, 2_000L))
        assertNull(memory.retainForDialog(window.copy(windowId = 15), 13, 2_000L))
        assertNull(memory.retainForDialog(window.copy(generation = 5L), 13, 2_000L))
    }

    @Test fun sameWindowOverlayCanRetainRecentIdentityButNeverAcrossGeneration() {
        val window = WindowRef("com.immomo.momo", "Main", 4L, 12)
        val memory = MomoPageIdentityMemory()
        memory.remember(window, "DYNAMIC_FEED", "feed-v2", 1_000L)
        assertEquals("feed-v2", memory.retainForObstruction(window, 1_500L)?.fingerprint)
        assertNull(memory.retainForObstruction(window.copy(generation = 5L), 1_500L))
    }

    @Test fun doesNotRetainAfterExpiryOrWhenTimeMovesBackwards() {
        val window = WindowRef("com.immomo.momo", "Main", 4L, 12)
        val memory = MomoPageIdentityMemory(maxRetentionMs = 2_000L)
        memory.remember(window, "MESSAGE_WORKSPACE", "msg-v2", 1_000L)
        assertNull(memory.retainForDialog(window, 13, 3_001L))
        assertNull(memory.retainForDialog(window, 13, 900L))
    }
}
