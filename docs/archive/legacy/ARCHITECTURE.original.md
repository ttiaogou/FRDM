# Freedom — architecture migration history and current boundaries

> 本文件后半部分保留早期 Stage 1–9 的迁移记录。其中文件路径是当时的历史位置，不应视为当前项目结构；当前结构以本页“当前边界”及 `README.md` 为准。

## 当前边界（2026-11-12）

- `core/`：仅保留平台无关的运行、无障碍事件、树/节点和 Overlay 契约；Android 实现在 `adapters/`。
- `features/`：平台无关 Feature 源码，Gradle 由 `:business` 编译；不暴露 `AccessibilityNodeInfo`、`Rect`、`Handler` 或 Android Service。
- `application/`：运行协调、用例和内向 Repository/平台 Ports。
- `adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt`：Android 执行桥，负责将 Feature 动作映射为 Android 无障碍/手势/窗口操作。
- `adapters/social/momo/`：实现陌陌页面、卡片、导航、弹窗和 Feed 手势能力；不持有 AccountBrowse 状态机或运行时调度器。
- `adapters/runtime/android/AccountBrowseModeRuntimeAdapter.kt`：把统一 Runtime 事件分发接入 AccountBrowse Android 执行桥。
- `data/`：MonitorStore 和 SharedPreferences 的唯一实现边界；其他层只通过 Repository/Settings Port 访问。
- 项目保持单 APK、单进程；Gradle 工程现包含 `:app`、`:business`、`:core`、`:gesture-api`、`:gesture-core`、`:gesture-runtime`、`:gesture-android` 七个模块。Feature 业务代码由 `:business` 编译，依赖仅限 `:core` 与 `:gesture-api`；Android / Social Adapter 仍由 `:app` 编译。
- Gesture 模块职责、当前 source set 归属、依赖方向和剩余隔离工作见 [`GESTURE-MODULE-BOUNDARIES.md`](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-MODULE-BOUNDARIES.md)。步骤 2 已完成 Business 与 Gesture/Core 模块拆分；步骤 3 已将业务入口收敛为 `:gesture-api` 中的 `GestureApi`；Android / Social Adapter 仍在 `:app`，尚未获得独立 Adapter 模块的编译期隔离。 步骤 4 的强类型验证边界见 [`GESTURE-STRONG-TYPED-VALIDATION.md`](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-STRONG-TYPED-VALIDATION.md)；步骤 5 的策略所有权见 [`GESTURE-POLICY-MANAGER.md`](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-POLICY-MANAGER.md)；步骤 6 的 Runtime 生成、编译、审批和执行阶段见 [`GESTURE-RUNTIME-PIPELINE.md`](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-RUNTIME-PIPELINE.md)。

本轮清理了早期迁移目录中的重复 AccountBrowse 状态/策略实现、旧 Momo 浏览组件、旧树扫描器和 Core 中残留的 Android 实现。历史阶段说明保留作变更轨迹；若历史段落中的旧路径与当前边界冲突，以当前边界和 `STAGE-7-12-ARCHITECTURE-REPAIR-REPORT.md` 为准。

## 步骤 2 — Gesture Gradle 多模块拆分（2026-11-12）

本阶段在保持单 APK / 单进程形态、不删除原始文件的前提下，将平台无关业务 Feature 与手势实现从原 `:app` 编译单元拆为以下 Gradle 模块：

- `:business`：纯 Kotlin Feature 业务代码，仅依赖 `:core` 和 `:gesture-api`。
- `:core`：公共平台无关类型与通用能力。
- `:gesture-api`：业务意图、`GestureApi`、结果状态及监听器。
- `:gesture-core`：纯 Kotlin `GestureGeometryPolicy` 与坐标区域模型。
- `:gesture-runtime`：`GesturePolicyPipeline`、`GesturePolicyValidator`、`GestureExecutionCoordinator`、Runtime SPI 和 `GestureRuntimeFactory`。
- `:gesture-android`：Android Path 工厂、可视化适配、Overlay 及唯一实际调用 `AccessibilityService.dispatchGesture()` 的 `AndroidGestureDispatcher`。
- `:app`：保留 APK、`FreedomAccessibilityService`、`AndroidAccessibilityGateway`、Android / Social Adapter 及应用组装职责。

