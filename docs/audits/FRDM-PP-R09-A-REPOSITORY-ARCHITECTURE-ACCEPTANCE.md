# FRDM PP R09-A — 全仓组件职责、模块边界、状态所有权及调用链专项验收

## 基线、方法与证据边界
- Repository ttiaogou/FRDM, PP, 1644c0f875b4d64a663b721bab86411e8b585840, tree 2437ffc0f99bd1e1e3ffbb01e73994e31bc6cdf0; R08-H CI 46123532211 全部五项成功。
- 完整递归 Git tree 未截断：435 个 production Kotlin 源码、0 个 production Java 源码、6 个 AIDL 声明。AIDL 与生成源码、Android 资源/manifest 由既有构建/资源门禁核验，不将其冒充为 Kotlin 语义扫描。
- 对全部九模块 Gradle 构建及源码根进行检查；读取核心运行时、业务状态、数据、OCR、装配和手势调用点，结合既有全仓 verify_boundaries.py、R00/R01、R02 及 R04–R08 迁移证据。新增 R09-A 验收测试覆盖物理源码与原扫描范围的一致性、六个显式 Kotlin source set 的包含/排除规则、依赖图无环、20 个附加关键类型唯一声明、31 个文件的 84 个实际装配/状态/调用锚点及反向变异。
- 不是编译器级完整动态调用图，也不证明任意不同命名状态的语义等价或每一条运行时分支；不以文件大小、类名相似或测试通过推断全部职责已经完美分离。完整 CI 结果另行核验。

## 物理模块及实际依赖
| Module | Kotlin files | Direct project dependencies | Responsibility |
| --- | ---: | --- | --- |
| app | 218 | business, core, gesture-api, gesture-runtime, gesture-android, ocr-plugin-api | Android service, adapter, composition, UI, persistence |
| business | 118 | core, gesture-api | feature runtime, business policy, transaction state, ports |
| core | 58 | none | neutral events, page observation, task/UI authority, common contracts |
| gesture-api | 3 | core | gesture intent and public API |
| gesture-core | 4 | core | deterministic geometry and motion policy |
| gesture-runtime | 9 | core, gesture-api, gesture-core | compiler, validator, scheduling and execution coordination |
| gesture-android | 4 | core, gesture-runtime | platform dispatch and visualization |
| ocr-plugin-api | 7 | none | shared IPC contract |
| ocr-plugin | 14 | ocr-plugin-api | standalone OCR inference APK |
16 direct project edges. Physical module roots are independent; core/business/gesture source selection points to local src/main/kotlin, not app shared source. Some core exclusion entries still reference files moved out of core; retain configuration pending R09-B verification. OCR engine/model dependency isolation remains enforced by existing verifier.

## Authority inventory and call chains
| State / boundary | Actual owner / scope |
| --- | --- |
| Persisted user authorization | RunStateRepository port / MonitorStoreRepository; not equivalent to page READY |
| Process-wide object graph | FreedomApplication / AppCompositionRoot; synchronized lazy shared repositories |
| Run generation | RunGenerationClock (process shared via root) |
| Page runtime route and lifecycle | RunSessionCoordinator (service); currentWriteRoute requires READY, active authorization, current run and matching task lease |
| Business task lease | TaskCoordinator, process shared, binds exact UiOperationLease |
| Exclusive UI lease | UiOperationOwnershipCoordinator, process shared |
| Feature-held lease capability | TaskExecutionController; AiReplyTaskOwnership and AccountBrowse mode adapter delegate to injected coordinator, not a second authoritative grant |
| Page subscription and route registry | PageObservationCoordinator; business read/observation coordinators use existing route evidence |
| Recovery admission and lane snapshots | UnifiedRecoveryTransactionManager process owner; RecoveryEventDispatcher and local reducers retain distinct lane identity and proof |
| Private traversal/visit/cursor/return | PrivateMessagesTraversalTaskExecutor |
| Greeting lifecycle and visit/send-return | ReceivedGreetingsTaskController / ReceivedGreetingsTaskFlowController; MomoSayHiTraversalState owns adapter ledger |
| OCR/read epoch and draft visit | ChatMessageReadCoordinator; OcrManager owns IPC request and plugin binding, not business visit |
| Paired task quota/handoff | AiReplyTaskOwnership |
| Browse phase/session | AccountBrowseRuntime, internal AccountBrowseSessionStateOwner; Android workflow owns platform callback and watchdog lifetimes |
| AI model/draft/send state | AiReplyDraftCoordinator; gateway/engine/provider are distinct decision/transport boundaries |
| Durable conversations and claims | LocalConversationRepository / LocalReplyTaskRepository / segment repository, checkpoint data stores |
| Diagnostics lifecycle | RunDiagnosticsManager; not run or task authorization |
| Gesture queue and admission | GestureExecutionCoordinator, policy pipeline/compiler/validator, AndroidGestureDispatcher |

- Service onAccessibilityEvent -> AndroidAccessibilityEventForwarder -> AndroidAccessibilityEventPipeline -> UnifiedAccessibilityEntry -> RunSessionCoordinator -> AiReplyModeRuntimeAdapter -> AiReplyObservationRuntime.schedule; Browse via AccountBrowseModeRuntimeAdapter -> AndroidAccountBrowseWorkflow -> existing AccountBrowseRuntime. Global touch/transition and navigation observation retain existing routes; independent read-only entry observation is not a second task write grant.
- Gesture: existing GestureApi -> GesturePolicyPipeline -> GestureCommandCompiler -> GesturePolicyValidator -> GestureExecutionCoordinator -> platform dispatch (withCurrentLease in task-bound route) -> AndroidGestureDispatcher. Existing gate checks all Android write surfaces; calibration/manual exceptions not autonomous authority.
- AI: draft coordinator createGateway(configuration).decide -> EngineBackedAiDecisionGateway -> AiReplyDecisionEngine -> AiModelProvider.generate; model decisions not UI write authorization.
- Recovery: shared process manager + dispatcher identity/lane gate, existing reducers and persisted checkpoints; distinguish local proof/projection from global task lease. R09-D must verify all generations/route/checkpoint and callbacks.
- Additional audited type declaration map and actual source tokens live in tools/architecture/test_pp_r09a_repository_acceptance.py; P0/P7 authority declaration/construction rules remain unchanged. R07-I scoped 63-state-declaration proof remains in original test.

## Findings and remaining acceptance
- Existing nullable coordinator / sharedManager fallback paths in TaskExecutionController, RunSessionCoordinator, RecoveryEventDispatcher and other test-compatible contracts: actual inspected composition injects shared process dependencies. Do not assume every fallback path is unreachable without R09-B/C call-site audit.
- RecoveryContext and lane reducer/manager store related lifecycle projections, not automatically a duplicate authorization owner; R09-D must check proof synchronization and late callbacks.
- ChatPagePort still mixes generic and greeting capabilities (42 methods); R08-G split data models, not interface segregation. Existing provider-specific fingerprint literals are frozen.
- R08-F preload cache still relies on prepare/consume sequencing rather than independent contact/visit binding. R09-D must inspect identity/generation correctness.
- Legacy regionRefreshTask, thin delegates, isRestoredViewportCompatible, repeated report/write/marker paths, UI and OCR lifecycle mixing remain R09-B review items. No complete file is proven safe to delete.
- The module and source inventory is whole-repository; semantic owner/call-chain audit is targeted to the named critical components. No claim that all 435 files were individually reviewed line by line. No production change authorized or made in R09-A; exact source freeze and R01/R02 are preserved. API-only diff does not prove local git worktree state, and no real-device test is available here.
