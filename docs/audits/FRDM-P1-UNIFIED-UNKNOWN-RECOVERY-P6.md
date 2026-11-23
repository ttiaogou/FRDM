# FRDM P1 — P6 UNKNOWN 统一恢复事务完全收口

基线：`ttiaogou/FRDM` → `P1`，`23e2149cc606f5df4ff53d993a75e2fa8fe51008`（P5 Push/PR CI Success）。

## 审计简报

前五阶段已分别完成统一无障碍入口（P1）、页面观察协调器（P2）、任务运行代次和租约（P3）、精确 UI 操作许可（P4）、业务恢复适配器注册表（P5）。P5 的注册表只统一只读路由识别；它**不是** UNKNOWN 恢复事务的统一管理器。

P6 基线仍存在以下具体分裂：

1. AI Reply 的消息列表和收到招呼由 `AiReplyObservationRuntime.recoveryTransaction`（`RecoveryEventDispatcher`）管理，一般 `PAGE_ROUTE_CHANGED`、`MESSAGE_LIST_UNKNOWN`、`RECEIVED_GREETINGS_UNKNOWN`、手势可重试异常会经 `requestUnifiedPauseAndCheckpoint` 持久化原业务检查点，随后只读恢复。
2. 动态浏览 `DynamicPageRecovery` 有自己的 `RecoveryEventDispatcher` 和 `RecoveryScheduler`。虽然它与 AI 使用相同的事件/状态机类型，**没有一个进程级入口**在事件提交前共同检查运行代次、任务与检查点身份。
3. 两个 AI 检查点双快照验证器还各自构造 `RecoveryEventDispatcher`；其观察代次从零开始，不能直接和 AI 主恢复事务共享同一个 event slot，否则服务重连时会误把合法验证当成旧事件。
4. 空快照 `EMPTY_SNAPSHOT`、列表边界 `MESSAGE_LIST_BOUNDARY_UNKNOWN` 曾直接给局部分发器发 `UnknownPage`，未与当前已运行任务的统一检查点请求绑定。
5. 几条可重试的未读状态无法判定/低频页面重新获取失败分支只进入 `PAUSED`，没有启动相同的检查点恢复流程与持续只读观察。这些情况应区别于明确的用户停止或原有业务安全拒绝（身份冲突、不可安全输入/导航等），不能盲目自动授权动作。

## P6 事务入口与行为边界

```
AppCompositionRoot (process scope)
  └── UnifiedRecoveryTransactionManager [一个实例]
         ├─ PRIMARY_LANE / AI Reply 主事务
         │    ├─ AI 私聊 UNKNOWN / 发送确认未知 / 可重试列表异常
         │    └─ 收到招呼 UNKNOWN / 恢复页指纹异常
         ├─ PRIMARY_LANE / AccountBrowse 主事务
         │    └─ 动态页 UNKNOWN / 导航或手势暂时失败
         ├─ AI_REPLY_PRIVATE_MESSAGES_CHECKPOINT (双快照子槽)
         └─ AI_REPLY_RECEIVED_GREETINGS_CHECKPOINT (双快照子槽)
              ↓
     共用 P6 事件准入（taskId + routeId + runGeneration + checkpointId）
              ↓
     原 RecoveryEventDispatcher / UnifiedRecoveryTransactionStateMachine
              ↓
     原业务独立 RecoveryScheduler / CheckpointVerifier / CheckpointRepository
              ↓
     原 P3 TaskCoordinator 与 P4 UiWriteAuthorization（仍须再验证）
```

这里的**单一管理器**指进程级唯一的恢复请求准入、任务与检查点身份仲裁；不同业务的调度定时器、持久化计划及双快照局部 reducer 仍是**隔离的子事务**，不能错误地将多个检查点共享一组验证计数或将某一任务的完成授予另一任务。P0 的原所有者构造数量和严苛断言保持不变，不借迁移放宽安全检查。

