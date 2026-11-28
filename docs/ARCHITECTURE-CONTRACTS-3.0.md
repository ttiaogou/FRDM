# Freedom — 接口契约索引与 3.0 迁移历史

> 当前工程描述以 [ARCHITECTURE.md](ARCHITECTURE.md) 为准；施工规则以 [AGENTS.md](../AGENTS.md) 及现行手册为准。本页不是另一套施工规范。核查基线 `9919a0b30a9cfd19235d691ee4ccfee5ec664564`。

## 现行契约与证据索引

| 契约范围 | 当前对应证据 |
| --- | --- |
| 模块依赖及物理源码 | [现行架构](ARCHITECTURE.md)；settings.gradle.kts 和模块 Gradle 文件；R09-A |
| 事件与节点边界 | UnifiedAccessibilityEntry、平台中立快照与现有 Gateway；Service 原始回调转发；现行 02/03 分片 |
| 页面、任务、UI 操作权 | PageObservationCoordinator、RunSessionCoordinator、TaskCoordinator、UiOperationOwnershipCoordinator、UiWriteAuthorization；R09-C |
| 业务状态与端口 | 现有 Feature/Runtime 与平台适配端口；R07/R08 所有权证据及 R09-G |
| 恢复与异步隔离 | 既有共享恢复链、运行代次、检查点验证；R09-D |
| 手势及输入 | [手势边界](GESTURE-MODULE-BOUNDARIES.md)、现行 04–09 分片 |
| AI、消息及持久化 | 现行 10 分片及实际 Feature/Repository；R01/R02 行为证据 |
| OCR IPC | [OCR 插件架构](ocr/OCR-PLUGIN-ARCHITECTURE.md) |

实际冻结证据为 [R01](audits/FRDM-PP-R01-CONTRACT-FREEZE.md)、[R02](audits/FRDM-PP-R02-BEHAVIOR-REGRESSION.md)、`tools/architecture/pp_r01_contracts.json` 和相应测试/精确迁移证据，不能用本文替代。

## 与旧 3.0 目标的区分

- 单 :app、共享源码、旧类依赖和历史目标目录：已不代表现行九模块物理工程，不能据此重建组件。
- 平台中立边界、状态唯一、节点生命周期、业务/数据契约和行为保护：仍需按现行规范、现有源码与冻结测试核实；不得从历史目标推断已实现或授权新增。
- 旧阶段的 AI 只读、未接入模型/发送、旧验证限制：仅为当时状态。当前存在草稿及发送事务、OCR 插件、自动化测试和 APK 交付；真机仍待验收。
- 历史报告有追溯价值，原文保留；若发现与现行规则/代码冲突，依 AGENTS 记录，不反向修改现行规范。

## 历史记录（非现行施工依据）

旧正文原样保存在下方及[固定提交原文](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/ARCHITECTURE-CONTRACTS-3.0.md)，包括当时的路径、目标、未完成事项与验证限制。它们不是 PP 的现行实现或新增架构授权；G01-C 再核查归档位置与引用。

<details>
<summary>展开历史正文</summary>

# Freedom 3.0 整体迁移 — 阶段 1：最终接口与依赖边界

**阶段状态：** 接口基线已定义；未接管或重写现有业务流程。  
**输入基线：** `Freedom-Architecture-Migration-2.2-source(1).zip`  
**基线 SHA-256：** `e09f444851c8a0cf4586b0979517086948a76182f0e4be830e857f4e3b0fe1f7`  
**本阶段范围：** 新增平台无关契约、运行协调边界、社交适配端口、数据端口及接口测试；保留现有 Service、Runtime、Inspector、Controller 的执行接线不变。

---

## 1. 阶段目标与边界

阶段 0 已确认 2.2 仍是过渡结构：`FreedomAccessibilityService` 负责大量自动浏览决策与步骤，`AccountBrowseRuntime` 的阶段动作仍回调 Service，AI 观察器直接依赖 Momo Inspector，运行模式协调器直接依赖 `MonitorStore`，手势校准器直接依赖具体 Service。

阶段 1 的目标是先固定最终组件之间的调用契约，后续迁移只替换实现与接线，不再反复讨论组件边界。

本阶段**不做**：

