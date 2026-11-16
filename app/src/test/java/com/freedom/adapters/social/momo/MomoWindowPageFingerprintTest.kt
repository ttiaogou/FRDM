package com.freedom.adapters.social.momo

import com.freedom.core.accessibility.api.WindowRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomoWindowPageFingerprintTest {
    @Test fun activityClassIsDiagnosticAndNotPartOfPageIdentity() {
        val first = WindowRef("com.immomo.momo", "com.immomo.momo.FirstActivity", 7L, 21)
        val second = WindowRef("com.immomo.momo", "com.immomo.momo.SecondActivity", 7L, 21)
        assertEquals(
            MomoWindowPageFingerprint.create(first, "com.immomo.momo", "DYNAMIC_FEED", "stable-shell-v1"),
            MomoWindowPageFingerprint.create(second, "com.immomo.momo", "DYNAMIC_FEED", "stable-shell-v1")
        )
    }

    @Test fun windowIdGenerationPageKindAndStructureRemainIdentityBound() {
        val window = WindowRef("com.immomo.momo", "A", 7L, 21)
        val key = MomoWindowPageFingerprint.create(window, "com.immomo.momo", "DYNAMIC_FEED", "shell-v1")
        assertNotEquals(key, MomoWindowPageFingerprint.create(window.copy(windowId = 22), "com.immomo.momo", "DYNAMIC_FEED", "shell-v1"))
        assertNotEquals(key, MomoWindowPageFingerprint.create(window.copy(generation = 8L), "com.immomo.momo", "DYNAMIC_FEED", "shell-v1"))
        assertNotEquals(key, MomoWindowPageFingerprint.create(window, "com.immomo.momo", "RECEIVED_GREETINGS_LIST", "shell-v1"))
        assertNotEquals(key, MomoWindowPageFingerprint.create(window, "com.immomo.momo", "DYNAMIC_FEED", "shell-v2"))
    }

    @Test fun wrongPackageOrMissingWindowIdCannotCreateActionablePageKey() {
        val window = WindowRef("com.immomo.momo", "A", 7L, 21)
        assertNull(MomoWindowPageFingerprint.create(window, "com.example", "DYNAMIC_FEED", "shell-v1"))
        assertNull(MomoWindowPageFingerprint.create(window.copy(windowId = -1), "com.immomo.momo", "DYNAMIC_FEED", "shell-v1"))
        assertNull(MomoWindowPageFingerprint.create(window, "com.immomo.momo", "", "shell-v1"))
        assertNull(MomoWindowPageFingerprint.create(window, "com.immomo.momo", "DYNAMIC_FEED", ""))
    }
}
