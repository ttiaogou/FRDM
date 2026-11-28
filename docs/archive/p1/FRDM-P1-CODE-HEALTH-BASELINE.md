# FRDM P1 — 第一阶段代码健康审计基线

> 范围：仅审计、记录和分类；不执行生产代码重构。  
> 仓库：ttiaogou/FRDM；分支：P1；基线 HEAD：`7c920a998146ca62cbc2d6219dc1ee44ec789aa2`。  
> 日期：2026-11-21；数据源：GitHub 上该 HEAD 的递归 Git tree、选定源码、构建配置、工程规范与 CI 记录。  
> 注意：这是**全仓文件清单审计 + 关键路径的静态源码核验**；不是对全部 371 个生产 Kotlin 文件逐行检查，也不是运行时行为验收。未确认的问题标记为待验证，不得据此删除代码。

## 1. 审计简报

- **当前实现**：九个 Gradle 模块（app、business、core、四个 gesture 模块、ocr-plugin-api、ocr-plugin）；Service 的 `onAccessibilityEvent` 通过 `AndroidAccessibilityEventForwarder` 转交，事件标准化与路由在 `AndroidAccessibilityEventPipeline`；`AppCompositionRoot` 组装应用服务、Gateway、任务/UI 租约及业务适配；恢复逻辑已有 `UnifiedRecoveryTransactionStateMachine`、`RecoveryEventDispatcher`、`RecoveryScheduler` 和 `RecoveryCheckpointVerificationCoordinator`，各业务保留页面证据和检查点适配。
- **需求理解**：定位旧语义、重复逻辑、失效/无效代码候选、职责过载、状态所有权冲突及测试覆盖盲点；只提交审计证据，不迁移或清理。
- **涉及文件**：核心入口、业务恢复、AI Reply、Momo 适配、Gradle/CI、Python 架构检查器和规范文件。具体锚点见下。
- **影响范围**：本轮仅新增文档；不修改运行时、持久化、API、页面指纹、手势、安全断言或测试。
- **修改方案**：创建本报告，按“已确认结构问题 / 待验证语义风险 / 既有正确边界”分类；后续处理须单独授权、附调用链与回归测试。
- **是否新增文件**：是，`docs/audits/FRDM-P1-CODE-HEALTH-BASELINE.md`；不修改或删除任何既有文件。

## 2. 全仓清单（Git tree 审计）

基线的 Git tree 共 943 个 entry，其中 **684 个文件**（`truncated=false`）。含 **371 个生产 Kotlin 文件**，**200 个 Kotlin 单元测试文件**，及 **2 个 Python 测试文件**；未发现 `src/androidTest` 路径的文件。模块按文件数统计：

| 模块 | 文件数 | 生产 Kotlin | Kotlin 测试 |
| --- | ---: | ---: | ---: |
| app | 305 | 189 | 90 |
| business | 160 | 95 | 64 |
| core | 97 | 56 | 40 |
| gesture-api | 4 | 3 | 0 |
| gesture-core | 5 | 2 | 2 |
| gesture-runtime | 14 | 9 | 4 |
| gesture-android | 6 | 4 | 0 |
| ocr-plugin-api | 11 | 5 | 0 |
| ocr-plugin | 12 | 8 | 0 |

其余条目为配置、docs、tools、资源与根目录文件。测试文件数不代表实际用例数、有效覆盖率或测试通过率。

### 模块与调用链核验

- `FreedomAccessibilityService.kt:449-451`：系统无障碍回调仅转交给 forwarder；`AndroidAccessibilityEventPipeline.kt:45-78,83-129` 处理标准化、按运行模式分发与全局触摸先路由；`PageObservationCoordinator` 接收页面事件信号。
- `AppCompositionRoot.kt:281-428`：按 Service 生命周期组合 `PageObservationCoordinator`、`AndroidAccessibilityGateway`、`GestureRuntimeFactory`、`MomoSocialAdapter` 和 AI 草稿协调器。
- `TaskCoordinator.kt` / `UiOperationOwnershipCoordinator.kt`：分别管理任务租约和 UI 操作所有权；不能把相似租约 API 直接视为冗余。
- `DynamicPageRecovery.kt`、`MessageListCheckpointVerification.kt`、`ReceivedGreetingsCheckpointVerification.kt` 均使用统一恢复验证组件；对应业务分别采集自身页面证据、维护检查点状态。
- `docs/ARCHITECTURE-CONTRACTS-3.0.md` 是历史阶段接口基线，不能将其“尚未接线”的叙述直接认定为今天仍未接线。应以本次 HEAD 源码为准。

## 3. 代码健康问题清单

### F-01｜P1，待验证：恢复事件的无身份兼容分发入口