- 不改变自动浏览算法、阶段值、等待时间、重试次数、手势曲线、阈值或动作顺序。
- 不改变 AI 事件观察的 300ms / 500ms debounce 和有限区域重查行为。
- 不接入真实陌陌 Adapter、新的 Android Accessibility Gateway 或真实数据仓储实现。
- 不调整 Manifest、权限、Gradle 依赖、SharedPreferences key、广播 Action 或 UI。
- 不实现聊天消息解析、AI API 调用或消息发送。

因此本阶段新增的是**可编译的接口与数据契约**，不是已接入应用运行路径的完整模块迁移。

## 2. 最终目标包结构

保持一个 `:app` Gradle Module。逻辑目录按以下目标组织：

```text
com.freedom
├── application
│   ├── ports
│   └── runtime
│       └── RunSessionCoordinator
├── core
│   ├── accessibility
│   │   ├── api
│   │   ├── events
│   │   ├── gestures
│   │   ├── nodes
│   │   └── overlay
│   ├── runtime
│   └── common
├── features
│   ├── accountbrowse
│   │   ├── domain
│   │   ├── application
│   │   ├── runtime
│   │   ├── state
│   │   └── ports
│   └── aireply
│       ├── domain
│       ├── application
│       ├── runtime
│       ├── context
│       ├── tasks
│       └── ports
├── adapters
│   └── social
│       ├── momo
│       │   ├── page
│       │   ├── browse
│       │   ├── chat
│       │   └── interaction
│       └── xiaohongshu
├── data
│   ├── repository
│   ├── preferences
│   └── database
├── logging
├── platform
│   ├── accessibility
│   ├── overlay
│   └── stability
├── ui
└── accessibility
    └── FreedomAccessibilityService（最终只保留系统入口和生命周期桥接）
```

`application` 是新增的应用编排层，负责把 RunCoordinator、业务 Runtime、数据仓储和 Android 平台实现组合起来。它可以依赖这些抽象契约，但不能让 `core` 反过来依赖业务 Feature。

## 3. 已新增的接口与模型文件

### 3.1 无障碍与节点操作契约

文件：`core/accessibility/api/AccessibilityGateway.kt`

已定义：

- `ActiveWindowInspectionPort`：屏幕尺寸、当前窗口快照、窗口有效性检查。
- `AccessibilityActionPort`：节点动作与 Android 全局动作。
- `GestureExecutionPort`：手势提交和异步完成/取消回调。
- `AccessibilityGateway`：供 Service 组合根使用的聚合接口。
- `WindowRef`：包名、Activity 辅助信息和窗口代次。
- `AccessibilityTreeSnapshot` / `AccessibilityNodeSnapshot`：平台无关的节点树快照。
- `NodePath` / `NodeTarget`：窗口代次与子节点路径构成的短时目标。
- `ScreenRect` / `GestureSpec`：取代业务模型中的 Android `Rect` / `Path`。

**节点生命周期约束：**

1. Android 适配器读取当前 root，遍历并创建不可变快照。
2. 读树过程中获取的节点由适配器在本次扫描内释放。
3. Feature 只能持有 `NodeTarget`，不能持有 `AccessibilityNodeInfo`；Adapter 只在单次检查或操作内持有临时节点。
4. 执行动作时，适配器重新获取当前 root，并验证窗口代次、包名、路径和预期 ViewId/Class。
5. 窗口已变化或路径已失效，动作返回失败结果；调用方按现有业务恢复规则处理，不能对旧节点继续操作。
6. `NodeTextCapture.EPHEMERAL` 表示文字只允许在本次内存检查中使用，不可默认写日志或持久化。

这使自动浏览仍可使用文字、ViewId、几何位置和节点结构进行原有判断，但不再把 Android 节点对象带入业务状态。

### 3.2 无障碍事件契约

文件：`core/accessibility/events/AccessibilityEventEnvelope.kt`

已定义：

- `AccessibilityEventType`：把 Android 原始事件类型归一化为平台无关枚举。
- `AccessibilityContentChange`：子树、面板出现/消失等结构变化类型。
- `AccessibilitySourceSnapshot`：来源控件的类名、ViewId、几何范围和交互属性。
- `AccessibilityUiSignal`：经过白名单提取的消息入口、聊天导航、发送按钮、输入框、列表容器信号。
- `AccessibilityEventEnvelope`：不可变事件载荷，不持有 `AccessibilityEvent` 或来源节点。
- `AccessibilityEventRoutePolicy`：事件路由与日志去重边界。

