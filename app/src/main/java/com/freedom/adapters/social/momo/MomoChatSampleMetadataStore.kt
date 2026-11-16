package com.freedom.adapters.social.momo

import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.conversation.domain.contact.ContactRowIdentity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Owns chat-header validation and the explicitly requested, local-only SayHi sample records. */
internal class MomoChatSampleMetadataStore(
    private val isRunAcceptingRecords: () -> Boolean,
    private val runDirectory: () -> File?,
    private val appendJsonLine: (String, JSONObject) -> Unit,
    private val onArchiveWriteFailure: () -> Unit
) {
    private companion object {
        const val MAX_SAYHI_PAGE_SAMPLES = 100
    }

    private var sayHiPageSampleCount = 0
    private val sayHiPageSampleSignatures = mutableSetOf<String>()

    val sayHiSampleCount: Int get() = sayHiPageSampleCount

    fun resetForNewRun() {
        sayHiPageSampleCount = 0
        sayHiPageSampleSignatures.clear()
    }

    fun persistChatHeaderValidationDiagnostic(
        snapshot: ChatPageSnapshot,
        expectedSessionName: String?,
        matched: Boolean,
        expectedCandidate: com.freedom.features.aireply.ports.ChatListCandidateSnapshot? = null
    ) {
        if (!isRunAcceptingRecords() || runDirectory() == null) return
        val diagnostic = snapshot.chatHeaderDiagnostic
        val expectedProfile = ChatSessionManagementPolicy.profileSessionTitle(expectedSessionName)
        val actualProfile = ChatSessionManagementPolicy.profileSessionTitle(snapshot.chatTitleText)
        val comparison = ChatSessionManagementPolicy.compareSessionTitles(
            expectedSessionName, snapshot.chatTitleText
        )
        appendJsonLine("session_navigation.jsonl", JSONObject()
            .put("event", "chat_header_validation")
            .put("capturedAt", System.currentTimeMillis())
            .put("activity", snapshot.activityClass)
            .put("pageKind", snapshot.pageKind.name)
            .put("matched", matched)
            .put("expectedTitleLength", expectedSessionName.orEmpty().trim().length)
            .put("actualTitleLength", snapshot.chatTitleText.orEmpty().trim().length)
            .put("expectedCandidate", expectedCandidate?.let(MomoChatSampleDataMapper::candidateIdentityJson))
            .put("titleComparison", JSONObject()
                .put("normalizedEqual", comparison.normalizedEqual)
                .put("commonPrefixCodePointCount", comparison.commonPrefixCodePointCount)
                .put("commonSuffixCodePointCount", comparison.commonSuffixCodePointCount)
                .put("expectedUnmatchedCodePointCount", comparison.expectedUnmatchedCodePointCount)
                .put("actualUnmatchedCodePointCount", comparison.actualUnmatchedCodePointCount)
                .put("expectedProfile", MomoChatSampleDataMapper.titleProfileJson(expectedProfile))
                .put("actualProfile", MomoChatSampleDataMapper.titleProfileJson(actualProfile)))
            .put("headerNode", diagnostic?.let { item ->
                JSONObject()
                    .put("viewId", "com.immomo.momo:id/chat_user_name")
                    .put("nodePath", item.nodePath)
                    .put("found", item.found)
                    .put("textLength", item.textLength)
                    .put("contentDescriptionLength", item.contentDescriptionLength)
                    .put("isVisibleToUser", item.isVisibleToUser)
                    .put("bounds", JSONObject()
                        .put("left", item.left).put("top", item.top)
                        .put("right", item.right).put("bottom", item.bottom))
                    .put("boundsValid", item.boundsValid)
                    .put("parentFound", item.parentFound)
                    .put("parentClassName", item.parentClassName)
                    .put("parentViewId", item.parentViewId)
                    .put("parentIsVisibleToUser", item.parentIsVisibleToUser)
                    .put("parentBoundsValid", item.parentBoundsValid)
                    .put("readOutcome", item.readOutcome)
            }))
    }

    fun persistSayHiIdentityResult(
        rowKeyHash: String,
        displayName: String?,
        status: MomoSayHiPlatformIdentityStatus,
        candidateCount: Int,
        source: String,
        identity: ContactRowIdentity?
    ): Boolean {
        if (!isRunAcceptingRecords() || rowKeyHash.isBlank()) return false
        val directory = runDirectory() ?: return false
        val file = File(directory, "sayhi_identities.jsonl")
        val record = JSONObject()
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("rowKeyHash", rowKeyHash)
            .put("displayName", displayName ?: JSONObject.NULL)
            .put("identityStatus", status.name)
            .put("candidateCount", candidateCount)
            .put("source", source)
            .put("contactId", identity?.contactId ?: JSONObject.NULL)
            .put("identityConfidence", identity?.confidence?.name ?: JSONObject.NULL)
        return runCatching {
            check(file.exists() || file.createNewFile()) { "无法创建收到招呼身份样本文件" }
            java.io.RandomAccessFile(file, "rw").use { output ->
                output.seek(output.length())
                output.write((record.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            true
        }.getOrDefault(false)
    }

    fun persistSayHiInspection(inspection: MomoSayHiPageInspection): Boolean {
        if (!isRunAcceptingRecords() || inspection.kind == MomoSayHiPageKind.UNKNOWN) return false
        val directory = runDirectory() ?: return false
        if (sayHiPageSampleCount >= MAX_SAYHI_PAGE_SAMPLES) return false

        val sample = MomoChatSampleDataMapper.sayHiInspectionJson(inspection)
        val signature = MessageDigest.getInstance("SHA-256")
            .digest(sample.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (signature in sayHiPageSampleSignatures) return false

        val record = JSONObject(sample.toString())
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("sampleIndex", sayHiPageSampleCount + 1)
            .put("contentSignature", signature)
        val file = File(directory, "sayhi_pages.jsonl")
        val originalLength = file.takeIf(File::isFile)?.length() ?: 0L
        return runCatching {
            check(file.exists() || file.createNewFile()) { "无法创建 SayHi 样本文件" }
            java.io.RandomAccessFile(file, "rw").use { output ->
                output.seek(output.length())
                output.write((record.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            sayHiPageSampleSignatures += signature
            sayHiPageSampleCount++
            true
        }.getOrElse {
            runCatching { java.io.RandomAccessFile(file, "rw").use { it.setLength(originalLength) } }
            onArchiveWriteFailure()
            false
        }
    }
}