为满足“不得删除原项目文件”的要求，Kotlin 源文件仍保留在原 `app/src/...` 相对路径；各 Gradle 模块使用严格的 source include/exclude 规则接管对应源码，`:app` 排除已迁移至模块的源文件和测试，避免重复编译。`AndroidAccessibilityGateway` 留在 `:app`，继续负责非手势 Accessibility 能力，并将手势派发委托给 `AndroidGestureDispatcher`。

模块拆分现已由 `settings.gradle.kts` 和各模块 `build.gradle.kts` 声明。`:gesture-runtime` 不依赖 Android SDK 或 `:gesture-android`；`:gesture-android` 依赖 Runtime SPI；`:app` 负责实例化 `GestureRuntimeFactory` 并注入平台适配器。

**尚未完成的隔离：** Android / Social Adapter 的消费者源文件仍在 `:app`，因为 APK 组装根本身需要依赖 Runtime；这些 Adapter 尚未单独成为消费者 Gradle Module。纯 Kotlin Feature 业务代码已移入 `:business`，仅依赖 `:core` 和 `:gesture-api`。下一轮可继续拆分 Android / Social Adapter，使其作为独立模块仅依赖 Gesture API。

**验证限制：** 环境没有 Gradle 可执行程序、Gradle Wrapper 和 Android SDK。纯 JVM 的 `:core`、`:gesture-api`、`:gesture-core`、`:gesture-runtime` 源码已使用本地 Kotlin 编译器按模块顺序进行编译检查；Android Library 与 APK 的 Gradle 编译、Gradle 原生单元测试和真机验证尚未执行。具体结果以本次交付说明为准。


## 步骤 8 — 架构自动审查与 CI 门禁（2026-11-12）

- 新增 `tools/architecture/verify_boundaries.py`，在不依赖 Gradle/Android SDK 的前提下检查 Gradle 模块依赖方向、唯一 `GestureApi`、Business/Adapter 手势边界、Runtime 审批令牌、命令构造所有权及唯一 Android 派发适配器。
- 新增 `tools/architecture/test_verify_boundaries.py`，测试源码屏蔽、Gradle 依赖解析等静态检查器基础行为。
- 扩展 `ArchitectureBoundaryTest`，对 Gradle 依赖图、唯一 GestureApi、Android 派发位置和 CI 门禁接线增加回归断言。
- GitHub Actions 增加独立 `architecture` Job；静态检查通过后才启动 Android build Job。构建 Job 显式运行 Core、Business、Gesture Core、Gesture Runtime JVM 测试，以及 App ArchitectureBoundaryTest 和 Debug APK 构建。
- 检查器是基于源码的架构守卫，不替代 Kotlin/Gradle 编译或 Android 真机验证；当前 Adapter 仍由 `:app` 编译的限制保留，不把它误报为独立 Gradle 隔离。

详细规则、运行命令和检查器限制见 [`GESTURE-ARCHITECTURE-GUARDRAILS.md`](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-ARCHITECTURE-GUARDRAILS.md)。 手势轨迹、运动参数、方向、触摸能力边界、惯性估算与多手势编排的源码映射见 [`GESTURE-ALGORITHM-COVERAGE.md`](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-ALGORITHM-COVERAGE.md)。

## 全局坐标手势执行约束（Stage 1–2，2026-11-08）

- 所有自动屏幕点击、长按、双击、滑动和拖动必须以平台无关的 `GestureCommand` 表达，并经 `application/gesture/GestureExecutionCoordinator` 执行；业务流程不得直接调用 `AccessibilityService.dispatchGesture()`。
- `GestureExecutionCoordinator` 负责屏幕尺寸/坐标/路径/时长校验、有界 FIFO 串行调度，以及“可视化准备成功后才允许派发”的硬门控。活动平台手势只能有一个；并发提交进入最多 16 项的等待队列，超过容量、command ID 重复、可视化不可用或验证失败时拒绝。平台回调前不会推进队列。
- `AndroidGesturePathFactory` 是 Android `Path` 的唯一转换器，实际派发和轨迹可视化使用同一个 `GestureSpec` 和同一转换器，禁止分别计算显示路径与执行路径。
- `GestureTrailOverlay` 通过 `TYPE_ACCESSIBILITY_OVERLAY` 显示命令中的实际触摸坐标、滑动路径、起点/终点、方向和执行状态；该手势可视化窗口本身不检查 `SYSTEM_ALERT_WINDOW`。服务其他监控悬浮窗仍沿用现有授权/运行门控。
- 首页双击刷新、Feed 坐标滑动、设备手势校准、陌陌动态/首页导航、弹窗明确关闭、动态卡片打开和备用 Feed 滑动均通过统一 `GestureExecutor`；手势显示与派发使用同一份坐标路径。
- Stage 2 已从 `NodeAction` 移除 `CLICK`、`LONG_CLICK`、`SCROLL_FORWARD`、`SCROLL_BACKWARD`；生产源码不得使用对应 Android Action 常量、节点级动作枚举或旧节点点击 helper。当前仅保留 `FOCUS`、`SET_TEXT`、`DISMISS` 等非触摸语义操作，节点读取和几何边界提取仍然允许。
- 弹窗关闭优先尝试目标窗口明确支持的语义 `ACTION_DISMISS`；该操作不是屏幕点击或滑动。若采用可见关闭控件，则必须同时取得原始图标/标签边界与已验证可点击祖先边界，以图标/标签中心生成坐标手势；不得点击祖先区域的猜测中心。
- Stage 2 架构测试会扫描全部生产 Kotlin 源码，阻止节点级点击/长按/滚动常量、NodeAction 变体和遗留 helper 回归。任何未来触摸操作必须经过 GestureExecutor 和可视化门控。

