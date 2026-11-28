# Freedom PP — 现行架构与工程边界

> 现状证据基线：`9919a0b30a9cfd19235d691ee4ccfee5ec664564`（G01-A），[CI 46134802486](https://github.com/ttiaogou/FRDM/actions/runs/46134802486) 成功。本文描述已核查的工程现状，不制定新规则；[AGENTS.md](../AGENTS.md) 及现行第 0 节/对应分片仍为施工依据。历史目标不构成新增架构授权。

## 现行物理模块与依赖

依据 [settings.gradle.kts](../settings.gradle.kts)、九个模块的 Gradle 文件及完整 Git tree（未截断）。Gradle 项目直接依赖如下（不含外部库、测试依赖和传递依赖）。

| 模块 | 物理生产 Kotlin 根 | 直接项目依赖 | 职责 |
| --- | --- | --- | --- |
| app | app/src/main/java | business, core, gesture-api, gesture-runtime, gesture-android, ocr-plugin-api | Android Service、平台/社交适配、组装、UI、数据实现 |
| business | business/src/main/kotlin | core, gesture-api | 平台无关业务、Feature、事务、端口 |
| core | core/src/main/kotlin | 无 | 中立契约、事件、页面观察、任务与 UI 权限 |
| gesture-api | gesture/gesture-api/src/main/kotlin | core | 公开手势意图/API |
| gesture-core | gesture/gesture-core/src/main/kotlin | core | 内部几何、运动模型 |
| gesture-runtime | gesture/gesture-runtime/src/main/kotlin | core, gesture-api, gesture-core | 策略、验证、编译及执行协调 |
| gesture-android | gesture/gesture-android/src/main/kotlin | core, gesture-runtime | Android 派发和可视化 |
| ocr-plugin-api | ocr-plugin-api/src/main/java | 无 | 共享轻量 IPC 契约（AIDL 另有源码目录） |
| ocr-plugin | ocr-plugin/src/main/java | ocr-plugin-api | 独立 OCR 应用、引擎及模型 |

共 16 条直接项目依赖；core/business/gesture 模块使用自己的源码根，不再以 app 的共享源码 include/exclude 模拟模块化。Android / Social Adapter 仍由 app 编译，受源码检测和 API 边界约束，并非独立 Adapter Gradle 模块。主 APK 与可选 OCR 插件是独立应用，Gesture 四模块不是独立 APK。core 中历史 GesturePolicyPort.kt 无声明且被排除，六个 main 和两个 test 旧排除路径无对应本地文件；按 R09-G 保留冻结保护，不视为活动重复实现。

基线 436 个物理 main Kotlin 文件（含一个明确排除的历史占位；生成源码不计入），实际编译仍以 Gradle 为准。不能由模块数量推断每个职责均已完全收敛。

## 现行无障碍入口、观察和写入

    FreedomAccessibilityService.onAccessibilityEvent
      -> AndroidAccessibilityEventForwarder.accept
      -> AndroidAccessibilityEventPipeline
      -> UnifiedAccessibilityEntry
      -> AppCompositionRoot 接线 / RunSessionCoordinator
      -> 对应 ModeRuntimePort

Service 的原始事件回调只转发；生命周期通知也使用既有统一入口。GlobalTouch、ActivityTransition 和目标应用事件是不同语义，不是多个独立任务入口。PageObservationCoordinator 负责观察协调，RunSessionCoordinator 按页面证据维护路由；只读观察不授予写权。

| 权威边界 | 实际承担者及范围 |
| --- | --- |
| 持久用户运行意图 | RunStateRepository / MonitorStoreRepository |
| 共享对象组装 | FreedomApplication / AppCompositionRoot |
| 运行代次 | RunGenerationClock |
| 页面运行路由 | RunSessionCoordinator；READY 不等同于所有写条件均满足 |
| 任务租约 | TaskCoordinator |
| UI 操作权 | UiOperationOwnershipCoordinator |
| 最终写门禁 | UiWriteAuthorization：当前路由、任务、租约/代次等；任务手势仍经过既有执行上下文 |
| 恢复事务及检查点 | UnifiedRecoveryTransactionManager、RecoveryEventDispatcher、RecoveryScheduler、RecoveryCheckpointVerificationCoordinator；各自负责事务、分发、调度、证明，非平行写权 |

页面切换、UNKNOWN、旧代次和停止的行为依据现行规范及 [R09-C](audits/FRDM-PP-R09-C-ENTRY-STATE-UI-OWNERSHIP-ACCEPTANCE.md)、[R09-D](audits/FRDM-PP-R09-D-RECOVERY-CHECKPOINT-ASYNC-ISOLATION.md)。旧任务释放与新页面验证、恢复检查点证明分离。两个新鲜样本及显式 completeResume 是既有恢复证明，不将历史节点或手势作为检查点。手工校准/安全等受控入口不是业务写权。

## 业务与数据责任

- 私聊遍历/访问/返回游标：PrivateMessagesTraversalTaskExecutor；招呼流程：ReceivedGreetingsTaskController / ReceivedGreetingsTaskFlowController，MomoSayHiTraversalState 保留适配账本职责。
- 动态业务：AccountBrowseRuntime / AccountBrowseStateMachine；AndroidAccountBrowseWorkflow 保有平台执行桥和回调生命周期，不新增权威任务授权。
- AI 观察：AiReplyObservationRuntime 编排既有协作者；ChatMessageReadCoordinator 管理读取代次与 OCR 生命周期，AiReplyDraftCoordinator 管理草稿请求、输入、发送确认等状态。模型经既有 decision gateway/engine/provider 处理，不授予 UI 写权。ContactHistoryResolver 处理联系人历史，R09-D 已补跨联系人缓存隔离。具体发送资格和 OCR 可信度以现行代码、规则及测试为准，不采用旧草稿手动发送报告。
- 社交平台：MomoSocialAdapter 提供既有平台适配能力；ChatPagePort 仍为现有端口，不宣称已完成接口细分。持久化通过现有 Repository、DataStore、账本和检查点。RunDiagnosticsManager 管理诊断，不拥有任务授权。

这些是 R09-A/C/D/G 与当前关键源码复核支持的责任摘要，不是完整逐方法所有权清单。三份治理文档由 G01-D 单独建立。

## 手势及 OCR 边界

业务通过 GestureApi 提交意图；Runtime 完成既有验证、编译和执行调度，AndroidGestureDispatcher 是实际平台派发边界。详见 [手势模块](GESTURE-MODULE-BOUNDARIES.md)、[执行管线](GESTURE-RUNTIME-PIPELINE.md)、[执行队列](GESTURE-EXECUTION-QUEUE.md) 和 [工程检测](GESTURE-ARCHITECTURE-GUARDRAILS.md)。本说明不更改算法、参数或安全断言。

主程序通过 ocr-plugin-api 的 IPC 与独立 OCR 应用通信；ONNX、OpenCV、PaddleOCR 与固定模型在插件侧。固定模型在插件构建时校验并打包，首次使用优先从 assets 安装，不要求手机首次识别访问模型源。详见 [OCR 插件架构](ocr/OCR-PLUGIN-ARCHITECTURE.md)。

## 证据与未完成事项

- [R09-A](audits/FRDM-PP-R09-A-REPOSITORY-ARCHITECTURE-ACCEPTANCE.md) 物理模块、关键责任；[R09-G](audits/FRDM-PP-R09-G-FINAL-ENGINEERING-ACCEPTANCE.md) 最终工程基线及 G01–G09 遗留项；[G01-A](audits/FRDM-PP-G01-A-DOCUMENT-INTEGRITY-AUDIT.md) 文档引用及冻结保护。
- G01-A 的 213 项 Python 测试、架构硬检测、Gradle 单元测试、三份 APK 和 CodeQL 通过；仅对已验收提交成立。
- R09-F [F01–F12](audits/FRDM-PP-R09-F-APK-DEVICE-REGRESSION.md) 仍 DEFERRED / NOT_RUN。大文件 advisory、历史占位、接口候选、异步与 APK 体积遗留风险仍按 R09-G 保留，不能当成已清零。
- 本文不证明全仓语义无重复、所有实际窗口行为正确或完整真机验收完成。

## 历史资料

历史正文已移至 [归档副本](archive/legacy/ARCHITECTURE.original.md)；[固定提交原文](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/ARCHITECTURE.md) 可核对。历史目标、旧路径、旧验证结果均不构成现行施工依据。其他历史证据见 [归档索引](archive/README.md)。
