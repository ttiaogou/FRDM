package com.freedom.adapters.accessibility.android

import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedNavigationBackSourceTest {
    private val source = WindowRef("com.immomo.momo", "", 42L, 17)

    @Test
    fun verifiedIdentitySourceDoesNotRequireActivity() {
        assertTrue(matchesVerifiedBackSource(source, "", source, true))
        assertTrue(matchesVerifiedBackSource(source, "", source.copy(activityClass = "ProfileActivity"), true))
        assertFalse(matchesVerifiedBackSource(source, "", source, false))
    }

    @Test
    fun verifiedIdentitySourceStillRequiresExactLiveWindow() {
        assertFalse(matchesVerifiedBackSource(source, "", source.copy(packageName = "other"), true))
        assertFalse(matchesVerifiedBackSource(source, "", source.copy(windowId = 18), true))
        assertFalse(matchesVerifiedBackSource(source, "", source.copy(generation = 43L), true))
        assertFalse(matchesVerifiedBackSource(source.copy(windowId = -1), "", source.copy(windowId = -1), true))
        assertFalse(matchesVerifiedBackSource(source.copy(generation = -1L), "", source.copy(generation = -1L), true))
    }

    @Test
    fun ordinaryBackRetainsStrictActivityEvidence() {
        val chat = source.copy(activityClass = "ChatActivity")
        assertTrue(matchesVerifiedBackSource(chat, "ChatActivity", chat, false))
        assertFalse(matchesVerifiedBackSource(chat, "ChatActivity", source, false))
        assertFalse(matchesVerifiedBackSource(chat, "ChatActivity", chat.copy(activityClass = "OtherActivity"), false))
        assertFalse(matchesVerifiedBackSource(chat, "", chat, false))
    }
}
