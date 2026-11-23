package com.freedom.data.database

import com.freedom.features.aireply.ai.ReplyTaskRecord
import com.freedom.features.aireply.ai.ReplyTaskStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReplyTaskRepositoryTest {
    @Test
    fun claimIsAtomicAcrossRepositoryInstancesAndStatusIsOwnerChecked() {
        val file = File.createTempFile("reply-task", ".tsv")
        file.delete()
        try {
            // Keep the fixture inside the claim timeout; this test is about ownership,
            // not wall-clock expiry.
            val first = LocalReplyTaskRepository(file) { 10_000L }
            val second = LocalReplyTaskRepository(file) { 10_000L }
            val record = record("request-1", "owner-1", 1_000L)

            assertTrue(first.tryClaim(record, 120_000L))
            assertFalse(second.tryClaim(record.copy(requestId = "owner-2"), 120_000L))
            assertFalse(second.updateStatus("request-1", "owner-2", ReplyTaskStatus.MODEL_COMPLETED, 2_000L))
            assertTrue(second.updateStatus("request-1", "owner-1", ReplyTaskStatus.MODEL_COMPLETED, 2_000L))
            assertEquals(ReplyTaskStatus.MODEL_COMPLETED, first.find("request-1")?.status)
        } finally {
            file.delete()
            File(file.parentFile, "${file.name}.lock").delete()
        }
    }

    @Test
    fun staleRequestedIsRecoveredToUnknownAndCannotBeReclaimed() {
        val file = File.createTempFile("reply-task", ".tsv")
        file.delete()
        try {
            var now = 10_000L
            val repository = LocalReplyTaskRepository(file) { now }
            val record = record("request-2", "owner-2", now - 121_000L)
            repository.save(record)

            assertEquals(ReplyTaskStatus.UNKNOWN, repository.find("request-2")?.status)
            assertFalse(repository.tryClaim(record.copy(requestId = "owner-3"), 120_000L))
            assertNull(repository.find("missing"))
        } finally {
            file.delete()
            File(file.parentFile, "${file.name}.lock").delete()
        }
    }

    @Test
    fun modelOnlyRetryableFailureCanBeReclaimedAfterRepositoryReopen() {
        val file = File.createTempFile("reply-task", ".tsv")
        file.delete()
        try {
            val originalOwner = LocalReplyTaskRepository(file) { 10_000L }
            val task = record("model-empty-request", "first-owner", 1_000L)
            assertTrue(originalOwner.tryClaim(task, 120_000L))
            assertTrue(originalOwner.updateStatus(task.requestKey, task.requestId, ReplyTaskStatus.FAILED_RETRYABLE, 2_000L))

            val reopened = LocalReplyTaskRepository(file) { 10_000L }
            assertTrue(reopened.tryClaim(task.copy(requestId = "second-owner", updatedAtMillis = 3_000L), 120_000L))
            assertEquals(ReplyTaskStatus.REQUESTED, originalOwner.find(task.requestKey)?.status)
            assertFalse(originalOwner.updateStatus(task.requestKey, task.requestId, ReplyTaskStatus.MODEL_COMPLETED, 4_000L))
            assertTrue(reopened.updateStatus(task.requestKey, "second-owner", ReplyTaskStatus.MODEL_COMPLETED, 4_000L))
        } finally {
            file.delete()
            File(file.parentFile, "${file.name}.lock").delete()
        }
    }

    @Test
    fun durableRecordPreservesMemoryBufferIdentityAndRejectsIllegalTransition() {
        val file = File.createTempFile("reply-task", ".tsv")
        file.delete()
        try {
            val repository = LocalReplyTaskRepository(file) { 10_000L }
            val record = record("request-3", "owner-3", 3_000L).copy(
                userId = "user-3",
                contentHash = "reply-hash",
                createdAtMillis = 2_000L,
                cooldownUntilMillis = 9_000L,
                resultCode = "CLAIMED"
            )
            assertTrue(repository.tryClaim(record, 120_000L))
            val restored = repository.find("request-3")
            assertEquals("user-3", restored?.userId)
            assertEquals("reply-hash", restored?.contentHash)
            assertEquals(2_000L, restored?.createdAtMillis)
            assertEquals(9_000L, restored?.cooldownUntilMillis)
            assertEquals("CLAIMED", restored?.resultCode)
            assertFalse(repository.updateStatus("request-3", "owner-3", ReplyTaskStatus.SENT, 4_000L))
            assertTrue(repository.updateStatus("request-3", "owner-3", ReplyTaskStatus.MODEL_COMPLETED, 4_000L))
        } finally {
            file.delete()
            File(file.parentFile, "${file.name}.lock").delete()
        }
    }

    private fun record(key: String, requestId: String, updatedAt: Long) = ReplyTaskRecord(
        requestKey = key,
        conversationKey = "conversation",
        logicalMessageId = "logical-message",
        contentRevision = "revision",
        status = ReplyTaskStatus.REQUESTED,
        requestId = requestId,
        updatedAtMillis = updatedAt
    )
}
