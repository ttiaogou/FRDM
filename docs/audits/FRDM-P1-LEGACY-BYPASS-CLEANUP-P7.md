# FRDM P1 — P7 清理旧架构旁路与重复职责

- 仓库 / 目标分支：`ttiaogou/FRDM` / `P1`
- 基线 SHA：`3c7be5c776353bee1e93d2e7417597d53c0d50fd`（P6 Push 与 PR CI 均 Success）。
- 策略：只清除证实仍可执行的双通道、重复身份组装及验证器共享状态问题；保留仅服务测试/观察工具的兼容接口和原有安全业务职责。未删除任何生产源文件或既有测试。

## 审计简报：旧路径与实际调用

| 原有路径 | 问题 | P7 收口与验证 |
|---|---|---|
| `AndroidAccessibilityEventPipeline` 的可空 `unifiedAccessibilityEntry` + 三组 `dispatchTargetApplicationEvent` / `dispatchGlobalTouchEvent` / `dispatchActivityTransition` 回调 | 原设计：UnifiedAccessibilityEntry 存在时走新链路，否则仍能直接派发给业务。AppCompositionRoot 已用抛错回调绕过兼容，但旁路代码仍可运行。 | 构造函数改为必须注入非空统一入口；删除三组并存回调和全部 if-null 分支；AppCompositionRoot 不再提供假回调；单测也只通过统一入口验证旧事件顺序。 |
| `PageObservationCoordinator.registerRecoveryAdapter` | 把同一业务恢复适配器同时写入 `PageRecoveryAdapterRegistry`（有 taskId）和 `PageFingerprintRouter`（无 taskId），留下跨业务宽松匹配的后门；反方向原 `registerFingerprintRoute` 还能移除已注册业务适配器。 | 恢复适配器只保存在任务作用域 registry；普通非业务指纹路由仍可单独注册，但不能与恢复路由 ID 冲突或删除业务注册；提供显式 `unregisterRecoveryAdapter`。新增冲突、注销与跨任务匹配回归用例。 |
| `RecoveryEventDispatcher.dispatch` 的无 identity 兼容路径 | 即使生产注入了 P6 统一管理器，未识别事件仍直接修改本地状态机，绕开 taskId/runGeneration/checkpointId 仲裁。 | 已接入管理器的生产分发器对无身份事件只诊断并拒绝状态修改；未接入管理器的隔离测试夹具保留原型行为。 |
| `RecoveryEventDispatcher.observeFingerprint` | 旧实现无条件直调 `UnifiedRecoveryTransactionStateMachine.observeFingerprint`，绕过 P6 `UnifiedRecoveryTransactionManager.dispatch`。 | 有身份的生产指纹验证在执行原状态机函数前先由统一管理器串行鉴权。传给管理器的事件仅带布尔稳定性，不泄漏页面/用户指纹；无身份生产输入拒绝，测试夹具可沿用原本 reducer。 |
| `DynamicPageRecovery` 恢复指纹以 `stableVerificationSamples` 冒充观察代次 | 稳定样本数会在 UNKNOWN 发生时归零，导致 P6 按观察代次拒绝正常的下一次页证据。 | 为恢复事件单独维护递增 `recoveryObservationGeneration`，保留原两次新鲜样本规则及时间节奏。常规新鲜动态页的初次稳定性检查也绑定任务身份，走同一管理器。 |
| `DynamicPageRecovery` 主 UNKNOWN 与恢复检查点共用一个 `RecoveryEventDispatcher` | 检查点 verifier 重新初始化 reducer 后，主 UNKNOWN 事务可能被隐式复位；检查点证据从 0 计数又会与主事务的单调代次冲突。 | 同一个 P6 进程管理器下，主事务和 `ACCOUNT_BROWSE_CHECKPOINT` 使用独立子验证槽；业务双快照确实通过且 `completeResume()` 确认后才清理管理器主事务并重置本地 reducer。更换已验证的动态页指纹时只重开子验证槽，不清理原业务检查点、不接受旧 UI 数据。 |
| `AiReplyObservationRuntime.requestSendConfirmationRecovery` | 独立构造 `RecoveryEventIdentity` 与 `RecoveryContext.request`，和常规 UNKNOWN 的 `activateRecoveryIdentity` 重复。 | 统一调用 `activateRecoveryIdentity`，显式传入聊天详情次级 routeId。发送未知时仍只读核验既有发送，不重新发送消息或执行盲目返回。 |

## P7 业务主链路

