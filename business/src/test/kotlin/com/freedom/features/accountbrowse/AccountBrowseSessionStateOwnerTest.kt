package com.freedom.features.accountbrowse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccountBrowseSessionStateOwnerTest {
    @Test
    fun portWritesReplaceImmutableSnapshotWithoutMutatingPreviouslyReadSnapshot() {
        val owner = AccountBrowseSessionStateOwner()
        val before = owner.snapshot()

        owner.port.pauseReason = "人工接管"
        owner.port.touchActive = true

        val after = owner.snapshot()
        assertNotSame(before, after)
        assertEquals("", before.pauseReason)
        assertEquals(false, before.touchActive)
        assertEquals("人工接管", after.pauseReason)
        assertEquals(true, after.touchActive)
    }

    @Test
    fun concurrentFieldUpdatesAreSerializedWithoutDroppingEitherChange() {
        val owner = AccountBrowseSessionStateOwner()
        val start = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val first = Thread {
            start.await()
            owner.port.pauseReason = "manual"
            completed.countDown()
        }
        val second = Thread {
            start.await()
            owner.port.feedRefreshWaitReason = "refresh"
            completed.countDown()
        }

        first.start()
        second.start()
        start.countDown()

        assertEquals(true, completed.await(2, TimeUnit.SECONDS))
        assertEquals("manual", owner.snapshot().pauseReason)
        assertEquals("refresh", owner.snapshot().feedRefreshWaitReason)
    }

    @Test
    fun resetRestoresTheOriginalSessionDefaults() {
        val owner = AccountBrowseSessionStateOwner()
        owner.port.pauseReason = "manual"
        owner.port.touchActive = true
        owner.port.overlayPassthroughActive = true

        owner.port.reset()

        val reset = owner.snapshot()
        assertEquals("", reset.pauseReason)
        assertEquals(false, reset.touchActive)
        assertEquals(true, reset.overlayPassthroughActive)
    }
}