`DefaultAccessibilityEventRoutePolicy` 位于同一目录，固定了现有路由规则：

- `TOUCH_INTERACTION_START / END` 必须在包名过滤前识别为 `GLOBAL_TOUCH`。
- 非目标应用事件和 Freedom 自身事件返回 `IGNORE`。
- 其余目标应用事件返回 `TARGET_APPLICATION`。
- 250ms 同签名去重仅用于降低日志重复，不能丢弃自动浏览状态机所需的真实事件。

**隐私约束：** `eventText` / `contentDescription` / source text 是临时事件数据，不得直接序列化整个 Envelope。AI 模式由 Android 转换器优先只提取 `AccessibilityUiSignal`，不保留聊天正文和联系人描述；自动浏览模式为保持现有人工操作归因，可在内存中短时携带必要文字，但日志仍必须遵循既有截断和过滤规则。

### 3.3 运行模式、状态存储与调度契约

文件：

- `core/runtime/RunStatePorts.kt`
- `core/runtime/TaskScheduler.kt`
- `core/runtime/ModeRuntimePort.kt`
- `application/runtime/RunSessionCoordinator.kt`

已定义：

- `RunStateRepository`：运行意图、活动模式的读写。
- `RunModeSelectionSource`：读取现有 UI 模式选项。
- `RuntimeLogSink`：记录兼容迁移提示等运行日志。
- `TaskScheduler` / `ScheduledTask`：将 Handler 延迟执行和取消能力抽象出来。
- `ModeRuntimePort`：两个业务 Runtime 统一的启动、停止、服务连接/断开、目标应用事件和全局触摸事件入口。
- `RunSessionCoordinator`：按当前模式把事件与生命周期分发给唯一 Runtime。

**存储顺序固定：**

- 启动：先持久化活动模式，再设置运行意图为 true，然后通知所选 Runtime。
- 停止：先确定当前锁定模式，再设置运行意图为 false；数据适配器沿用现有 `MonitorStore.setRunning(false)` 清除活动模式的语义，然后通知对应 Runtime 停止。
- 恢复：只恢复已锁定的活动模式；只有旧版本缺少锁定值且当前运行意图为 true 时，才读取无歧义的 UI 选择进行兼容迁移。两个选项同时选中或都未选中时不得猜测。
- UI 选项改变不能中途改变已运行会话的模式。

`TaskScheduler` 只提供延迟执行机制，不负责替代 Feature 调度语义。`AccountBrowseScheduler` 现有“只允许一个待执行 step、不替换 pending、执行前清除 pending、取消同一个 Runnable”的行为，仍必须由 AccountBrowse 自己保持。

`RunSessionCoordinator` 目前是可编译的编排契约草案，尚未替换 MainActivity / Service 当前直接访问 MonitorStore 的路径。后续接线必须同时保留 UI 权限检查、悬浮窗创建、保活服务启动、目标应用启动和失败回滚的既有顺序。

### 3.4 AccountBrowse 到社交软件的端口

文件：`features/accountbrowse/ports/SocialBrowsePort.kt`

已定义的只读结构化证据：

- 页面类型、Activity 辅助信息和页面指纹。
- 动态 Tab / 首页 Tab 的目标与边界。
- 弹窗危险性、是否可关闭和安全关闭目标。
- Feed readiness、指纹、列表目标。
- 动态卡片候选及广告/直播/未知分类。
- 详情页身份和列表返回验证。
- Feed 手势计划。

`SocialBrowsePort` 在内部请求有限窗口快照，只把结构化证据和临时 `NodeTarget` / `GestureSpec` 返回 AccountBrowse；原始窗口树和临时文字不会传给 Feature。以下内容仍由 AccountBrowse 自己决定：

- 什么时候导航、点击或返回。
- 哪个状态进入哪个阶段。
- 等待时间、deadline、重试次数和停止条件。
- 手势之后何时验证滚动、刷新、详情和列表位置。
- 弹窗证据是否足以安全关闭。
- 人工接管、暂停、恢复和异常恢复策略。

这样可以替换社交应用的 UI 适配方式，而不把业务规则交给 Momo Adapter。

### 3.5 AIReply 到聊天页面的端口

文件：`features/aireply/ports/ChatPagePort.kt`

已定义：