```text
Android AccessibilityEvent
  -> AndroidAccessibilityEventPipeline
  -> UnifiedAccessibilityEntry (唯一业务事件派发出口)
  -> RunSessionCoordinator / PageObservationCoordinator
  -> PageRecoveryAdapterRegistry (业务任务作用域)
  -> UnifiedRecoveryTransactionManager (P6 进程唯一准入)
       -> PRIMARY_LANE (AI / AccountBrowse 任务隔离)
       -> AI 两个既有 Checkpoint 子验证槽
       -> ACCOUNT_BROWSE_CHECKPOINT 子验证槽
  -> 原业务新鲜页面 + 双快照 + 身份 / Activity / 窗口检查
  -> 原 TaskCoordinator 任务租约 -> UiWriteAuthorization (P4 唯一写权限)
```

## 保留但不算“新旧并行”的接口

- `PageFingerprintRouter`、`PageObservationCoordinator.registerFingerprintRoute` 仍保留给纯观察工具和既有测试。**恢复业务路由不再写入它**，因此不具有选择恢复任务、保存检查点或执行 UI 的权限。
- `PageObservationCoordinator` 的旧双 channel 构造器以及 `RecoveryEventDispatcher` 未注入 manager 的 isolated-fixture 行为仍为既有测试兼容层；生产只在根依赖图建立统一观察 coordinator 和进程级恢复管理器。
- `RecoveryCheckpointVerificationCoordinator`、`RecoveryScheduler`、P4 TaskCoordinator/UI 写许可仍承担相异职责；为了消除“旁路”不改变已有检查点数据、持久化名称、页面指纹、双快照安全门和浏览手势节奏。
- 明确用户停止、身份冲突、不可确认的 Activity / 窗口仍 fail-closed；不将 `RESUMING` 视为 UI 授权。

## 验证计划

- Python 原 P0～P6 强制边界与新 P7 门禁及负例：禁止可空统一事件入口与旧事件直接回调、重复恢复路由注册/覆盖、无身份恢复事件与指纹直接改状态机、检查点复用主 reducer，以及聊天详情再次独立造身份。
- Kotlin 原事件时序测试（触摸、Browse 去重前派发、AI 去重后派发、Activity 时序）迁移到统一入口后仍逐项执行；新增 raw / typed 路由冲突用例。
- Business 原有 UNKNOWN / 服务重连 / 进程恢复测试继续执行；新增管理器未识别事件拒绝、旧代次指纹防旁路、动态浏览普通初始稳定双快照、UNKNOWN 中断复核、恢复检查点双槽隔离，以及动态页指纹变化后的重新双样本验证。
- 完整 CI：Python 测试/强制架构门禁、Android 资源、Core/Business/Gesture/App 全部单元测试、Freedom 和 OCR Plugin 两份 Debug APK、CodeQL。

## 第一次 CI 回归根因及最小修复

首个 P7 提交的 Python 强制架构检查和 Android 资源检查通过，Gradle 实际 Kotlin 编译通过；Business 单测报告 4 条回归失败，而非语法/构建依赖问题：

- `AccountBrowseManualRecoveryTest` 的三个既有安全断言要求恢复检查点第 1 次验证时显示 `VERIFYING / stableSamples=1`，中途不完整证据必须退回 `RECOVERING / 0`。首次拆分子验证槽时，`DynamicPageRecovery.state()/stableSamples()` 仍错误地返回主 UNKNOWN lane，导致对外显示状态不同步。修复为有活动 checkpointVerifier 时读取 checkpointDispatcher 的状态与样本数；二次确认成功才恢复主事务 ACTIVE，未放宽任何样本门槛。
- `UnifiedRecoveryRouteRegistrationTest` 仍用旧 `unregisterFingerprintRoute` 取消恢复业务注册。P7 已禁止普通指纹接口删除恢复适配器，因此测试改用唯一的 typed `unregisterRecoveryAdapter`，仍断言业务注销成功、恢复路由不可匹配；Core 额外测试继续证明 raw 注销不能移除业务 route。
- 在 P7 Python 门禁中增加对浏览检查点验证状态出口的正/负测试，防止“内部验证中，外部误报 ACTIVE”的安全回退。

失败后未删除测试，也未降低安全断言或修改 CI 规则。最终成功与否须以补丁所在最终 SHA 的完整新 CI 为准。

## 与上一轮 P6 的关系、遗留

P6 的目标是同一个进程级 UNKNOWN 事务管理器，但仍允许分发器在无身份/指纹入口直接改局部 reducer；P7 去掉这些最后的具体业务旁路，并纠正自动浏览在主 UNKNOWN 与恢复检查点之间共用同一 reducer 的验证代次冲突。P5 的业务路由仍可被旧无作用域路由表重复注册，P7 现在消除了该重复。

仍需真机验证无 Usage Access 下 Activity 取证不足、服务重连/崩溃重启、陌陌非预期窗口遮挡、用户触摸与异步 OCR 时序。CI 不能证明这些真机问题已经修复。通过 GitHub API 提交时无法检查本地工作区 `git status`；以远端 P1 头、提交 compare diff 和两条 CI 为准。
