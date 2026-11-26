package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.ImageSequencePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ImagePagingProgressTest {
    private fun position(n: Int, total: Int = 9) = ImageSequencePosition(n, total)

    @Test
    fun eachSwipeMustAdvanceExactlyOneImageBeforeCountChanges() {
        for (direction in ImageSwipeDirection.values()) {
            val start = position(5)
            val step = if (direction == ImageSwipeDirection.LEFT) 1 else -1
            val progress = ImagePagingProgress("window", ImageBrowsePlan(start, direction, 2), deadlineAt = 100)
            assertEquals(ImagePagingProgress.Decision.READY, progress.observe(start, 1))
            val pending = progress.copy(awaitingVerification = true)
            assertEquals(ImagePagingProgress.Decision.WAIT, pending.observe(start, 2))
            assertEquals(ImagePagingProgress.Decision.WAIT, pending.observe(null, 2))
            assertEquals(ImagePagingProgress.Decision.FINISH, pending.observe(position(5 + 2 * step), 2))
            assertEquals(ImagePagingProgress.Decision.VERIFIED, pending.observe(position(5 + step), 2))
            val next = pending.markVerified(200)
            assertEquals(1, next.confirmedSwipes)
            assertFalse(next.finished)
            assertEquals(ImagePagingProgress.Decision.READY, next.observe(position(5 + step), 3))
            val done = next.copy(awaitingVerification = true).markVerified(300)
            assertTrue(done.finished)
            assertEquals(ImagePagingProgress.Decision.FINISH, done.observe(position(5 + 2 * step), 4))
        }
    }

    @Test
    fun uncertainOrChangedEvidenceNeverAuthorizesBlindRepeat() {
        val progress = ImagePagingProgress("window", ImageBrowsePlan(position(5), ImageSwipeDirection.LEFT, 2),
            awaitingVerification = true, deadlineAt = 100)
        assertEquals(ImagePagingProgress.Decision.WAIT, progress.observe(null, 99))
        assertEquals(ImagePagingProgress.Decision.FINISH, progress.observe(null, 100))
        assertEquals(ImagePagingProgress.Decision.FINISH, progress.observe(position(5), 100))
        assertEquals(ImagePagingProgress.Decision.FINISH, progress.observe(position(6, 8), 20))
        assertEquals(ImagePagingProgress.Decision.FINISH, progress.observe(position(4), 20))
        assertTrue(progress.copy(stopAfterVerification = true).markVerified(200).finished)
    }

    @Test
    fun singleImageAndMissingCounterDoNotPreventSafeReturn() {
        val initial = ImagePagingProgress("window", deadlineAt = 100)
        assertEquals(ImagePagingProgress.Decision.FINISH, initial.observe(position(1, 1), 1))
        assertEquals(ImagePagingProgress.Decision.WAIT, initial.observe(null, 1))
        assertEquals(ImagePagingProgress.Decision.FINISH, initial.observe(null, 100))
        assertEquals(ImagePagingProgress.Decision.READY, initial.observe(position(5), 1))
    }
}