## 早期结构拆分记录（历史路径）

- `ui/config/ConfigPanel.kt`：现有设置面板及其行布局；需要与 Activity 交互的动作通过回调回到 Activity。
- `ui/config/TargetAppEditor.kt`：目标应用包名编辑和保存流程。
- `ui/calibration/GestureCalibrationUiController.kt`：校准对话框、校准广播接收器、采样计时和本地 MotionEvent 采集。
- `ui/logs/LogTextFormatter.kt`、`LogRecordsDialog.kt`：日志文本兼容格式化和运行记录对话框。

## 调度关系核对

自动浏览的唯一主流程调度仍然由 Service 的主线程 Handler 驱动。抽取后的 `AccountBrowseScheduler` 保留原语义：

1. 入队前检查监听运行状态、自动浏览模式、触摸状态、`IDLE`/`PAUSED` 阶段。
2. 若已经有待执行步骤，不覆盖、不重置已有步骤。
3. 入队后不在回调前额外重新检查条件；回调开始时先清空 pending，再调用原 `performAccountBrowseStep()`。
4. 原先取消 `accountBrowseRunnable` 并清除 pending 的位置，改为调用同一个 scheduler 的 `cancel()`。
5. 手动触摸静默恢复、悬浮窗触摸穿透 watchdog、首次滑动倒计时和手势校准仍使用各自独立 Runnable，不合并到主调度器。

页面识别、导航重试、滑动派发与完成回调、详情停留、图片浏览返回及目标应用事件处理仍由 `FreedomAccessibilityService` 执行；自动浏览的阶段状态、会话数据和 pause/reset/manual-resume/recovery 协调由 `AccountBrowseController` 持有并编排，平台副作用仍通过 Service 回调完成。

## 明确未做

- 没有更换 Android View UI 技术。
- 没有引入新的第三方依赖或 Gradle Module。
- 没有修改配置键、Intent Action、目标包名、页面识别规则、手势参数、点击规则、延时时间和用户交互步骤。
- 没有主动改变日志内容、节点扫描上限或节点快照格式。

## 验证限制

本地容器没有 Android SDK、Gradle 可执行程序和 Gradle Wrapper，因此无法在此执行 `assembleDebug`。已执行源码结构/引用检查；最终 Android 编译和设备端行为仍需要 GitHub Actions 与真机验证。


## Stage 2 — 设备手势校准执行组件

新增 `accessibility/gesture/GestureCalibrationController.kt`，只接管设备内部手势校准会话：

- 持有校准进行状态、当前测试组序号、校准区域和专属 Runnable。
- 接收原有开始/取消广播；校准进度、成功、失败广播的 Action、Extra 与目标包名保持不变。
- 检查 Freedom 是否为当前前台窗口，并验证校准区域仍位于屏幕范围内。
- 保留原来的四组测试手势、350ms 初始等待、700ms 单次手势时长、350ms 组间等待和最后 250ms 完成等待。
- 继续使用原来的贝塞尔路径控制点比例、`dispatchGesture` 回调和主线程 Handler。
- 仍通过原有日志入口记录开始、取消、失败和完成信息。

`FreedomAccessibilityService` 继续负责 Android 无障碍服务生命周期，以及校准广播接收器的注册/注销；控制器通过 Service 执行平台手势派发和发送应用内广播。自动浏览的手势状态、调度器、页面识别与操作流程没有迁入该控制器。

