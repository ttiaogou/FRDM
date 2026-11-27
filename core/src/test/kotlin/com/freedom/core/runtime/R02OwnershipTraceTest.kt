package com.freedom.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixed transition trace against the actual UI ownership coordinator. */
class R02OwnershipTraceTest {
    @Test
    fun handoffAndNewGenerationPreserveExclusiveWriteAuthority() {
        val owner = UiOperationOwnershipCoordinator()
        val trace = mutableListOf<String>()
        val first = requireNotNull(owner.tryAcquire("private", 7L))
        trace += "owner:${owner.currentOwnerId()}"
        trace += "competing:${owner.tryAcquire("greetings", 7L) != null}"
        val next = requireNotNull(owner.transfer(first, "greetings"))
        trace += "handoff:${owner.currentOwnerId()}"
        trace += "old:${owner.isCurrent(first)}:${owner.release(first)}"
        val fresh = requireNotNull(owner.tryAcquire("browse", 8L))
        trace += "generation:${owner.currentOwnerId()}:${owner.isCurrent(next)}"
        trace += "late:${owner.tryAcquire("private", 7L) != null}"
        trace += "stop:${owner.release(fresh)}:${owner.currentOwnerId()}"
        assertEquals(listOf("owner:private", "competing:false", "handoff:greetings",
            "old:false:false", "generation:browse:false", "late:false", "stop:true:null"), trace)
    }
}
