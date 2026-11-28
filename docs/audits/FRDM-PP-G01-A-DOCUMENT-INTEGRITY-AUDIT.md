# FRDM PP G01-A — 全量文档引用、完整性及冻结保护核查

## 1. 范围与安全基线

- 仓库 ttiaogou/FRDM，分支 PP，审计 HEAD a61a7cf736a5e98d98b1325f7a244865e59b8805，tree 84880760bdbbd650aa271059aa1db24583bb3169；远端写权限已确认。完整递归 tree 未截断：1196 项，925 blob，97 Markdown，docs/ 下 95 文件（包括 53 历史审计报告、16 规范及配套文件）。9 个 Gradle 模块。
- R09-G HEAD 的 CI [46133562394](https://github.com/ttiaogou/FRDM/actions/runs/46133562394) completed/success，head_sha 一致；F01–F12 真机验收仍为 DEFERRED / NOT_RUN。
- 本地环境无检出的 Git 工作区，git ls-remote 因 DNS 无法解析 github.com 失败。采用固定 HEAD 的远端完整 tree、正文和 blob 证据；本地 git status/diff 不能宣称通过。提交只增加审计报告和只读清单采集测试，不修改或删除旧文件，不启动 G01-B/C/D/E。
- 已读取 AGENTS、现行 §0 和 01–10 分片；规则优先级不变。本报告是证据，不是规范或施工授权。

## 2. 架构及生产入口复核

- FreedomAccessibilityService.kt:447–448 的 onAccessibilityEvent 只转发至 AndroidAccessibilityEventForwarder.accept；Forwarder:103–104 选择现有 pipeline；AndroidAccessibilityEventPipeline:122/160/194/249 将归一化事件交给 UnifiedAccessibilityEntry。Service:492 的独立生命周期通知同样走现有统一入口，不是另建任务授权。
- AppCompositionRoot.kt:214–225 共享 runGenerationClock、UiOperationOwnershipCoordinator、TaskCoordinator、UnifiedRecoveryTransactionManager；:478 UI 写入门禁；:917–921 统一入口到 RunSessionCoordinator；后者 :508 为目标事件分发。UiWriteAuthorization.kt:65 使用 withCurrentLease。settings.gradle.kts 明确 9 模块及 gesture 物理目录。
- 当前证据为针对上述入口、所有权及模块的源码复核，不是全仓语义调用图。G01-A 不改变源码、手势、算法、页面指纹、行为、状态或操作权。

## 3. 全量文档扫描和引用关系

对基线 97 份 Markdown 全部取得正文并核对返回 Git blob 与 tree 一致，按相对路径和 basename 扫描；常规内联相对链接的目标路径未发现缺失（扫描未验证锚点、外部 URL、Markdown 引用定义、动态路径或旧提交链接；根路径回退需区分实际相对路径）。非 Markdown 反向引用不能仅靠默认分支代码搜索判断。

新增 tools/architecture/test_pp_g01a_document_inventory.py：利用现有 CI unittest discover，在实际 checkout 使用 git ls-files -z 读取所有跟踪文件，报告每项 Markdown/docs 文件、文件名引用来源/行号、非文档路径提示、常规链接候选及显式跳过的二进制/非 UTF-8 文件；不改 CI 或旧检测，不对历史链接候选强制判错，不输出源文内容。证据标记 G01A_SCAN / DOC / REF / PATH_HINT / LINK / SKIPPED_BINARY。该扫描在新提交 CI 执行前不得报告已完成。文件名命中只是候选，不证明运行时依赖；外部引用、动态拼接及锚点仍需专项核实。报告与扫描器自身引用可识别为本次新增证据，不是原有依赖。

关键已确认正文引用：
- AGENTS.md → 10 分片、现行全文、两份原件、INTEGRITY.json、NAVIGATION.md、REVIEW-NOTES.md；NAVIGATION.md → AGENTS/01/原件/现行全文/REVIEW-NOTES；REVIEW-NOTES → 现行全文。
- ARCHITECTURE.md → README、STAGE-7-12、GESTURE-ALGORITHM-COVERAGE、GESTURE-ARCHITECTURE-GUARDRAILS、GESTURE-COMPLIANCE-REMEDIATION、GESTURE-MODULE-BOUNDARIES、GESTURE-POLICY-MANAGER、GESTURE-RUNTIME-PIPELINE、GESTURE-STRONG-TYPED-VALIDATION；GESTURE-MODULE-BOUNDARIES → GESTURE-EXECUTION-QUEUE。
- FRDM-P1-CODE-HEALTH-BASELINE → AGENTS、ARCHITECTURE-CONTRACTS-3.0；R09-G → R09-F（真机验收必须保留）。
- 根 OCR 说明与 docs/ocr/OCR-PLUGIN-ARCHITECTURE.md 均引用 AUTOJS6-PLUGIN-PORTING-NOTICE；OCR 架构引用 OCR-PLUGIN-DEVICE-ACCEPTANCE；PaddleOCR-Android-Integration 引用 ARCHITECTURE、OCR 架构、AutoJs6 notice。许可证及来源链不得删改。
- 原始索引引用 AGENTS；历史整改基线引用 AGENTS 及已淘汰的 00–07 分片/AGENTS.original.md。后者为历史描述，不是缺失现行规范。

## 4. 规范完整性与冻结保护

- INTEGRITY.json 的 2 原件、10 分片、1 现行全文全部存在，13 项 UTF-8 字节长度与记录相等；十个分片按序拼接与现行全文完全相等，AGENTS.md 原索引前缀与原件完全相等。旧文件删除清单只记录历史迁移，不是本轮删除授权。
- verify_boundaries.py 的 agents_integrity_violations 读取原件/分片/全文、校验 SHA-256 和长度、分片目录与顺序、原始索引前缀。此处 SHA-256 由基线绿 CI 的现有检测验证；本次远端独立复核为正文等值、长度和 Git blob，不冒称本地运行。
- pp_r01_contracts.json blob 033bb92c5bc369ba7d7e315ac11fd3bfc9b513a7，protected_rule_files 的 11 项全部逐项与当前 tree blob 匹配：AGENTS e0879a754be35699d0cc8741caa4e31cccd1aa1b，10 份规则分片全部一致。test_pp_r01_contract_freeze.py 的 test_existing_tests_rules_and_ci_gates_are_unchanged 执行精确保护；原测试还明确允许新增测试而非删除/修改既有证据。
- G01-E 若增加 AGENTS 治理导航，须独立明确授权，保留原清单和原前缀，登记原 blob 与准确新 blob，适配现有精确迁移验证（不采用通配豁免、不重录清单、不放宽断言），保持十份正文与三份原件/现行全文不变；本轮不执行。
- R00、R01、R02、M04、各迁移验证及 R09-F/G 是行为/验收证据，历史报告的直接文本引用较少不代表可安全删除。

## 5. 文档处置清单（仅建议，不修改）

| 分组 | 路径或范围 | 后续处置 |
| --- | --- | --- |
| 现行说明 | README.md；docs/ARCHITECTURE.md | G01-B 更新现行导航及九模块/唯一入口，历史段落明确隔离 |
| 混合契约 | docs/ARCHITECTURE-CONTRACTS-3.0.md | 单 app/旧 Runtime 描述已过期；逐条区分仍有效契约与历史计划，禁止改动现行施工规则 |
| 历史整改 | docs/ARCHITECTURE-REMEDIATION-BASELINE.md；docs/STAGE-7-12-ARCHITECTURE-REPAIR-REPORT.md | G01-C 归档候选，保留历史证据及链接；前者旧 00–07 规则路径，后者单 app |
| 手势边界 | docs/GESTURE-MODULE-BOUNDARIES.md；docs/GESTURE-ARCHITECTURE-GUARDRAILS.md | G01-B 更新实际模块及检测边界；后者仍称七模块。其余手势专业文档保留待专项核对；GESTURE-COMPLIANCE-REMEDIATION 为历史候选 |
| 旧业务修复 | docs/DRAFT_REPLY_FIX.md、DRAFT_REPLY_FIX_V2.md、FREEDOM-MOMO-RECEIVED-GREETINGS-ENTRY-NAVIGATION-V6.md、repairs/MOMO-RECEIVED-GREETINGS-BACK-RECOVERY-V5.md、fixes/model-call-diagnostics.md、fixes/text-container-ci-contract-fix.md、fixes/text-container-only-reply.md | 历史候选，抽取仍有效契约并核对当前源码；普通 UNVERIFIED OCR 准入两版不同、人工发送描述过期，禁止以旧文档覆盖现行行为 |
| OCR | 根 OCR-#U6539#U9020#U8bf4#U660e.md、docs/ocr/OCR-PLUGIN-ARCHITECTURE.md、OCR-PLUGIN-DEVICE-ACCEPTANCE.md、release-manifest.example.json | 根说明重复候选，先整合独有安装/签名/交付信息；其余保留 |
| 历史审计 | docs/audits/FRDM-P1-* 11 份、FRDM-PP-* 42 份 | P1 及 R03–R08 归档候选；M04、R00–R02、R09-A–G 保留验收证据；R09-F 真机未验收，R09-G 当前基线 |
| 保护 | AGENTS、docs/agent 全部 16 项、tools/architecture、现有测试/冻结清单 | 原样保留，不能按重复内容或无正文引用删减 |
| 其他 | docs/feature-node-inspector-port.md、design/、docs/third-party/ | 保留技术/设计/许可证与来源声明；不是普通冗余 |

本轮不认定任何文件可以无条件删除。零引用、相似内容、旧时间均不足以证明可删。后续若移动文档，应验证正反向路径、Markdown 及第三方声明、冻结规则、CI 和 Git 历史；外部历史链接无法由本仓穷尽。R09-G 的 G01–G09 遗留问题原样保留，尤其 showNodeMoreMenu、GesturePolicyPort 排除路径、真机验收及 APK 瘦身风险；不在文档任务中清理生产代码。

## 6. 后续批次及验收

G01-B 重整现行说明；G01-C 在授权后归档/清理；G01-D 三份治理文档；G01-E 唯一入口导航与精确保护；G01-F 全量复审。三份治理文档在本轮仍未创建。
本提交只新增本报告与只读扫描测试。需核对远端 diff/PP HEAD、现有架构门禁及完整 CI，检查 G01A_SCAN 和引用日志。CI 结果以本提交实际 run 为准，不提前填写通过；本地工作区及真机未验证。
