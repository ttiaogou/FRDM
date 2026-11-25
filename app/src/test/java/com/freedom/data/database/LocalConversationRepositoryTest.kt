package com.freedom.data.database

import com.freedom.core.accessibility.api.ScreenRect
import com.freedom.features.aireply.application.message.ChatMessageCandidate
import com.freedom.features.aireply.application.message.ChatMessageParser
import com.freedom.features.aireply.domain.message.ChatMessageDirection
import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import com.freedom.features.conversation.domain.contact.ContactProfile
import com.freedom.features.conversation.domain.message.ConversationMessageInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LocalConversationRepositoryTest {
    @Test
    fun contactProfilesAndMessagesSurviveRepositoryRecreationInEncryptedFiles() {
        val directory = Files.createTempDirectory("freedom-conversation-store").toFile()
        val cipher = TestConversationStoreCipher()
        try {
            val firstRepository = LocalConversationRepository(directory, cipher, nowMillis = { 200L })
            val profile = ContactProfile(
                contactId = "contact-1",
                identityKeyHash = "hashed-name-key",
                displayName = "张三",
                identitySource = "NORMALIZED_DISPLAY_NAME",
                confidence = ContactIdentityConfidence.DISPLAY_NAME_PROVISIONAL,
                firstSeenAtMillis = 100L,
                lastSeenAtMillis = 100L
            )
            firstRepository.upsertContact(profile)
            val message = sampleMessage("fingerprint-1", "你好")
            val firstWrite = firstRepository.persistMessages(
                contactId = profile.contactId,
                conversationId = "momo-direct-contact-1",
                identityConfidence = profile.confidence.name,
                capturedAtMillis = 200L,
                messages = listOf(message)
            )
            assertEquals(1, firstWrite.insertedCount)

            val contactsFile = directory.resolve("contacts.v1.tsv")
            val messagesFile = directory.resolve("messages.v1.tsv")
            assertTrue(contactsFile.readText().startsWith("#FREEDOM_CONVERSATION_STORE\t2\tAES/GCM/NoPadding"))
            assertTrue(messagesFile.readText().startsWith("#FREEDOM_CONVERSATION_STORE\t2\tAES/GCM/NoPadding"))
            assertFalse(contactsFile.readText().contains("张三"))
            assertFalse(messagesFile.readText().contains("你好"))

            val restartedRepository = LocalConversationRepository(directory, cipher, nowMillis = { 200L })
            assertEquals(profile, restartedRepository.contactsSnapshot().single())
            val restored = restartedRepository.messagesForContact(profile.contactId).single()
            assertEquals("你好", restored.content)
            assertEquals("fingerprint-1", restored.messageFingerprint)
            assertEquals(200L, restored.firstObservedAtMillis)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun versionOneBase64StoreIsMigratedToEncryptedStoreWithoutLosingHistory() {
        val directory = Files.createTempDirectory("freedom-conversation-migration").toFile()
        val cipher = TestConversationStoreCipher()
        try {
            val fields = listOf(
                "M", "record-1", "contact-1", "conversation-1", "fingerprint-1", "INCOMING", "TEXT",
                "历史消息", "VERIFIED", "EXPLICIT_LAYOUT_HINT", "10", "20", "110", "60",
                "DISPLAY_NAME_PROVISIONAL", "100", "100", "1"
            )
            val encodedRecord = fields.mapIndexed { index, value ->
                if (index == 0 || index in 10..13 || index >= 15) value else encodeLegacy(value)
            }.joinToString("\t")
            directory.resolve("messages.v1.tsv").writeText(
                "#FREEDOM_CONVERSATION_STORE\t1\n$encodedRecord\n",
                StandardCharsets.UTF_8
            )

            val repository = LocalConversationRepository(directory, cipher, nowMillis = { 100L })
            assertEquals("历史消息", repository.messagesForContact("contact-1").single().content)
            val migratedText = directory.resolve("messages.v1.tsv").readText()
            assertTrue(migratedText.startsWith("#FREEDOM_CONVERSATION_STORE\t2\tAES/GCM/NoPadding"))
            assertFalse(migratedText.contains("#FREEDOM_CONVERSATION_STORE\t1"))
            assertFalse(migratedText.contains("历史消息"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun tamperedEncryptedStoreFailsClosed() {
        val directory = Files.createTempDirectory("freedom-conversation-tamper").toFile()
        val cipher = TestConversationStoreCipher()
        try {
            val repository = LocalConversationRepository(directory, cipher, nowMillis = { 200L })
            repository.persistMessages(
                "contact-1", "conversation-1", "DISPLAY_NAME_PROVISIONAL", 200L,
                listOf(sampleMessage("fingerprint", "仅用于完整性验证"))
            )
            val file = directory.resolve("messages.v1.tsv")
            val lines = file.readLines()
            val bytes = Base64.getUrlDecoder().decode(lines[1])
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            file.writeText(lines[0] + "\n" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "\n")

            assertThrows(Exception::class.java) {
                LocalConversationRepository(directory, cipher, nowMillis = { 200L })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun repeatedObservationIsDeduplicatedAndSameTextDifferentFingerprintIsPreserved() {
        val directory = Files.createTempDirectory("freedom-conversation-dedup").toFile()
        try {
            val repository = LocalConversationRepository(directory, TestConversationStoreCipher(), nowMillis = { 200L })
            val first = sampleMessage("message-a", "一样的内容")
            val second = sampleMessage("message-b", "一样的内容")
            val initial = repository.persistMessages(
                "contact-1", "conversation-1", "DISPLAY_NAME_PROVISIONAL", 100L,
                listOf(first, second)
            )
            assertEquals(2, initial.insertedCount)

            val repeated = repository.persistMessages(
                "contact-1", "conversation-1", "DISPLAY_NAME_PROVISIONAL", 200L,
                listOf(first, second)
            )
            assertEquals(0, repeated.insertedCount)
            assertEquals(2, repeated.duplicateObservationCount)
            val stored = repository.messagesForContact("contact-1")
            assertEquals(2, stored.size)
            assertEquals(setOf(2), stored.map { it.observationCount }.toSet())
            assertEquals(setOf(200L), stored.map { it.lastObservedAtMillis }.toSet())
            assertNotEquals(stored[0].recordId, stored[1].recordId)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun appendingReplyPersistsOnlyTheNewMessageUsingRealParserFingerprints() {
        val directory = Files.createTempDirectory("freedom-conversation-append").toFile()
        try {
            val cipher = TestConversationStoreCipher()
            val repository = LocalConversationRepository(directory, cipher, nowMillis = { 300L })
            val parser = ChatMessageParser()
            val list = ScreenRect(0, 293, 720, 1359)
            fun row(index: Int, top: Int, bottom: Int, direction: ChatMessageDirection) =
                ChatMessageCandidate(
                    rowIndex = index,
                    rowBounds = ScreenRect(0, top, 720, bottom),
                    bubbleBounds = if (direction == ChatMessageDirection.INCOMING)
                        ScreenRect(140, top + 10, 390, bottom - 10)
                    else ScreenRect(410, top + 10, 650, bottom - 10),
                    listBounds = list,
                    text = "相同正文",
                    explicitDirectionHint = direction
                )
            val first = listOf(
                row(0, 434, 554, ChatMessageDirection.INCOMING),
                row(1, 554, 674, ChatMessageDirection.INCOMING)
            )
            val second = first + row(2, 674, 864, ChatMessageDirection.OUTGOING)
            fun inputs(rows: List<ChatMessageCandidate>, batchId: String) = parser.parse(rows).map {
                ConversationMessageInput(
                    messageFingerprint = it.stableLogicalMessageId,
                    direction = it.direction.name,
                    type = it.type.name,
                    content = it.content,
                    contentQuality = it.contentQuality.name,
                    directionEvidence = it.directionEvidence.name,
                    bounds = it.bounds,
                    contentRevision = it.effectiveContentRevision,
                    observationBatchId = batchId,
                    observationRowIndex = it.observationRowIndex
                )
            }
            val initial = repository.persistMessages(
                "contact-1", "conversation-1", "PLATFORM_ID_VERIFIED", 100L, inputs(first, "before")
            )
            assertEquals(2, initial.insertedCount)
            val appended = repository.persistMessages(
                "contact-1", "conversation-1", "PLATFORM_ID_VERIFIED", 200L, inputs(second, "after")
            )
            assertEquals(1, appended.insertedCount)
            assertEquals(2, appended.duplicateObservationCount)
            assertEquals(0, appended.rejectedCount)
            val reopened = LocalConversationRepository(directory, cipher, nowMillis = { 300L })
            val stored = reopened.messagesForConversation("contact-1", "conversation-1")
            assertEquals(3, stored.size)
            assertEquals(listOf("INCOMING", "INCOMING", "OUTGOING"), stored.map { it.direction })
            assertEquals(listOf(2, 2, 1), stored.map { it.observationCount })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ocrCorrectionUpdatesExistingLogicalMessageInsteadOfInsertingAnotherRecord() {
        val directory = Files.createTempDirectory("freedom-conversation-ocr-revision").toFile()
        try {
            val repository = LocalConversationRepository(directory, TestConversationStoreCipher(), nowMillis = { 300L })
            val first = sampleMessage("logical-message", "初次 OCR").copy(contentRevision = "rev-1")
            repository.persistMessages("contact-1", "conversation-1", "PLATFORM_ID_VERIFIED", 100L, listOf(first))

            val corrected = first.copy(content = "修正后的 OCR", contentRevision = "rev-2")
            val result = repository.persistMessages(
                "contact-1", "conversation-1", "PLATFORM_ID_VERIFIED", 200L, listOf(corrected)
            )

            assertEquals(0, result.insertedCount)
            assertEquals(1, result.duplicateObservationCount)
            val stored = repository.messagesForConversation("contact-1", "conversation-1").single()
            assertEquals("修正后的 OCR", stored.content)
            assertEquals("rev-2", stored.contentRevision)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun messagesForConversationFiltersExactSessionAndOrdersMessagesWithinObservationByScreenPosition() {
        val directory = Files.createTempDirectory("freedom-conversation-scope").toFile()
        try {
            val repository = LocalConversationRepository(
                directory, TestConversationStoreCipher(), nowMillis = { 500L }
            )
            repository.persistMessages(
                "contact-1",
                "momo-direct-contact-1",
                ContactIdentityConfidence.PLATFORM_ID_VERIFIED.name,
                100L,
                listOf(
                    sampleMessage("lower", "第二条").copy(bounds = ScreenRect(0, 80, 100, 120)),
                    sampleMessage("upper", "第一条").copy(bounds = ScreenRect(0, 20, 100, 60))
                )
            )
            repository.persistMessages(
                "contact-1", "another-conversation",
                ContactIdentityConfidence.PLATFORM_ID_VERIFIED.name, 90L,
                listOf(sampleMessage("other", "其他会话"))
            )

            val history = repository.messagesForConversation("contact-1", "momo-direct-contact-1")

            assertEquals(listOf("第一条", "第二条"), history.mapNotNull { it.content })
            assertTrue(history.all { it.conversationId == "momo-direct-contact-1" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun observationBatchAndRowOrderWinOverDelayedScreenRefreshPosition() {
        val directory = Files.createTempDirectory("freedom-conversation-batch-order").toFile()
        try {
            val repository = LocalConversationRepository(
                directory, TestConversationStoreCipher(), nowMillis = { 500L }
            )
            val firstBatch = listOf(
                sampleMessage("first", "第一条").copy(
                    observationBatchId = "batch-001", observationRowIndex = 0,
                    bounds = ScreenRect(0, 400, 100, 440)
                ),
                sampleMessage("second", "第二条").copy(
                    observationBatchId = "batch-001", observationRowIndex = 1,
                    bounds = ScreenRect(0, 100, 100, 140)
                )
            )
            repository.persistMessages(
                "contact-1", "conversation-1", ContactIdentityConfidence.PLATFORM_ID_VERIFIED.name,
                100L, firstBatch
            )
            repository.persistMessages(
                "contact-1", "conversation-1", ContactIdentityConfidence.PLATFORM_ID_VERIFIED.name,
                100L, listOf(
                    sampleMessage("third", "第三条").copy(
                        observationBatchId = "batch-002", observationRowIndex = 0,
                        bounds = ScreenRect(0, 10, 100, 50)
                    )
                )
            )

            assertEquals(listOf("第一条", "第二条", "第三条"),
                repository.messagesForConversation("contact-1", "conversation-1").mapNotNull { it.content })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun expiredConversationHistoryIsRemovedOnNextRepositoryAccess() {
        val directory = Files.createTempDirectory("freedom-conversation-retention").toFile()
        try {
            var now = 100L * ConversationStoreRetentionPolicy.DEFAULT_MAX_AGE_MILLIS
            val repository = LocalConversationRepository(
                directory = directory,
                cipher = TestConversationStoreCipher(),
                retentionPolicy = ConversationStoreRetentionPolicy(10L * DAY_MILLIS),
                nowMillis = { now }
            )
            repository.persistMessages(
                "contact-1", "conversation-1", "DISPLAY_NAME_PROVISIONAL", now - 9L * DAY_MILLIS,
                listOf(sampleMessage("fingerprint", "将过期的消息"))
            )
            assertEquals(1, repository.messagesForContact("contact-1").size)
            now += 2L * DAY_MILLIS
            assertTrue(repository.messagesForContact("contact-1").isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun incompleteIdentityOrFingerprintIsNotPersisted() {
        val directory = Files.createTempDirectory("freedom-conversation-reject").toFile()
        try {
            val repository = LocalConversationRepository(directory, TestConversationStoreCipher(), nowMillis = { 100L })
            val result = repository.persistMessages(
                contactId = "",
                conversationId = "conversation-1",
                identityConfidence = "UNRESOLVED",
                capturedAtMillis = 100L,
                messages = listOf(sampleMessage("fingerprint", "内容"))
            )
            assertEquals(1, result.rejectedCount)
            assertNull(repository.messagesForContact("contact-1").firstOrNull())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sampleMessage(fingerprint: String, content: String) = ConversationMessageInput(
        messageFingerprint = fingerprint,
        direction = "INCOMING",
        type = "TEXT",
        content = content,
        contentQuality = "VERIFIED",
        directionEvidence = "EXPLICIT_LAYOUT_HINT",
        bounds = ScreenRect(10, 20, 110, 60)
    )

    private fun encodeLegacy(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private class TestConversationStoreCipher : ConversationStoreCipher {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plaintext)
            return cipher.iv + encrypted
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            val iv = ciphertext.copyOfRange(0, 12)
            val encrypted = ciphertext.copyOfRange(12, ciphertext.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return cipher.doFinal(encrypted)
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
