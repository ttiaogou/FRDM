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
}
