# AUDIT_CHECKPOINT — PP 当前治理检查点

仅记录当前进度，不替代 [AGENTS.md](../../AGENTS.md)，不授权下一阶段改动。历史见 Git 提交及原审计报告。reviewedHead/tree 为本轮实际复核的施工前基线，最近完整 CI 已通过的提交另见已验收工程基线；本次结项记录提交的 SHA 和 CI 以 Git/Actions 实际记录为准，避免自引用及无意义反复提交。

| 字段 | 当前记录 |
| --- | --- |
| repository / branch | ttiaogou/FRDM / PP |
| reviewedHead | 2f5a7a4aa0325cf14cc1163ffc280f56d8d2122a |
| reviewedTree | 633c7e5d64c3ec93f6cd8ce56ea7478e5f2851e8 |
| 已验收工程基线 | D01-C 2f5a7a4aa0325cf14cc1163ffc280f56d8d2122a；[完整 CI 46143262439](https://github.com/ttiaogou/FRDM/actions/runs/46143262439) completed/success |
| 当前任务 | D01-D1 退役旧批量 ZIP 打包器 |
| 当前状态 | IMPLEMENTED / BASELINE_CI_PASS / COMMIT_CI_PENDING；旧节点检查器、缓存及 S0 未施工，真机未验 |
| 验证策略 | 旧 writer 精确删除，测试迁移及历史逆向保护；全模块 src 引用检测及完整 CI；真机待验 |
| 设备验收 | F01–F12 DEFERRED / NOT_RUN；用户独立执行 |
| 当前职责建档 | 32 项：A 16、B 12、C 4；证据基线仍按职责表，不虚报逐方法核查 |
| 台账 | 30 项：CLOSED 8、RETAINED 3、CANDIDATE 7、OPEN 3、LIMITATION 1、DEFERRED 1、VERIFY_PENDING 7 |
| 工作区 | 远端 Git tree/compare；无本地 checkout 验证 |
| 改动范围 | 仅删 SessionSampleArchiveManager.createBatchArchive；相关单测、R00/R01/D01-C/D01-D1 精确迁移及治理记录；现行三范围打包及安全机制不改 |
| 下一阶段 | 复核 D01-D1 完整 CI；D01-D2 旧检查器专属组件审计与退役；D01-D3 S0 冻结冲突独立决议 |

## 批次进度

| 批次 | 状态及证据 |
| --- | --- |
| G01-A | 完成：[引用及冻结核查](FRDM-PP-G01-A-DOCUMENT-INTEGRITY-AUDIT.md)，46134802486 success |
| G01-B | 完成：[现行架构说明](FRDM-PP-G01-B-CURRENT-DOCUMENTATION.md)，46135597745 架构 gate success |
| G01-C | 完成：[历史归档](FRDM-PP-G01-C-HISTORICAL-DOCUMENT-ARCHIVE.md)，46136302512 架构 gate success |
| G01-D | 完成：三文档已建，46136977140 架构 gate success |
| G01-E | 完成：导航及精确保护接入，46137618668 完整 CI success |
| G01-F | 累计差异、规范、归档、结构及已验收基线复核完成；完整 CI 46139876328 success |
| D01-A | 只读核心链路审计；遗留引用未穷尽 |
| D01-B | 独立节点 ZIP 退役子项实施，修复提交完整 CI 46142186044 success；剩余缓存/旧接收器未收敛 |
| D01-C | 三范围统一导出已实施；完整 CI 46143262439 success |
| D01-D1 | 旧批量 ZIP 打包器退役；新提交完整 CI 待查 |

## G01-F 复核及验收证据

- G01-A 前基线 a61a7cf736a5e98d98b1325f7a244865e59b8805 与 G01-E HEAD 完整未截断 tree 比较：925 → 936 blob；Git compare 5 commits ahead、0 behind、50 renamed / 11 added / 10 modified（Git rename 为相似性分类）。仅两份非 Markdown 差异：G01-A 新增文档扫描器、G01-E 原 R01 测试追加；778 个原源码/规范/冻结清单/CI/Gradle 等保护范围文件 blob 一致，436 个物理 main Kotlin 文件不变。没有生产源码、业务测试、构建及工作流变更。
- AGENTS 旧 blob e0879a754be35699d0cc8741caa4e31cccd1aa1b、新 blob edb31ea66ae4ff5f6fd8aea0dd3b347c7cf8352f；原全文 7238 bytes 为新全文 9563 bytes 的完整前缀。十份规则 blob 与原 R01 一致；R01 原清单 033bb92c5bc369ba7d7e315ac11fd3bfc9b513a7，INTEGRITY.json 718949976c8ec98598f247fdd5d854e815101c03 均未变。现有 R01 精确迁移保留全部旧测试，新增三项结构/逆向验证。
- 归档索引 53 项逐一比对：52 项原 blob 完全一致，旧 ARCHITECTURE.original.md 的 7 处链接反向恢复后全文与原文完全一致。重点检查 AGENTS、三份治理文档、归档索引 218 处仓库内部路径链接，无缺失目标；全仓词法扫描不证明外部 URL、锚点或动态路径有效。
- [G01-E 架构 job 140956354191](https://github.com/ttiaogou/FRDM/actions/runs/46137618668/job/140956354191)：216 Python tests、三项新增测试均 ok、ARCHITECTURE CHECK PASSED；936 tracked / 934 text / 2 PNG / 110 文档及配套 / 0 链接异常候选。Unit tests and reports、Build Freedom debug APK、Build OCR plugin debug APK、CodeQL 均 completed/success。
- [G01-E APK job 140956846775](https://github.com/ttiaogou/FRDM/actions/runs/46137618668/job/140956846775) 实际日志三次 BUILD SUCCESSFUL，R09F_APK_SIGNING_PAIR_VERIFIED SHA256=716ffba13bbb5019717d9eecfc21b793353005138a31ea1cdbafe607954b7220。普通、精简、OCR 工件均存在且查询时未过期；为同 run debug 配套，不是长期正式签名合同。
- 台账 DOC-007/008 依据 G01-E 完整 CI 与 G01-F 实际复审关闭；其余 R09G-G01～09 及 GOV-001 保留原分类。职责表 A/B/C 不改变；尚未穷尽逐方法状态读写、动态调用图及全仓语义去重，不将结构验证当作全部 AI 合规或设备通过。用户延期真机 F01–F12 仍有效。
- 此为治理工程基础设施验收，不是新增生产架构，不是对任何历史候选代码的删除授权。历史索引见 [归档](../archive/README.md)。

## D01-B 本轮证据

- 基线 S0 Service blob 2ee39d722c21947e1a73305ec8559e1f0226cdc8；其旧高亮接收器仍读取 LatestAccessibilityNodeDump。禁止为清理缓存而擅改 S0。
- MainActivity 7e4148b1006924f66b11308f394b7752279f0984 → 94a8c122f60d52f24d59b4edb96d212a7d6a295d；LatestAccessibilityNodeDump 0373e7e20e2d921aebb2559fe910a6c7aed22c67 → 2d636faa98ed11fdecae133692ab3b4ff720905e。两文件均仅删 70 行，精确逆向重建原文。新检测 [D01-B](../../tools/architecture/test_pp_d01b_node_zip_retirement.py) 保留原 R08-D 历史逆向，R00/R01 追加准确 S1/证据 pin，未删减原断言。
- 保留现有运行诊断、归档导出、可视化布局保存、自动监控摘要及缓存读写；D01-C 未施工。无本地 Gradle/真机结果；新提交 CI 状态以后续实际 Actions 为准。

## D01-B CI 修复证据

- [CI 46140766984](https://github.com/ttiaogou/FRDM/actions/runs/46140766984) 对 6dc090025ab32234804efe415b6f7bf295416056 completed/failure：架构及资源、Freedom APK、OCR APK、CodeQL 均 success；[单测 job 140966463064](https://github.com/ttiaogou/FRDM/actions/runs/46140766984/job/140966463064) App 961 项中 1 项失败，FloatingWindowLifecycleBoundaryTest.runRecordsZipExportRemainsIndependentOfTheFloatingWindow 第 106 行仍要求已授权退役的 launchNodeDumpExport() 存在。
- 仅把旧存在性断言迁移为禁止入口及方法存在，更新 logExport 方法截取边界；原有悬浮生命周期、保存、回读、清理及其他断言不变。原测试 blob b7e725a22c349128aa720bf32fa8e13f95336ca9 → 9dc38c8285e88d2d30f033f414f46b7111e6eab2；R01 保留原清单并追加精确新 pin 与逆向重建检查。生产源码不变，新 CI 待验证。

## D01-C 本轮证据

- MainActivity 94a8c122f60d52f24d59b4edb96d212a7d6a295d → 099ca52fb0d6dcf1ae9d394fa624725f5d233a3c；综合范围默认全部已封存样本，选择等待封存时复用原确认、轮询，保留原归档选择路径。诊断、归档、综合共用 createExportBundle、SAF、回读和保护性清理；未改请求码及保存事务。
- SessionSampleArchiveManager 7840a6f0f279bd4b6f33bc6814c6e053eed059ae → c1527cfb178dffd2b429924f0a245cb6327edde3；只修正关联历史报告兼容条件，排除 active/finalizing、未关联历史封存要求与删除授权不变。
- [D01-C 精确迁移测试](../../tools/architecture/test_pp_d01c_combined_export_migration.py) 接入原 R00/R01/D01-B/R08-D 历史链；[组合导出测试](../../app/src/test/java/com/freedom/ui/logs/D01cCombinedExportTest.kt) 覆盖双样本共享关联诊断、未封存、旧关联与未关联历史、独立异常、无样本情形、清理保护。新 CI 待验证，未本地构建/真机测试。

## D01-D1 本轮证据

- 基线 2f5a7a4aa0325cf14cc1163ffc280f56d8d2122a；[CI 46143262439](https://github.com/ttiaogou/FRDM/actions/runs/46143262439) 五个 job 全部 success。分批扫描同一 Git Tree 中 App main 239 个 Kotlin/Java/XML/AIDL/JSON/properties 文件，旧方法仅自身定义，明确已见两处单测调用；不将词法扫描冒充全仓动态调用分析。
- SessionSampleArchiveManager c1527cfb178dffd2b429924f0a245cb6327edde3 → f64aa840ffb36e8ae9a679af9bbcb2af7fc1efe5，精确删除旧方法 61 行；现行 createExportBundle、cleanupAfterVerifiedExport、源指纹与封存保护逐字节不变。
- SessionSampleArchiveManagerTest 755a8f1aa7c51037e3c1036223ea9e733d313423 → efbc15dfc2c46e3c976caa10270e07c34aa2fa25；旧多归档隔离、内容和源保护迁移至当前 samples/runId 结构，旧至少两条限制退役，验证单条有效及空选择无诊断拒绝。新增 [D01-D1 精确迁移与全模块引用门禁](../../tools/architecture/test_pp_d01d1_batch_packager_retirement.py)，串接 D01-C 历史逆向，原清单及 S0 不变。新 CI 待验，无本地 Gradle/真机结果。

## 接续维护

- 每次获授权施工先读现行规范、[职责表](RESPONSIBILITY_MAP.md)、[台账](AUDIT_REGISTER.md) 和本检查点，核实实际 PP HEAD；只读或禁止写入任务不擅自维护文件。
- 更新真实受影响职责/问题及证据；如无职责或问题变化，不强制改写对应文档。检查点在下一次获授权任务核实前一提交实际 CI 后更新；不把未知结果填 PASS、不为追写结果反复触发无意义提交。
- 继续复用原有架构和业务测试。现有治理结构门禁保护编号、记录及导航，不证明所有业务无重复。GOV-001 与 R09-G 候选按独立授权逐项处理；F01–F12 设备结果仅在有实际证据后更新。
