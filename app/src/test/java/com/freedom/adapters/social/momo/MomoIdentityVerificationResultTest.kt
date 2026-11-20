package com.freedom.adapters.social.momo

import com.freedom.features.conversation.domain.contact.ContactIdentityConfidence
import org.junit.Assert.assertEquals
import org.junit.Test

class MomoIdentityVerificationResultTest {
    @Test
    fun verified_requires_platform_confidence_and_contact_id() {
        assertEquals(
            MomoIdentityVerificationResult.IDENTITY_VERIFIED,
            classifyMomoIdentityVerification(
                ContactIdentityConfidence.PLATFORM_ID_VERIFIED,
                "contact-1"
            )
        )
    }

    @Test
    fun non_verified_confidence_is_retryable_without_explicit_conflict() {
        assertEquals(
            MomoIdentityVerificationResult.IDENTITY_RETRYABLE,
            classifyMomoIdentityVerification(
                ContactIdentityConfidence.UNRESOLVED,
                null
            )
        )
    }

    @Test
    fun explicit_terminal_categories_have_priority() {
        assertEquals(
            MomoIdentityVerificationResult.IDENTITY_CONFLICT,
            classifyMomoIdentityVerification(null, null, explicitIdentityConflict = true)
        )
        assertEquals(
            MomoIdentityVerificationResult.IDENTITY_USER_STOPPED,
            classifyMomoIdentityVerification(null, null, userStopped = true)
        )
    }
}