- `UnifiedRecoveryTransactionManager.dispatch` 以一把进程级锁将身份检查与原 `RecoveryEventDispatcher` 实际 reducer 提交串行化：拒绝旧代次、其他路由/检查点、倒退观察代次；未完成的检查点不能被同一运行代次的新 UNKNOWN 覆盖。
- `RecoveryEventDispatcher` 的原有事件接口不变。生产 AI 和 Browse 均注入同一个管理器；AI 两个检查点子验证器也通过 `CheckpointVerificationWiring` 注入同一管理器，但使用独立验证子槽，从而不改变原样本计数。
- 任一任务只有显式的 `ResumeCompleted` 或原业务完成了**新鲜同路由双快照、业务确认和 UI 租约转交**后才清理对应管理槽。AI 在既有 `markUiOwnershipTransferred()` 之后调用 `completeVerified`。
- 无业务任务身份的启动前观察只记录 unbound 诊断，不捏造 checkpoint/task/已确认页面、不授予 UI 许可；旧代次晚到的异步回调一律拒绝修改当前事务状态。
- AI `EMPTY_SNAPSHOT` 和 `MESSAGE_LIST_BOUNDARY_UNKNOWN` 在有正在运行的任务时进入原 `requestUnifiedPauseAndCheckpoint`；初始未读 UNKNOWN、从收到招呼返回普通消息列表后的未读 UNKNOWN、低频复查页不确定或未读状态不确定，均进入原检查点恢复并使用已经存在的 `RecoveryScheduler.readOnlyRetryDelayMs(1)` 只读观察间隔。额外收口当前私聊任务的通用 `CONFIRMED_UNKNOWN`（即使缺少先前稳定列表页面）和点击会话后非预期页面的可重试异常：仅重新取证和检查点恢复，不重放点击、不盲目返回。首次必需的上滑准入、手势执行节奏和安全点击限制不变。
- 浏览业务的 `pauseAccountBrowse → AccountBrowseRuntime.requestRecovery → DynamicPageRecovery.observePage` 原路径保持：任何动作未获确认后继续安全暂停和只读观察，原有限次导航/动作尝试的保护仍在**动作端**生效，并不变成 UNKNOWN 事务的硬终止上限。
- `RecoveryScheduler` 继续按照原来的 500ms、1s、2s、5s、10s、30s 非终止退避；触摸期间仍不执行危险操作，失效页/窗口/Activity/身份仍不能通过恢复验证。

## 验证覆盖

- 新增 manager 测试：AI 与 Browse 多任务隔离、两次样本和明确恢复完成、跨运行代次拒绝、同一运行代次不同 checkpoint 禁止冒充、子验证槽不污染主事务、无任务启动观测不能捏造 UI 授权。
- 保留既有 AI 消息列表 / 收到招呼 / 动态浏览 UNKNOWN、服务重连、进程重启、业务恢复时序、手势和 P0～P5 架构测试。
- 新增 P6 架构门禁与回归负例：进程内单管理器实例、两业务注入链、AI 主/子分发器和 Browse 分发器接线、旧代次拒绝、统一检查点 UNKNOWN 入口、未读和空页可重试事务接线、UI 转交后才完成事务。
- 完整 CI 必须执行 Python 架构测试、Android 资源校验、Core/Business/Gesture/App 测试、Freedom/OCR Plugin Debug APK 构建及 CodeQL，不删测试、不修改算法/页面指纹/原业务断言。

## P5 关联、已知边界及真机验证

P5 的“恢复路由属于哪个任务”现在作为 P6 的事件身份基础；P6 在此基础上解决“不同恢复分支只能各自通过自己分发器而没有共用进程级仲裁”的缺口。P4 的 UI 写许可仍是独立且必要的最后一道安全关，管理器的 `RECOVERING` 或 `RESUMING` 不代表 Android 可操作。

所有明确的业务安全拒绝（例如身份冲突、缺少已验证目标、用户停止、无有效输入控件）继续保持 fail-closed；它们不在“可自动恢复异常”的范围内，不被强制转成危险操作循环。对于没有可归属任务 ID 的早期观察失败也不会伪造检查点。

本轮仍需真机验证：无 Usage Access 的 Activity 证据不足，服务断开重连/进程崩溃重启，用户持续操作与静默窗口，聊天详情 OCR 异步回调，以及因卡片遮挡造成的头像实时定位/重试。CI 和单测不能代替这些 Android 现场验证；只通过 GitHub API 写入时无法断言本地 `git status`，以远端 P1 HEAD、diff 和 CI 为准。
