# Gesture 算法与运动参数覆盖

本文件记录 `代码示例2.txt` 中 12 段示例的移植位置、现有统一执行链、可表达能力及 Android 平台限制。算法均由 `GesturePolicyManager` 按请求选择，经 `GestureCommandCompiler` 生成同一份 `GestureCommand`，再由 `GestureExecutionCoordinator` 进入唯一 FIFO 执行通道；业务层不直接创建 Android `Path` 或调用 `dispatchGesture()`。

## 1. 12 段示例迁移映射

| 上传示例 | 移植内容 | 当前实现 |
|---|---|---|
| 1 | 固定偏移三次贝塞尔 | `GestureTrajectoryKind.FIXED_CUBIC_BEZIER`；控制点使用起点/终点 30%/70% 插值并应用 `(+50,-30)`、`(-40,+50)` 像素偏移，超出安全区域时拒绝/受限于安全控制点计算 |
| 2 | 左偏三次贝塞尔 | `LEFT_BIASED_CUBIC_BEZIER`；控制点按 25%/75% 插值并向左偏移 60px。水平轨迹会添加小幅法线弯曲，避免退化为直线路径 |
| 3 | 随机控制点三次贝塞尔 | `RANDOMIZED_CUBIC_BEZIER`；两个控制点各自独立采样 ±40px 偏移 |
| 4 | 余弦缓入缓出 | `COSINE_EASE_IN_OUT` |
| 5 | 三次缓出 | `CUBIC_EASE_OUT` |
| 6 | 二次缓入缓出 | `QUADRATIC_EASE_IN_OUT` |
| 7 | 法线方向正弦微扰 | `NORMAL_DIRECTION_TREMOR`；保留法线方向、频率、幅度和首尾包络，但底层基线使用三次贝塞尔曲线，遵守项目禁止直线插值的空间轨迹约束 |
| 8 | 二次贝塞尔与二维微扰 | `QUADRATIC_BEZIER_2D_TREMOR`；控制点为中点 `(+30,-20)`，叠加二维正弦/余弦微扰；采样结果再编译成连续贝塞尔曲线段 |
| 9 | 随机相位二维微扰 | `RANDOM_PHASE_2D_TREMOR`；每次规划随机相位，X/Y 两轴分别施加正弦/余弦扰动，底层基线使用三次贝塞尔曲线 |
| 10 | Box–Muller 对数正态等待 | `sampleLogNormalDelay()`；支持中位数、sigma、最小和最大边界，按请求采样 |
| 11 | 延迟后派发 | 延迟由 Runtime `TaskScheduler` 在 FIFO 活动槽内安排；延迟结束后重新验证上下文，再进入唯一 Android 派发器。不新增绕过队列的 Handler/dispatch 路径 |
| 12 | 单次滑动中的停顿 | `GesturePauseProfile`；按概率在指定路径进度插入连续触点保持段，使用零长度三次曲线保持路径非空，停顿时长计入总手势时长 |

示例 7 原文用两点直线作为基础移动，示例 9 也是起终点线性插值。Freedom 的工程规范要求所有滑动/拖动都以二次或三次贝塞尔曲线作为空间基线，因此本次移植时保留其微扰数学模型，但将直线基线修正为三次贝塞尔；不是逐字复制原函数。

## 2. 独立运动参数模型

核心模型位于 `GestureDynamicsPolicy.kt`，业务请求不暴露这些低层参数。

- **初始速度**：`initialVelocityPxPerSecond`。
- **最大速度**：`maximumVelocityPxPerSecond`，作为硬上限；规划器求解/测量的峰值不得超过它。
- **抬起时末端速度**：`releaseVelocityPxPerSecond`。
- **加速度**：`accelerationPxPerSecondSquared`。
- **减速度**：`decelerationPxPerSecondSquared`。
- **速度曲线**：余弦、三次缓出、二次缓入缓出，或显式运动学模式。
- **运动模式**：`DURATION_DRIVEN` 以时间曲线参数化距离；`KINEMATIC_LIMITED` 根据起始/末端速度、最大速度、加/减速度和给定距离/持续时间求解加速、匀速、减速三阶段。物理约束不成立时拒绝，不静默伪造一条不满足约束的轨迹。

`GesturePolicyManager` 可以在每次请求时从候选集合中选择轨迹、速度曲线和运动模式。采样随机数由 Runtime 注入；FeedSwipe 的持久化偏好和设备校准频率仍从既有 Settings Provider 读取。

