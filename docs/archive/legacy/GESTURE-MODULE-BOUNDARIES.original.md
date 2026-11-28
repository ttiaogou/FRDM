# Gesture 模块边界与依赖关系（步骤 1 设计基线 + 步骤 2 实施结果）

- 状态：步骤 1 设计完成；步骤 2 已创建并配置 Gesture/Core Gradle Modules。
- 基线源码：`freedom-ts-step1-complete.zip`，检查日期：2026-11-12。
- 本文同时记录目标边界、当前 Gradle 编译归属、依赖方向和未完成的业务模块隔离。

## 1. 设计结论

Gesture 能力采用四个独立 Gradle Library Module：

- `:gesture-api`：业务可见的唯一公开手势 API。
- `:gesture-core`：平台无关的手势几何、计划和内部模型。
- `:gesture-runtime`：验证、策略、计划编排、命令审批和执行调度。
- `:gesture-android`：Android Path / GestureDescription 编译、AccessibilityService 派发和平台回调适配。

业务 Feature 和普通 Adapter 是 API 的消费者，不是 Runtime 的消费者。当前 `:business` 已对 Gesture 唯一依赖 `:gesture-api`；Android / Social Adapter 仍由 `:app` 编译，遵守同一源码约束，但尚无独立 Gradle 依赖边界。

`:app` 是唯一组装根：依赖当前已创建的 Gesture 模块，负责创建对象图并注入实现。Feature 源文件已由独立 `:business` Gradle Module 编译，该模块只依赖 `:core` 与 `:gesture-api`。Android / Social Adapter 源文件仍由 `:app` 编译，因此这些 Adapter 尚未形成独立 Gradle Module 的依赖隔离；`:app` 本身仍是 Composition Root，可以依赖 Runtime。

这四个 Gesture 模块是同一个 Android 项目中的 Gradle Library Module，不是独立 APK，不新建第二个 AccessibilityService，也不改变现有单 APK / 单进程运行形态。

建议插件类型：`:business`、`:gesture-api`、`:gesture-core`、`:gesture-runtime` 使用 Kotlin/JVM Library（保持无 Android 依赖）；`:gesture-android` 使用 Android Library；`:app` 继续使用 Android Application。若后续代码审查发现 Runtime 必须依赖 Android 类型，应先抽象端口，不应直接把 Android 依赖引入 Runtime。

## 2. 目标目录与模块

当前 Gradle Module 布局如下。业务 Feature 已独立为 `:business`；Android / Social Adapter 模块仍属于后续工作：

```text
freedom-ts/
├── app/                              # Android APK、Service 生命周期、应用组装根
├── business/                         # :business，平台无关 Feature 消费者
├── core/                             # :core，平台无关的基础契约
├── gesture/
│   ├── gesture-api/                  # :gesture-api
│   ├── gesture-core/                 # :gesture-core
│   ├── gesture-runtime/              # :gesture-runtime
│   └── gesture-android/              # :gesture-android
├── features/                         # 业务源目录；由 :business 编译
└── adapters/                         # Adapter 源目录；当前仍由 :app 编译
```

`features/` 已作为一个纯 Kotlin `:business` 模块编译；其依赖仅为 `:core` 与 `:gesture-api`。`adapters/` 仍由 `:app` 编译，后续可以按 Android 能力或 Social Adapter 边界继续拆分。业务 Feature 对 Gesture 的唯一依赖是 `:gesture-api`，不能依赖 `:gesture-core`、`:gesture-runtime` 或 `:gesture-android`。

## 3. 各模块职责与禁止事项

### 3.0 `:business`

**职责**

- 编译 `features/**` 下的平台无关 Feature 业务代码及对应单元测试。
- 依赖 `:core` 基础契约和 `:gesture-api` 手势请求契约。
- 不负责手势策略、算法生成、Android 编译或系统派发。

**依赖限制**

- 允许依赖：`:core`、`:gesture-api`。
- 禁止依赖：`:gesture-core`、`:gesture-runtime`、`:gesture-android`、Android SDK。

Feature 与 Adapter 跨模块使用的少数稳定契约已显式改为 public；只供 Feature 内部使用的实现仍保留 internal。

