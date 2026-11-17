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
            val first = LocalReplyTaskRepository(file)
            val second = LocalReplyTaskRepository(file)
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