## 3. 方向、角度与曲率

`GestureDirectionProfile` 包含：

- 主轴：`HORIZONTAL`、`VERTICAL`、`FREE_ANGLE`；
- 基准角度、方向偏移角、路径旋转角；
- 水平/垂直位置偏置；
- 曲线弯曲方向（正法线、负法线、随机）与弯曲幅度。

新增 `GestureIntent.DirectionalSwipe` 允许业务声明目标角度和轴约束，但业务不能传入控制点或采样轨迹。角度按屏幕坐标计算，正角度朝屏幕下方旋转。水平/垂直轴默认允许 15° 内的方向容差；越界请求会被拒绝。

## 4. 触摸压力、接触面积、工具类型与接触状态

`GestureTouchProfile` 记录并验证 pressure、contact width/height 和 tool type（finger/stylus/mouse/unknown），`GesturePlatformCapabilityReport.ACCESSIBILITY_SERVICE` 明确报告当前执行通道不支持将这些字段注入系统触摸事件。

Android `AccessibilityService.dispatchGesture()` 的 `GestureDescription.StrokeDescription` 只提供路径、开始时间、持续时间和连续触点标志；它没有 pressure、touch major/minor 或 tool type 的设置入口。因此这些值目前是策略描述/诊断元数据，不代表 Android 已收到对应 MotionEvent 属性。触点接触生命周期通过 Stroke 的 `continuationOfPrevious` / `willContinue` 表达：首段建立触点，连续段维持同一触点，最终段结束触点。当前模型是单指；多指同时接触不在本次实现范围内。

## 5. 滑动末端速度与释放后惯性

运动学模式可以指定抬起前的末端速度。`estimatePostReleaseInertia()` 使用恒定减速度公式 `distance = v² / (2a)` 估算目标应用可能产生的后续滚动距离和时间，并把结果作为诊断元数据保留。

这只是预测，不会在 `UP` 之后额外派发手势，也不能接管目标应用的 fling。实际惯性滚动由 Android/目标应用的滚动容器、触摸速度估计和滚动物理决定；当前 Accessibility 手势 API 没有独立的 post-release inertia 控制接口。因此不能保证最终滚动距离或停止时间。

## 6. 多手势编排

`GestureChoreographyRunner` 支持有界步骤数、按顺序提交多个 `GestureIntentRequest`、等待每一步的完成回调、在步骤间安排有界分布等待、按步骤超时、取消当前步骤或停止后续步骤。现在每一步可以使用静态请求，也可以通过 `requestFactory` 在阶段开始时重新读取页面证据并生成新请求/新坐标上下文；每一步仍需重新经过 `GestureApi` 验证并进入全局 FIFO 队列，编排器本身不创建 Path、不直接派发 Android 手势。

阶段支持 `precondition` 和 `postcondition`：前者在请求上下文复核后、提交前运行，后者在平台完成回调后运行。步骤超时覆盖请求生成、上下文检查、排队等待和平台完成。对同步回调、执行提交与取消/超时竞争也有独立保护。

当前编排不是全队列原子预留：其他独立 `GestureApi` 客户端可能在两步之间提交任务。现有 AccountBrowse/AiReply 工作流仍由各自领域状态机持有业务阶段；本轮没有把它们批量迁移到通用 Runner，以免预先构造下一阶段坐标、脱离页面状态验证。Runner 现已支持 just-in-time 请求工厂，供确有固定序列/阶段需求的业务通过依赖注入使用。若业务需要“步骤之间不可插入其他任务”的事务式序列，仍需未来为全局队列增加受所有者约束的 reservation/session 机制。

## 7. 默认值与兼容性边界

- 新增的通用 pre-dispatch 延迟默认关闭，避免影响未明确启用新策略的调用者；Momo Settings Provider 显式启用示例 10 的 150–1200ms 对数正态延迟。
- FeedSwipe 原有持久化 key、默认曲线偏移、校准频率、微扰开关、采样点数和微扰幅度保持从原 Provider 读取。
- 停顿、速度模式、轨迹候选和方向参数由 Provider 配置；不可行的运动学组合会被拒绝。
- 压力/面积/工具类型仅可校验和报告；惯性仅可估算；同时多指和原子化编排仍未实现。

## 8. 对应源码

