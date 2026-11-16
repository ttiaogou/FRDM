# 04/05 工程规范整改记录

基线：`freedom-ts-gesture-algorithm-expansion-complete.zip`。本轮在现有 Gesture API、Runtime、Policy Manager、Compiler、FIFO Coordinator 和节点检索适配器上增量修正，不重做 Step 8，不丢弃此前算法扩展。

## 1. 滑动起止点动态变化

普通 `VerticalSwipe`、`FeedSwipe`、`DirectionalSwipe` 均由 `GestureDynamicsPolicy.createSwipe()` 生成。每次规划独立采样：起点 X/Y、终点 X/Y、距离比例、方向偏移、路径旋转、曲线幅度、持续时间和速度/运动学参数。

默认起点使用截断高斯偏移（sigma 16px，最大 25px），终点使用截断高斯偏移（sigma 6px，最大 12px），距离按基础值浮动 ±7.5%。所有点必须在安全内收区域内，采样失败或轨迹验证失败即拒绝，不做坐标裁剪、不回退到固定中心。曲线路径仍以贝塞尔为基础并由时间参数化。

`CalibrationSwipe` 是明确例外：保持确定性起点、距离、角度、曲线和速度，不参与普通随机化，以保护校准数据可比性。

## 2. 统一时间分布

`GestureTimeDistributionPolicy` 统一处理前置延迟、滑动内部停顿和多阶段等待，支持 Uniform、Truncated Normal、Truncated Log-Normal。所有 Profile 都具备有限最小/最大边界（最大 120 秒）；使用拒绝采样而不是将越界样本 clamp 到边界。配置中心不在有效范围或 64 次采样均失败时，返回失败，由上游拒绝本次动作。

Momo provider 显式启用 150–1200ms 前置延迟和 55–145ms 的有界内部停顿时长。通用默认依旧关闭前置延迟。

## 3. 多阶段编排

`GestureChoreographyRunner` 支持：

- 静态请求或每阶段 `requestFactory` 即时生成新请求和坐标上下文；
- 阶段前置检查与手势完成后的后置检查；
- 覆盖请求生成、上下文校验、FIFO 等待和平台回调的阶段超时；
- 使用共享有界时间分布进行阶段间等待；
- 取消当前阶段、阻止后续步骤；
- 防止 execute/timeout/cancel 和同步回调之间的竞态。

Runner 仍将每一步交给唯一 `GestureApi`，不直接创建 Android Path 或调用 `dispatchGesture()`。不同业务请求仍可能插入两个阶段之间；本轮没有实现全队列原子 reservation。现有 AccountBrowse/AI Reply 保留自己的领域状态机，不批量迁移到通用 Runner，以免丢失它们的页面/会话检查点。

## 4. 节点检索时间边界

- `TreeInspectionRequest`：有限最大深度和最长持续时间。
- `NodeQueryBudget`：默认最多 5000 节点、250ms，UNBOUNDED 只适用于明确受控的离线不可变快照。
- `AndroidAccessibilityGateway`：当前窗口/多窗口根采集与节点遍历共享单调 deadline；未访问的窗口对象回收。
- Momo Feed Inspector：顶部 Tab、列表查找和列表项扫描共享 350ms 总预算。
- Momo Chat Inspector：页面/区域 BFS 有时间、深度、节点上限，并将截断状态纳入结果标识。
- Momo Popup Inspector：广告线索、弹窗候选、弹窗文本、关闭目标搜索共享总预算；超时/截断返回 `inspectionIncomplete` 阻断状态，不将不完整扫描当作“无弹窗”。

检索到的 AccessibilityNodeInfo 只在适配器内短期持有，离开扫描后回收，不存入业务队列或 Domain。

## 5. 原有行为与明确限制

- 不修改 Step 8 模块架构和 `GestureApi` 唯一入口。
- 不绕过 `GestureExecutionCoordinator`、FIFO 或 Android 单一派发器。
- 不修改 AccountBrowse 阶段顺序、既有节点匹配阈值/重试次数、持久化 key 或 UI 默认值。
- 校准手势保持确定性。
- 通用编排器具备生产可调用能力，但现有 AccountBrowse/AI Reply 仍使用自己的领域状态机；是否迁移特定流程必须逐阶段验证。
- 高速事件回调节流/合并、完整文本输入/发送检查、LongPress/Drag 等不属于本轮已完成能力。
