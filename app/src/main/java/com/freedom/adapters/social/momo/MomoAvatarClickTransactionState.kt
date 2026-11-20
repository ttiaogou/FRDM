package com.freedom.adapters.social.momo

/**
 * Explicit state for the short-lived avatar/profile navigation transaction.
 *
 * This state is deliberately separate from page fingerprints and from the
 * received-greetings traversal state. A gesture completing is not proof that
 * the profile page was entered, and a missing profile page is not itself a
 * terminal task failure.
 */
internal enum class MomoAvatarClickTransactionState {
    AVATAR_CLICK_PREPARING,
    AVATAR_PAGE_READING,
    AVATAR_LOCATING,
    AVATAR_IDENTITY_VERIFYING,
    AVATAR_CLICKING,
    AVATAR_ENTRY_VERIFYING,
    AVATAR_RETRY_WAITING,
    AVATAR_UNKNOWN_RECOVERY,
    AVATAR_COMPLETED
}

internal fun MomoAvatarClickTransactionState.canTransitionTo(
    next: MomoAvatarClickTransactionState
): Boolean = when (this) {
    MomoAvatarClickTransactionState.AVATAR_CLICK_PREPARING -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_PAGE_READING,
        MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
        MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
    )
    MomoAvatarClickTransactionState.AVATAR_PAGE_READING -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_LOCATING,
        MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
        MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
    )
    MomoAvatarClickTransactionState.AVATAR_LOCATING -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING,
        MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
        MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
    )
    MomoAvatarClickTransactionState.AVATAR_IDENTITY_VERIFYING -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_CLICKING,
        MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
        MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
    )
    MomoAvatarClickTransactionState.AVATAR_CLICKING -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING,
        MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
        MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
    )
    MomoAvatarClickTransactionState.AVATAR_ENTRY_VERIFYING -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_COMPLETED,
        MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING,
        MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
    )
    MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_CLICK_PREPARING,
        MomoAvatarClickTransactionState.AVATAR_PAGE_READING,
        MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY
    )
    MomoAvatarClickTransactionState.AVATAR_UNKNOWN_RECOVERY -> next in setOf(
        MomoAvatarClickTransactionState.AVATAR_PAGE_READING,
        MomoAvatarClickTransactionState.AVATAR_RETRY_WAITING
    )
    MomoAvatarClickTransactionState.AVATAR_COMPLETED -> false
}