- `app/src/main/java/com/freedom/core/accessibility/gesture/GestureDynamicsPolicy.kt`
- `app/src/main/java/com/freedom/application/gesture/GesturePolicyManager.kt`
- `app/src/main/java/com/freedom/application/gesture/GestureCommandCompiler.kt`
- `app/src/main/java/com/freedom/application/gesture/GestureExecutionCoordinator.kt`
- `app/src/main/java/com/freedom/adapters/android/gestures/MomoGesturePolicySettingsProvider.kt`
- `app/src/main/java/com/freedom/features/gesture/GestureChoreographyRunner.kt`

## 9. 对照 04/05 工程规范的整改记录

本节记录基于 `04-节点检索手势与空间坐标.md` 和 `05-时间节律滑动起止点与输入控制.md` 的本轮整改。它不覆盖未在本轮实现的消息输入、发送按钮业务流程或平台无法注入的触摸硬件属性。

### 9.1 普通滑动起止点和距离动态变化

`GestureDynamicsPolicy.createSwipe()` 已将原 `GestureGeometryPolicy` 中的受约束高斯采样与距离浮动接入实际编译路径。普通 `VerticalSwipe`、`FeedSwipe` 和 `DirectionalSwipe` 每次请求独立采样起点 X/Y、终点 X/Y、距离比例、方向偏移角、路径旋转、曲线幅度、持续时间和运动参数。默认起点高斯 sigma 为 16px，最大偏移 25px；终点 sigma 为 6px，最大偏移 12px；距离变化为 ±7.5%。

采样是在内收后的视口安全区域内进行的。最多 12 次截断高斯重采样仍无法得到安全点、端点越界、角度不符合轴约束或轨迹任何采样点越界时，本次规划被拒绝；不把坐标裁剪到边界，也不回退到固定点。每个独立维度由 Runtime 注入随机源，测试使用固定 seed 验证重复运行可复现、不同 seed 能产生变化。

校准手势显式使用 `GestureSwipeRandomizationProfile.DETERMINISTIC`、固定轨迹候选、固定速度曲线，关闭停顿和操作前延迟，保证校准样本可比较。

### 9.2 统一有界时间分布

`GestureTimeDistributionPolicy` 是前置等待、滑动内部停顿及多阶段等待的共同采样器，支持均匀、截断正态和截断对数正态分布。每份 Profile 都必须定义非负最小值、有限最大值（最多 120 秒）及与分布相匹配的中心参数；采样采用拒绝采样而非边界裁剪。64 次采样仍无法落入范围时返回失败，不制造边界概率尖峰。

Momo 普通滑动沿用显式的 150–1200ms 前置延迟；内部停顿有 55–145ms 有界对数正态时长；编排等待按阶段配置范围。通用策略默认仍关闭操作前延迟，不修改现有持久化键。

### 9.3 多阶段编排

`GestureChoreographyRunner` 支持静态请求或每阶段 `requestFactory`、阶段前置/后置检查、阶段总超时、共享时间分布等待和取消。每一步使用新请求上下文重新调用 `GestureApi`；上下文变化时保守返回 `DEFERRED`。并发其他业务请求仍可能插入编排步骤之间，本轮没有实现原子队列 reservation。

AccountBrowse 和 AI Reply 保留其领域状态机，没有批量替换为通用编排器；这是为了保持它们在每阶段重新识别页面、会话和检查点的既有所有权。通用 Runner 已提供 just-in-time 请求工厂能力，但是否迁移具体业务工作流需要按业务阶段单独验证。

### 9.4 节点检索时间边界

`TreeInspectionRequest`、`NodeQueryBudget`、Momo 页面/聊天页/弹窗检查均有节点数、深度和单调时钟时间预算。主窗口、多窗口根扫描共用总 deadline；Momo feed 检查的顶部 Tab、列表容器和可见子项共享 350ms 总预算；聊天页消息区与输入区共享区域 deadline；弹窗候选、文本、关闭目标与广告线索扫描共享弹窗总预算。预算耗尽时返回截断/不完整标记并采取保守拒绝，不把部分树误判为“没有弹窗/页面有效”。

Android `AccessibilityNodeInfo` 仍只在适配器的短期扫描过程中持有并回收，不传入 Runtime/Domain 长期状态。

### 9.5 未在本轮覆盖的规范

本轮不实现 `ACTION_SET_TEXT` 后的完整文本检查/发送业务工作流，也不新增 LongPress/Drag 意图类型；不改变 AccountBrowse 阶段顺序、现有重试次数、阈值、UI 或持久化默认值。节点检索时间预算已落地，但高频事件节流/合并行为需要在各事件类型的独立调用链中继续审查，不能由本轮扫描 deadline 代替。
