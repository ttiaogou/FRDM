package com.freedom.features.aireply.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class R08PrivateViewportPolicyTest {
    @Test
    fun collectionPositionDecoderPreservesWindowAndRejectsInvalidRanges() {
        val window = "test:window|generation=4"
        assertEquals("collection-position:$window" to (3..7),
            ChatSessionManagementPolicy.collectionPositionFromKey("collection-position:$window:3:7"))
        for (key in listOf(null, "", "identity@0,0,10,10",
            "collection-position:$window:-1:7",
            "collection-position:$window:7:3",
            "collection-position:$window:x:7")) {
            assertNull(ChatSessionManagementPolicy.collectionPositionFromKey(key))
        }
    }

    @Test
    fun identityProjectionRetainsOrderAndDuplicatesWithoutPositionOrGeometry() {
        val keys = listOf("collection-position:window:1:2", "b@0,1,2,3",
            "a@0,4,2,6", "b@0,7,2,9", "@0,0,0,0")
        assertEquals(listOf("b", "a", "b"), ChatSessionManagementPolicy.orderedIdentityKeys(keys))
    }
}
