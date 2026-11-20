package com.freedom.features.aireply.ai

import com.freedom.data.database.LocalReplySegmentTaskRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReplySegmentTaskTest {
    @Test
    fun factoryCreatesIndependentMessageTasks() {
        val records = ReplySegmentTaskFactory.create(
            requestId = "request-1",
            conversationKey = "conversation-1",
            messages = listOf("第一条回复", "第二条回复", "第三条回复"),
            createdAtMillis = 100L
        )

        assertEquals(3, records.size)
        assertEquals(listOf(0, 1, 2), records.map { it.segmentIndex })
        assertTrue(records.all { it.segmentCount == 3 && it.status == ReplySegmentTaskStatus.CREATED })
        assertNotEquals(records[0].segmentContentHash, records[1].segmentContentHash)
    }

    @Test
    fun repositoryPersistsAndGuardsIndependentState() {
        val file = File.createTempFile("reply-segments", ".tsv").apply { delete() }
        try {
            val repository = LocalReplySegmentTaskRepository(file)
            val records = ReplySegmentTaskFactory.create("request-2", "conversation-2", listOf("甲", "乙"), 200L)
            records.forEach(repository::save)

            val first = records.first().copy(status = ReplySegmentTaskStatus.DRAFT_APPLIED)
            assertTrue(repository.update(first))
            assertEquals(ReplySegmentTaskStatus.DRAFT_APPLIED, repository.find(first.taskId)?.status)
            assertEquals(ReplySegmentTaskStatus.CREATED, repository.find(records[1].taskId)?.status)
            assertEquals(2, repository.findByRequest("request-2").size)
        } finally {
            file.delete()
        }
    }
}