本阶段没有新增权限、Manifest 声明、持久化键、Intent Action、第三方依赖或用户可见功能。

静态检查已确认旧校准状态字段/方法不再由 Service 持有，接收器在连接与销毁时仍由 Service 配对注册/注销；新控制器已使用 Kotlin 编译器和最小 Android API stub 做独立语法/类型形状检查。当前环境没有 Android SDK/Gradle，完整 `assembleDebug` 与真机校准仍需通过 GitHub Actions 和设备确认。


## Stage 3 — Momo page inspection extraction (2026-11-07)

- Extracted the existing Home bottom-navigation evidence, Dynamic-tab row inspection, feed-readiness check, visible feed-container lookup, and page fingerprint into `accessibility/browse/MomoPageInspector.kt`.
- The inspector returns the existing `HomeBottomNavEvidence`, `DynamicTabInspection`, `FeedReadinessInspection`, and `PageFingerprint` data types. Its thresholds, labels, scan cap, bounds calculations, child counting, and node recycling are supplied from the same existing service constants and retain their prior values.
- `FreedomAccessibilityService` keeps its existing private entry points as thin delegates so existing state-machine call sites and action ordering are unchanged.
- The detail-page identity check also lives in the inspector; the service passes its current/expected Activity values because those belong to the live navigation session. Navigation click-target lookup, popup handling, gestures, and all state transitions remain in the service.
- `MomoPageInspector` is read-only: it does not click, scroll, navigate, schedule callbacks, or alter account-browse state.
- No UI behavior, timing, gesture parameters, package filters, or third-party dependencies were changed.


## Stage 4 — Momo popup inspection extraction

- 新增 `accessibility/browse/MomoPopupInspector.kt`，迁移陌陌弹窗候选扫描、容器评分、弹窗文本收集、危险内容识别、明确关闭控件查找和识别结果节点回收。
- `MomoPopupInspector` 只返回既有 `PopupInspection` 识别证据；不执行 `ACTION_DISMISS` / `ACTION_CLICK`，不安排重试，不修改自动浏览阶段。
- `FreedomAccessibilityService.handleMomoPopup()` 继续独占弹窗安全决策、关闭动作、重试次数、900ms 重试间隔、350ms 验证轮询、2500ms 关闭验证超时和失败等待。
- 原有弹窗候选扫描上限、候选数量、节点文本数量/长度上限、识别分数、几何范围、关键词集合和节点回收顺序保持不变。可点击祖先查找仍调用 Service 的既有辅助函数，避免改变点击目标尺寸与深度约束。
- 页面导航、状态机、卡片过滤、点击、返回和手势执行均未迁入或改写。
- 本阶段无新权限、广播、持久化键、依赖或用户可见功能。完整 Android 构建仍需 GitHub Actions 验证。


## Stage 5 — Feed gesture planning

`MomoFeedGestureController` owns feed-list target discovery, scrollable-container selection, bounded visible-node snapshots, persisted gesture-profile loading, and path construction. It returns `FeedGestureTarget`, `ScrollGestureProfile`, and `ScrollGesturePlan` data only. It does not create `GestureDescription`, call `dispatchGesture`, toggle overlay touch passthrough, schedule work, or mutate browse phases. `FreedomAccessibilityService` retains the actual gesture dispatch and all completion/cancellation/recovery behavior.


## Stage 6 — Navigation action boundary extraction

- Added `accessibility/browse/MomoNavigationController.kt` for the existing bounded label-to-clickable-node lookup, clickable-ancestor validation, `ACTION_CLICK` submission/node release, and the low-level global Back callback.
- Kept `submitNavigationClick()` and `submitNavigationBack()` in `FreedomAccessibilityService`; attempt counters, before-page snapshots, event suppression windows, verification deadlines, logs, retry delays, stop conditions, and `VERIFY_NAVIGATION_ACTION` transitions remain service-owned.
- Kept `verifyNavigationAction()`, `beginDynamicNavigationRecovery()`, and `handleMomoActivityTransition()` in the service. Their evidence collection and page-state decisions remain in the service and `MomoPageInspector`.
- The popup inspector continues to use the same clickable-ancestor bounds/depth rule through a thin service delegate. Popup dismissal actions remain in `handleMomoPopup()`.
- The detail-page Back action uses the same low-level controller callback; its original direct Boolean handling and subsequent list-return transition remain in place.
- Preserved scan limit, target-center tolerance, maximum clickable-ancestor width/height, ancestor depth, action exception handling, and node ownership/recycling.
- No navigation retries, timing values, labels, package/activity identifiers, page verification rules, or automation state-machine behavior were intentionally changed.
- Full Android `assembleDebug` and device validation remain required through GitHub Actions.