### 3.1 `:gesture-api`

**职责**

- 提供唯一业务入口 `com.freedom.gesture.api.GestureApi`；提交、上下文捕获及按请求 ID 取消统一由该接口承接。
- 定义业务意图：点击、双击、垂直滑动、Feed 滑动、校准滑动等。
- 定义请求身份、描述、窗口/坐标上下文，以及结果状态、监听器和提交结果。
- 保持契约稳定，使 Feature 和 Adapter 不需要知道轨迹如何生成或怎样派发。

**允许公开**

- `GestureApi`。
- `GestureIntent`、`GestureIntentRequest`。
- `GestureExecutionContext`、`GestureCoordinateContext`。
- `GestureExecutionResult`、`GestureExecutionStatus`、`GestureExecutionListener`。
- 为兼容现有签名而需要的轻量共享值类型；这些类型优先复用 `:core` 中平台无关的 `ScreenRect`、`WindowRef`、`GestureSubmission` 等契约。

**禁止公开**

- `GestureCommand`、`GestureSpec`、`GestureStrokeSpec`、`GesturePathCommand`、原始路径点集合。
- `GestureGeometryPolicy`、具体随机算法、曲线参数、抖动参数、内部 Validator 和 Executor。
- `AccessibilityService`、`GestureDescription`、Android `Path`、`Handler` 等 Android 类型。

`:gesture-api` 可依赖平台无关的 `:core` 契约；`:core` 不得反向依赖任何 Gesture 模块。

### 3.2 `:gesture-core`

**职责**

- 持有平台无关的轨迹几何、采样、速度曲线和内部 Gesture Plan / Command 模型；共享坐标区域值类型 `GestureCoordinateRegion` 归属 `:core`。
- 提供 Runtime 生成与验证计划时使用的纯 Kotlin 算法。
- 只表达“计划是什么”，不负责 Android 编译、排队或实际派发。

**禁止事项**

- 不导入 Android SDK、AccessibilityService、UI、Service 或具体 Adapter。
- 不调用 `dispatchGesture()`。
- 不向业务模块暴露内部物理轨迹模型。

### 3.3 `:gesture-runtime`

**职责**

- 接收 `:gesture-api` 请求并验证请求结构、运行上下文和坐标上下文。
- 通过策略管理器执行权限/策略判断、配置选择和算法选择。
- 调用 `:gesture-core` 生成手势计划，并进行生成后验证。
- 生成经过审批的内部命令，统一编译为平台执行契约。
- 管理执行队列、串行执行、取消、中断、超时、最终验证和结果回调。
- 持有执行端口和可视化端口的抽象契约；具体 Android 实现由外部 Adapter 注入。

**禁止事项**

- 不直接导入 Android SDK 或 `AccessibilityService`。
- 不直接生成 Android `Path` / `GestureDescription`。
- 不直接调用 `dispatchGesture()`。
- 不把内部 Command、Validator、Executor 或 PolicyManager 暴露给业务模块。

未来 `:gesture-runtime` 内部的验证、策略、队列和审批类应优先使用 Kotlin `internal`。供 `:gesture-android` 实现的少数 SPI 可以是模块级 public，但不属于业务 API；业务模块必须没有 `:gesture-runtime` 依赖。

### 3.4 `:gesture-android`

**职责**

- 实现 Runtime 定义的 Android 手势执行/可视化端口。
- 将平台无关的手势计划编译为 Android `Path`、`GestureDescription` 和 `StrokeDescription`。
- 调用 `AccessibilityService.dispatchGesture()`，处理 Android 接受结果、完成/取消回调和连续 Stroke。
- 实现 Android 手势轨迹可视化，并确保预览与派发使用同一份已验证计划。
- 把执行结果回传给 Runtime，不承担业务流程状态机。

**禁止事项**

- 不接受业务模块直接调用。
- 不提供让 Feature / Adapter 自行创建 `GestureDescription` 的公开 API。
- 不拥有 AccountBrowse / AIReply 业务状态或策略。

本模块依赖 `:gesture-runtime` 是为了实现 Runtime 声明的低层 SPI；反向依赖禁止，即 `:gesture-runtime` 不得依赖 `:gesture-android`。

