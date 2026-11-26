package com.freedom.application

import org.junit.Assert.*
import org.junit.Test

class TaskLaunchParametersTest {
    @Test fun defaultAndExplicitZeroAreMappedWithoutLoss() {
        assertEquals(60, TaskLaunchParameters().conversationFilterSettings().maxLastActiveMinutes)
        val settings = TaskLaunchParameters(maxDistanceKm = 8, minAge = 25, maxAge = 40,
            maxLastActiveMinutes = 0).conversationFilterSettings()
        assertEquals(0, settings.maxLastActiveMinutes)
        assertEquals(8, settings.maxDistanceKm)
        assertEquals(25, settings.minAge)
        assertEquals(40, settings.maxAge)
    }
    @Test fun imageThresholdRequiresPoolOnlyWhenEnabled() {
        val empty = TaskLaunchParameters()
        assertNull(empty.imageSendThresholdValidationError(0))
        assertEquals("请先导入至少一张图片", empty.imageSendThresholdValidationError(1))
        val configured = empty.copy(imageResourceUris = listOf("content://images/example"))
        assertNull(configured.imageSendThresholdValidationError(0))
        assertNull(configured.imageSendThresholdValidationError(1))
        assertNotNull(empty.copy(imageResourceUris = listOf(" ")).imageSendThresholdValidationError(1))
    }

    @Test fun imageThresholdMayEqualInterceptionButCannotExceedItWhenEnabled() {
        val configured = TaskLaunchParameters(messageInterceptionThreshold = 3,
            imageResourceUris = listOf("content://images/example"))
        assertNull(configured.imageSendThresholdValidationError(3))
        assertNotNull(configured.imageSendThresholdValidationError(4))
        assertNull(configured.copy(messageInterceptionThreshold = 0).imageSendThresholdValidationError(4))
    }
    @Test fun importedImagePoolPreservesOrderAndDeduplicates() {
        val original = TaskLaunchParameters(imageResourceUris = listOf("content://media/a"))
        val updated = original.withImportedImageResourceUris(listOf(
            "content://media/b", "content://media/a", "content://media/c", "content://media/b"
        ))
        assertEquals(listOf("content://media/a", "content://media/b", "content://media/c"),
            updated.imageResourceUris)
        assertTrue(original.imageResourceUris.size == 1)
    }

    @Test fun imageSendTestIsOffByDefaultAndRequiresExactlyOneImage() {
        val empty = TaskLaunchParameters()
        assertNull(empty.imageSendTestRequestId)
        assertNotNull(empty.imageSendTestValidationError())
        assertNull(empty.copy(imageResourceUris = listOf("content://media/a")).imageSendTestValidationError())
        assertNotNull(empty.copy(imageResourceUris = listOf("content://media/a", "content://media/b")).imageSendTestValidationError())
    }

    @Test fun imagePoolNormalizesBlankImportedResources() {
        val updated = TaskLaunchParameters().withImportedImageResourceUris(
            listOf("", " ", " content://media/a ", "content://media/a")
        )
        assertEquals(listOf("content://media/a"), updated.imageResourceUris)
    }
}