- **证据**：`business/src/main/kotlin/com/freedom/features/recovery/RecoveryEventDispatcher.kt:18-38`。`dispatch(event, identity = activeIdentity)` 在 identity 为 null 时执行 `transaction.dispatch(event)`，记录 `legacy_dispatch accepted=true`，而非走 `isCurrent` 交易身份校验。`DynamicPageRecovery.kt:216` 还存在未显式传入 identity 的 `dispatcher.dispatch(ResumeRequested)`。
- **判断**：确认存在兼容旁路；**尚未证明生产运行会在无活动身份时实际绕过有效恢复门禁**。必须追踪初始化、重连与所有 dispatch 调用点后才能判定缺陷，禁止直接删除兼容路径。
- **建议**：绘制 dispatcher 调用点表（入口、调用时 identity、预期拒绝策略），补充“未激活身份、过时代次、服务重连、恢复确认”回归测试；若确认新链路不再需要，另轮制定最小迁移方案。

### F-02｜P2，已确认的语义重复：两处恢复重试节奏拥有相同常量

- **证据**：`business/src/main/kotlin/com/freedom/features/aireply/ReceivedGreetingsRetrySchedule.kt:4-10` 与 `business/src/main/kotlin/com/freedom/features/recovery/RecoveryScheduler.kt:205-226` 均定义 500 / 1000 / 2000 / 5000 / 10000 / 30000 ms。`ReceivedGreetingsTaskFlowController.kt:138` 仍调用前者；`AiReplyObservationRuntime.kt:3738,4692` 调用后者。
- **判断**：**常量/节奏来源确有重复，但用途和状态所有权尚不同**；旧策略仍有生产引用，不属于死代码。
- **建议**：补一份“调用入口→重试目的→计数代次→手势/只读行为”对照，确认两条路径可等价替代后再考虑统一常量所有权；不可改时间参数。

### F-03｜P2，待验证：两类检查点验证器的镜像样板

- **证据**：`MessageListCheckpointVerification.kt:25-163` 与 `ReceivedGreetingsCheckpointVerification.kt:26-161` 均持有 binding、`RecoveryScheduler`、`RecoveryEventDispatcher`、`RecoveryCheckpointVerificationCoordinator`，实现 `observe/reject/defer/completeResume/reset` 等类似流程。
- **判断**：共同的双新鲜快照校验已由通用组件负责；外层相似代码主要还承担各自页面类型、检查点及执行阶段适配。**不应按文本相似度直接合并**。
- **建议**：未来考虑抽取只读构造样板，但保留 CHAT_LIST/NON_CHAT 路由证据和不同的业务检查点；先补防串路由、代次和业务阶段回归用例。

### F-04｜P2，已确认的维护风险：超大聚合类

以下为实际读取到的 P1 文件行数，单纯行数是风险信号，**不是功能错误或应删除证据**：

| 文件 | 行数 | 复核方向 |
| --- | ---: | --- |
| `app/.../MomoSocialAdapter.kt` | 6,387 | Momo 适配门面与功能协作者边界 |
| `business/.../AiReplyObservationRuntime.kt` | 4,975 | 观察、恢复、任务协调之间的状态所有权 |
| `app/.../AndroidAccountBrowseWorkflow.kt` | 2,662 | 页面操作与恢复门禁边界 |
| `app/.../MainActivity.kt` | 2,461 | UI 与应用服务组合 |
| `app/.../AppCompositionRoot.kt` | 740 | 组装职责是否继续膨胀 |

**建议**：以职责矩阵、状态读写列表、出入参和回归用例作为拆分条件；不允许仅为压缩行数改变执行顺序。

### F-05｜P2，待验证：事件日志描述可能仍带旧语义

- **证据**：`app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt:202-204` 日志描述“AI Reply 仅将目标事件用于页面结构观察；UNKNOWN 不参与任务调度”。
- **判断**：这段文字仅出现在事件诊断分支，并不能据此证明业务 UNKNOWN 仍会停止任务。但从用户侧看到日志可能被理解成与统一 UNKNOWN 恢复策略矛盾。
- **建议**：分别审计“事件 envelope / 页识别 UNKNOWN / 恢复事务 UNKNOWN”三个语义域，再决定日志是否需要更精确命名；本轮不改日志。

### F-06｜P3，已确认：Python 编译缓存被纳入版本控制

- **证据**：Git tree 跟踪以下 4 个文件：
  - `tools/architecture/__pycache__/normalize_agent_parts.cpython-312.pyc`
  - `tools/architecture/__pycache__/test_normalize_agent_parts.cpython-312.pyc`
  - `tools/architecture/__pycache__/test_verify_boundaries.cpython-312.pyc`
  - `tools/architecture/__pycache__/verify_boundaries.cpython-312.pyc`
- `.gitignore` 未忽略 `__pycache__/` 或 `*.pyc`。
- **建议**：另轮经授权增加忽略规则，并在验证 CI 不依赖跟踪缓存之后清理 Git 索引。**本轮没有删除文件**。