### 3.5 `:app`

**职责**

- 保持 APK 入口、`FreedomAccessibilityService` 生命周期和应用级初始化。
- 作为 Composition Root 创建 Runtime、Android Adapter、业务 Feature 和其他 Adapter。
- 将同一个 `GestureApi` 实例注入所有业务消费者。

**禁止事项**

- 不在 Service 内继续堆叠手势算法、验证、策略或调度逻辑。
- 不让业务代码留在 `:app` 后再借助 `:app` 对 Runtime 的依赖直接访问 Runtime 实现。
- 不创建第二个手势执行入口或另一个独立 AccessibilityService。

## 4. 依赖方向

目标依赖图（箭头表示“发起模块依赖目标模块”）：

```text
:business (Feature) ──────────────> :gesture-api ─────────────> :core
:gesture-core ────────────────────> :core
:gesture-runtime ─────────────────> :gesture-api、:gesture-core、:core
:gesture-android ─────────────────> :gesture-runtime、:core
:app (Adapters + Composition Root) > :business、:core、:gesture-api、:gesture-runtime、:gesture-android
```

对应的允许依赖表：

| 发起模块 | 允许依赖 | 禁止依赖 |
|---|---|---|
| `:business` (Feature) | `:core`、`:gesture-api` | `:gesture-core`、`:gesture-runtime`、`:gesture-android` |
| `:adapter-social-momo` | `:core`、Feature 对外 Port、`:gesture-api` | `:gesture-core`、`:gesture-runtime`、`:gesture-android` |
| 普通 Android Workflow / Calibration Consumer（当前在 `:app`） | 所需公开 Port、`:core`、`:gesture-api` | 直接依赖几何算法或直接调用底层派发；后续应移入独立 Adapter Module |
| `:gesture-api` | `:core` | 所有 Gesture 实现模块、Android SDK |
| `:gesture-core` | `:core` | `:gesture-runtime`、`:gesture-android`、业务模块、Android SDK |
| `:gesture-runtime` | `:core`、`:gesture-api`、`:gesture-core`（implementation） | `:gesture-android`、Android SDK、业务实现模块 |
| `:gesture-android` | `:core`、`:gesture-runtime`（实现 SPI） | Feature 内部实现和业务状态机 |
| `:app` | `:business`、Adapter、Core、Gesture Runtime / Android（仅作组装） | 在 app 内重新实现业务规则或创建第二执行通道 |

**关键隔离条件：**纯 Kotlin Feature 已作为 `:business` 独立 Gradle Module，Gradle 依赖仅为 `:core` 和 `:gesture-api`。Android / Social Adapter 目前仍位于组装模块 `:app`；后续将其拆为独立 Adapter Module，才能对这些消费者也施加同样的编译期隔离。

## 5. 当前源码到目标模块的映射

下表中的当前源码路径为便于阅读，省略了公共前缀 `app/src/main/java/com/freedom/`；实际源文件均位于该前缀之下。

