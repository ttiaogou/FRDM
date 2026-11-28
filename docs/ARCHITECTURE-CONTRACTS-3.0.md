# Freedom — 接口契约索引与 3.0 迁移历史

> 当前工程描述以 [ARCHITECTURE.md](ARCHITECTURE.md) 为准；施工规则以 [AGENTS.md](../AGENTS.md) 及现行手册为准。本页不是另一套施工规范。核查基线 `9919a0b30a9cfd19235d691ee4ccfee5ec664564`。

## 现行契约与证据索引

| 契约范围 | 当前对应证据 |
| --- | --- |
| 模块依赖及物理源码 | [现行架构](ARCHITECTURE.md)；settings.gradle.kts 和模块 Gradle 文件；R09-A |
| 事件与节点边界 | UnifiedAccessibilityEntry、平台中立快照与现有 Gateway；Service 原始回调转发；现行 02/03 分片 |
| 页面、任务、UI 操作权 | PageObservationCoordinator、RunSessionCoordinator、TaskCoordinator、UiOperationOwnershipCoordinator、UiWriteAuthorization；R09-C |
| 业务状态与端口 | 现有 Feature/Runtime 与平台适配端口；R07/R08 所有权证据及 R09-G |
| 恢复与异步隔离 | 既有共享恢复链、运行代次、检查点验证；R09-D |
| 手势及输入 | [手势边界](GESTURE-MODULE-BOUNDARIES.md)、现行 04–09 分片 |
| AI、消息及持久化 | 现行 10 分片及实际 Feature/Repository；R01/R02 行为证据 |
| OCR IPC | [OCR 插件架构](ocr/OCR-PLUGIN-ARCHITECTURE.md) |

实际冻结证据为 [R01](audits/FRDM-PP-R01-CONTRACT-FREEZE.md)、[R02](audits/FRDM-PP-R02-BEHAVIOR-REGRESSION.md)、`tools/architecture/pp_r01_contracts.json` 和相应测试/精确迁移证据，不能用本文替代。

## 与旧 3.0 目标的区分

- 单 :app、共享源码、旧类依赖和历史目标目录：已不代表现行九模块物理工程，不能据此重建组件。
- 平台中立边界、状态唯一、节点生命周期、业务/数据契约和行为保护：仍需按现行规范、现有源码与冻结测试核实；不得从历史目标推断已实现或授权新增。
- 旧阶段的 AI 只读、未接入模型/发送、旧验证限制：仅为当时状态。当前存在草稿及发送事务、OCR 插件、自动化测试和 APK 交付；真机仍待验收。
- 历史报告有追溯价值，原文保留；若发现与现行规则/代码冲突，依 AGENTS 记录，不反向修改现行规范。

## 历史资料

历史正文已移至 [归档副本](archive/legacy/ARCHITECTURE-CONTRACTS-3.0.original.md)；[固定提交原文](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/ARCHITECTURE-CONTRACTS-3.0.md) 可核对。历史目标、旧路径、旧验证结果均不构成现行施工依据。其他历史证据见 [归档索引](archive/README.md)。
