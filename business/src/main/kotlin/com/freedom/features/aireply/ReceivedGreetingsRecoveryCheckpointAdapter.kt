package com.freedom.features.aireply

import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.recovery.RecoveryCheckpoint
import com.freedom.features.recovery.RecoveryCheckpointAdapter
import com.freedom.features.recovery.RecoveryCheckpointAdapterContext

/**
 * 收到的招呼检查点不保存业务执行阶段或列表游标，因此必须由调用方提供阶段，
 * 绝不从 deferredRows、重试次数或页面行号猜测。
 * 已处理行 Hash 可作为统一完成记录；联系人 Hash 与延期队列保留在原业务仓库。
 */
class ReceivedGreetingsRecoveryCheckpointAdapter :
    RecoveryCheckpointAdapter<MomoSayHiTraversalCheckpointRecord> {
    override fun adapt(
        checkpoint: MomoSayHiTraversalCheckpointRecord,
        context: RecoveryCheckpointAdapterContext
    ): RecoveryCheckpoint {
        require(context.pageType == ChatPageKind.NON_CHAT.name) {
            "Received greetings recovery requires a confirmed non-chat greetings page"
        }
        val phase = requireNotNull(context.executionPhase?.takeIf(String::isNotBlank)) {
            "Received greetings execution phase must be provided by its task owner"
        }
        return RecoveryCheckpoint(
            checkpointId = context.checkpointId,
            taskId = ReceivedGreetingsTaskController.TASK_ID,
            routeId = ReceivedGreetingsTaskController.TASK_ID,
            runGeneration = context.runGeneration,
            executionPhase = phase,
            targetPackage = context.targetPackage,
            pageType = context.pageType,
            pageFingerprint = context.pageFingerprint,
            targetIdentityFingerprint = context.targetIdentityFingerprint,
            businessCursor = null,
            completedRecordKeys = checkpoint.processedRowKeys.toSet(),
            recoveryReason = context.recoveryReason,
            savedAtEpochMs = checkpoint.savedAtEpochMs
        )
    }
}
