package com.freedom.features.aireply

import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.MomoSayHiTraversalCheckpointRecord
import com.freedom.features.aireply.ports.ReceivedGreetingsTraversalStatus
import com.freedom.features.recovery.RecoveryCheckpointAdapterContext
import com.freedom.features.recovery.RecoveryCheckpointRestoreSource
import com.freedom.features.recovery.RecoveryCheckpointVerificationCoordinator
import com.freedom.features.recovery.RecoveryCheckpointVerificationResult
import com.freedom.features.recovery.RecoveryScheduler
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager

/**
 * 收到招呼持久化检查点的只读验证入口。
 *
 * 使用 3-A 的映射契约与统一验证器；页面指纹仍由 ChatPagePort 从当前真实窗口采集。
 * 本类不会恢复旧节点、操作 UI、恢复延期队列或转交任务租约。
 * 当前招呼业务阶段必须来自已有任务状态，不能由检查点记录中的行号/延期队列推断。
 */
internal class ReceivedGreetingsCheckpointVerification(
    private val scheduler: TaskScheduler,
    private val targetPackage: () -> String,
    private val currentRunGeneration: () -> Long,
    private val onReadOnlyObservation: () -> Unit,
    private val onLog: (String) -> Unit = {},
    private val scheduleRepository: RecoveryScheduleRepository? = null,
    private val recoveryTransactionManager: UnifiedRecoveryTransactionManager? = null
) {
    private data class Binding(
        val checkpointId: String,
        val savedAtEpochMs: Long,
        val executionPhase: String,
        val runGeneration: Long,
        val pageFingerprint: String
    )

    private val adapter = ReceivedGreetingsRecoveryCheckpointAdapter()
    private var schedule: RecoveryScheduler? = null
    private var verifier: RecoveryCheckpointVerificationCoordinator? = null
    private var binding: Binding? = null
    private var lastObservationGeneration = -1L

    fun observe(
        checkpoint: MomoSayHiTraversalCheckpointRecord,
        checkpointId: String,
        recoveryReason: String,
        restoreSource: RecoveryCheckpointRestoreSource,
        snapshot: ChatPageSnapshot,
        pageFingerprint: String,
        routeVerified: Boolean,
        executionStatus: ReceivedGreetingsTraversalStatus,
        observationGeneration: Long
    ): RecoveryCheckpointVerificationResult {
        if (observationGeneration <= lastObservationGeneration) {
            return RecoveryCheckpointVerificationResult.StaleEvidence
        }
        if (!routeVerified ||
            snapshot.packageName != targetPackage() ||
            snapshot.pageKind != ChatPageKind.NON_CHAT ||
            pageFingerprint.isBlank()
        ) return reject(observationGeneration)

        lastObservationGeneration = observationGeneration
        val current = Binding(
            checkpointId = checkpointId,
            savedAtEpochMs = checkpoint.savedAtEpochMs,
            executionPhase = executionStatus.name,
            runGeneration = currentRunGeneration(),
            pageFingerprint = pageFingerprint
        )
        if (verifier == null || binding != current) {
            val unified = adapter.adapt(
                checkpoint,
                RecoveryCheckpointAdapterContext(
                    checkpointId = checkpointId,
                    runGeneration = currentRunGeneration(),
                    targetPackage = targetPackage(),
                    pageType = ChatPageKind.NON_CHAT.name,
                    pageFingerprint = pageFingerprint,
                    recoveryReason = recoveryReason,
                    executionPhase = executionStatus.name
                )
            )
            val readOnlySchedule = schedule ?: RecoveryScheduler(
                scheduler = scheduler,
                nowEpochMs = System::currentTimeMillis,
                onReadOnlyObservation = { onReadOnlyObservation() },
                onLog = onLog,
                scheduleRepository = scheduleRepository
            ).also { schedule = it }
            verifier = RecoveryCheckpointVerificationCoordinator(
                transaction = CheckpointVerificationWiring.newDispatcher(
                    onLog, recoveryTransactionManager, "AI_REPLY_RECEIVED_GREETINGS_CHECKPOINT"
                ),
                scheduler = readOnlySchedule,
                onLog = onLog
            ).also { it.restore(unified, restoreSource) }
            binding = current
        }
        return requireNotNull(verifier).observe(
            CheckpointVerificationWiring.verifiedEvidence(
                snapshot = snapshot,
                pageFingerprint = pageFingerprint,
                runGeneration = currentRunGeneration(),
                observationGeneration = observationGeneration
            )
        )
    }

    /** 错误页面、未知状态与路由缺失须使上次采样失效；不能删除业务检查点。 */
    fun reject(observationGeneration: Long): RecoveryCheckpointVerificationResult {
        if (observationGeneration <= lastObservationGeneration) {
            return RecoveryCheckpointVerificationResult.StaleEvidence
        }
        lastObservationGeneration = observationGeneration
        return verifier?.observe(
            CheckpointVerificationWiring.rejectedEvidence(
                runGeneration = currentRunGeneration(),
                observationGeneration = observationGeneration
            )
        ) ?: RecoveryCheckpointVerificationResult.NoActiveCheckpoint
    }

    /** 适配器二次确认或租约尚未通过时，不可复用已得到的两次采样凭证。 */
    fun deferResume() {
        verifier = null
        binding = null
    }

    /** 仅供任务协调者在完成页面/租约确认后调用。 */
    fun completeResume(): Boolean {
        val active = verifier ?: return false
        if (active.state() != RecoveryTransactionState.RESUMING || !active.completeResume()) return false
        verifier = null
        binding = null
        return true
    }

    fun hasActiveVerification(): Boolean = verifier != null

    /** 服务中断时取消旧定时器；下次必须使用新鲜快照重新验证。 */
    fun reset() {
        schedule?.suspendForServiceDisconnect()
        schedule = null
        verifier = null
        binding = null
        lastObservationGeneration = -1L
    }
}
