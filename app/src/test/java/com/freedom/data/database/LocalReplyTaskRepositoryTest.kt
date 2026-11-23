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
    fun uncertainSendUnknownCannotBeReclaimedAfterReopenOrByAnotherOwner() {
        val file = File.createTempFile("reply-task", ".tsv")
        file.delete()
        try {
            val first = LocalReplyTaskRepository(file) { 10_000L }
            val task = record("unknown-sent-request", "original-owner", 1_000L)
            assertTrue(first.tryClaim(task, 120_000L))
            assertTrue(first.updateStatus(task.requestKey, task.requestId, ReplyTaskStatus.MODEL_COMPLETED, 1_100L))
            assertTrue(first.updateStatus(task.requestKey, task.requestId, ReplyTaskStatus.DRAFT_APPLIED, 1_200L))
            assertTrue(first.updateStatus(task.requestKey, task.requestId, ReplyTaskStatus.PENDING_SEND, 1_300L))
            assertTrue(first.updateStatus(task.requestKey, task.requestId, ReplyTaskStatus.SENDING, 1_400L))
            assertTrue(first.updateStatus(task.requestKey, task.requestId, ReplyTaskStatus.UNKNOWN, 1_500L))

            val reopened = LocalReplyTaskRepository(file) { 300_000L }
            assertEquals(ReplyTaskStatus.UNKNOWN, reopened.find(task.requestKey)?.status)
            assertFalse(reopened.tryClaim(task.copy(requestId = "another-owner", updatedAtMillis = 300_000L), 120_000L))
            assertFalse(reopened.updateStatus(task.requestKey, "another-owner", ReplyTaskStatus.FAILED_RETRYABLE, 300_001L))
            assertEquals(ReplyTaskStatus.UNKNOWN, first.find(task.requestKey)?.status)
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


    @Test
    fun sendEvidenceSurvivesSegmentLedgerReopenAndUnknownTransition() {
        val file = File.createTempFile("reply-segments", ".tsv")
        file.delete()
        try {
            val repository = LocalReplySegmentTaskRepository(file)
            val created = com.freedom.features.aireply.ai.ReplySegmentTaskRecord(
                taskId = "request:segment:0", requestId = "request",
                conversationKey = "conversation", segmentIndex = 0, segmentCount = 1,
                segmentContentHash = "content-hash", segmentContent = "你好",
                status = com.freedom.features.aireply.ai.ReplySegmentTaskStatus.CREATED,
                createdAt = 1L
            )
            repository.save(created)
            assertTrue(repository.update(created.copy(
                status = com.freedom.features.aireply.ai.ReplySegmentTaskStatus.DRAFT_APPLIED)))
            assertTrue(repository.update(created.copy(
                status = com.freedom.features.aireply.ai.ReplySegmentTaskStatus.PENDING_SEND)))
            val proof = created.copy(
                status = com.freedom.features.aireply.ai.ReplySegmentTaskStatus.SENDING,
                sendAttemptStartedAt = 2L, sendWindowSessionKey = "pkg|21|7|conversation=conversation",
                sendChatTitleHash = "title-hash", preSendOutgoingKeyHashes = setOf("one", "two"),
                sendRequestKey = "request-key"
            )
            assertTrue(repository.update(proof))
            val reopened = LocalReplySegmentTaskRepository(file)
            assertEquals(proof, reopened.find(proof.taskId))
            assertTrue(reopened.update(proof.copy(
                status = com.freedom.features.aireply.ai.ReplySegmentTaskStatus.UNKNOWN)))
            assertEquals(com.freedom.features.aireply.ai.ReplySegmentTaskStatus.UNKNOWN,
                LocalReplySegmentTaskRepository(file).find(proof.taskId)?.status)
            assertEquals(setOf("one", "two"),
                LocalReplySegmentTaskRepository(file).find(proof.taskId)?.preSendOutgoingKeyHashes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacySegmentRowsWithoutSendProofRemainReadableAndUnproven() {
        val file = File.createTempFile("reply-segments-legacy", ".tsv")
        try {
            fun encoded(value: String) = java.util.Base64.getUrlEncoder().encodeToString(value.toByteArray())
            file.writeText(listOf(encoded("old:segment:0"), encoded("old"), encoded("conversation"),
                "0", "1", "content-hash", "UNKNOWN", "1", "", encoded(""),
                encoded("你好"), "v2").joinToString("\t"))
            val original = LocalReplySegmentTaskRepository(file).find("old:segment:0")
            assertEquals(com.freedom.features.aireply.ai.ReplySegmentTaskStatus.UNKNOWN, original?.status)
            assertNull(original?.sendAttemptStartedAt)
            assertNull(original?.sendWindowSessionKey)
            assertTrue(original?.preSendOutgoingKeyHashes?.isEmpty() == true)
        } finally {
            file.delete()
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
