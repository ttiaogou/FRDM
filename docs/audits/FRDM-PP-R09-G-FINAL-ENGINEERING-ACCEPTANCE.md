# FRDM PP R09-G — 最终工程验收、冻结基线及交付

## 1. 结论与验收边界

- 仓库 ttiaogou/FRDM，仅 PP。已验收代码基线 **eb8f3cfc2bd29714cda9ae9ebf49468a8ab261c3**，Git tree 8aad14cba5145753557185bbb2f65e36ba662ddc，CI [46132653400](https://github.com/ttiaogou/FRDM/actions/runs/46132653400) 五项全部 completed/success，head_sha 一致。
- R00–R08、R09-A–E 的工程施工及自动化证据已通过当前门禁；R09-F APK 构建、签名配套、工件交付已通过。R09-G 复核累计 Git tree、冻结指纹、真实 CI 日志及测试报告 XML、剩余候选及风险。
- 用户明确同意由自己独立完成真机回归，并先进入 R09-G。R09-F 的 F01–F12 全部登记为 **DEFERRED / NOT_RUN**，责任方：用户。不能把自动化成功当作真机通过；完整业务验收仍未完成。
- 用户已免除本地工作区核验；采用远端分支、HEAD、tree/diff。AGENTS、现行 §0、工程验证规则及相关 R09 报告已复核。本轮只新增本验收报告，不更改源码、测试、检测、配置、权限、清单或规则，不扩大施工范围。

## 2. R00 至当前累计差异

原始 R00 生产基线 435406f593bc1dd512edac0af28f8f7c48feb0c2，tree f5e7d6b8a5bc334f66c4d4911975ffcab79a8ac4。两个完整递归 tree 均未截断，按文件路径及 Git blob 比较：

| 文件类别 | 新增 | 修改 | 删除 |
|---|---:|---:|---:|
| 生产源码 | 12 | 43 | 0 |
| 测试及夹具 | 24 | 7 | 0 |
| 架构检测/清单 | 46 | 3 | 4（历史生成缓存） |
| 文档 | 37 | 0 | 0 |
| CI 工作流 | 0 | 1 | 0 |
| .gitignore | 0 | 1 | 0 |
| 合计 | 119 | 55 | 4 |

原始 809 个 blob 文件，当前 924 个。累计删除仅 tools/architecture/__pycache__/ 下 normalize_agent_parts、test_normalize_agent_parts、test_verify_boundaries、verify_boundaries 的 cpython-312.pyc；.gitignore 仅新增 __pycache__/ 与 *.pyc 忽略项及注释/空行。当前没有跟踪 APK、build、.gradle、__pycache__ 或 .pyc。此为累计历史差异，不是 R09-G 删除行为。本阶段无任何文件删除。生产文件按路径统计；迁移的具体方法和历史逆向证据见对应批次报告及测试。

## 3. 最终冻结与验收证据

| 对象 | 原数量 | 原指纹一致 | 经登记变更 | 缺失 |
|---|---:|---:|---:|---:|
| S0 永久冻结 | 76 | 76 | 0 | 0 |
| S1 原始源码/配置 | 393 | 350 | 43 | 0 |
| R01 原测试及夹具 | 254 | 247 | 7 | 0 |
| R01 原检测及 CI | 15 | 10 | 5 | 0 |
| R01 原规则 | 11 | 11 | 0 | 0 |
| R02 固定轨迹 | 11 | 11 | 0 | 0 |

新增 12 个受控生产文件，现有 R00 受控范围 481 个；原 469 文件均在。R00 manifest blob 4510f804078288427e8f8b61f2ec69e8a210bab7，R01 manifest blob 033bb92c5bc369ba7d7e315ac11fd3bfc9b513a7，R02 oracle protection blob 659ebbdf34c97d4aa8af77f4d366ff5b53c637d3；历史清单及轨迹预期未重录。S1 变更由既有精确 pin 与各阶段逆向证明登记；不新设覆盖旧清单的宽泛冻结，不自动重算哈希，不以绿 CI 作为任意新改动授权。最终可复现工程基线为本节不可变 commit/tree 与既有门禁，而非对所有未来改动永久禁止。

七个历史测试文件变更：ArchitectureBoundaryTest、AccountBrowseManualHandoffBoundaryTest、AiReplyObservationRuntimeTest、ChatMessageReadCoordinatorTest、MomoPageObservationCoordinatorTest、PrivateMessagesTraversalTaskExecutorTest、ReceivedGreetingsTaskFlowControllerTest。五个历史检测/CI 文件变更：android-build.yml、test_m04_media_click_freeze.py、test_pp_r00_freeze.py、test_verify_boundaries.py、verify_boundaries.py。R01 当前精确证据核验通过；不可表述为全部历史测试逐字节未变。R09-F 旧上传断言定位迁移后保留独立 OCR build/test，新增三 APK 签名及顺序约束。M04 原行为保护及 R02 固定预期继续执行。

## 4. 实际自动化与 APK 验收

- [架构 job 140941016183](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/job/140941016183)：211 Python tests OK；ARCHITECTURE CHECK PASSED；9 模块依赖方向有效；Android XML 校验 success。11 项 advisory 非硬错误。R09-F 两项新增签名交付检测通过。
- [测试 job 140941446062](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/job/140941446062)：实际下载工件 15258134469、解析 TEST-*.xml，268 suites、2339 tests、0 failures、0 errors、0 skipped。app 961、business 977、core 286、gesture-core 38、gesture-runtime 77；11 份 R02 轨迹各 1 项均通过，R09cPageWriteAuthorityTest 2 项、R09dDraftHistoryIsolationTest 3 项、R09dRecoveryIsolationTest 1 项及 ArchitectureBoundaryTest 55 项均通过。OCR 独立测试任务成功，不计入 2339。
- [OCR job 140941446089](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/job/140941446089)：独立 testDebugUnitTest/assembleDebug success。[CodeQL job 140941446108](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/job/140941446108) success；不推断零漏洞。
- [交付 job 140941446086](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/job/140941446086)：普通、精简、OCR 配套构建成功；上传前 apksigner 验证成功，三件单 signer SHA256 均为 ac382dac8a4c87a6e99bee1a321821a8c4943e3624461ad02ac9d8f8e1d7a962，实际日志 R09F_APK_SIGNING_PAIR_VERIFIED。已确认 3 个正式工件存在且未过期。当前基线工件签名结论来自 SDK 验证日志，非再次本地解包或设备安装。

| 工件 | 下载（同一 run） |
|---|---|
| Freedom-debug-apk | [15258830077](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/artifacts/15258830077) |
| Freedom-slim-debug-apk | [15258019861](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/artifacts/15258019861) |
| Freedom-OCR-plugin-debug-apk | [15258840174](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/artifacts/15258840174) |
| Gradle-test-reports | [15258134469](https://github.com/ttiaogou/FRDM/actions/runs/46132653400/artifacts/15258134469) |

工件有保留期，应及时保存同批次配套文件及日志；不同 run 的 debug 签名可能不同，不保证覆盖已装版本。测试使用普通+OCR，精简版后续单独复测。签名不匹配时不要盲目卸载或清数据。真实安装、IPC、页面、手势、发送、恢复均待设备证据。

## 5. 架构责任复核结论

| 边界 | 现有主责任及证据 |
|---|---|
| 模块 | app、business、core、gesture-api/core/runtime/android、ocr-plugin-api、ocr-plugin 共 9；R09-A 物理选择与依赖图检测 |
| 入口 | Service → Forwarder → Pipeline → UnifiedAccessibilityEntry → RunSessionCoordinator → 业务 Runtime；R09-C 事件分发及构造旁路检查 |
| 页面与授权 | PageObservationCoordinator 负责观察；RunStateRepository 负责用户运行意图；RunSessionCoordinator 负责页面 READY 路由；共享 RunGenerationClock / TaskCoordinator / UiOperationOwnershipCoordinator；UiWriteAuthorization 最终写入门禁 |
| 私聊/招呼/动态 | PrivateMessagesTraversalTaskExecutor、ReceivedGreetingsTaskFlowController / MomoSayHiTraversalState、AccountBrowseRuntime/StateMachine；适配层不新增权威任务授权 |
| OCR/AI | ChatMessageReadCoordinator 读取代次；AiReplyDraftCoordinator 草稿/发送；ContactHistoryResolver 修复跨联系人残留缓存；既有 OCR IPC 与 provider 边界 |
| 恢复 | UnifiedRecoveryTransactionManager / RecoveryEventDispatcher / RecoveryScheduler / RecoveryCheckpointVerificationCoordinator；新代次与两次新鲜证明；R09-D 组合测试 |
| 手势 | GestureApi / 编译、校验、执行队列 / Android dispatch；S0 手势及平台安全代码不变，M04 媒体动作保护继续执行 |
| 持久化 | 既有 Repository、DataStore、账本和检查点；没有新增平行持久化合同 |

依据本轮实际通过的 R09-A/C/D、P0–P7、R00/R01/R02 和各迁移检测复核；不重新声称逐方法完成编译器级全仓调用图，也不证明所有不同命名状态无语义重复。组件级 golden trace 不是双 APK 真机差分。当前未发现新增必须修复的硬门禁缺陷。

## 6. 遗留清单及处置

| ID | 遗留问题 | 处置 |
|---|---|---|
| G01 | showNodeMoreMenu 单引用候选 | R09-B1 曾清理导致原悬浮菜单契约两项失败，已精确回滚。保留，另行授权 UI 契约/真实可达性审计 |
| G02 | GesturePolicyPort.kt 历史无声明占位、六个 main 和两个 test 失效排除路径 | 保留已冻结配置/原测试引用，不是活动重复实现；删除被排除文件不能降低 APK 体积 |
| G03 | 7 组相同方法体 | recycle（节点寿命/S0）、clear（不同存储）、getOrCreateKey（不同密钥域）、sha256（不同 APK）、reset（不同恢复 lane）、bool/int（诊断类型）、intersect（S0）；既有分类保留，不强行合并 |
| G04 | ChatPagePort 42 方法、fixture fallback、跨 owner 薄委托及诊断/归档路径 | 未证明无效或语义重复；保留，未来单独审计所有实现/调用、默认契约、锁和副作用 |
| G05 | 11 个大文件 advisory | 非硬错误；MainActivity、AndroidAccessibilityGateway、AndroidAccountBrowseWorkflow、MomoChatListSessionExtractor、MomoChatMessageReader、MomoSocialAdapter、AppCompositionRoot、RunDiagnosticsManager、AiReplyObservationRuntime、PrivateMessagesTraversalTaskExecutor、AiReplyDraftCoordinator。按实际职责评估，不按行数强拆 |
| G06 | 同一联系人跨 visit 历史缓存新鲜度、已经离开定时器令牌门禁的回调 | 仍依赖既有运行代次/路由/租约校验；R09-D 已解决跨联系人缓存污染，未穷尽同联系人新鲜度和并发；由实际故障证据触发专项审计 |
| G07 | shrink-only 实际 APK 仅减少 1806 bytes | 最新 CI 普通 5595612、精简 5593806 bytes；DEX 28→1、压缩 DEX 5379267→2650275 bytes。不要把外层 ZIP 或 DEX 差当作 APK 大小差；独立打包审计 |
| G08 | debug 签名仅同 run 配套，非长期升级签名合同；debug 日志可能含聊天内容 | 不在本轮改变 release signing；保留签名权限。使用授权测试会话并脱敏日志，不将 debug 工件当正式发布包 |
| G09 | 真机业务及完整回归 | 用户独立完成，见下节；未通过前不宣称产品级最终验收 |

最新 R09B_INVENTORY：436 production files、3779 simple functions、100 interfaces、1 private candidate、0 interface candidate、7 exact nontrivial body groups。词法扫描仅候选，不是全面可达性证明。没有新增确认可安全删除的生产文件。本轮不再清理。

## 7. 真机延期登记及结项条件

R09-F 原报告 [F01–F12](FRDM-PP-R09-F-APK-DEVICE-REGRESSION.md) 保留原场景/通过标准。用户明确接受延期且独立执行，F01–F12 均为 DEFERRED / NOT_RUN，无虚构设备证据。优先验证配套安装与 OCR 实际绑定、私聊/招呼返回原游标、动态翻页、头像 ID、OCR/AI 晚到回调、发送及图片/拦截结果核验、六方向操作权切换、重连/UNKNOWN、停止、重启/精简版。使用授权测试账号，记录设备及系统版本、目标 app 版本、同 run APK、实际行为、脱敏日志；失败按独立最小修复任务处理，原基线与测试预期保持。

工程收敛与自动化验收已完成，真机全业务验收延期，不能标为全部通过。本报告提交是仅文档变更，提交自身的新 CI 仍须核验，不把旧 run 当成新 HEAD 的 CI。下一轮正常开发应以 PP 绿基线和原 AGENTS、现有硬检测、精确迁移证据为准；本报告仅验收记录，不增加或覆盖施工规范。