## Stage 7 — 自动浏览阶段协调器初步拆分

- 新增 `accessibility/browse/AccountBrowseController.kt`。
- 将自动浏览当前阶段的存储迁入 controller；`FreedomAccessibilityService.accountBrowsePhase` 保留同名读写代理，现有业务判断与阶段赋值位置不变。
- 将原 `performAccountBrowseStep()` 的运行条件门控和 `when (phase)` 分派迁入 controller。Service 仍保留原方法作为兼容入口，调度器的回调关系不变。
- 阶段动作通过显式 `Actions` 回调回到 Service；节点访问、无障碍事件处理、页面识别、弹窗关闭、导航重试、手势派发、计时器及日志仍由 Service/既有组件负责。
- 保留执行门控顺序：监控或自动浏览模式关闭时先重置；触摸期间或已暂停时不执行；随后按当前阶段分派。
- 本阶段只建立控制器对当前阶段和阶段分派的所有权，不迁移会话其余字段，也不重写阶段转换、暂停恢复和任何阶段动作。
- 不新增权限、广播、持久化键、第三方依赖或用户功能。完整 Android `assembleDebug` 和真机行为仍需 GitHub Actions / 设备验证。


## Stage 8 — 自动浏览会话状态归属整理

- 新增 `accessibility/browse/AccountBrowseSessionState.kt`，集中承载每次自动浏览会话的可变证据：暂停原因/恢复上下文、前后台 Activity 和页面指纹、列表稳定采样与返回验证、触摸接管、手势执行/滚动事件、详情停留、导航尝试上下文及弹窗关闭验证状态。
- `AccountBrowseController` 创建并持有唯一的 `session` 实例；`FreedomAccessibilityService` 通过该实例读取/更新原有会话数据，仍保留 Android 无障碍事件、系统手势、日志、计时和状态副作用的执行责任。
- 53 个原 Service 会话字段的默认值与原类型保持不变；原有字段访问只改为访问 `accountBrowseSession`，原有赋值语句、递增操作、重置顺序和判断条件不调整。
- `accountBrowsePhase` 继续由 `AccountBrowseController` 持有；阶段变换、`performAccountBrowseStep()` 门控和 `when` 分派未改写。
- 本阶段不改变页面指纹算法、暂停/恢复时序、手动触摸判定、手势参数、导航/弹窗重试参数或任何自动操作。未增加持久化、广播、权限或第三方依赖。
- 基线 ZIP 带有上一轮 Stage 7 遗漏的 `AccountBrowseController` import；本次一并补齐该 import，避免将已确认的编译错误继续带入后续版本。
- 当前环境没有 Android SDK/Gradle，完整 Android `assembleDebug` 与真机回归仍须由 GitHub Actions 和设备完成。

## Stage 9 — 自动浏览暂停、恢复与会话生命周期协调

- `AccountBrowseController` 现在统一协调会话 reset、暂停、暂停期间人工接管记录、手动静默结束后的恢复准备，以及页面不确定时的主动导航恢复准备。
- `AccountBrowseSessionState.reset()` 承接原 Service reset 方法中的 52 项会话字段清理；`overlayPassthroughActive` 仍由 WindowManager 触摸穿透恢复流程负责清除，保持原有失败时保留标记的行为。
- Service 保留 Android/窗口副作用：清理手势轨迹、恢复悬浮窗触摸状态、取消 Handler 回调、隐藏倒计时，以及日志输出和定时调度；Controller 通过回调在原有顺序点调用这些动作。
- 手动恢复的运行状态、模式、触摸状态判断顺序，暂停上下文读取、导航证据清理、阶段切换、deadline 计算及后续调度顺序保持原实现。
- 页面不确定时的恢复仍保留原有暂停上下文只在为空时创建、先清理悬浮窗/调度、再清理导航尝试字段、进入 `WAIT_FOR_DYNAMIC_TAB` 并安排下一步的顺序。
- `pauseAccountBrowse()`、`resetAccountBrowseSession()`、`beginDynamicNavigationRecovery()` 在 Service 中保留为兼容入口；不迁移 AccessibilityService 生命周期、实际手势、页面识别、导航动作或 Handler 所有权。
- 本阶段没有改变阶段枚举、超时数值、静默时长、页面识别规则、点击/滑动行为和用户操作方式；没有新增权限、广播、持久化键或第三方依赖。