| 当前源码 | 目标模块 | 处理原则 |
|---|---|---|
| `gesture/gesture-api/src/main/kotlin/com/freedom/gesture/api/GestureApi.kt` | `:gesture-api` | 唯一公开入口；统一上下文捕获、意图提交和按请求 ID 取消 |
| `core/accessibility/gesture/GesturePolicyPort.kt` | 历史占位文件，不参与编译 | 保留原路径与文件，不再声明公开接口；避免旧入口复活 |
| `core/accessibility/gesture/GestureIntent.kt` | `:gesture-api` | 保留语义请求、请求上下文和业务回调；清理对内部实现类型的依赖 |
| `core/accessibility/gesture/GestureCommand.kt` | 拆分到 `:gesture-api` / `:core` / `:gesture-runtime` | `GestureKind`、结果状态/Listener 属于 API；物理 Command 属于 Runtime，`GestureCoordinateRegion` 归 `:core`；Executor / Visualizer SPI 归 Runtime |
| `core/accessibility/gesture/GestureGeometryPolicy.kt` | `:gesture-core` | 纯 Kotlin 轨迹与几何策略，不被业务直接依赖 |
| `application/gesture/GesturePolicyPipeline.kt` | `:gesture-runtime` | 请求验证、上下文复核、策略选择、编译、审批和执行的唯一编排入口 |
| `application/gesture/GestureCommandCompiler.kt` | `:gesture-runtime` | 只接受已验证请求，独占几何生成及 GestureCommand / GestureSpec 构造 |
| `application/gesture/GesturePolicyValidator.kt` | `:gesture-runtime` | 请求级验证、编译后完整几何审批、执行前复核 |
| `application/gesture/GestureExecutionCoordinator.kt` | `:gesture-runtime` | 执行协调、状态、取消、超时和串行执行 |
| `adapters/accessibility/android/AndroidGesturePathFactory.kt` | `:gesture-android` | Android Path 编译适配 |
| `adapters/accessibility/android/AndroidGestureVisualizer.kt`、`GestureTrailOverlay.kt` | `:gesture-android` | Android 轨迹可视化适配 |
| `adapters/accessibility/android/AndroidAccessibilityGateway.kt` 的手势派发部分 | 拆出至 `:gesture-android` | 仅抽离 GestureExecutionPort / Android dispatch 职责，不整体搬迁整个 Gateway |
| `features/aireply/AiReplyObservationRuntime.kt` | `:business` | 只通过 `:gesture-api` 提交意图、读取执行结果 |
| `adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt` | Android Workflow 消费者模块 | 只通过 `:gesture-api`；保留其 Android 节点/窗口适配职责 |
| `adapters/social/momo/MomoSocialAdapter.kt` | `:adapter-social-momo` | 只通过 `:gesture-api` 提交手势意图 |
| `accessibility/gesture/GestureCalibrationController.kt`、`adapters/android/gestures/AndroidGestureCalibrationHost.kt` | Calibration Consumer / Android Adapter | 只通过 `:gesture-api` 请求校准手势；生命周期继续由 Service 托管 |
| `accessibility/FreedomAccessibilityService.kt` | `:app` | 保留 Android 服务生命周期；后续把对象组装整理到 App Composition Root |

### 5.1 当前已存在的边界

- `GestureApi` 已成为 `:gesture-api` 中唯一公开的业务手势入口；`AiReplyObservationRuntime`、`AndroidAccountBrowseWorkflow`、`MomoSocialAdapter`、校准 Host 等调用者均通过同一实例提交意图。取消活动手势也已合并到该接口，AI Reply 不再通过强转额外的中断接口。
- `GesturePolicyPipeline`、`GestureCommandCompiler`、`GesturePolicyValidator`、`GesturePolicyManager` 与 `GestureExecutionCoordinator` 已由 `:gesture-runtime` 编译；它们与原物理文件路径分离，`internal` 可阻止 `:app` 直接访问这些实现类。
- `GesturePolicyManager` 独占策略选择；`GestureCommandCompiler` 独占生产 `GestureCommand` / `GestureSpec` 构造；`GesturePolicyPipeline` 只编排阶段，不直接构造底层手势数据。
- **历史基线（步骤 1 设计审查时）：** `GestureExecutionCoordinator` 当时只有单活动执行槽，并发请求会被拒绝。步骤 7 已将其升级为默认最多 16 个等待项的有界 FIFO 队列；当前实现和验收边界见 `docs/GESTURE-EXECUTION-QUEUE.md`。
- `GestureCommand` 与 `GestureVisualizationPort` 是 Runtime 向 Android 适配层暴露的必要类型；`GestureExecutor` / `InterruptibleGestureExecutor` 仍为 Runtime 内部接口。`GestureCoordinateRegion` 是共享的纯数据契约，现归属 `:core`；轨迹算法 `GestureGeometryPolicy` 由 `:gesture-core` 编译。
- `AndroidAccessibilityGateway` 留在 `:app`，负责完整 Accessibility Gateway 和窗口/截图/ readiness 能力，并将手势派发委托给 `:gesture-android` 的 `AndroidGestureDispatcher`。
- Android `service.dispatchGesture()` 的实际调用仅位于 `AndroidGestureDispatcher`；连续 Stroke 的多次平台派发仍属于一个由 Runtime 管理的逻辑手势执行。
- `FreedomAccessibilityService` 通过 `GestureRuntimeFactory` 创建一个共享 Runtime handle，并向其注入 Gateway、Visualizer 和调度器；Service 继续拥有 Android 生命周期和系统回调职责。

