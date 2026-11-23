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
}
