package com.freedom.adapters.social.momo

/**
 * Page-aware popup-candidate rules for the known Momo received-greetings profile-card deck.
 *
 * The profile Activity exposes several overlapping, preloaded cards below one visible card.
 * Those carousel layers and their centered descendants are normal page content, not independent
 * popup windows. Only their geometric overlay heuristic is suppressed; explicit Dialog/Popup
 * classes, unknown dismissable nodes, and all candidates outside the deck remain inspectable.
 */
internal object MomoPopupCandidatePolicy {
    const val SAY_HI_CARD_STACK_VIEW_ID = "com.immomo.momo:id/slideStackView"

    private val EXPECTED_CARD_DECK_VIEW_IDS = setOf(
        SAY_HI_CARD_STACK_VIEW_ID,
        "com.immomo.momo:id/slide_card_container",
        "com.immomo.momo:id/slidecard_layout",
        "com.immomo.momo:id/rv_card_content",
        "com.immomo.momo:id/slide_like_container",
        "com.immomo.momo:id/slide_dislike_container",
        "com.immomo.momo:id/top_bar_container",
        "com.immomo.momo:id/ai_reply_section",
        "com.immomo.momo:id/svga_like_bg"
    )


    private val CHAT_INLINE_CARD_CUES = setOf("是否关注对方", "相遇成功", "聊天助手")

    /** True only for the target app's known inline chat cards; this is not a blanket dialog bypass. */
    fun isInsideKnownChatInlineCard(
        activityClass: String?,
        inherited: Boolean,
        text: String,
        description: String
    ): Boolean {
        if (activityClass != "com.immomo.momo.message.activity.ChatActivity") return false
        if (inherited) return true
        val evidence = "$text $description"
        return CHAT_INLINE_CARD_CUES.any { evidence.contains(it, ignoreCase = true) }
    }

    fun isFreedomOwnedOverlayPackage(packageName: String?): Boolean =
        packageName == "com.freedom" || packageName == "com.freedom.debug"

    fun chatCandidateScore(
        insideKnownInlineCard: Boolean,
        dismissable: Boolean,
        explicitDismissControl: Boolean,
        dialogClass: Boolean,
        centeredOverlay: Boolean
    ): Int = when {
        // Real Dialog/Popup semantics remain visible even when nested inside inline content.
        dialogClass -> 90
        insideKnownInlineCard -> 0
        dismissable && explicitDismissControl -> 100
        // ChatActivity is a dense message surface. Geometry alone is not modal evidence there.
        centeredOverlay -> 0
        else -> 0
    }

    fun isInsideExpectedSayHiCardDeck(
        activityClass: String?,
        inherited: Boolean,
        viewId: String
    ): Boolean =
        activityClass == MomoSayHiPageContract.CARD_ACTIVITY &&
            (inherited || viewId == SAY_HI_CARD_STACK_VIEW_ID)

    fun candidateScore(
        activityClass: String?,
        insideExpectedSayHiCardDeck: Boolean,
        viewId: String,
        dismissable: Boolean,
        dialogClass: Boolean,
        centeredOverlay: Boolean
    ): Int {
        val expectedDeckStructure = activityClass == MomoSayHiPageContract.CARD_ACTIVITY &&
            insideExpectedSayHiCardDeck && viewId in EXPECTED_CARD_DECK_VIEW_IDS

        // Preserve the existing priority: dismissable evidence scores above Dialog class names.
        // Ignore it only for the known carousel's own structural nodes. Unknown dismissable
        // descendants remain candidates and are still checked.
        if (dismissable && !expectedDeckStructure) return 100
        if (dialogClass) return 90

        // A centered rectangle is only a weak popup signal. Inside slideStackView it describes
        // the visible card, its preloaded neighbors, and their content regions, so ignore that
        // heuristic there while continuing to traverse and inspect every descendant.
        if (centeredOverlay && !insideExpectedSayHiCardDeck) return 20
        return 0
    }

    fun isExpectedDeckCandidateSuppressed(
        activityClass: String?,
        insideExpectedSayHiCardDeck: Boolean,
        viewId: String,
        dismissable: Boolean,
        centeredOverlay: Boolean,
        dialogClass: Boolean
    ): Boolean {
        if (activityClass != MomoSayHiPageContract.CARD_ACTIVITY ||
            !insideExpectedSayHiCardDeck || dialogClass
        ) return false

        val knownDeckStructure = viewId in EXPECTED_CARD_DECK_VIEW_IDS
        return (centeredOverlay && !dismissable) || (dismissable && knownDeckStructure)
    }
}
