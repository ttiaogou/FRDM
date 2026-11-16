# Gesture Policy Manager

## 目的

`GesturePolicyManager` 是 Gesture Runtime 内部的策略所有者。业务请求只描述手势意图和目标区域；轨迹算法、扰动开关、采样点数、设备密度和曲线设置由 Runtime 在每次请求编译前解析。

## 调用链

```text
Business / Adapter
        |
        | GestureIntentRequest (semantic intent)
        v
GestureApi
        |
        v
GesturePolicyValidator.validateRequest
        |
        v
GesturePolicyManager.select
        |
        +-- Tap / DoubleTap     -> SAFE_UNIFORM_TAP
        +-- VerticalSwipe       -> VERTICAL_CUBIC_BEZIER
        +-- FeedSwipe           -> FEED_CUBIC_BEZIER
        |                         or FEED_CUBIC_BEZIER_WITH_MICRO_PERTURBATION
        +-- CalibrationSwipe    -> CALIBRATION_CUBIC_BEZIER
        |
        v
GestureGeometryPolicy
        |
        v
second validation -> ApprovedGestureCommand -> coordinator
```

## 所有权边界

- `GestureIntent.FeedSwipe` 只包含视口和业务流程需要的持续时间，不再接受曲线偏移、扰动频率、密度、扰动开关、幅度或采样点数。
- `GesturePolicySettingsProvider` 是 Runtime 给应用组装层使用的配置端口。当前 `MomoGesturePolicySettingsProvider` 从既有 `GestureSettingsRepository` 读取曲线偏好和设备校准频率；不创建坐标、路径或执行命令。
- 屏幕密度来自请求绑定的 `GestureExecutionContext.coordinateContext`，不从业务意图接收。
- `GesturePolicyManager` 每次生成手势时重新读取 FeedSwipe 策略，不在应用启动时固定一次选择。
- 只有在当前配置允许微扰时，FeedSwipe 才会在基础三次贝塞尔和带微扰的三次贝塞尔两个兼容变体之间按请求随机选择；关闭微扰时只允许基础变体。
- Tap、VerticalSwipe 和 CalibrationSwipe 当前各自只有一个已验证的兼容算法，Manager 明确映射，不会为了随机而将一种手势替换成不适用的算法。

## 保持的现有配置

以下现有配置来源、持久化契约和默认值保持不变：

- 账号浏览滑动时长默认值 `620ms`，范围 `350ms..1200ms`。
- 曲线偏移默认值 `0.012f`，最大比例 `0.04f`；原 SharedPreferences key 不变。
- 设备校准频率仍由 `GestureTuning.currentFrequencyHz()` 读取；校准存储结构和 key 不变。
- 微扰默认启用，幅度 `1.2px`；基础/微扰采样点数分别为 `32` / `100`。

启用微扰时，Manager 每次 FeedSwipe 可以选择基础或微扰变体，这是本步骤新增的 per-request 策略选择行为；轨迹的几何边界、五阶段时间参数化、二次验证、可视化和执行通道不变。

## 失败处理

配置提供器异常、非有限数值、负频率/幅度、无效曲线比例或越界采样点数都会使策略解析失败；Pipeline 拒绝该请求，不回退到未校验的业务参数，也不生成手势。

## 测试重点

- FeedSwipe 每次请求重新选择兼容算法。
- 微扰禁用时只选基础贝塞尔算法。
- 无效策略配置被拒绝。
- 其他意图始终映射到其兼容算法。
- Business 的 FeedSwipe 意图不暴露底层轨迹参数。
