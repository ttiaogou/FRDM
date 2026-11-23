package com.freedom.features.recovery

/**
 * 持久化恢复观察计划的业务层端口；具体存储由数据层实现。
 *
 * 只保存 [RecoveryScheduleSnapshot] 中的值，不保存定时任务、回调、无障碍节点、
 * 窗口、手势或服务对象。读取计划不意味着页面已验证或已获得 UI 操作权；
 * 后续恢复接线必须重新校验任务、路由、运行代次及检查点，并创建新的只读观察任务。
 * 不同恢复任务必须使用相互隔离的仓库实例，避免两个检查点的观察计划相互覆盖。
 */
interface RecoveryScheduleRepository {
    /** 返回当前保存的恢复计划；没有计划时返回 null。 */
    fun load(): RecoveryScheduleSnapshot?

    /** 用最新的完整快照替换已保存的恢复计划，不累加观察次数或改变退避算法。 */
    fun save(snapshot: RecoveryScheduleSnapshot)

    /** 清除已经结束或被新运行代次取代的恢复计划，不作为 UNKNOWN 或重试耗尽处理。 */
    fun clear()
}
