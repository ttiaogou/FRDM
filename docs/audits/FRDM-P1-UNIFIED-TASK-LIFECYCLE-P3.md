# FRDM P1 — P3 统一任务生命周期、运行代次与租约

仓库：`ttiaogou/FRDM`；唯一目标分支：`P1`。P3 基线 SHA：`bdf710cfb1d8c6a0083c80e56472ebd65d2ef3ea`。

## 审计简报

1. `AppCompositionRoot` 已创建唯一的进程级 `TaskCoordinator(UiOperationOwnershipCoordinator)`；AI Reply 的 `AiReplyTaskOwnership → TaskExecutionController` 与 AccountBrowse 的 `AccountBrowseModeRuntimeAdapter → TaskExecutionController` 都注入这个公共任务槽。普通消息与收到招呼在同一 AI Reply 控制器中按核验条件完成原子转交。
2. `RunSessionCoordinator` 使用进程持有的 `RunGenerationClock`，在运行请求变化、用户停止、无障碍断开/中断、运行时重置及服务销毁时增加代次；过去只在控制器再次 `tryAcquire` 新代次时，`TaskCoordinator` 才清理旧租约。因此旧公共任务槽可能在代次推进后、下一次新租约产生前仍报告旧任务 ID。
3. `TaskExecutionController` 已通过 `runGeneration != currentRunGeneration()` 拒绝过期控制器的 `claim/owns`，但公共 `activeTaskId()` 原来仅返回保留的任务 ID，无法立即代表本次运行的有效租约。App 层有只读使用 `activeTaskId` 的门禁，存在跨层状态不一致窗口。
4. AI Reply 和 AccountBrowse 各自保留页面、遍历、阶段和检查点业务状态；这些不是第二套 UI 租约协调器，不应为追求统一而重写手势或恢复逻辑。

## P3 修改和权限边界

- `TaskCoordinator.advanceRunGeneration(generation)` 成为进程运行代次的统一撤销入口。新代次即时释放旧任务对应的 UI 租约、清空任务槽并记录最新代次；重复或过时代次通知幂等，不会撤销当前运行租约。
- `RunSessionCoordinator.advanceRunGeneration()` 在推进进程时钟后同步通知共享 `TaskCoordinator`；既有的停止、切换、断开、重连前断开、中断、重置及销毁路径统一生效。**撤销发生在旧业务运行时清理或新业务运行时启动之前**。
- `TaskCoordinator.tryAcquire` 仍按原规则接受同代次幂等申请、拒绝旧代次、支持有效租约的原子 `transfer`；仅复用上述公共撤销逻辑。
- `TaskCoordinator.activeTaskId()` 只报告当前仍有效的 `TaskLease`，避免上层把已撤销能力当成任务在运行。
- `AppCompositionRoot` 为 `RunSessionCoordinator` 注入同一个 `root.taskCoordinator`；不创建第二套公共任务协调器。
- `TaskExecutionController` 原有的独立测试模式保持兼容；真实生产组装必须注入共享实例。P3 静态门禁要求三个生产注入点共用相同对象、两种业务都通过共享 `TaskExecutionController`，禁止生产额外创建执行控制器、独立会话协调器或业务侧推进共享运行代次。
- 本阶段不改变 `TaskExecutionController` 的租约申请/转移策略或业务的页面验证前置条件；不为任何失败或 UNKNOWN 直接授予操作权。

## 回归验证

- Core：代次推进后无新任务时旧租约与 UI 所有者立即清零；旧释放及旧代次申请无效；重复/旧代次通知不破坏新租约。
- App：AI 私聊→招呼同代次原子转交，AI→自动浏览切换前旧租约撤销，服务断开→重连后才允许新代次重新申请，用户停止撤销；外部重置、中断、销毁都立即清空任务槽。
- 原有全部 Core、Business、Gesture、App 测试与两份 APK 构建保留；`tools/architecture` 增加正反向 P3 接线检查并继续执行 P0/P1/P2 检查。

## 与上一阶段关联及留待后续

P2 已收敛页面观察调度与观察/业务决策边界，最新基线 CI 已成功。P3 修复的是**观察/运行代次发生变化之后，公共任务槽未同步失效**的跨层一致性缺口。P4 仍须逐项审核各 UI 操作端口是否验证精确任务租约及运行代次；P5/P6 仍有多路恢复实例待统一。本轮不代替后续阶段，更不降低原 Activity/页面指纹、双快照、身份验证或手势安全门禁。

## 边界与未验证项

静态检查加 CI 单测可保证共同组装与代次撤销行为，不能证明设备端所有异步 OCR、手势回调及 Activity/窗口取证时序均正确。无 Usage Access、崩溃重启及无障碍服务重连下的页面恢复必须继续真机回归。本轮仅使用 GitHub 远端接口，因此不报告无法验证的本地工作区清洁状态。