## Stage 10 — 一次性领域归属迁移（Architecture 2.2）

本阶段以 `Freedom-AI-EventDriven-Transition-2.1.zip` 为唯一源码基线，集中完成核心运行职责与代码包归属迁移。目标是建立清晰的模块化单体边界，不拆分 Gradle Module，也不重写已存在的自动浏览算法。

### 当前包结构与所有权

- `core/runtime`：`RunMode`、`RunModePreferences` 与 `RunCoordinator`。`RunCoordinator` 统一负责运行中的模式恢复、旧配置无歧义迁移与活动模式查询。持久化仍沿用原 `MonitorStore` key。
- `core/accessibility`：`AccessibilityEventRouter` 管理无障碍事件第一层路由和 250ms 相同事件签名去重；`core/accessibility/event` 保存事件分类、策略与格式化工具。全局触摸交互事件仍先于目标应用包名过滤。
- `features/accountbrowse`：自动浏览阶段、53 项会话状态、阶段分派、调度、暂停/恢复、人工接管和导航恢复准备由 `AccountBrowseRuntime` 统一持有。领域策略与会话模型也归入该包。
- `features/aireply`：`AiReplyObservationRuntime` 持有 AI 模式的页面观察与聊天区域有界刷新调度。它只做结构观察，不提取聊天正文、不管理会话历史、不调用模型、不发送消息。
- `adapters/social/momo`：陌陌动态卡片筛选、页面检查、弹窗检查、导航、Feed 手势规划，以及 2.1 已有的聊天页面 Inspector/Models 均归入陌陌适配器。此次只移动现有行为实现并调整包引用，没有虚构陌陌聊天消息节点 ID。
- `accessibility/FreedomAccessibilityService`：继续承担 Android `AccessibilityService` 生命周期、系统窗口/节点/手势接口、广播注册、平台副作用和对成熟自动浏览动作的调用。

### 关键边界

1. 自动浏览与 AI 回复不互相调用。AI 模式不会进入自动浏览的人工触摸暂停/恢复分支；全局触摸事件在路由后仍只交给自动浏览模式处理。
2. Service 通过显式 `Actions` 回调触发原有自动浏览阶段动作。阶段动作中的页面判断、导航验证、卡片选择、手势派发和恢复策略仍沿用原函数，不为了迁移重写算法。
3. AI 观察运行时只持有结构化检查结果与调度状态；每次事件 source 仅短时读取并回收，不把 `AccessibilityEvent` 或 `AccessibilityNodeInfo` 保存为长期状态。
4. 事件路由保留原目标包名过滤与 Freedom 自身包过滤关系；全局触摸开始/结束事件继续在包名检查之前处理。重复日志抑制窗口仍为 250ms。
5. AI 页面完整识别和聊天区域刷新仍沿用 2.1 的 300ms / 500ms debounce 配置，以及已知聊天详情的有界区域复查、定位失效后重新识别机制。

### 自动浏览逻辑保护范围

对比 2.1 基线，本阶段没有重写自动浏览各阶段处理函数，也没有调整原有页面识别阈值、动态列表卡片筛选、导航点击/返回、详情停留、手势规划、人工接管判定、弹窗关闭策略、重试次数或阶段动作延迟。领域模型和控制器搬迁后，Service 原调用点主要改为访问 `accountBrowseSession` 或调用 `accountBrowseRuntime`。

此次没有把 Service 中所有成熟的 Android UI 操作函数全部搬出。`FreedomAccessibilityService.kt` 从 3,596 行缩减至 3,153 行，仍承担大量自动浏览平台操作和阶段工作流函数。这是刻意保留的行为风险控制边界，不代表 Service 已经达到最终“薄 Service”状态。若后续进一步抽取这些函数，需要围绕节点所有权、回调时序和 Handler 生命周期做独立回归，不能简单复制/移动代码。

### 未做事项

- 未拆分为多个 Gradle Module；仍保持单一 `app` Module。
- 未引入 Hilt/Koin、Room、协程、网络 API、AI Provider 或第三方依赖。
- 未加入真实聊天消息解析、上下文存储、Prompt、模型调用、回复队列或发送动作。
- 未修改 Manifest、权限、目标包名、持久化 key、广播 Action、应用 UI 和用户操作流程。
- 未把陌陌的某个 ViewId（例如曾出现于日志的 `active_rv`）认定为真实聊天消息列表 ID。

