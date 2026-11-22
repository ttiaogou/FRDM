package com.freedom.features.aireply

import com.freedom.core.runtime.TaskScheduler
import com.freedom.features.aireply.ports.ChatPageKind
import com.freedom.features.aireply.ports.ChatPageSnapshot
import com.freedom.features.aireply.ports.PrivateMessagesTraversalCheckpoint
import com.freedom.features.recovery.RecoveryCheckpointAdapterContext
import com.freedom.features.recovery.RecoveryEventIdentity
import com.freedom.features.recovery.RecoveryCheckpointRestoreSource
import com.freedom.features.recovery.RecoveryCheckpointVerificationCoordinator
import com.freedom.features.recovery.RecoveryCheckpointVerificationResult
import com.freedom.features.recovery.RecoveryScheduler
import com.freedom.features.recovery.RecoveryScheduleRepository
import com.freedom.features.recovery.RecoveryTransactionState
import com.freedom.features.recovery.UnifiedRecoveryTransactionManager

/**
 * 消息列表检查点的只读验证接线。页面身份与结构指纹由原 AI 页面观察链提供；
 * 只调用通用检查点验证器，不获取 UI 租约、不推进遍历游标、不执行任何手势。
 *
 * 旧代次、错误路由及不稳定页面不能完成恢复；页面结构变化会重新执行两次新鲜采样，
 * 业务检查点仍由原来的 PrivateMessagesTraversalCheckpointRepository 负责保存。
 */
internal class MessageListCheckpointVerification(
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
        val phase: String,
        val runGeneration: Long,
        val pageFingerprint: String
    )

    private val adapter = PrivateMessagesRecoveryCheckpointAdapter()
    private var schedule: RecoveryScheduler? = null
    private var verifier: RecoveryCheckpointVerificationCoordinator? = null
    private var binding: Binding? = null
    private var lastObservationGeneration = -1L

    fun observe(
        checkpoint: PrivateMessagesTraversalCheckpoint,
        checkpointId: String,
        recoveryReason: String,
        restoreSource: RecoveryCheckpointRestoreSource,
        snapshot: ChatPageSnapshot,
        pageFingerprint: String,
        routeVerified: Boolean,
        observationGeneration: Long
    ): RecoveryCheckpointVerificationResult {
        if (observationGeneration <= lastObservationGeneration) {
            return RecoveryCheckpointVerificationResult.StaleEvidence
        }
        if (!routeVerified ||
            snapshot.packageName != targetPackage() ||
            snapshot.pageKind != ChatPageKind.CHAT_LIST ||
            pageFingerprint.isBlank()
        ) return reject(observationGeneration)

        lastObservationGeneration = observationGeneration
        val newBinding = Binding(
            checkpointId = checkpointId,
            savedAtEpochMs = checkpoint.savedAtEpochMs,
            phase = checkpoint.phase,
            runGeneration = currentRunGeneration(),
            pageFingerprint = pageFingerprint
        )
        if (verifier == null || binding != newBinding) {
            // A fresh binding invalidates only this checkpoint's verification lane.
            // The primary UNKNOWN transaction and durable business cursor remain owned elsewhere.
            restartVerificationLane()
            // Never use an old viewport or contact name as a page fingerprint. The caller passes
            // the existing recoveryStructuralKey obtained from a fresh, route-matched read.
            val unified = adapter.adapt(
                checkpoint,
                RecoveryCheckpointAdapterContext(
                    checkpointId = checkpointId,
                    runGeneration = currentRunGeneration(),
                    targetPackage = targetPackage(),
                    pageType = ChatPageKind.CHAT_LIST.name,
                    pageFingerprint = pageFingerprint,
                    recoveryReason = recoveryReason
                )
            )
            val observationSchedule = schedule ?: RecoveryScheduler(
                scheduler = scheduler,
                nowEpochMs = System::currentTimeMillis,
                onReadOnlyObservation = { onReadOnlyObservation() },
                onLog = onLog,
                scheduleRepository = scheduleRepository
            ).also { schedule = it }
            verifier = RecoveryCheckpointVerificationCoordinator(
                transaction = CheckpointVerificationWiring.newDispatcher(
                    onLog, recoveryTransactionManager, VERIFICATION_LANE
                ),
                scheduler = observationSchedule,
                onLog = onLog
            ).also { it.restore(unified, restoreSource) }
            binding = newBinding
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

    /** A new non-matching observation invalidates earlier stable samples, not the checkpoint. */
    fun reject(observationGeneration: Long): RecoveryCheckpointVerificationResult {
        if (observationGeneration <= lastObservationGeneration) {
            return RecoveryCheckpointVerificationResult.StaleEvidence
        }
        lastObservationGeneration = observationGeneration
        val activeVerifier = verifier ?: return RecoveryCheckpointVerificationResult.NoActiveCheckpoint
        return activeVerifier.observe(
            CheckpointVerificationWiring.rejectedEvidence(
                runGeneration = currentRunGeneration(),
                observationGeneration = observationGeneration
            )
        )
    }

    /** On a denied task lease, require fresh verification again instead of reusing ResumeReady. */
    fun deferOwnership() {
        restartVerificationLane()
        verifier = null
        binding = null
    }

    /** Only the task owner can acknowledge the shared proof after reclaiming its UI lease. */
    fun completeResume(): Boolean {
        val current = verifier ?: return false
        if (current.state() != RecoveryTransactionState.RESUMING || !current.completeResume()) {
            return false
        }
        verifier = null
        binding = null
        return true
    }

    /** Service boundaries invalidate all pending read-only callbacks and verification samples. */
    fun reset() {
        restartVerificationLane()
        schedule?.suspendForServiceDisconnect()
        schedule = null
        verifier = null
        binding = null
        lastObservationGeneration = -1L
    }


    /**
     * The old verifier's last sample belongs to a separate, non-primary lane.
     * Retire that lane before a replacement emits CheckpointLoaded(generation=0),
     * otherwise the shared manager rejects the replacement as an older observation.
     */
    private fun restartVerificationLane() {
        val previous = binding ?: return
        recoveryTransactionManager?.restartVerificationLane(
            RecoveryEventIdentity(
                taskId = PrivateMessagesTaskController.TASK_ID,
                routeId = PrivateMessagesTaskController.TASK_ID,
                runGeneration = previous.runGeneration,
                checkpointId = previous.checkpointId,
                observationGeneration = lastObservationGeneration.coerceAtLeast(0L)
            ),
            VERIFICATION_LANE
        )
    }

    private companion object {
        const val VERIFICATION_LANE = "AI_REPLY_PRIVATE_MESSAGES_CHECKPOINT"
    }

    fun hasActiveVerification(): Boolean = verifier != null
}
