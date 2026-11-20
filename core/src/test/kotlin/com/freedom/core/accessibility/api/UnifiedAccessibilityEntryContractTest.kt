package com.freedom.core.accessibility.api

import com.freedom.core.accessibility.events.AccessibilityEventEnvelope
import com.freedom.core.accessibility.events.AccessibilityEventType
import org.junit.Assert.assertSame
import org.junit.Test

class UnifiedAccessibilityEntryContractTest {
    @Test
    fun accept_forwards_the_normalized_event_without_recreating_it() {
        val event = AccessibilityEventEnvelope(
            type = AccessibilityEventType.OTHER,
            packageName = "com.example.target",
            className = "ExampleActivity",
            contentChanges = emptySet(),
            eventTimeMillis = 1L,
            eventText = emptyList(),
            contentDescription = null,
            source = null
        )
        var received: AccessibilityEventEnvelope? = null
        val entry = UnifiedAccessibilityEntry { received = it }

        entry.accept(event)

        assertSame(event, received)
    }
}