### 5.2 已处理的 API 参数泄漏

步骤 5 已将 `GestureIntent.FeedSwipe` 中的曲线偏移、扰动频率、密度、扰动开关、扰动幅度及采样数等底层参数全部收回 Runtime。业务意图只表达视口和业务持续时间；策略配置由 `GesturePolicySettingsProvider` 读取，算法由 `GesturePolicyManager` 在每次请求时选择。

原有配置来源、默认值及持久化 key 保持不变。当前只允许策略管理器产生与意图类型兼容的算法决策；`GestureCommandCompiler` 会再次拒绝策略 ID 与请求意图不匹配的组合。

## 6. 步骤 2 的 Gradle 模块实施结果与剩余规则

本次已按以下顺序创建并配置模块，避免引入循环依赖：

1. 已创建 `:business`，编译平台无关 `features/**` 及对应单元测试，且只依赖 `:core` 与 `:gesture-api`。
2. 已创建 `:gesture-api` 并编译 `GestureApi`、业务意图和执行结果契约。
3. 已创建 `:gesture-core`，由其编译纯 Kotlin `GestureGeometryPolicy`；共享 `GestureCoordinateRegion` 契约放在 `:core`，避免 Runtime 为暴露该数据类型而向上导出算法模块。
4. 已创建 `:gesture-runtime`，编译 Pipeline、Validator、Coordinator、Runtime SPI 与 Factory；内部 Executor / Pipeline / Validator / Coordinator 不对 `:app` 暴露。
5. 已创建 Android Library `:gesture-android`，编译 Path Factory、Visualizer、Overlay 和 `AndroidGestureDispatcher`。`AndroidAccessibilityGateway` 保留在 `:app`，只把平台手势派发委托给新 Dispatcher。
6. 已更新 `settings.gradle.kts`、模块 `build.gradle.kts`、`:app` source exclude 与 `ArchitectureBoundaryTest`。
6. **剩余工作：** 把 Feature / Adapter 业务消费者迁入独立 Gradle Module，使它们的 Gradle 依赖只包含 `:gesture-api`（以及它们各自需要的其他公开基础模块）。
8. 后续再将 App 对象组装从 Service 源码中进一步收口到独立 `AppCompositionRoot`，目前 Service 使用 `GestureRuntimeFactory` 创建 Runtime handle。

Gradle 依赖应尽量使用 `implementation`；只有某模块的公开 API 签名确实暴露了另一模块的类型时才使用 `api`。不得为了方便而把 `:gesture-runtime` 加到所有消费者模块的依赖中。

## 7. 必须保持的架构约束

1. 一个业务手势只能通过 `GestureApi.execute()` 提交；业务不得创建 `GestureCommand`、`GestureSpec`、`GesturePathCommand` 或 Android `GestureDescription`。
2. 业务模块在 Gesture 能力上的唯一依赖是 `:gesture-api`（另可依赖 `:core` 基础契约），不得依赖 Runtime / 几何算法实现 / Android 执行实现。
3. `:gesture-runtime` 不依赖 Android SDK 和 `:gesture-android`。
4. `:gesture-android` 拥有唯一直接调用 `AccessibilityService.dispatchGesture()` 的 Android Dispatcher；`:app` 中的 `AndroidAccessibilityGateway` 仅作为兼容性 Gateway 门面委托给 Dispatcher，不直接调用平台派发。
5. 所有业务消费者注入同一个 Runtime Port 实例，由统一 Runtime 做执行协调；不得按 Feature 创建并行执行器。
6. 运行上下文、窗口代次、坐标上下文和结果反馈仍通过明确的不可变契约传递；不得把 Android Node 或 Service 对象暴露给业务 API。
7. `:app` 可以同时依赖 Runtime 和 Android Adapter 以完成组装，但业务源码不得因此留在 `:app` 中绕过依赖边界。
8. 多模块隔离用于防止正常开发中的直接依赖和误用；它不是对恶意修改 APK、反射或独立创建另一个 AccessibilityService 的安全防护边界。