### 验证状态

- 源码包 ZIP 完整性：通过。
- 移动文件和包引用扫描：通过；旧包路径引用未发现残留。
- 迁移的既有策略/模型/适配器实现：抽样差异核对确认逻辑主体未变，变化限于包路径及必要的领域类型 import；Service 与 `MainActivity` / `MonitorStore` 的引用更新已核对。
- 核心新增/迁移类：使用最小 Android API stub 执行独立 Kotlin 编译，未发现编译错误。
- 纯 JVM 测试：使用临时兼容 harness 执行 11 个测试类，共 75 条用例通过。该结果不是 Gradle 原生测试任务结果。
- 完整 Android Gradle 构建：未执行。当前执行环境没有 Android SDK、`android.jar`、Gradle 可执行文件或 Gradle Wrapper。
- 真机回归：未执行。本阶段不得视为真机验收通过。

---

## Freedom 3.0 architecture migration status (Stage 7–12)

The current implementation follows a single-module Accessibility-Centric Modular Monolith. The Android application remains one Gradle module (`:app`); package boundaries are enforced by source-level architecture tests.

### Runtime and event flow

- `FreedomAccessibilityService` is the Android composition root. It creates the platform adapters and the sole runtime `RunSessionCoordinator` instance.
- UI surfaces submit start/stop intent through `RunSessionCommandUseCase`; the Service coordinator reconciles persisted intent and owns Runtime lifecycle/event dispatch.
- Android AccessibilityEvents are normalized into immutable `AccessibilityEventEnvelope` values before Core routing. Global touch events are routed before target-package filtering.
- AccountBrowse receives its normalized event before visible-log de-duplication, preserving the existing preflight event ordering. AIReply receives its normalized/redacted event after the established log de-duplication point.

### Dependency directions

- `core` is platform- and feature-independent.
- `features` is Android-free and depends on Core plus its own domain contracts.
- `application` owns run coordination and ports; it has no Android, Adapter, or Data implementation imports.
- `adapters` contains Android, Momo, scheduler, overlay, and Runtime bridge implementations.
- `data` owns `MonitorStore` and SharedPreferences access behind repository contracts.
- `ui` uses Application ports and callbacks; it does not import concrete Adapter or Data implementations.
- `MainActivity` and `FreedomAccessibilityService` are composition roots and may instantiate concrete adapters/repositories.

### AccountBrowse compatibility boundary

The Android execution workflow lives in `adapters/accessibility/android` and depends on the feature-facing `SocialBrowsePort` for Momo page, navigation, popup, feed, card-selection, gesture-plan, and detail evidence. It sends short-lived `NodeTarget` actions, global Back actions, and sampled gesture specifications through `AccessibilityGateway`. Raw `AccessibilityNodeInfo` access is confined to the Android/Momo adapters; the legacy `AccountBrowseAndroidPort` compatibility bridge has been removed. AccountBrowse phase/session state, retry policy, timing, gesture-selection policy, and return verification remain in their established owners.

### Data ownership

`MonitorStore` calls and `SharedPreferences` APIs are confined to `data`. Existing preference file names, keys, defaults, and clamping semantics are retained. UI uses `MonitorControlRepository` and `GestureCalibrationPresentationPort`; gesture settings are read through `GestureSettingsRepository`.

### Verification status

`ArchitectureBoundaryTest` checks Core/Feature/Application dependency boundaries, UI implementation isolation, Data storage ownership, normalized runtime event routing, Android adapter placement, and the single `:app` module. Pure Core/Feature/Application sources and the locally runnable JVM tests pass. The current migration has not yet passed a full Android Gradle build or real-device regression; do not infer either from the prior Stage 5/6 build history.

## AI Reply Stage 3 — visible chat message observation

