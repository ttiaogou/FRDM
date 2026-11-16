package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoSayHiIdentityFingerprintPolicyTest {
    @Test
    fun contactIdHashIsStableAndNormalizesSurroundingWhitespace() {
        val first = MomoSayHiIdentityFingerprintPolicy.contactIdHash("platform-user-123")
        val second = MomoSayHiIdentityFingerprintPolicy.contactIdHash("  platform-user-123  ")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals("platform-user-123", first)
    }

    @Test
    fun differentContactIdsProduceDifferentHashes() {
        assertNotEquals(
            MomoSayHiIdentityFingerprintPolicy.contactIdHash("platform-user-123"),
            MomoSayHiIdentityFingerprintPolicy.contactIdHash("platform-user-124")
        )
    }
}
