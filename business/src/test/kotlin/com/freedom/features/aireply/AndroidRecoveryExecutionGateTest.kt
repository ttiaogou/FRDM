package com.freedom.features.aireply

import com.freedom.core.accessibility.api.NodePath
import com.freedom.core.accessibility.api.NodeTarget
import com.freedom.core.accessibility.api.WindowRef
import com.freedom.core.runtime.RunMode
import com.freedom.core.runtime.TargetAppReadinessSnapshot
import com.freedom.core.runtime.TargetAppReadinessStatus
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRecoveryExecutionGateTest {
    private val pkg = "com.immomo.momo"
    private val activity = "com.immomo.momo.maintab.MaintabActivity"
    private val anchor = NodeTarget(WindowRef(pkg, activity, 8L, 116), NodePath(listOf(1)))
    private fun page() = ChatPageSnapshot(
        packageName = pkg, activityClass = activity, pageKind = ChatPageKind.CHAT_LIST,
        scannedNodeCount = 20, editableInputCount = 0, messageListCount = 1,
        sendActionCount = 0, messageRelatedViewIdCount = 1, hasChatTab = true,
        messageListTarget = anchor, inputFieldTarget = null, windowGeneration = 8L
    )
    private fun ready() = TargetAppReadinessSnapshot(
        status = TargetAppReadinessStatus.READY,
        targetPackage = pkg, foregroundPackage = pkg, activityClass = activity,
        runMode = RunMode.AI_REPLY, windowId = 116, pageGeneration = 8L
    )

    @Test
    fun activeReadyWindowAndRouteMayContinueToBusinessDoubleVerification() {
        assertTrue(AndroidRecoveryExecutionGate.assess(page(), pkg, ready()).allowed)
    }

    @Test
    fun startingAndBlockedCanNeverReleaseTheCheckpoint() {
        for (status in listOf(
            TargetAppReadinessStatus.UNKNOWN, TargetAppReadinessStatus.STARTING,
            TargetAppReadinessStatus.BLOCKED
        )) {
            val result = AndroidRecoveryExecutionGate.assess(page(), pkg, ready().copy(status = status))
            assertFalse(result.allowed)
            assertEquals("ANDROID_" + status.name, result.reasonCode)
        }
        assertFalse(AndroidRecoveryExecutionGate.assess(page(), pkg, null).allowed)
    }

    @Test
    fun activityOrForegroundChangesInvalidateEarlierReadyStatus() {
        assertFalse(AndroidRecoveryExecutionGate.assess(
            page(), pkg, ready().copy(activityClass = "OtherActivity")
        ).allowed)
        assertFalse(AndroidRecoveryExecutionGate.assess(
            page(), pkg, ready().copy(foregroundPackage = "com.android.settings")
        ).allowed)
        assertFalse(AndroidRecoveryExecutionGate.assess(
            page(), pkg, ready().copy(runMode = RunMode.ACCOUNT_BROWSE)
        ).allowed)
    }

    @Test
    fun changedWindowIdAndGenerationAlwaysRejectReadyFromPreviousWindow() {
        assertEquals("ACTIVE_WINDOW_IDENTITY_CHANGED",
            AndroidRecoveryExecutionGate.assess(page(), pkg, ready().copy(windowId = 117)).reasonCode)
        assertEquals("ACTIVE_WINDOW_IDENTITY_CHANGED",
            AndroidRecoveryExecutionGate.assess(page(), pkg, ready().copy(pageGeneration = 9L)).reasonCode)
    }

    @Test
    fun nodeFreeGreetingsPageStillRequiresTrustedActivityAndLiveAndroidReady() {
        val greetings = page().copy(
            pageKind = ChatPageKind.NON_CHAT, messageListTarget = null,
            windowGeneration = 0L
        )
        assertTrue(AndroidRecoveryExecutionGate.assess(greetings, pkg, ready()).allowed)
        assertFalse(AndroidRecoveryExecutionGate.assess(
            greetings, pkg, ready().copy(activityClass = "")
        ).allowed)
    }
}
