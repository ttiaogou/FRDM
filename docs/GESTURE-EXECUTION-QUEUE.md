# Gesture Runtime FIFO 执行队列

## 目标

所有已通过强类型审批的手势命令由 `GestureExecutionCoordinator` 统一调度。系统始终最多只有一个未确认排空的 Android 平台手势；后续命令按提交顺序等待。

## 队列策略

- 使用 FIFO 队列，默认最多容纳 16 个等待项；当前活动项不计入等待容量。
- 队列满或 command ID 与活动/等待命令重复时拒绝新请求，不覆盖、不丢弃已有请求。
- `GestureSubmission.ACCEPTED` 表示 Runtime 已接纳请求，不保证提交调用返回时它已经到达 Android。调用者必须处理最终结果回调。
- 每项在入队前验证一次，到达队首并准备启动时再验证一次。队列等待期间 run generation、window 或 display coordinate snapshot 变化会使该项失效。
- 页面代次变化会立即取消绑定旧页面的等待项。
- `cancelActive(commandId, reason)` 命中活动项时沿用原有 Android drain/callback 取消语义；命中等待项时只移除该项并回调 `CANCELLED`。
- 如果取消或页面切换发生在 `visualizer.show()` 尚未返回时，Coordinator 暂缓释放活动槽；等当前可视化调用返回并交付取消终态后才推进队列，防止两个轨迹更新过程交错。

## 单通道与回调排空

`AndroidGestureDispatcher` 仍是唯一调用 `AccessibilityService.dispatchGesture()` 的位置。活动手势收到平台 `COMPLETED` 或 `CANCELLED` 回调后，协调器先更新可视化、释放活动槽，再推进队首。重复平台回调会被忽略。

取消或页面切换后的 drain watchdog 只在平台回调缺失时结束业务等待，不构成平台已经停止手势的证明。因此 watchdog 超时后活动槽和 FIFO 队列都保持锁定；只有迟到的平台终态回调确认旧手势排空后，下一项才可执行。

## 延迟就绪

若立即执行的首项返回 `DEFERRED_NOT_READY`，保留现有同步提交返回值，调用者负责重新采集证据再提交。若一个已异步排队接纳的命令后来才轮到，平台返回 `DEFERRED_NOT_READY` 时通过 `GestureExecutionStatus.DEFERRED` 通知调用者刷新上下文并重试；旧的坐标轨迹不会自动重复提交。

## 验收

- 多个并发请求严格按 FIFO 顺序派发。
- 等待队列容量有限且拒绝溢出请求。
- 取消指定排队项不影响活动项及其他等待项。
- 页面切换立即清理旧页面等待项。
- 队列等待期间上下文变化时，在派发前拒绝。
- 平台 drain watchdog 超时不得释放活动槽；迟到平台回调到达后才推进队列。
- 无论队列长度如何，Android 平台同时最多只有一个未确认排空的手势。
- `visualizer.show()` 重入提交下一项并取消当前项时，下一项必须等待当前 `show()` 返回和取消终态更新完成后才开始。