- 通用 `ChatPageKind`。
- `ChatPageSnapshot`：包名、Activity、结构计数、消息列表和输入框短时目标。
- `ChatRegionSnapshot`：已知区域路径刷新和有效性信息。
- `ChatPagePort`：全页面结构识别和已知聊天区域刷新。

**依赖方向：** AIReply 只依赖 `ChatPagePort`；后续 `MomoChatAdapter` 实现这个端口，并在 Adapter 内部调用 Core Gateway 完成受限扫描。AIReply 不得 import `MomoChatPageInspector`、`MomoChatPageType` 等 Momo 专属类型，也不会接收原始窗口树或消息正文。聊天页识别仍只是页面结构候选，不等于已识别消息、会话或可以安全发送。

### 3.6 Overlay 与数据展示端口

文件：

- `core/accessibility/overlay/OverlayPort.kt`
- `application/ports/MonitorDataPorts.kt`

已定义：

- Overlay 显示状态、触摸穿透、倒计时、监控刷新与手势轨迹接口。
- 目标应用设置与监控展示快照的 Repository 接口。

触摸穿透接口允许传入 watchdog timeout；后续平台实现必须保持当前“穿透失败就不发送滑动”“超时自动恢复”“正常结束后取消 watchdog 并恢复悬浮窗”的安全行为。

### 3.7 事件与运行编排

`RunSessionCoordinator` 位于 `application/runtime`，而非 `core`。它接收已标准化事件，并将目标应用事件或全局触摸事件交给当前锁定的唯一 Runtime。具体 Android Event 到 Envelope 的转换、权限与服务生命周期仍属于平台入口。

---

## 4. 模块依赖方向冻结

| 发起方 | 允许依赖 | 禁止依赖 |
|---|---|---|
| `core` | Kotlin 标准库、稳定的核心模型/端口 | `features`、`adapters`、`data`、`ui`、`MainActivity`、具体 Service |
| `features/accountbrowse` | core 端口/模型、自己的领域模型 | Momo 类、其他社交 Adapter、Android Service / NodeInfo / Handler、MonitorStore、UI |
| `features/aireply` | core 事件/节点模型、自己的 ChatPagePort | Momo 类、自动浏览内部状态、Android Service / NodeInfo / Handler、MonitorStore、UI |
| `adapters/social/*` | core accessibility API、Feature 对外 Port、Android SDK（仅平台实现文件） | `FreedomAccessibilityService`、MainActivity、MonitorStore、另一个社交 Adapter 的内部实现 |
| `data` | core/application 的 Repository Port、Android 存储 API | Feature Runtime、Service、UI 内部实现 |
| `platform` | core 平台接口、Android SDK、overlay/stability API | Feature 的内部状态机、Momo 的业务规则 |
| `application` | core 端口、Feature 对外 Runtime Port、adapter/data/platform 实现的组合接口 | Android View/UI 细节；业务判断细节不应复制到这里 |
| `ui` | application 的启动/停止用例、UI 状态模型 | 直接持有 AccountBrowseSessionState、AI 内部 Runtime 或直接修改活动模式存储 |
| `FreedomAccessibilityService` | Android 生命周期、Application Runtime Host、平台桥接 | 页面流程、AccountBrowse 状态、AI 回复状态、Momo Inspector 决策 |

**循环依赖禁止。** 所有依赖图必须从业务实现指向抽象端口，而不是从 core/feature 指向某个具体 Android Service 或存储类。

### 当前源码尚未满足的旧依赖（本阶段保留）

上表是最终允许的依赖方向，不代表整个 2.2 代码当前已经全部符合。为了避免把“建立接口”误报成“完成迁移”，以下旧实现仍需后续阶段逐个切换：

- `core/runtime/RunCoordinator.kt` 仍直接引用 `MonitorStore` 和 Android `Context`。
- `core/runtime/RunMode.kt` 内的 `RunModePreferences` 仍直接读取 Android SharedPreferences。
- `features/aireply/AiReplyObservationRuntime.kt` 仍直接引用 Momo Inspector、`AccessibilityService`、`AccessibilityEvent` 和 `Handler`。
- `features/accountbrowse/AccountBrowseRuntime.kt` 仍直接使用 `Handler`，并通过 14 个 Actions 回调委托给 Service。
- `features/accountbrowse/AccountBrowseModels.kt` 仍含 Android `Rect`、`Path` 和 `AccessibilityNodeInfo` 数据类型。
- `adapters/social/momo/*` 仍有旧的 Android 节点对象实现，尚未全面切换为 Tree Snapshot / NodeTarget。
- `GestureCalibrationController` 仍持有具体 `FreedomAccessibilityService`。
- `MainActivity` 和 Service 仍直接读写 MonitorStore。

