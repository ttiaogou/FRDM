# Gesture Runtime 生成与编译流程

## 目标

本阶段将 Runtime 由“一个类同时做请求编排和几何/命令构造”整理为明确的阶段管线。业务入口和执行通道不变；算法、几何、默认配置和持久化契约不改。

## 唯一生产路径

```text
GestureApi.execute(request)
        │
        ▼
GesturePolicyPipeline
        │
        ├─ 1. GesturePolicyValidator.validateRequest
        │      原始参数校验，签发 ValidatedGestureRequest
        │
        ├─ 2. 实时执行上下文复核
        │      Run generation / Window / Display coordinate snapshot
        │
        ├─ 3. GesturePolicyManager.select
        │      每请求选择与意图兼容的策略及受控参数
        │
        ├─ 4. GestureCommandCompiler.compile
        │      生成几何、转换 GestureSpec、构造不可变 GestureCommand
        │
        ├─ 5. GesturePolicyValidator.validateAndApprove
        │      完整几何、区域、路径、时间、连续指针二次验证
        │      通过后签发 ApprovedGestureCommand
        │
        └─ 6. GestureExecutionCoordinator.execute
               实时复核、可视化门控、有界 FIFO 队列、单活动平台槽和回调状态管理
                       │
                       ▼
              AndroidGestureDispatcher
                       │
                       ▼
             Android Path / Stroke / GestureDescription
                       │
                       ▼
               AccessibilityService.dispatchGesture
```

## 组件职责

### GesturePolicyPipeline

只负责编排，不再构造 `GestureCommand`、`GestureSpec` 或底层 Path 命令。每个拒绝结果包含阶段标识：`request_validation`、`context_validation`、`policy_selection`、`command_compilation` 或 `command_validation`。成功请求只把 `ApprovedGestureCommand` 交给 Coordinator。

### GestureCommandCompiler

`GestureCommandCompiler` 位于 `:gesture-runtime`，只接受 `ValidatedGestureRequest` 和 Runtime 生成的 `GesturePolicyDecision`。它负责把安全区域和当前坐标快照应用到点击/滑动意图，生成平台无关的 `GestureSpec` 和不可变 `GestureCommand`。

编译器在一次编译中只读取一次 `DisplayMetricsSnapshot`，先验证其尺寸、密度及与请求绑定的坐标上下文一致，再以同一个快照生成几何。坐标快照变化、策略与意图不兼容、几何不可用或编译异常都会返回明确的拒绝结果，不会转交执行器。

### GesturePolicyValidator

请求级校验签发 `ValidatedGestureRequest`。命令生成后，`validateAndApprove()` 对完整路径、采样点、控制点、安全区域、时长、速度阶段和连续指针结构进行二次检查，只有通过后才签发 `ApprovedGestureCommand`。

### GestureExecutionCoordinator

Coordinator 不信任审批时刻的环境快照。请求先进入有界 FIFO（默认最多 16 个等待项），队列中只保留一个活动平台手势；每个命令到达队首时都会重新验证运行代次、目标窗口、坐标上下文和命令几何，然后执行可视化门控与 Android 派发。队列项可按 command ID 取消；页面代次变化会清理绑定旧页面的等待项。平台回调仍是释放活动槽的唯一依据，回调超时只向业务交付超时状态，不会提前推进队列。

### AndroidGestureDispatcher

Android 适配层负责把同一个 `GestureSpec` 转换为 `Path`、`StrokeDescription` 和 `GestureDescription`，并通过唯一派发入口调用 `AccessibilityService.dispatchGesture()`。预览和执行继续消费同一 `GestureCommand`/坐标数据。

## 行为兼容边界

- 不改变 Tap、DoubleTap、VerticalSwipe、FeedSwipe、CalibrationSwipe 的公开业务意图。
- 不改变 GesturePolicyManager 的算法选择方式和现有策略参数。
- 不改变贝塞尔几何、五阶段速度曲线、随机起点/距离、微扰、点击安全内收或系统边缘安全区规则。
- 不改变 SharedPreferences 文件、key、默认值和设置页面。
- 不改变 Android 唯一 dispatch 通道和可视化强制门控；并发提交从“活动时拒绝”调整为有界 FIFO 接纳。排队后才遇到 `DEFERRED_NOT_READY` 的请求通过 `GestureExecutionStatus.DEFERRED` 通知调用方刷新上下文。
- 只增强阶段边界、编译前坐标一致性检查和阶段拒绝诊断。

## 验收

- 业务模块仍只依赖 `GestureApi`。
- Pipeline 不包含生产 `GestureCommand` / `GestureSpec` 构造。
- 只有 `GestureCommandCompiler` 构造生产命令与手势规范。
- Compiler 只能接收 `ValidatedGestureRequest`。
- 未获得 `ApprovedGestureCommand` 的命令不能进入 Coordinator。
- Coordinator 在入队时和真正到达队首时分别复核上下文/几何，平台派发前仍做最后一次同步复核。
- 活动平台手势未收到终态回调（包括取消看门狗超时）时，队列不得推进。
- 并发请求按 FIFO 顺序派发，等待项有上限且可按 ID 取消。
- Android 的 Path / GestureDescription 构造与 `dispatchGesture()` 仍集中在 Gesture Android Adapter。