- `features/aireply/domain/message` owns platform-neutral message direction/type/status models. Message bodies are short-lived in-memory values; model diagnostics redact them from `toString()`.
- `features/aireply/application/message/ChatMessageParser` classifies only adapter-provided row candidates. Sender direction is inferred from explicit layout hints or clearly left/right bubble geometry; centered, full-width or ambiguous bubbles remain `UNKNOWN`.
- `features/aireply/ports/ChatMessagePort` is the only message-read boundary used by the AI Reply runtime. It returns a bounded snapshot of messages currently visible in the chat list, not a complete conversation history.
- `adapters/social/momo/MomoChatMessageReader` owns Android `AccessibilityNodeInfo` traversal, bounded row extraction and node recycling. Raw message text does not escape this adapter except as an ephemeral `ChatMessageBatch` delivered through the port; it is not written to ordinary logs or persisted.
- `AiReplyObservationRuntime` triggers a maximum-20-message read after a chat-detail candidate inspection and after a successful known-region refresh. It logs only counts/status and whether a latest incoming text candidate exists. It does not retain the message batch, call an AI provider, type text or send messages.
- `CHAT_DETAIL_CANDIDATE` remains evidence-based and is not proof of a one-to-one conversation. Group/one-to-one confirmation, stable conversation identity and durable de-duplication are deferred to the next state/context stage. The current message ID is an ephemeral content fingerprint, not a target-app/server ID.

- [Gesture Policy Manager](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-POLICY-MANAGER.md)


## 步骤 6 — Gesture Runtime 生成与编译流程整理（2026-11-12）

- `GesturePolicyPipeline` 现在只负责编排阶段：原始请求验证、实时上下文复核、策略选择、命令编译、二次审批验证，以及把审批令牌交给串行 Coordinator。
- 新增 `GestureCommandCompiler`，独占生产环境 `GestureCommand` / `GestureSpec` 构造、屏幕安全区域求交、点击落点采样、滑动计划转换和 FeedSwipe 策略参数应用。编译器只接受 `ValidatedGestureRequest`，并返回显式的成功/拒绝结果。
- 编译器只读取一次显示指标快照，用同一快照完成尺寸/密度/旋转一致性检查和几何生成；若请求校验后显示坐标上下文变化，则在命令生成前拒绝。策略算法必须与请求意图类型匹配。
- `GesturePolicyValidator.validateAndApprove()` 仍负责完整坐标、区域、路径、时间和连续指针几何验证；`GestureExecutionCoordinator` 在可视化/派发前仍复核实时上下文与命令几何。Android Dispatcher 保持唯一平台 `GestureDescription` 构造和 `dispatchGesture()` 回调通道。
- 手势类型、随机策略选择、默认参数、SharedPreferences key、时长和已有几何算法没有改变；变更仅整理阶段职责、增强阶段拒绝诊断并增加坐标快照/策略匹配检查。
- 新增 `GestureCommandCompilerTest`，并更新架构测试，确保只有编译器构造生产命令和规范、Pipeline 不再直接生成底层路径。
- 本阶段未删除任何原项目文件；原有 Pipeline 源文件路径保留，编译器新增为单独源文件并纳入 `:gesture-runtime`，同时从 `:app` 排除，防止重复编译。


## Gesture Runtime FIFO 执行队列（步骤 7）

- `GestureExecutionCoordinator` 是唯一队列所有者，使用 `ArrayDeque` 按提交顺序维护等待命令；默认最多 16 个等待项（不含当前活动项），容量满时返回 `REJECTED`，不会丢弃已接纳项。
- `GestureSubmission.ACCEPTED` 表示 Runtime 已接纳请求，可能已进入 Android 派发，也可能等待 FIFO；调用者应通过结果回调判断最终状态。
- 每项到达队首时重新校验 run generation、目标窗口、显示坐标上下文和几何；提交后环境变化的旧命令会在实际派发前被拒绝。页面代次变化会主动清理绑定旧页面的等待项。
- `cancelActive(commandId, reason)` 可取消活动项或指定排队项。排队项取消立即回调 `CANCELLED`，不会触碰当前 Android 手势。
- Android `dispatchGesture()` 接受的活动项必须等待平台终态回调，才能推进队列。取消后的回调看门狗只结束业务等待，不证明平台手势已经停止；即使看门狗超时，活动槽仍保持占用直到迟到的平台回调。
- 若等待项轮到时 Android 返回 `DEFERRED_NOT_READY`，因原始调用已异步接纳，Runtime 通过 `GestureExecutionStatus.DEFERRED` 回调调用方，要求重新采集上下文/证据后重试；不会把过期轨迹自动再次派发。

## 04/05 工程规范整改

本轮已在现有算法扩展基础上补齐受约束的滑动起止点随机采样、统一有界时间分布、多阶段编排的即时请求生成与状态检查，以及节点检索时间/深度/节点数预算。详细映射见 `GESTURE-ALGORITHM-COVERAGE.md` 第 9 节和 `GESTURE-COMPLIANCE-REMEDIATION.md`。