## 8. 步骤 2 本次实施范围和未做事项

### 已完成

- 新增 Gradle 模块：`:business`、`:core`、`:gesture-api`、`:gesture-core`、`:gesture-runtime`、`:gesture-android`。
- 更新 `settings.gradle.kts` 与根 `build.gradle.kts` 插件声明。
- 更新 `app/build.gradle.kts`：添加模块依赖，并排除已由新模块编译的 Feature、Core、Runtime、Android Gesture 旧源路径和测试路径。
- 将平台无关 `com.freedom.features/**` 源码及其测试交由 `:business` 编译；该模块只依赖 `:core` 和 `:gesture-api`。为跨模块消费的少数 Feature 契约明确调整 Kotlin 可见性。
- 将 Runtime 内部执行/验证/编排组件置于独立 Kotlin/JVM 模块边界之后；通过 `GestureRuntimeFactory` 向 `:app` 提供窄工厂和 handle。
- 将 Android 实际派发实现抽取到 `AndroidGestureDispatcher`，`AndroidAccessibilityGateway` 保留其他 Accessibility 操作并委托派发。
- 更新 ArchitectureBoundaryTest，检查模块存在、依赖方向及唯一实际 Android 派发文件。
- 更新 README 与架构状态说明。

### 保留与限制

- **没有删除任何原项目文件。** Kotlin 源文件继续保留原来的 `app/src/main/java` / `app/src/test/java` 相对路径；新模块 Gradle source sets 用精确 include/exclude 编译这些原文件。这样可以在不删除或重命名历史路径的情况下改变其 Gradle 编译所有权。
- Feature 源码现属于 `:business` 编译归属；`AndroidAccessibilityGateway`、`AndroidAccountBrowseWorkflow`、`MomoSocialAdapter` 等 Android / Social Adapter 仍由 `:app` 编译。Adapter 级别的独立 Gradle Module 尚未完成。
- `GestureIntent.FeedSwipe` 仍暴露部分轨迹/扰动参数，需在后续策略管控阶段收回 Runtime Policy。
- **历史记录（步骤 2 实施时）：** 当时 Coordinator 仍使用单活动槽并拒绝并发请求。步骤 7 已增加有界 FIFO 队列，详见 `docs/GESTURE-EXECUTION-QUEUE.md`。
- 本次未改变业务流程、手势几何参数、等待时间、UI、Manifest 权限、持久化键、广播 Action 或服务生命周期。

### 删除文件清单

无。本次未删除任何原项目文件，也没有提出需要删除的文件。

### 验证边界

本地使用 Kotlin 编译器按依赖顺序检查了纯 JVM 的 `:core`、`:gesture-api`、`:gesture-core`、`:gesture-runtime` 源码。由于执行环境没有 Gradle、Gradle Wrapper 和 Android SDK，未能执行 Gradle 原生单元测试、`assembleDebug`、Android Library 编译或设备端回归。不得将纯 Kotlin 源码编译检查表述成完整 Android Gradle 构建通过。


## 9. 步骤 3：唯一公开 Gesture API（2026-11-12）

### 已完成

1. 在 `gesture/gesture-api/src/main/kotlin/com/freedom/gesture/api/GestureApi.kt` 建立唯一公开 `GestureApi` 接口。
2. `GestureApi` 统一提供 `captureContext()`、`execute(GestureIntentRequest, listener)` 和按明确请求 ID 执行的 `cancelActive()`；取消能力不再通过第二个 `InterruptibleGesturePolicyPort` 接口或运行时强转暴露。
3. `GesturePolicyPipeline` 与 `GestureRuntimeHandle` 实现同一 `GestureApi`；Service 仍在 Composition Root 创建一个共享 Runtime 实例，并把其 `GestureApi` 视图注入 Feature / Adapter。
4. `:business` 仍只依赖 `:core` 和 `:gesture-api`；业务 Feature 及手势消费者源码统一引用 `com.freedom.gesture.api.GestureApi`，不引用 Pipeline、Coordinator、Factory、Executor、GestureCommand、GestureSpec 或 Android GestureDescription。
5. 旧 `app/src/main/java/com/freedom/core/accessibility/gesture/GesturePolicyPort.kt` 作为历史占位文件保留，但已从 `:gesture-api` 源集排除，文件内不再声明可编译的入口接口。原始文件没有删除。
6. `ArchitectureBoundaryTest` 增加唯一 API 声明、模块依赖、业务/Adapter 禁止访问 Runtime 内部实现和原始执行类型的静态边界检查。