### F-07｜P2，已确认的覆盖盲点：部分模块缺少独立测试

- **证据**：Git tree 中 `gesture-api`、`gesture-android`、`ocr-plugin-api`、`ocr-plugin` 均无 `src/test` Kotlin 测试；也未发现 `src/androidTest` 文件。现有 workflow 执行 `core:test`、`business:test`、`gesture-core:test`、`gesture-runtime:test`、`app:testDebugUnitTest`，同时组装两个 debug APK。
- **判断**：这说明没有这些模块的独立测试文件，**不等于它们完全没有被上层测试间接覆盖**。
- **建议**：先建立功能-测试映射表，优先补 OCR 插件服务生命周期、Android 实际派发边界及插件接口兼容场景；需设备/模拟器的行为单列为未验证项。

### F-08｜P3，工具覆盖建议（尚未实施）

- `.github/workflows/android-build.yml` 已执行 Python 架构单测、边界核验、资源 XML 校验、Gradle 各模块单测与双 APK 构建、CodeQL 分析。
- 现有 workflow **没有单独的 Kotlin 重复代码 / 风格复杂度报告阶段**；可评估 detekt 或 jscpd，以“先报告、再讨论增量门槛”为原则。不能替代现有架构检查器。
- `.github/workflows/codeql.yml` 是**故意保留的 workflow_call 占位文件，且 job 被关闭**；CodeQL 已集中在 android-build 中。**这不是两个并行 CodeQL 构建**，不建议删除或改变触发器。

## 4. 未确认为可删的项目（禁止先斩后奏）

- `ReceivedGreetingsRetrySchedule`：仍被 `ReceivedGreetingsTaskFlowController` 调用，非死代码。
- `PageRecoveryVerificationGate` 与统一检查点验证器：形式上均关注稳定样本，但前者负责页面结构连续性，后者含持久化检查点与运行代次的恢复证明；目前**无法确认可相互替代**。
- `RecoveryEventDispatcher` 旧分发兼容路径：存在运行时语义风险，需要实测和调用链校验，不可直接移除。
- `.github/workflows/codeql.yml`：明确为兼容保留的非活动占位文件，非重复运行。
- 历史架构文档：用作演进记录，不因为与当前源代码不同而作为待删文件。

## 5. 优先级和后续施工建议

| 优先级 | 范围 | 执行前必须完成 |
| --- | --- | --- |
| P1 | 恢复身份无参分发审计 | 列齐调用点、过时代次测试、服务重连测试 |
| P2 | 重试节奏双来源与检查点镜像样板 | 画出调用链及所有权对照；保证不改节奏和页面指纹 |
| P2 | 聚合类边界与部分模块测试 | 建立状态读写/依赖矩阵，优先新增守护测试 |
| P3 | 已跟踪 pyc 与日志旧语义 | 核验实际依赖，再单独提议清理 |
| P3 | 代码健康工具 | 先非阻断报告，维持所有原 CI 检查 |

执行方式建议：每轮仅处理一个同类问题组；先出对照/测试，再最小修改；对手势、节奏、安全断言、页面指纹、业务算法不作隐式更改。

## 6. 验证记录与限制

- **已验证**：P1 分支与 HEAD；完整 Git tree（非截断）；九模块清单；相关入口、恢复、业务与 CI 文件的静态证据；`AGENTS.md` 和十份按需规则分片存在且可读取。
- **CI 基线**：推送前的 HEAD `7c920a9` 对应 [Freedom CI #426](https://github.com/ttiaogou/FRDM/actions/runs/44984162004) 状态为 `success`；这证明基线 CI 曾通过，**不代表新提交已经通过**。
- **未执行**：本地 Git checkout/`git status`、本地 `git diff`、本地单测与完整 Gradle 构建。当前执行环境无法连接 GitHub 主机进行本地 clone；通过 GitHub API 按特定提交读取仓库；文件 diff 可在提交后使用远端 compare 核对。提交后以新 HEAD 的 GitHub Actions 结果作为回归验证依据。
- **未覆盖**：全部 371 个生产 Kotlin 文件的逐行语义证明、AST 级别死代码/重复代码检测、设备无障碍/插件运行时、性能与真实 UI 行为。故不得把“没有发现”表述为“证明不存在”。
- **仓库工作区**：本任务直接对 GitHub 远端 P1 进行只读审计及文档提交，没有本地 checkout，无法报告本地工作区状态；远端 commit/tree 是已提交的确定快照。

## 7. 修改边界声明

本阶段仅新增本报告；生产 Kotlin、Java、Python 检查器、Gradle、CI、测试、Manifest、资源均保持原样。若要消除上述缺陷或删文件，须在下一阶段单独指定目标和验证范围。