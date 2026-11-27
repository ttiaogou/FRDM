# FRDM PP — R01 核心能力与业务行为冻结

## 基线与范围

- 仅 PP，R00 提交 `099b5ee0ca528cdf2608efe22d88ed23896a3d37`；CI [46050312939](https://github.com/ttiaogou/FRDM/actions/runs/46050312939) 完整成功，包括架构检查、全部现有 CI 单测、Freedom 普通/精简 APK、OCR 单测及 APK、CodeQL。
- 沿用 R00 的 469 个生产/配置文件清单（76 S0 + 393 S1），原 M04 专项区域指纹、解析和相对调用顺序不变。本次不修改生产源码、现有测试、规则正文、CI 工作流；不执行 R02、迁移或冗余清理。
- 本文及机器可读契约记录当前保护对象与回归证据，不宣称现有实现已满足所有理想架构要求。

## R01-A—D：能力、调用契约与所有权

`tools/architecture/pp_r01_contracts.json` 记录 12 类业务契约，57 个唯一实际源码路径及 45 个唯一现有回归文件路径，复用 R00 源码冻结，不复制生产指纹或执行链路。

| 契约 | 冻结的当前责任边界 | 主要回归证据/约束 |
|---|---|---|
| entry | Service → Forwarder → Pipeline → PageObservationCoordinator；CompositionRoot 装配 | AndroidAccessibilityEventPipelineTest、PageObservationCoordinatorTest、PageFingerprintRouterTest；入口唯一、页面证据 |
| gesture | GestureExecutionCoordinator、Compiler、Gateway、平台 SPI；串行执行与结果反馈 | GestureExecutionCoordinatorTest、GestureCommandCompilerTest、Geometry/Dynamics 测试；现有调用参数、目标验证、可视化、取消和结果 |
| ownership | TaskCoordinator 管任务租约，UiOperationOwnershipCoordinator 管 UI 租约，RunSessionCoordinator 管运行授权和代次 | RunSessionCoordinatorTest（六种独立页面切换、UNKNOWN 写权撤销）、UiOperationOwnershipCoordinatorTest |
| private | PrivateMessagesTraversalTaskExecutor 持有遍历、未读及检查点；Runtime 调度副作用 | PrivateMessagesTraversalTaskExecutorTest（返回保持方向、局部重排/卡片插入、身份返回及恢复锚点）、PrivateMessagesCheckpointRecoveryTest |
| greetings | MomoSayHiTraversalState 持有持久遍历账本与访问期过滤证据；Runtime/控制器协调 | MomoSayHiTraversalStateTest（过滤不污染持久事实、切换及续接）、返回确认及检查点测试 |
| browse | AccountBrowseStateMachine 持有 phase；Runtime、AndroidAccountBrowseWorkflow 协调执行 | AccountBrowseStateMachineTest、FeedReturnVerificationPolicyTest、ImagePagingProgressTest、手动切换与恢复测试 |
| identity | MomoSocialAdapter 提供平台证据和动作，ContactIdentityManager 解析身份 | ContactIdentityManagerTest、MomoIdentityVerificationResultTest、招呼身份指纹测试；现有头像/资料读取/返回和分类 |
| ocr | ChatMessageReadCoordinator 持有有界异步读取；Runtime 决定业务结果准入 | ChatMessageReadCoordinatorTest（迟到、重复、超时、代次、页面复核）、OcrBatchAdmissionGateTest、P4 回归 |
| reply | AiReplyDraftCoordinator 持有草稿和发送事务，平台适配负责输入执行，Repository 持久化 | AI 决策、输入流程、发送控件资格、可见草稿相关测试；保持确认与去重 |
| media | ContactMessageLedger/Repository 管已确认计数和检查点，Runtime 编排，PrivateChatFollowUpPort 只执行 | TemporaryContactMessageLedgerTest（重复确认、0 关闭、同阈值先图、跨重启、写入失败）、M04 区域及顺序、独立结果证据测试；仅私聊 |
| recovery | UnifiedRecoveryTransactionManager 持有恢复事务，RecoveryScheduler 调度，检查点验证器确认 | UnifiedRecoveryTransactionManagerTest、RecoveryEventDispatcherTest、RecoverySchedulerTest、检查点测试；停止、代次及未知结果隔离 |
| storage | 原 Repository、DataStore、配置、检查点和账本 | 原键、格式、默认值、写入失败及生命周期；已有持久化/遍历测试；不新增平行账本 |

以上是职责边界与必须保护的现有行为，尤其不能把测试通过或源码一致当作真机全链路通过。大文件内部跨层字段访问、回调时序以及端到端操作记录仍需要 R02 及具体迁移批次审计。

## R01-E：保护既有证据和门禁

- 从 R00 已验收 Git tree 提取现有 254 个测试目录文件（249 Kotlin、5 fixture）、15 个架构检测及 CI 文件、11 个 AGENTS/现行规则文件，固定 Git blob 指纹，共 280 个证据文件。新增回归测试允许，既有测试删改、替换或断言弱化会被检测。
- `test_pp_r01_contract_freeze.py` 复用 R00 的 git_blob 实现与既有 unittest discover；验证两份清单固定身份、契约映射及真实工作区证据，测试篡改、删除、符号链接、缺失责任方/回归映射和新增测试许可。不新增 CI workflow。
- R00 与 M04 现有检测独立保留。本阶段没有将所有 R00 S1 内容变为永久冻结。未来只允许按授权迁移批次进行最小范围的门禁更新；不能为了通过 CI 修改旧预期或自动重算清单。R01 证据指纹固定的是旧测试基线，R02 可新增测试，无需解冻生产文件。
- 若后续确需移动原测试或改变源码定位检查，须独立审查并证明语义、断言及覆盖未减弱；保留历史基线和批准差异。不能将临时全文件冻结变成阻止所有合法迁移的永久规则。
- Git 指纹用于回归比对，不是权限签名。仓库内测试无法防止有权者同时删除工作流/检测器或篡改全部保护；必须配合审查与必要的分支保护。未修改 GitHub 仓库权限设置。
- 本阶段不是 R02：尚未建立完整的同输入/时钟/页面快照/回调序列的双版本行为对比，不把现有单元测试等同于行为等价性证明。

## 下一阶段与真机

R01 本提交完整 CI 通过后才允许 R02；R02 新增测试，不改变生产代码。后续迁移前须具备相应正常及异常路径行为记录（点击目标、参数、调用顺序、状态、租约、回调、持久化与结果），并验证迁移前后等价。

真机待验收：私聊遍历/返回原位置、招呼筛选及返回、动态导航及翻页、头像及资料 ID、OCR 晚到/页面切换、文本发送、图片确认及拦截返回、手势失败/服务重连、操作权切换及用户停止。无真机环境，本轮不宣称通过。仅新增工程测试和契约，无生产改动，不清理历史占位、缓存或旧 UI 方法。
