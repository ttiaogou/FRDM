# FRDM PP G01-B — 现行架构说明与重复文档收敛

## 基线及约束

- Repository ttiaogou/FRDM, PP, reviewed HEAD 9919a0b30a9cfd19235d691ee4ccfee5ec664564, tree 9d644f3b37e1f4ae0599ff137497854e1135110f. G01-A CI [46134802486](https://github.com/ttiaogou/FRDM/actions/runs/46134802486) completed/success at same HEAD; all five jobs, 213 Python tests. G01-A full tracked text inventory and existing link evidence reused.
- 已阅读 AGENTS 与全部现行 01–10 分片；本轮是文档修订，不新增/重建架构，不改生产源码、测试、检测、配置、权限、手势、算法、页面指纹、状态或业务行为。
- 本地无 Git 工作区且 git ls-remote DNS 解析失败，使用固定提交的远端源码、tree 和 compare，不能声称本地 git status/diff 通过。

## 关键源码复核及差异

- settings.gradle.kts、九个 build.gradle.kts：9 physical modules / 16 direct project edges；core/business/gesture 使用自己的源码根，不共享 app 旧根。436 物理 main Kotlin，含 1 明确排除的历史占位。旧单 app/七模块/共享源码叙述不再适用。
- Service onAccessibilityEvent:447–448 → Forwarder:103–104 → Pipeline:122/160/194/249 → UnifiedAccessibilityEntry → AppCompositionRoot:917–921 → RunSessionCoordinator:508。AppCompositionRoot:214–225 共享代次、任务/UI、恢复；:478 写门禁，UiWriteAuthorization:65 withCurrentLease。Service:492 的生命周期通知仍用统一入口。结合 R09-A/C/D/G，区分只读观察、权限与恢复；不宣称完整语义调用图。
- ChatMessageReadCoordinator:49/234/258/334 读取代次；AiReplyDraftCoordinator:125/134/237–239 草稿、发送确认；RecoveryCheckpointVerificationCoordinator:81 completeResume；GesturePolicyPipeline:73/111/118/135 验证/编译/审批/执行；AndroidGestureDispatcher:181/218/258 平台派发。当前状态归属摘要引用 R09 证据，不提前建设 G01-D。
- OCR 重大文档冲突：ocr-plugin/build.gradle.kts:178–297 prepareBundledOcrModels 从固定目录验证大小/SHA-256 后生成 assets 并接入 preBuild；OcrModelManager:292–319 优先 APK assets、缺失时保留下载路径、安装前校验。固定模型已随插件 APK 交付而非手机首次识别必下载，主 APK 不含 OCR 引擎/模型。两 Manifest 的 signature 权限、OcrPluginHost:430/470/617 发现/权限/绑定、OcrPluginInstaller:94–144 下载验证与系统安装保持原样。
- android-build.yml 实际 5 jobs，签名配套后分别上传 3 APK；codeql.yml 只是保留的无触发 stub。当前 PP 工作流未发现旧文档所述 tag Release 发布 job，不宣称现有自动正式发布。两个 app 支持 release signing 属性，正式发布及长期签名未验收。

## 实施及文档归属

| 文件 | 处置 |
| --- | --- |
| README.md | 项目导航、构建/验收入口 |
| docs/ARCHITECTURE.md | 唯一现行架构说明；旧正文折叠并保留固定提交引用 |
| docs/ARCHITECTURE-CONTRACTS-3.0.md | 现行契约索引、历史目标隔离；原文保留 |
| docs/GESTURE-MODULE-BOUNDARIES.md | 当前物理目录、依赖与手势边界；旧正文隔离 |
| docs/GESTURE-ARCHITECTURE-GUARDRAILS.md | 9 模块、5 job、现有 gate、授权约束 |
| docs/ocr/OCR-PLUGIN-ARCHITECTURE.md | OCR 唯一现行说明，整合根文档、模型打包与 CI 配套签名 |
| 根 OCR 旧文件 | 保留路径作为导航，原文固定提交可追溯，不删文件 |
| docs/ocr/OCR-PLUGIN-DEVICE-ACCEPTANCE.md | 修订过期首次模型下载场景，全部 checkbox 保持未执行；保留原验收保护，增加插件 assets/配套及离线首次识别验证 |
| docs/third-party/PaddleOCR-Android-Integration.md | 只纠正过期 runtime 下载描述，保留来源、许可证及 SDK 适配内容 |

G01-C 仍需归档旧架构正文、P1/PP 旧审计、旧修复说明及根 OCR 候选；R00/R01/R02、M04、R09-F/G、AGENTS/agent、第三方声明及许可证不删改。未确认任何生产文件可删除；R09-G G01–G09 遗留事项不变。F01–F12 仍 DEFERRED / NOT_RUN。文档与引用扫描不证明全仓无语义重复或真机成功。

## 验收边界

仅修改上述九份文档、新增本报告，无文件删除/移动，无现有代码、测试、规则、工作流、清单变化。需验证 tree/compare、G01A_SCAN、架构/测试/完整 APK 和 CodeQL；新提交 CI 以实际结果为准，不能以基线成功代替。后续 G01-C、D、E、F 仍为独立任务。
