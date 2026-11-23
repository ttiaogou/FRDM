package com.freedom.features.aireply.ai

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.domain.message.ChatMessage
import com.freedom.features.aireply.domain.message.ChatMessageBatch
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.aireply.domain.message.ChatMessageType
import com.freedom.features.aireply.domain.message.DirectionEvidence
import com.freedom.features.aireply.domain.message.MessageContentQuality
import com.freedom.features.aireply.domain.message.MessageReadStatus
import com.freedom.features.conversation.domain.contact.ContactProfile
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import com.freedom.features.conversation.domain.message.MessageStoreResult
import com.freedom.features.conversation.domain.message.StoredConversationMessage
import com.freedom.features.conversation.ports.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextManagerTest {
    @Test
    fun recordAndLoadMergesVerifiedIncomingAndOutgoingMessagesInChronologicalOrder() {
        val repository = FakeConversationRepository(
            mutableListOf(
                stored("other-contact", "momo-direct-other-contact", "other", "别人的记录", 1L, 10),
                stored("contact-1", "another-conversation", "wrong-conversation", "其他会话", 1L, 20),
                stored("contact-1", "momo-direct-contact-1", "old-in", "之前对方说", 10L, 10),
                stored("contact-1", "momo-direct-contact-1", "old-out", "之前我说", 10L, 30, direction = "OUTGOING"),
                stored("contact-1", "momo-direct-contact-1", "unverified", "未验证内容", 11L, 40, quality = "UNVERIFIED"),
                stored("contact-1", "momo-direct-contact-1", "image", "", 11L, 50, type = "IMAGE")
            )
        )
        val manager = ConversationContextManager(repository)
        val batch = batch(
            capturedAtMillis = 12L,
            messages = listOf(
                message("latest-in", ChatMessageDirection.INCOMING, "最新收到", top = 80),
                message("latest-out", ChatMessageDirection.OUTGOING, "最新发出", top = 100)
            )
        )

        val context = manager.recordAndLoad(
            contactId = "contact-1",
            conversationId = "momo-direct-contact-1",
            identityConfidence = "PLATFORM_ID_VERIFIED",
            batch = batch
        )

        assertEquals(2, context.insertedCount)
        assertEquals(
            listOf(
                AiModelMessage(AiModelMessage.Role.USER, "之前对方说"),
                AiModelMessage(AiModelMessage.Role.ASSISTANT, "之前我说"),
                AiModelMessage(AiModelMessage.Role.USER, "最新收到"),
                AiModelMessage(AiModelMessage.Role.ASSISTANT, "最新发出")
            ),
            context.messages
        )
        assertTrue(context.hasVerifiedIncoming)
        assertEquals("momo-direct-contact-1", context.conversationId)

        val repeated = manager.recordAndLoad(
            contactId = "contact-1",
            conversationId = "momo-direct-contact-1",
            identityConfidence = "PLATFORM_ID_VERIFIED",
            batch = batch
        )
        assertEquals(0, repeated.insertedCount)
        assertEquals(6, repository.messages.count { it.conversationId == "momo-direct-contact-1" })
    }

    @Test
    fun unverifiedIdentityCannotLoadOrPersistConversationContext() {
        val repository = FakeConversationRepository()
        val manager = ConversationContextManager(repository)

        assertThrows(IllegalArgumentException::class.java) {
            manager.recordAndLoad(
                contactId = "temporary-contact",
                conversationId = "momo-direct-temporary-contact",
                identityConfidence = "DISPLAY_NAME_PROVISIONAL",
                batch = batch(1L, listOf(message("m", ChatMessageDirection.INCOMING, "你好")))
            )
        }
        assertTrue(repository.messages.isEmpty())
    }

    @Test
    fun emptyHistoryDoesNotClaimAVerifiedIncomingMessage() {
        val context = ConversationContextManager(FakeConversationRepository()).loadForConversation(
            contactId = "contact-1",
            conversationId = "momo-direct-contact-1",
            identityConfidence = "PLATFORM_ID_VERIFIED"
        )

        assertTrue(context.messages.isEmpty())
        assertFalse(context.hasVerifiedIncoming)
    }

    private class FakeConversationRepository(
        val messages: MutableList<StoredConversationMessage> = mutableListOf()
    ) : ConversationRepository {
        override fun findContactsByIdentityKeyHash(identityKeyHash: String): List<ContactProfile> = emptyList()
        override fun upsertContact(profile: ContactProfile) = Unit

        override fun persistMessages(
            contactId: String,
            conversationId: String,
            identityConfidence: String,
            capturedAtMillis: Long,
            messages: List<ConversationMessageInput>
        ): MessageStoreResult {
            var inserted = 0
            messages.forEach { input ->
                val id = "$contactId:$conversationId:${input.messageFingerprint}"
                if (this.messages.none { it.recordId == id }) {
                    this.messages += StoredConversationMessage(
                        recordId = id,
                        contactId = contactId,
                        conversationId = conversationId,
                        messageFingerprint = input.messageFingerprint,
                        direction = input.direction,
                        type = input.type,
                        content = input.content,
                        contentQuality = input.contentQuality,
                        directionEvidence = input.directionEvidence,
                        bounds = input.bounds,
                        identityConfidence = identityConfidence,
                        firstObservedAtMillis = capturedAtMillis,
                        lastObservedAtMillis = capturedAtMillis,
                        observationCount = 1
                    )
                    inserted++
                }
            }
            return MessageStoreResult(insertedCount = inserted)
        }

        override fun messagesForContact(contactId: String): List<StoredConversationMessage> =
            messages.filter { it.contactId == contactId }
    }

    private fun batch(capturedAtMillis: Long, messages: List<ChatMessage>) = ChatMessageBatch(
        status = MessageReadStatus.SUCCESS,
        windowSessionKey = "window-session",
        messages = messages,
        scannedRowCount = messages.size,
        candidateRowCount = messages.size,
        truncated = false,
        capturedAtMillis = capturedAtMillis
    )

    private fun message(
        id: String,
        direction: ChatMessageDirection,
        content: String,
        top: Int = 10
    ) = ChatMessage(
        id = id,
        direction = direction,
        type = ChatMessageType.TEXT,
        content = content,
        bounds = ScreenRect(0, top, 100, top + 20),
        directionEvidence = DirectionEvidence.EXPLICIT_LAYOUT_HINT,
        contentQuality = MessageContentQuality.VERIFIED
    )

    private fun stored(
        contactId: String,
        conversationId: String,
        id: String,
        content: String,
        observedAt: Long,
        top: Int,
        direction: String = "INCOMING",
        quality: String = "VERIFIED",
        type: String = "TEXT"
    ) = StoredConversationMessage(
        recordId = id,
        contactId = contactId,
        conversationId = conversationId,
        messageFingerprint = id,
        direction = direction,
        type = type,
        content = content,
        contentQuality = quality,
        directionEvidence = "EXPLICIT_LAYOUT_HINT",
        bounds = ScreenRect(0, top, 100, top + 20),
        identityConfidence = "PLATFORM_ID_VERIFIED",
        firstObservedAtMillis = observedAt,
        lastObservedAtMillis = observedAt,
        observationCount = 1
    )

    @Test
    fun legacyVerifiedHistoryIsFilteredBeforeItEntersModelContext() {
        val repo = FakeConversationRepository(mutableListOf(
            stored("contact-1", "momo-direct-contact-1", "safe", "晚上好😊", 1L, 10),
            stored("contact-1", "momo-direct-contact-1", "bad-marker",
                "在吗[表情/图形未识别]", 2L, 20),
            stored("contact-1", "momo-direct-contact-1", "bad-symbol", ")(", 3L, 30),
            stored("contact-1", "momo-direct-contact-1", "good", "明天见", 4L, 40)
        ))
        val result = ConversationContextManager(repo).loadForConversation(
            "contact-1", "momo-direct-contact-1", "PLATFORM_ID_VERIFIED"
        )
        assertEquals(listOf("晚上好", "明天见"), result.messages.map { it.content })
        assertTrue(result.hasVerifiedIncoming)
        assertEquals("晚上好😊", repo.messages.first().content)
        assertEquals("在吗[表情/图形未识别]", repo.messages[1].content)
    }

    @Test
    fun currentUnsafeContentIsNotNewlyPersistedAsVerifiedHistory() {
        val repo = FakeConversationRepository()
        val batch = batch(1L, listOf(
            message("safe", ChatMessageDirection.INCOMING, "你好😊"),
            message("bad", ChatMessageDirection.INCOMING, "在吗[表情/图形未识别]", top = 60)
        ))
        val context = ConversationContextManager(repo).recordAndLoad(
            "contact-1", "momo-direct-contact-1", "PLATFORM_ID_VERIFIED", batch
        )
        assertEquals(listOf("你好"), context.messages.map { it.content })
        assertEquals(1, repo.messages.size)
        assertEquals("你好😊", repo.messages.single().content)
    }


}