新接口文件没有引入上述反向依赖，但这些旧实现要到后续 Core / Momo Adapter / AccountBrowse / AIReply / Service 迁移中实际切换后，整个代码库才会满足目标依赖图。

---

## 5. 状态所有权基线

| 状态/资源 | 唯一所有者（最终） | 不允许的副本/做法 |
|---|---|---|
| 用户是否请求运行、当前锁定模式 | `RunStateRepository` + `RunSessionCoordinator` | MainActivity、Service 各自维护一份运行模式 |
| AccountBrowse 的 65 个可变会话字段和 17 个阶段 | `AccountBrowseRuntime` | Service 直接读写可变 session 字段；Adapter 记录阶段 |
| AI 页面 debounce、已知区域识别状态 | `AiReplyRuntime` | Service 额外持有同一套观察计时器/页面缓存 |
| Momo 页面的临时识别证据 | 当前一次 `SocialBrowsePort` / `ChatPagePort` 响应 | 长期缓存 AccessibilityNodeInfo 或跨窗口复用 NodeTarget |
| Android 当前窗口和节点对象 | Core Accessibility Android Adapter | Feature / Adapter / Repository 持有 NodeInfo |
| 手势实际派发与回调 | GestureExecutionPort 的 Android 实现 | Service 与 Adapter 两边重复派发/完成状态 |
| WindowManager、倒计时、触摸穿透 watchdog | Overlay 平台实现 | AccountBrowse 直接操作 WindowManager / View |
| 运行日志和监控快照 | Logging / Repository 实现 | Feature 直接依赖 MonitorStore |
| 目标包名和 UI 配置选择 | 对应 Settings Repository | RunCoordinator 直接调用 SharedPreferences |

注意：阶段 0 源码实际统计为 65 个 `AccountBrowseSessionState` 可变字段；历史 2.2 文档曾记 53 个。后续迁移以源码逐字段清单为准，不能因为接口文件已建立就假设状态已转移。

---

## 6. 关键流程契约

### 6.1 运行启动与失败回滚

目标调用链：

```text
UI 权限/目标应用预检查
        ↓
StartRunUseCase / RunSessionCoordinator
        ↓
保存活动模式 → 保存运行意图
        ↓
保活与悬浮窗按既有条件启动
        ↓
启动目标应用
        ↓
Service 连接后恢复当前锁定 Runtime
```

若保活、悬浮窗或目标应用启动失败，必须沿现有逻辑清理运行意图、活动模式、保活服务和悬浮窗。不能因为将启动流程抽到 UseCase 而改变先后顺序或失败提示。

### 6.2 无障碍事件路由

```text
Android AccessibilityEvent
        ↓（平台边界复制字段并回收 source）
AccessibilityEventEnvelope
        ↓
AccessibilityEventRoutePolicy
        ├── GLOBAL_TOUCH → 当前模式 Runtime 的全局触摸入口
        ├── TARGET_APPLICATION → 当前模式 Runtime 的目标应用事件入口
        └── IGNORE → 结束
```

全局触摸事件必须先于包名过滤；AI Runtime 不得调用 AccountBrowse 的人工接管逻辑。事件日志去重不得被误用成业务事件丢弃。

### 6.3 页面检查、动作执行与窗口验证

```text
Feature 调用 SocialBrowsePort / ChatPagePort
        ↓
Momo Adapter 在内部调用 AccessibilityGateway.inspectActiveWindow（有限节点数）
        ↓
不可变 AccessibilityTreeSnapshot（仅 Adapter 本次调用内使用）
        ↓
Momo Adapter 输出证据 / NodeTarget / GestureSpec（不返回原始树或文字）
        ↓
Feature 按现有策略决定是否执行
        ↓
AccessibilityActionPort / GestureExecutionPort
        ↓
Android 适配器重新取 root，验证 window generation 和 path
        ↓
返回 ActionResult / Gesture callback
        ↓
Feature 根据现有验证窗口和恢复策略决定下一状态
```

**点击成功不等于页面导航成功。** 页面导航、弹窗关闭、Feed 刷新、动态详情进入和返回都必须保留当前的“提交动作后重新检查并验证”机制。

