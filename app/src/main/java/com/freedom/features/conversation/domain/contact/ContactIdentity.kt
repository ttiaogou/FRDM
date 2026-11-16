package com.freedom.features.conversation.domain.contact

/** Confidence of a contact identity; display-name-only identities are explicitly provisional. */
enum class ContactIdentityConfidence {
    PLATFORM_ID_VERIFIED,
    DISPLAY_NAME_PROVISIONAL,
    AMBIGUOUS_DUPLICATE_NAME,
    UNRESOLVED,
    NOT_A_PRIVATE_CHAT
}

data class ContactProfile(
    val contactId: String,
    /** One-way hash of a platform ID or normalized display-name key; never stores the raw key. */
    val identityKeyHash: String,
    val displayName: String,
    val identitySource: String,
    val confidence: ContactIdentityConfidence,
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long
)

data class ContactIdentityObservation(
    val displayName: String?,
    val isPrivateChat: Boolean,
    /** Optional stable platform identifier. The current inbox extractor does not expose one yet. */
    val platformUserId: String? = null
)

data class ContactRowIdentity(
    val snapshotId: String,
    val temporaryUserId: String,
    val contactId: String?,
    val confidence: ContactIdentityConfidence
)
