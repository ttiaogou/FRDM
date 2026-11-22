# FRDM P1 分支 — P2 页面观察协调器完全统一

基线提交：`618c7862835d5aacd53d51f1b8ca9d12575f9518`；仓库：`ttiaogou/FRDM`；目标：`P1`。

## 审计发现

- P1 统一无障碍入口及 `AndroidAccessibilityEventPipeline` 已向服务级 `PageObservationCoordinator` 提交标准化事件，但现有 PageObservationCoordinator 频道在信号匹配时没有运行模式限制。
- `MomoPageObservationCoordinator` 与 `AndroidAccountBrowseWorkflow` 在未注入服务级协调器时仍自行创建公共协调器，违反真正单实例目标。
- AI Reply 的 `pageInspectionTask` 直接使用 `TaskScheduler`，收到招呼的稳定性重试、低频复核也在观察器内部直接使用 Scheduler。
- 收到招呼列表的观察器通过 `onReceivedGreetingsConfirmed` 回调直接要求启动任务，AccountBrowse 的观察回调也在适配器内决定唤醒业务步骤。

## 本阶段权威调度路径

```
FreedomAccessibilityService
  → AndroidAccessibilityEventForwarder
  → AndroidAccessibilityEventPipeline
  → PageObservationCoordinator (AppCompositionRoot 创建唯一服务级实例)
      ├─ AI Reply 观察频道（只在 AI 模式接收事件）
      ├─ AccountBrowse 观察频道（只在自动浏览模式接收事件）
      └─ 显式延迟只读复核（AI 整页、收到招呼重试及低频观察）
  → 业务读取页面、确认语义证据
  → AI Reply / AccountBrowse 运行时做任务决策
  → 既有任务协调、UI 操作权及恢复门禁
```

`MomoPageObservationCoordinator` 和 `AndroidAccountBrowseWorkflow` 均要求注入同一个公共实例，不再允许以 Scheduler 创建本地 fallback。单元测试显式注入独立的测试协调器（不共享跨测试状态）。

`ReceivedGreetingsPageArrivalWatcher` 保留原 350ms、8 次快速重试及其后持续低频回查的时序和过期回调屏障；只产生 `LIST_CONFIRMED` 等页面观察结果，**不调用任务启动**。唯一的人工确认收到招呼任务启动由 `AiReplyObservationRuntime.handleInspectionSnapshot` 消费证据后决定。

`AndroidAccountBrowseWorkflow.onCoalescedPageObservationRequested` 只向 `AccountBrowseRuntime` 发出请求，由其根据既有运行状态、用户触摸和合法阶段决定是否继续使用原 `AccountBrowseScheduler`。

## 保留约束

- 不改页面分类器、指纹、稳定性双快照、恢复状态机、手势、输入节奏、Activity 身份取证、旧检查点或 UI 操作权安全门禁。
- `PageObservationCoordinator` 只负责调度和事件合并，无权批准恢复或发布 UI 写操作。
- 不把不同的任务延迟（如手势反馈、界面停留、发送确认、UNKNOWN 恢复退避）混入页面观察调度；这些属于业务或恢复任务控制，并非单纯的页面观察。
- 原有去重策略及时间常量不变。已登记频道在非本模式时不接收新事件请求；已有任务仍由运行时自身的活动/代次检查约束。
- 生产创建路径只保留 `AppCompositionRoot`；同步收紧 P0 的构造站点白名单及别名允许项。

## 回归检查

- `core`：共享频道隔离、事件合并、显式观察任务取消。
- `business`：原收到招呼稳定性/低频回查测试转为纯结果断言；AI Runtime 测试显式注入协调器。
- `tools/architecture`：禁止本地观察协调器回退构造、直接调用收到招呼任务启动、恢复旧观察定时器或业务步骤旁路。
- CI 完整流程：Python 架构测试和边界检查、Core/Business/Gesture/App 测试、Freedom 与 OCR Debug APK 构建。

## 与上一阶段遗留的关系

本阶段关闭 P0/P1 遗留的页面协调器重复创建与观察器直接决定任务的问题；后续 P3/P4 仍需收敛任务协调及 UI 操作权、P5/P6 仍需整合多路恢复状态机和恢复调度。本轮没有擅自修改这些模块。

## 未覆盖

CI 只验证编译、单测及静态边界；真机服务重连、进程重启、缺少 Usage Access 时的 Activity 新鲜证据和多窗口交错仍须单独进行真实设备回归。
