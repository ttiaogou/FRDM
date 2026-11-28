# FRDM PP G01-C — 历史报告归档及无用文档清理

## 审计基线

- 仓库 ttiaogou/FRDM，PP；HEAD 77665e2ce4ef872041659d94f6012f8a9f3889af，tree 9b7406456939d71340665a83d8b17f9c9e4ff8b0，未截断，928 blob。G01-B CI [46135597745](https://github.com/ttiaogou/FRDM/actions/runs/46135597745) 对应 HEAD 的架构/资源任务 success：213 Python 测试及硬检测通过，928 跟踪文件 / 926 UTF-8 / 2 PNG / 102 文档及配套 / 0 链接异常候选。按用户确认的文档分级验收，不等待完整 APK；G01-F 统一完整验收。
- 已实际读取 AGENTS、现行 §0，沿用 G01-B 全分片复核及冻结保护。重核 Service:447–448 转发、AppCompositionRoot:214–225 共享代次/任务/UI/恢复、:478 写门禁、:917 统一入口、UiWriteAuthorization:65 租约；未改变生产架构或调用链。本轮通过远端 tree/compare 核查，不具备本地 git worktree。
- 对候选报告和旧修复文档逐项检查 basename 反向引用（实际 CI G01A_REF）、路径提示、R00/R01/M04/规范清单，未发现拟迁移文件被现行生产或检测直接引用。39 份历史审计及 10 份旧说明逐份读取并检查相对 Markdown 链接/引用定义，均无迁移后需重定向的相对链接。G01-A/B 旧报告中的路径是历史事实记录，不改历史正文。现行 ARCHITECTURE.md 中旧正文相关路径随原文进入归档；其他现行引用核对以实际新 tree 为准。
- 旧业务修复中的诊断及文本容器仍可能有技术价值：现行 AiModelCallDiagnostics.logFields 含 safeCode、failureStage、httpStatus、retryable、provider error 字段；AiReplyDecisionEngine 传播诊断；AiReplyDraftCoordinator 现有失败及输出分类不同于早期手动草稿流程；ChatMessage.isMessageTextTarget 与 classificationOnly 兼容契约仍存在，MomoChatMessageReader 仍使用方向判断且处理媒体。故仅归档旧说明，不删除这些能力、不把旧文本-only/普通 OCR 规则作为现行授权。现行源码和规范是行为依据。

## 施工结果

- 11 份 P1 报告迁至 docs/archive/p1/；28 份 PP R03–R08 报告迁至 docs/archive/pp/；10 份过期架构整改/业务修复说明迁至 docs/archive/legacy/，共 49 份原始 blob 逐字节保留的迁移。
- 三份现行文档去除 G01-B 临时折叠历史正文，现行部分不改，追加归档与固定提交导航。旧正文及根 OCR 原始正文共 4 份保存在 legacy/，其中旧 ARCHITECTURE 原文七处相对链接转为 G01-A 固定提交链接（其他文字保留，固定提交可逐字核对）；另三份原始 blob 不变。
- 删除已被现行 OCR 文档替代的根目录 OCR 导航副本 OCR-#U6539#U9020#U8bf4#U660e.md。原始内容另行归档，G01-B 导航版仍在本轮父提交；这是唯一非迁移的旧路径删除。外部旧链接不能穷尽，索引提供固定提交和新路径。
- README 增加 [归档索引](../archive/README.md)，索引列出全部 53 份归档资料的新旧路径和原始 blob。历史内容不再混在当前架构正文中。保留现行 M04、R00–R02、R09-A–G、G01-A/B、第三方声明/许可证和设备清单。
- 不提前新增三份治理文档，不改 AGENTS、十份分片、原件、冻结清单、任何生产/测试/检测/构建配置，不删生产代码。R09-G G01–G09 遗留风险保持；F01–F12 仍 DEFERRED / NOT_RUN。旧报告中的历史未验证事项不因归档而变为已验证。

## 验收

提交前核对新 tree、迁移 blob、文档链接和非文档无变化；提交后核对 PP HEAD / compare 及现有架构和资源 gate、G01A_SCAN。引用采集是词法证据，不能证明动态外部引用全部可达。完整 APK/CodeQL 不作为纯文档阶段等待条件，本提交实际 CI 结果以 run 为准。下一阶段 G01-D 建立三份治理文档。
