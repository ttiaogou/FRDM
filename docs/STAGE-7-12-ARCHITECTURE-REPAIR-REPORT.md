# Freedom Stage 7–12 架构边界修复报告

**修复基线：** 用户上传的 `phase-2.1-test-foundation(7).zip`
**对照 Actions：** Run #109，commit `67ae253920731798234ccf496b62f9d975900503`
**项目模块：** 单一 Android Gradle Module `:app`
**本轮目标：** 修复 Actions #109 暴露的 6 项架构边界失败，清理旧实现与重复职责，并把 AccountBrowse Android 执行桥从 Momo Adapter 的包与职责边界中拆出。

## 1. 本轮结论

本轮完成了源代码层面的架构边界修复。按照仓库内 `ArchitectureBoundaryTest.kt` 的 12 项静态断言逐项复核，当前源码树 **12/12 项静态架构断言通过**。项目仍是单一 `:app`，没有新增 Gradle Module，也没有删除或放宽架构测试断言。

本报告不把静态源码检查等同于正式构建或真机验收：当前执行环境没有 Gradle 可执行程序、项目没有 Gradle Wrapper、环境也没有 Android SDK，因此本轮没有运行 Android Gradle `compileDebugKotlin`、`testDebugUnitTest` 或 `assembleDebug`，没有生成 APK，也没有进行真机回归。正式构建结果必须由本轮源码在 GitHub Actions 中验证。

## 2. AccountBrowse 职责边界调整

### 2.1 Android 执行桥退出 Momo Adapter

将完整 Android 执行桥：

- 原：`adapters/social/momo/AndroidAccountBrowseWorkflow.kt`
- 现：`adapters/accessibility/android/AndroidAccountBrowseWorkflow.kt`

它仍然使用 Android `AccessibilityService`、`AccessibilityNodeInfo`、`Handler`、`GestureDescription` 等平台能力，因此归属 Android Accessibility Adapter，而不是 Core 或 Feature。它通过 Adapter 内部的 `AccountBrowseAndroidPort` 调用 Momo 页面检查、卡片选择、导航和 Feed 手势能力，不再直接依赖 `MomoSocialAdapter` 的具体类。

### 2.2 Runtime bridge 独立归入运行时适配器

将：

- 原：`adapters/social/momo/AccountBrowseModeRuntimeAdapter.kt`
- 现：`adapters/runtime/android/AccountBrowseModeRuntimeAdapter.kt`

Application 的 `RunSessionCoordinator` 仍然是唯一的运行模式协调与 Runtime 事件分发所有者；Runtime bridge 负责把生命周期和已归一化事件交给 AccountBrowse Android 执行桥。

### 2.3 Feature 不再暴露 Android Port/Models

删除：

- `features/accountbrowse/android/AccountBrowseAndroidPort.kt`
- `features/accountbrowse/android/AccountBrowseAndroidModels.kt`

这些定义包含 `AccessibilityNodeInfo`、`Rect`、`Path` 等 Android 类型，并不是平台无关的 Feature 契约。当前 Android 专用的 Momo UI 能力契约及其模型保留在 `adapters/social/momo/`；`features/accountbrowse/` 只保留阶段、会话、调度和业务策略。

### 2.4 状态所有权

`AccountBrowseRuntime` 继续持有自动浏览阶段、会话实例、单步调度、阶段动作分派、暂停/恢复、人工接管和导航恢复准备。Android 执行桥提供相应动作的 Android 节点/手势/窗口执行能力。Momo Adapter 只负责陌陌 UI 识别和交互能力，不再承载完整 AccountBrowse Workflow 或 ModeRuntime bridge。

**边界说明：** 当前 Android 执行桥仍是一个较大的兼容执行组件，承载阶段动作所需的平台操作和页面结果处理。它不是平台无关的 Feature，也不应继续向其中增加新的领域状态。未来若要进一步拆细，应围绕显式输入/输出结果契约拆分导航、Feed、详情和恢复执行器；不得改变原有动作顺序、时间参数、重试条件或手势参数。

## 3. Core / Feature / Data 清理

### 3.1 删除 Core 中的 Android 实现副本

删除旧路径中的 Android 实现，保留现有 Adapter 实现：

- `core/accessibility/android/AndroidAccessibilityGateway.kt`
- `core/accessibility/android/AndroidAccessibilityEventNormalizer.kt`
- `core/accessibility/overlay/android/AndroidOverlayPort.kt`
- `core/runtime/android/HandlerTaskScheduler.kt`

同时清理 Core 中不再使用且直接依赖 Android 的旧事件格式化器和旧手势设置实现。活动实现位于 `adapters/accessibility/android/`、`adapters/runtime/android/` 和 `adapters/android/gestures/`。

`core/accessibility/AccessibilityEventRouter.kt` 已改为消费 `AccessibilityEventEnvelope` 与 `AccessibilityEventType`，不再直接接收 Android `AccessibilityEvent`。

### 3.2 清理旧的、未被生产入口使用的直接持久化代码

删除不再被 Service/运行时使用的旧实现：

