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
}