### 6.4 AI 聊天页观察

```text
AiReplyRuntime
    ↓ ChatPagePort
MomoChatAdapter
    ↓ 内部调用 AccessibilityGateway，短时检查活动窗口
仅返回 ChatPageSnapshot / ChatRegionSnapshot
    ↓
AiReplyRuntime 维护 debounce 和观察状态
```

现有 300ms 页面结构 debounce、500ms 聊天区域 refresh、已知区域失效后重新识别的行为必须保留。该链路只识别页面结构，不代表已经实现消息解析、上下文、AI 模型或发送功能。

---

## 7. 当前旧实现与新契约的映射

| 现有实现 | 最终契约/所有者 | 后续迁移要求 |
|---|---|---|
| `AccessibilityEventRouter` | `AccessibilityEventRoutePolicy` | Android Event 先转换 Envelope；全局触摸先路由；250ms 日志去重不丢工作流事件 |
| `AccessibilityTreeScanner` | `ActiveWindowInspectionPort` / Tree Snapshot Repository | 保留扫描上限和预览上限，树遍历节点由 Android 适配器释放 |
| `GestureCalibrationController` | `GestureExecutionPort` + Calibration Runtime + Broadcast Port | 不再接收具体 FreedomAccessibilityService；保留校准页必须在 Freedom 前台的安全验证与原始试验时序 |
| `MomoPageInspector` / `MomoPopupInspector` | `SocialBrowsePort` | Inspector 改读 Tree Snapshot，输出纯证据和 NodeTarget，不输出 NodeInfo |
| `MomoFeedGestureController` | `SocialBrowsePort.planFeedGesture` + `GestureSpec` | 曲线、扰动参数、容器几何和限制保持不变 |
| `MomoNavigationController` | `SocialBrowsePort` + `AccessibilityActionPort` | 适配器识别目标，AccountBrowse 决定时机/验证，Gateway 执行动作 |
| `MomoDynamicCardSelector` | `SocialBrowsePort.selectVisibleFeedCard` | 广告、直播、未知卡片过滤条件不变 |
| `AiReplyObservationRuntime` | `ModeRuntimePort` + `ChatPagePort` + `TaskScheduler` | 移除对 Momo 类和 AccessibilityService/Handler 的直接依赖 |
| `RunCoordinator` / `RunModePreferences` | `RunSessionCoordinator` + `RunStateRepository` + `RunModeSelectionSource` | RunMode 纯模型与 SharedPreferences 实现分开；保留 key 和迁移规则 |
| `MonitorStore` | 多个 Repository/Log Sink 实现 | 现有 key、commit/apply、日志/快照上限、广播行为保持一致 |
| Service 页面流程方法 | `AccountBrowseRuntime` + `SocialBrowsePort` + Gateway + OverlayPort | 不可简单剪切；必须移入真正所有者并移除 Service 回调闭环 |
| `MainActivity` 启停流程 | Application Use Case / `RunSessionCoordinator` | 权限检查和失败回滚顺序保持不变 |

---

## 8. 本阶段源码变更清单

新增生产源码：

- `application/ports/MonitorDataPorts.kt`
- `application/runtime/RunSessionCoordinator.kt`
- `core/accessibility/api/AccessibilityGateway.kt`
- `core/accessibility/events/AccessibilityEventEnvelope.kt`
- `core/accessibility/events/DefaultAccessibilityEventRoutePolicy.kt`
- `core/accessibility/overlay/OverlayPort.kt`
- `core/runtime/ModeRuntimePort.kt`
- `core/runtime/RunStatePorts.kt`
- `core/runtime/TaskScheduler.kt`
- `features/accountbrowse/ports/SocialBrowsePort.kt`
- `features/aireply/ports/ChatPagePort.kt`

新增测试：

- `DefaultAccessibilityEventRoutePolicyTest.kt`：4 项路由/去重测试。
- `RunSessionCoordinatorTest.kt`：4 项启动顺序、重复启动、旧配置迁移、停止行为测试。

未改动：

- `FreedomAccessibilityService.kt`、`MainActivity.kt`、现有 Controller / Inspector / Runtime。
- `AndroidManifest.xml`、Gradle 构建文件、依赖、资源、权限、广播 Action 和 SharedPreferences key。

