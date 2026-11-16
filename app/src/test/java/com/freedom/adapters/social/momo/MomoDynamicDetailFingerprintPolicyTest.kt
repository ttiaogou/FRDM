package com.freedom.adapters.social.momo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomoDynamicDetailFingerprintPolicyTest {
    private fun complete() = MomoDynamicDetailFingerprintSignals(
        packageMatches = true,
        snapshotComplete = true,
        authorAvatarEntry = true,
        followAction = true,
        overflowAction = true,
        mainContentList = true,
        fixedBottomCommentAction = true,
        headerControlsShareShell = true,
        commentActionOutsideContentList = true
    )

    @Test fun allFourStableControlsAndTheirContainerRelationshipsConfirmDetail() {
        assertEquals(
            "AUTHOR_HEADER_TRIO_AND_FIXED_BOTTOM_COMMENT",
            MomoDynamicDetailFingerprintPolicy.matchedPath(complete())
        )
        assertTrue(MomoDynamicDetailFingerprintPolicy.isConfirmed(complete()))
    }

    @Test fun everyOneOfTheFourControlsIsMandatory() {
        val valid = complete()
        assertFalse(MomoDynamicDetailFingerprintPolicy.isConfirmed(valid.copy(authorAvatarEntry = false)))
        assertFalse(MomoDynamicDetailFingerprintPolicy.isConfirmed(valid.copy(followAction = false)))
        assertFalse(MomoDynamicDetailFingerprintPolicy.isConfirmed(valid.copy(overflowAction = false)))
        assertFalse(MomoDynamicDetailFingerprintPolicy.isConfirmed(valid.copy(fixedBottomCommentAction = false)))
    }

    @Test fun headerControlsMustBelongToTheSameHeaderShell() {
        assertFalse(
            MomoDynamicDetailFingerprintPolicy.isConfirmed(
                complete().copy(headerControlsShareShell = false)
            )
        )
    }

    @Test fun bottomCommentLauncherMustBeOutsideTheDynamicContentRecyclerView() {
        assertFalse(
            MomoDynamicDetailFingerprintPolicy.isConfirmed(
                complete().copy(commentActionOutsideContentList = false)
            )
        )
    }

    @Test fun contentListRemainsMandatoryAndOnlyOneCompletePathExists() {
        assertFalse(
            MomoDynamicDetailFingerprintPolicy.isConfirmed(
                complete().copy(mainContentList = false)
            )
        )
    }

    @Test fun incompleteSnapshotOrWrongPackageFailsClosed() {
        assertFalse(MomoDynamicDetailFingerprintPolicy.isConfirmed(complete().copy(snapshotComplete = false)))
        assertFalse(MomoDynamicDetailFingerprintPolicy.isConfirmed(complete().copy(packageMatches = false)))
    }

    @Test fun postAndCommentPayloadAreNotFingerprintInputs() {
        // No comment count, comment text, empty-state copy, post text, photo count or like count
        // participates in the identity policy; only the fixed four controls and structure do.
        assertTrue(MomoDynamicDetailFingerprintPolicy.isConfirmed(complete()))
    }
}
