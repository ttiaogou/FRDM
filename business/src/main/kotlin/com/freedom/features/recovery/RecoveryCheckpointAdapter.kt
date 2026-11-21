package com.freedom.features.recovery

/**
 * 业务检查点映射到统一恢复检查点时，由业务调用方提供的已确认页面证据。
 *
 * 原业务检查点可能只保存游标、阶段或已处理标识，并不包含可验证的页面指纹。
 * 此上下文必须来自调用方已确认的页面，不能从旧坐标、行号或联系人名称推断。
 * 此对象不代表恢复已完成，也不授予 UI 操作权。
 */
data class RecoveryCheckpointAdapterContext(
    val checkpointId: String,
    val runGeneration: Long,
    val targetPackage: String,
    val pageType: String,
    val pageFingerprint: String,
    val recoveryReason: String,
    val targetIdentityFingerprint: String? = null,
    /** 仅供原业务检查点未保存阶段的适配器显式提供，禁止猜测默认阶段。 */
    val executionPhase: String? = null
) {
    init {
        require(checkpointId.isNotBlank()) { "checkpointId must not be blank" }
        require(runGeneration >= 0L) { "runGeneration must not be negative" }
        require(targetPackage.isNotBlank()) { "targetPackage must not be blank" }
        require(pageType.isNotBlank()) { "pageType must not be blank" }
        require(pageFingerprint.isNotBlank()) { "pageFingerprint must not be blank" }
        require(recoveryReason.isNotBlank()) { "recoveryReason must not be blank" }
    }
}

/**
 * 只做业务检查点到统一检查点的值映射。
 * 完整业务进度继续由原 Repository 保存；本接口不启动恢复、调度或动作。
 */
interface RecoveryCheckpointAdapter<T> {
    fun adapt(checkpoint: T, context: RecoveryCheckpointAdapterContext): RecoveryCheckpoint
}