与基线逐文件 SHA-256 比较：原有 72 个项目文件均未变化、未缺失；本阶段只增加接口/测试/文档文件。新增后主源码为 52 个 Kotlin 文件、测试源码为 13 个 Kotlin 文件，测试方法标记合计 83 个（原 75 + 新增 8）。

---

## 9. 编译与验证记录

| 项目 | 本阶段结果 |
|---|---|
| 新增接口纯 Kotlin 编译 | 通过（临时 Android `Context` stub 仅用于解析现有 `RunMode.kt` 中的旧偏好读取对象） |
| 新契约对 Android 平台类型的直接依赖扫描 | 通过；新增接口文件不 import Android SDK 类型 |
| Feature Port 对具体 Momo / Service / MonitorStore 的依赖扫描 | 未发现 |
| 新增单元测试 | 8 项通过本轮本地 Kotlin 断言运行器（临时 JUnit 兼容桩）；未通过项目 Gradle/JUnit Runner 执行 |
| Android Gradle 构建 | 未执行；基线压缩包没有 Gradle Wrapper，当前环境无 Gradle 和 Android SDK 配置 |
| 真机测试 | 未执行；本阶段未接入运行路径 |

注意：Kotlin 独立编译只验证新增契约文件的语法/类型闭合，不代表整个 Android 项目构建成功。本轮 8 项测试通过的是本地断言运行器，不等于 Gradle/JUnit Runner 结果；仍需在 GitHub Actions 或具备 Gradle/JUnit 环境的 Android Studio 中正式运行。

---

## 10. 阶段 1 出口条件与下一阶段

本阶段接口基线已建立。进入阶段 2 前应接受以下边界：

1. Feature 不再接收 `AccessibilityService`、`AccessibilityEvent`、`AccessibilityNodeInfo`、`Handler`、Android `Rect` / `Path`。
2. AIReply 只依赖 `ChatPagePort`，不直接 import Momo 类型，也不直接取得聊天页原始节点树。
3. AccountBrowse 只依赖 `SocialBrowsePort`、Gateway、Scheduler、Overlay 等接口，不依赖 Momo 实现。
4. Momo Adapter 实现 AccountBrowse / AIReply 对外端口；不能反向控制 Feature 状态机。
5. Data 实现 Repository Port；Core / Feature 不 import MonitorStore。
6. Service 只做系统对象转换、生命周期和 Composition Root 桥接。
7. 状态唯一所有者和原始执行时序按本文件的表格冻结。

**下一阶段（阶段 2）：Core Accessibility 与 Momo Adapter 集中迁移。**

阶段 2 将把当前 `AccessibilityTreeScanner`、节点查找、低层节点动作、手势派发、全局 Back、悬浮窗操作和陌陌 Inspector / Selector 的实现迁移到这些契约后面；同时保持 AccountBrowse 的策略与状态流程不改动。进入迁移前还需把 `AccountBrowseModels` 中的 `Rect`、`Path`、`AccessibilityNodeInfo` 字段逐个映射为 `ScreenRect`、`GestureSpec`、`NodeTarget`，不能直接跨层保留平台对象。

---

## Stage 7–12 implementation verification notes

The implementation now places the active `RunSessionCoordinator` in the AccessibilityService composition root; UI start/stop requests use `RunSessionCommandUseCase`. All target/global-touch events pass through the normalized `AccessibilityEventEnvelope` and `DefaultAccessibilityEventRoutePolicy` before dispatch to a single mode Runtime.

Core, Feature, and Application source trees are Android-free. `SocialBrowsePort` exposes Momo page/navigation/popup/feed/card/gesture/detail evidence using `NodeTarget`, `ScreenRect`, and `GestureSpec`; `AndroidAccountBrowseWorkflow` depends on that port and `AccessibilityGateway`, while raw Android nodes remain inside Momo/Android adapters. The old `AccountBrowseAndroidPort` compatibility bridge has been removed. The workflow retains existing AccountBrowse orchestration, retry, timing, manual takeover, and return-verification policies.

`MonitorStore` and `SharedPreferences` access are restricted to Data implementations. UI controllers use ports/callbacks rather than concrete adapters or repositories. The module topology remains exactly `:app`.

Source-level boundary checks and locally runnable pure-JVM tests are not substitutes for `testDebugUnitTest`, `assembleDebug`, or device regression. Final acceptance requires all three against the current migration commit/source ZIP.

</details>
