package com.freedom.features.accountbrowse

import com.freedom.features.accountbrowse.ports.AccountBrowseCheckpoint
import com.freedom.features.recovery.RecoveryCheckpoint
import com.freedom.features.recovery.RecoveryCheckpointAdapter
import com.freedom.features.recovery.RecoveryCheckpointAdapterContext

/**
 * 将自动浏览的阶段和滑动游标映射到统一恢复元数据。
 * 页面指纹由调用方提供；原检查点中的列表/详情状态仍只在业务仓库恢复。
 */
class AccountBrowseRecoveryCheckpointAdapter : RecoveryCheckpointAdapter<AccountBrowseCheckpoint> {
    override fun adapt(
        checkpoint: AccountBrowseCheckpoint,
        context: RecoveryCheckpointAdapterContext
    ): RecoveryCheckpoint {
        require(context.pageType == "DYNAMIC_FEED") {
            "AccountBrowse recovery requires a confirmed dynamic-feed page"
        }
        require(checkpoint.phase.isNotBlank()) { "Browse checkpoint phase must not be blank" }
        require(checkpoint.swipeCount >= 0) { "Browse swipeCount must not be negative" }
        return RecoveryCheckpoint(
            checkpointId = context.checkpointId,
            taskId = "ACCOUNT_BROWSE",
            // 与现有 DynamicPageRecovery 的恢复事务路由保持一致。
            routeId = "account-browse",
            runGeneration = context.runGeneration,
            executionPhase = checkpoint.phase,
            targetPackage = context.targetPackage,
            pageType = context.pageType,
            pageFingerprint = context.pageFingerprint,
            targetIdentityFingerprint = context.targetIdentityFingerprint,
            businessCursor = checkpoint.swipeCount.toString(),
            completedRecordKeys = emptySet(),
            recoveryReason = context.recoveryReason,
            savedAtEpochMs = checkpoint.savedAtEpochMs
        )
    }
}