### 边界说明

`:business` 的编译期依赖边界由 Gradle 强制；Android / Social Adapter 目前仍在 `:app`，而 `:app` 必须依赖 Runtime 以创建和组装实现，因此 Adapter 不能仅靠 Gradle 依赖声明与 Composition Root 隔离。本阶段通过唯一接口注入、源码架构测试和 CI 可执行检查防止 Adapter 直接访问 Runtime。将 Adapter 拆成独立消费者 Gradle Module 仍是后续工作。

`GestureApi` 是常规应用架构边界，不是抵御恶意代码修改、反射或另行实现 AccessibilityService 的安全边界。


## 10. 步骤 6：Runtime 生成与编译流程整理

- 新增 `application/gesture/GestureCommandCompiler.kt` 并纳入 `:gesture-runtime`；对应源码仍保留原 `app/src/main/java` 路径，`:app` 的 Java/Kotlin source set 均排除该文件，防止重复编译。
- `GesturePolicyPipeline` 现在只负责顺序编排：原始请求验证、运行/窗口/坐标上下文复核、策略选择、命令编译、完整几何审批、执行器提交。
- `GestureCommandCompiler` 只接受 `ValidatedGestureRequest`，独占几何生成、点击/滑动计划转换以及生产 `GestureSpec` / `GestureCommand` 构造；编译期间只读取一次显示指标快照，并验证它与请求绑定坐标上下文一致。
- 编译失败以带原因的类型化结果返回，并由 Pipeline 记录 `command_compilation` 阶段拒绝；不会进入审批或执行器。策略算法与意图类型不匹配也会被编译器拒绝。
- `GesturePolicyValidator` 仍在命令生成后执行完整二次验证并签发 `ApprovedGestureCommand`；`GestureExecutionCoordinator` 在可视化/派发前继续验证实时上下文和命令几何；Android Path/Stroke/GestureDescription 转换仍集中在 `:gesture-android`。
- 未改变手势算法、采样参数、时间参数、配置 key、视觉反馈或 Android 派发回调语义。


## 11. 步骤 7：统一 FIFO 执行队列

- `GestureExecutionCoordinator` 成为唯一 FIFO 队列所有者，使用 `ArrayDeque` 管理默认最多 16 个等待项；活动项不计入等待容量。队列满、重复 command ID 或入队前验证失败时返回 `REJECTED`。
- `GestureSubmission.ACCEPTED` 表示 Runtime 已接纳请求，可能正在派发，也可能等待队列；最终状态通过回调报告。
- 请求入队时验证一次，轮到队首时再次验证运行代次、目标窗口、坐标快照和手势几何；页面代次变化清理旧页面等待项。
- `GestureApi.cancelActive(commandId, reason)` 可取消活动项或指定等待项；取消等待项不影响活动平台手势。
- Android 平台同一时刻最多一个未确认排空的逻辑手势。平台 `COMPLETED` / `CANCELLED` 回调是推进队列的依据；取消 drain watchdog 超时只结束业务等待，不释放活动槽，必须等迟到的平台终态回调。
- 对已排队接纳的命令，若轮到执行时 Android 返回 `DEFERRED_NOT_READY`，通过 `GestureExecutionStatus.DEFERRED` 通知调用方刷新上下文并重新提交；不会复用旧坐标轨迹。
- 可视化调用执行期间的取消和页面切换会等待当前 `show()` 返回后才释放 FIFO 槽，防止下一项与上一项可视化并发更新。
- 新增 `docs/GESTURE-EXECUTION-QUEUE.md`，记录队列容量、取消、上下文复核、平台排空和验收约束。
