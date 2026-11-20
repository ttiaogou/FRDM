package com.freedom.adapters.social.momo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoAvatarClickTransactionStateTest {
    @Test
    fun normalAvatarClickTransactionHasExplicitOrderedStates() {
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_CLICK_PREPARING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_PAGE_READING
            )
        )
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_PAGE_READING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_LOCATING
            )
        )
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_LOCATING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING
            )
        )
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_CLICKING
            )
        )
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_CLICKING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING
            )
        )
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_COMPLETED
            )
        )
    }

    @Test
    fun transientFailureCanWaitOrEnterUnknownWithoutBeingCompleted() {
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_CLICKING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING
            )
        )
        assertTrue(
            MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
            )
        )
        assertFalse(
            MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING.canTransitionTo(
                MomoAvatarClickTransactionState.AVATAR_COMPLETED
            )
        )
    }
}