- `accessibility/scan/AccessibilityTreeScanner.kt`：当前 Service 使用 `AccessibilityTreePreviewUseCase`、`AccessibilityGateway` 和 Snapshot Repository；该旧类无生产调用方。
- `accessibility/browse/MomoFeedGestureController.kt`：旧版本直接读取 SharedPreferences/MonitorStore 常量；当前运行路径使用 `adapters/social/momo/MomoFeedGestureController.kt`，其配置从 Repository 读取。
- `accessibility/gesture/GestureTuning.kt` 与 `core/accessibility/gestures/GestureTuning.kt`：未使用的旧副本；当前手势设置实现位于 `adapters/android/gestures/GestureTuning.kt`，通过 Settings Repository 读写。
- `runtime/RunMode.kt`：旧的重复 RunMode/SharedPreferences 实现；当前 RunMode 位于 Core，偏好读取位于 Data。
- `overlay/MonitorOverlay.kt` 与 `overlay/GestureTrailOverlay.kt`：旧 UI 实现副本；当前使用 Adapter 包内的悬浮窗实现。

删除上述旧入口后，`MonitorStore` 直接访问与 SharedPreferences API 调用在生产源码中均限定于 `data/`。

### 3.3 清理重复 AccountBrowse 代码与重复测试

删除早期迁移遗留的 `accessibility/browse/` 整个旧实现目录，包括旧 AccountBrowse controller、session/model、Momo inspector/navigation/popup 等实现。它们均不再被生产入口引用；当前活动实现分别位于 `features/accountbrowse/` 和 `adapters/social/momo/`。

同时删除 `app/src/test/java/com/freedom/accessibility/browse/` 中 7 份旧策略测试副本。逐个比较确认，这 7 份测试的断言主体与 `app/src/test/java/com/freedom/features/accountbrowse/` 中相应测试完全一致，仅 package 不同；Feature 包中的对应测试全部保留。另删除旧 Android 事件策略测试副本，保留基于归一化事件类型的 Core 测试。

## 4. 未修改的行为范围

本轮不改写活动 Momo Adapter 中的页面识别、动态卡片筛选、导航、弹窗识别和 Feed 手势算法；不改变活动 Android AccountBrowse 执行桥中的动作阶段、回调顺序、定时值、重试阈值、手势路径/采样参数、人工接管和恢复顺序。对活动执行桥的代码更改仅为 package/import 与职责注释调整。

本轮删除的是已无生产引用的旧实现/重复副本，不是当前运行链路中的活动组件。未增加 UI 功能、权限、Intent Action、持久化 key、网络请求、AI Provider 或消息发送功能。

## 5. 架构静态检查结果

按照 `ArchitectureBoundaryTest.kt` 的检查逻辑，在交付源码上执行独立静态检查，结果如下：

| 检查项 | 结果 |
|---|---:|
| Core 无 Android/AndroidX/外层依赖 | PASS |
| Feature 无 Android/AndroidX/外层依赖 | PASS |
| Application 无 Android/Adapter/Data 具体实现依赖 | PASS |
| MonitorStore 只在 Data 访问 | PASS |
| SharedPreferences 只在 Data 访问 | PASS |
| UI/Feature 不直接依赖 Data 实现 | PASS |
| AccountBrowse Feature 不暴露 Android node/service 类型 | PASS |
| UI 不依赖具体 Adapter/Data/accessibility 实现 | PASS |
| Android 实现不存放在 Core/Feature 禁止路径 | PASS |
| ModeRuntimePort 仅使用归一化事件 | PASS |
| Service 是唯一 Runtime Coordinator 组装入口 | PASS |
| 项目仍只有 `:app` 一个 Gradle Module | PASS |

**结果：12/12 静态架构断言通过。** 这是源码结构验证，不是 Kotlin/Android 编译。

交付树当前包含 78 个 `src/main` Kotlin 源文件和 21 个 Kotlin 单元测试文件。旧的重复 AccountBrowse 测试副本已移除；对应的 Feature 测试保留。测试文件数量不代表测试已执行。

## 6. 构建与真机验收状态

| 验证项目 | 本轮状态 |
|---|---|
| ZIP 完整性与源码树检查 | 已执行 |
| ArchitectureBoundaryTest 逻辑的独立静态复核 | 12/12 通过 |
| Kotlin import/path 静态核查 | 已执行；未发现未解析的 `com.freedom.*` import（`com.freedom.R` 为 Android Gradle 生成资源类） |
| `:app:compileDebugKotlin` | 未运行（缺 Gradle/Android SDK） |
| `:app:testDebugUnitTest` | 未运行 |
| `:app:assembleDebug` | 未运行；无 APK |
| 真机自动浏览回归 | 未运行 |

下一次 GitHub Actions 必须以本 ZIP 的完整源码树为准，而不是只覆盖旧 checkout。构建时重点确认：`compileDebugKotlin`、`testDebugUnitTest`、`assembleDebug`、APK artifact 上传均成功。之后仍需真机验证陌陌动态页识别、列表滑动、卡片筛选、详情进出、人工暂停/恢复、服务断连重连和 AI 观察模式。
