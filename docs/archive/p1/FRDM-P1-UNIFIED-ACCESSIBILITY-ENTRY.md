# FRDM P1 — 无障碍统一入口完全收敛（阶段 P1）

基线：e78eb546bd43c0f41c2965f14e62c01a4dd087cb，目标分支：ttiaogou/FRDM/P1。

## 架构责任与调用链

Android 原始回调只有 FreedomAccessibilityService.onAccessibilityEvent，始终交给 AndroidAccessibilityEventForwarder.accept。Forwarder 保留原生回调包过滤、触摸边界记录、窗口元数据预处理的严格顺序和运行请求判断。AndroidAccessibilityEventPipeline 继续负责 Android 事件归一化、目标包路由、输入来源分类、页面观察 signal 和可见日志去重。

**统一业务通知边界**：UnifiedAccessibilityEntry.accept(UnifiedAccessibilityEvent)，包含四类：

- TargetApplication：目标应用标准化事件
- GlobalTouch：仅 AccountBrowse 原有的全局触摸业务分发
- ActivityTransition：原有去重后 Activity 业务转场通知
- NavigationObservation：原有窗口预处理阶段的 AI 自导航归因/未匹配导航通知

所有业务通知均经 AppCompositionRoot 服务级 unifiedAccessibilityEntry，再进入既有 RunSessionCoordinator 或 AI Reply Runtime。Service 不再直接通知 AI Reply 的导航观察 API。Android raw AccessibilityEvent / AccessibilityNodeInfo 只由 Android 平台层接触，跨边界使用平台无关数据。

## 行为与安全约束

1. AccountBrowse 目标事件仍在可见日志去重前交付，重复日志不会丢弃业务反馈。
2. AI Reply 目标事件仍在日志去重后交付，不向业务分发原始消息文本或描述。
3. AI Reply 全局触摸事件仍不进入业务中断；原始触摸边界仍先由 Android 入口记录。
4. 窗口变化先作原有安全失效及导航归因，再进入正常事件管线；Activity 取证、就绪门禁、窗口和运行代次规则不变。
5. 不修改页面观察协调器、任务租约、恢复状态机、手势或输入节奏、页面指纹。
6. Pipeline 的原有命名回调保留供现有单元测试使用，生产接线必须传入统一入口；生产旧回调设置为明确拒绝，禁止回退。

## 回归与静态检查

- AndroidAccessibilityEventPipelineTest 增加统一入口的事件顺序、去重时机、全局触摸分发及生产旧路径拒绝测试。
- tools/architecture/verify_boundaries.py 增加 P1 生产接线硬约束，禁止 Service 直接调用 AI Reply 导航观察，以及 AppCompositionRoot 直接注入旧业务分发引用。
- 保留 P0 基线、所有既有手势和恢复测试及安全门禁。

## 上轮遗留问题关联

本阶段解决 P0 记录的 UnifiedAccessibilityEntry 仅有契约、Pipeline 多路直接分发、Service 到业务导航回调三个问题。

以下保留至后续阶段：P2 的页面观察协调器兼容实例；P3/P4 的任务协调及 UI 操作权唯一授权；P5/P6 的多路恢复调度和 UNKNOWN 统一事务。这些不是 P1 的完成声明。

## 验证边界

代码与 CI 构建可验证编译、架构边界及源级事件顺序；真机服务重连、Activity 身份恢复和异步时序仍需后续设备回归。
