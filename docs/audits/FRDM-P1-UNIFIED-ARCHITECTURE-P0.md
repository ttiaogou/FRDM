# FRDM P1 — P0 统一架构基线与职责约束

状态：P0 规则基线；不代表 P1～P7 生产路径已经完成统一。
审计目标：ttiaogou/FRDM 的 P1 分支，基线提交 3bf74c4302411a5c47a34ce1f7ca9248f6b7e8c9。
本阶段仅修改静态检查、单元测试与基线文档；不调整业务行为、手势、输入节奏、页面指纹或安全校验。

## 1. 职责所有权

| 领域 | 架构层权威职责 | 业务层可负责 | 业务层不得负责 |
|---|---|---|---|
| 无障碍事件 | Android 入口归一化、路由、代次关联 | 订阅平台无关语义事件 | 自建 Android 回调或直接处理原始事件 |
| 页面观察 | 公共观察调度、只读页面证据、稳定性信号 | App 专用页面识别与指纹数据 | 公共观察协调器的重复实例或 UI 写操作 |
| 任务协调 | 统一任务租约、代次、切换与释放 | 私聊、招呼、动态业务状态与步骤 | 自建公共租约系统或绕过授权转移 |
| UI 操作权 | 排他授权及租约与运行代次校验 | 通过端口请求合法操作意图 | 自行认定持有 UI 操作权 |
| 恢复适配 | 通用验证契约与调度框架 | 业务检查点、目标身份、页面证据 | 直接批准未经验证的恢复 |
| UNKNOWN 事务 | 状态、调度、过期回调拒绝及恢复确认 | 业务异常原因和恢复证据 | 自建可竞争的权威恢复决策 |

依赖方向：core 为平台无关公共契约/调度；business 依赖 core 和 gesture-api；app 负责 Android Adapter、composition 和具体集成；gesture-runtime 与 gesture-android 保持既有手势执行边界。禁止 core 反向 import app、业务和 Adapter。当前具体 Gradle 依赖继续由现有架构检查器强制验证。

## 2. 当前生产调用链（基线事实）

无障碍事件：FreedomAccessibilityService.onAccessibilityEvent → AndroidAccessibilityEventForwarder → AndroidAccessibilityEventPipeline → 页面观察 signal / RunSessionCoordinator 业务分发。核心契约 UnifiedAccessibilityEntry 已声明，但尚不是生产中唯一强制的标准化消费边界；窗口导航仍存在 Service 到业务运行时的直接通知，留待 P1。

页面观察：AppCompositionRoot 创建服务级 PageObservationCoordinator；MomoPageObservationCoordinator 默认注入它，但保留 SharedPageObservationCoordinator(scheduler) 兼容创建路径（主要供非生产注入场景），留待 P2。

任务与 UI：AppCompositionRoot 创建 UiOperationOwnershipCoordinator 和 TaskCoordinator，并注入业务运行时。GestureExecutionCoordinator 已有任务租约校验；AndroidAccessibilityGateway 部分操作当前只检查是否存在 activeTaskId，尚非每次都验证请求方精确租约，留待 P3/P4。P0 不改变该行为。

恢复：AI Reply 与 AccountBrowse 各有一个 UnifiedRecoveryTransactionStateMachine / RecoveryEventDispatcher；DynamicPageRecovery、MessageListCheckpointVerification、ReceivedGreetingsCheckpointVerification 分别创建 RecoveryScheduler 和 RecoveryCheckpointVerificationCoordinator。它们复用同一实现，但不是单一事务控制实例；P5/P6 再收口。已有 UNKNOWN 不终结、双新鲜快照、身份和检查点验证规则必须保留。

## 3. P0 自动化硬约束

tools/architecture/verify_boundaries.py 的 architecture_authority_violations：

1. 必须且只能在指定路径声明 UnifiedAccessibilityEntry、AndroidAccessibilityEventPipeline、PageObservationCoordinator、TaskCoordinator、UiOperationOwnershipCoordinator、UnifiedRecoveryTransactionStateMachine、RecoveryEventDispatcher、RecoveryScheduler、RecoveryCheckpointVerificationCoordinator。
2. 锁定上述具体架构组件在当前生产源码中的创建路径及次数；在其他业务、Adapter 或已获准文件内新增第二个实例同样失败。
3. 唯一过渡别名为 MomoPageObservationCoordinator 的 SharedPageObservationCoordinator，且只允许原有一次创建；新增 import alias 或 typealias 绕过被拒绝。
4. 禁止 core 生产源码 import 上层 business、adapter、application、data 或 UI 实现。
5. 不因大类行数直接阻断 CI，也不通过放宽原有恢复及手势安全检查来让 P0 通过。

现存过渡创建点属于明确的临时许可，不授予新业务复用权限；随 P1～P7 实施逐项缩小许可，不允许扩大旧路径。

## 4. 验证与后续迁移

自动验证入口（现有 CI 在 Gradle 构建前运行）：

- python3 -m unittest discover -s tools/architecture -p 'test_*.py' -v
- python3 tools/architecture/verify_boundaries.py --root . --show-advisories
- Gradle :core:test、:business:test、:gesture-core:test、:gesture-runtime:test、:app:testDebugUnitTest、:app:assembleDebug、:ocr-plugin:assembleDebug

P0 覆盖：真实生产代码基线通过；新增重复声明、跨层构造、同所有者重复实例、别名绕过、core 反向依赖均失败；注释和字符串不会产生误报。

后续阶段：P1 统一入口，P2 统一观察，P3 统一任务，P4 统一 UI 授权，P5 恢复适配，P6 UNKNOWN 事务，P7 清理旧旁路，P8 全链路回归。每阶段都需同步收紧本基线和对应测试。

## 5. 已知限制及遗留

本检查是基于 Kotlin 源码的词法门禁，不能证明动态分派、反射、DI、异步回调或运行时租约授权的完整正确性；精确运行时单控制源和真实页面恢复安全性须由后续阶段的行为测试及真机回归证明。P0 不把尚未迁移的过渡结构标记为已完成收敛。

既有文件不删除，既有测试和安全断言不弱化。后续如必须删除冗余源码，单独列出并事先获得明确授权。
