package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import com.freedom.features.recovery.RecoveryCheckpoint
import com.freedom.features.recovery.RecoveryCheckpointAdapter
import com.freedom.features.recovery.RecoveryCheckpointAdapterContext

/**
 * 只映射消息列表已保存的阶段和下一游标。
 * attemptedSessionKeys 表示尝试过的会话，不等于已完成记录，不能误写入 completedRecordKeys。
 * 其余遍历状态和会话锚点仍由原检查点仓库保管。
 */
class PrivateMessagesRecoveryCheckpointAdapter :
    RecoveryCheckpointAdapter<PrivateMessagesTraversalCheckpoint> {
    override fun adapt(
        checkpoint: PrivateMessagesTraversalCheckpoint,
        context: RecoveryCheckpointAdapterContext
    ): RecoveryCheckpoint {
        require(context.pageType == ChatPageKind.CHAT_LIST.name) {
            "Private messages recovery requires a confirmed message-list page"
        }
        require(checkpoint.phase.isNotBlank()) { "Message-list checkpoint phase must not be blank" }
        return RecoveryCheckpoint(
            checkpointId = context.checkpointId,
            taskId = PrivateMessagesTaskController.TASK_ID,
            routeId = PrivateMessagesTaskController.TASK_ID,
            runGeneration = context.runGeneration,
            executionPhase = checkpoint.phase,
            targetPackage = context.targetPackage,
            pageType = context.pageType,
            pageFingerprint = context.pageFingerprint,
            targetIdentityFingerprint = context.targetIdentityFingerprint,
            businessCursor = checkpoint.nextCursorKey,
            completedRecordKeys = emptySet(),
            recoveryReason = context.recoveryReason,
            savedAtEpochMs = checkpoint.savedAtEpochMs
        )
    }
}
