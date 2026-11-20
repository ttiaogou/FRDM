package com.freedom.adapters.social.momo

import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence

/** Adapter-level outcome; deliberately does not change the shared confidence model. */
internal enum class MomoIdentityVerificationResult {
    IDENTITY_VERIFIED,
    IDENTITY_RETRYABLE,
    IDENTITY_CONFLICT,
    IDENTITY_USER_STOPPED
}

internal fun classifyMomoIdentityVerification(
    confidence: ContactIdentityConfidence?,
    contactId: String?,
    explicitIdentityConflict: Boolean = false,
    userStopped: Boolean = false
): MomoIdentityVerificationResult = when {
    userStopped -> MomoIdentityVerificationResult.IDENTITY_USER_STOPPED
    explicitIdentityConflict -> MomoIdentityVerificationResult.IDENTITY_CONFLICT
    confidence == ContactIdentityConfidence.PLATFORM_ID_VERIFIED && !contactId.isNullOrBlank() ->
        MomoIdentityVerificationResult.IDENTITY_VERIFIED
    else -> MomoIdentityVerificationResult.IDENTITY_RETRYABLE
}
