package com.freedom.features.conversation.domain.contact

import com.freedom.features.aireply.ai.VerifiedDraftHistory
import com.freedom.features.aireply.ports.ChatSessionManagementPolicy
import com.freedom.features.conversation.ports.ConversationRepository

/** 使用已采集的平台联系人记录找回历史；昵称只作查找入口，不新建或伪造平台身份。 */
class ContactHistoryResolver(private val repository: ConversationRepository) {
    fun resolve(identity: ContactRowIdentity?, chatTitle: String?): ContactRowIdentity? {
        if (identity?.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
            !identity.contactId.isNullOrBlank()) return identity
        val matches = repository.contactsSnapshot().filter {
            it.confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED &&
                it.contactId.isNotBlank() &&
                ChatSessionManagementPolicy.matchChatHeader(it.displayName, chatTitle) != null
        }.distinctBy { it.contactId }
        val contact = matches.singleOrNull() ?: return null
        return ContactRowIdentity(
            identity?.snapshotId.orEmpty(), identity?.temporaryUserId.orEmpty(),
            contact.contactId, ContactIdentityConfidence.PLATFORM_ID_VERIFIED
        )
    }

    fun loadDraftHistory(identity: ContactRowIdentity?, chatTitle: String?): VerifiedDraftHistory? {
        val resolved = resolve(identity, chatTitle) ?: return null
        val contactId = resolved.contactId ?: return null
        val conversationId = "momo-direct-$contactId"
        val messages = repository.messagesForConversation(contactId, conversationId)
        return VerifiedDraftHistory(
            accountKey = "", contactId = contactId, conversationId = conversationId,
            messages = messages, lookupSource = "LOCAL_PLATFORM_CONTACT"
        )
    }

    /**
     * One runtime-local, single-use preload slot. The caller retains task-policy admission
     * and clears before preparing another visit or generating a greeting draft.
     * No process-wide cache, repository duplication, scheduling, or task authority.
     */
    class DraftHistoryPreload(
        private val loadHistory: (ContactRowIdentity, String?) -> VerifiedDraftHistory?,
        private val addLog: (String) -> Unit
    ) {
        private var preparedDraftHistory: VerifiedDraftHistory? = null

        fun clear() {
            preparedDraftHistory = null
        }

        fun prepare(verifiedIdentity: ContactRowIdentity, chatTitle: String?) {
            val history = loadHistory(verifiedIdentity, chatTitle)
            preparedDraftHistory = history
            addLog(
                if (history != null) {
                    "AI 草稿历史预加载：已验证联系人历史=${history.messages.size}条；OCR 尚未启动"
                } else {
                    "AI 草稿历史预加载：未找到可用历史；OCR 尚未启动"
                }
            )
        }

        fun consume(verifiedIdentity: ContactRowIdentity?, chatTitle: String?): VerifiedDraftHistory? =
            if (verifiedIdentity != null) {
                preparedDraftHistory?.also {
                    preparedDraftHistory = null
                    addLog("AI 草稿历史读取：复用 OCR 前已完成的历史预加载；不重复查询")
                } ?: runCatching {
                    loadHistory(verifiedIdentity, chatTitle)
                }.onFailure {
                    addLog(
                        "AI 草稿历史读取失败：CONTACT_HISTORY_READ_FAILED；" +
                            "contactIdVerified=true；exception=${it.javaClass.simpleName}；" +
                            "降级为仅当前窗口上下文"
                    )
                }.getOrNull()
            } else {
                preparedDraftHistory = null
                addLog("AI 草稿上下文：身份未达到 PLATFORM_ID_VERIFIED；不加载联系人历史；仅当前窗口上下文")
                null
            }
    }
}
