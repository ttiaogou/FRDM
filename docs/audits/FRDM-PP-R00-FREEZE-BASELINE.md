# FRDM PP — R00 健康基线与施工前冻结

## 范围与准入

- 仓库 `ttiaogou/FRDM`，仅 PP。基线 `435406f593bc1dd512edac0af28f8f7c48feb0c2`，Git tree `f5e7d6b8a5bc334f66c4d4911975ffcab79a8ac4`。
- 基线 CI [46048752283](https://github.com/ttiaogou/FRDM/actions/runs/46048752283) 已完成且成功：架构及资源检查、单元测试、Freedom 普通及 shrink-only APK、OCR 插件 APK、CodeQL。
- 相较前次审计 c9d8c3d24fe106168c541460ad61d575fe40f46e，仅招呼筛选测试数据变更；生产源码未变。本轮仅建立工程保护，不迁移生产源码、不删除旧代码、不修改既有测试或 CI 工作流。
- 原计划 R00 是健康基线，用户本次明确指定 R00 建立冻结机制，因此在绿基线基础上增加施工前冻结；行为等价性测试建设仍属后续阶段。

## 职责和状态所有权（冻结原实现，不新增运行时）

| 领域 | 当前责任方 / 边界 |
| --- | --- |
| 无障碍入口 | FreedomAccessibilityService → AndroidAccessibilityEventForwarder → AndroidAccessibilityEventPipeline；PageObservationCoordinator 负责观察协调 |
| 任务和 UI 操作权 | TaskCoordinator / UiOperationOwnershipCoordinator；RunSessionCoordinator 协调运行生命周期 |
| 手势执行 | 现有 GestureApi、gesture-runtime、gesture-android、AndroidAccessibilityGateway；不改变参数、门禁和执行反馈 |
| 私聊、招呼 | AiReplyObservationRuntime 调度，已有私聊/招呼任务组件、遍历执行器承接对应状态 |
| 动态浏览 | AccountBrowseRuntime、AccountBrowseStateMachine、AndroidAccountBrowseWorkflow 等现有实现 |
| 平台页面、点击、消息读取 | MomoSocialAdapter 现有端口、Inspector、Reader、Processor；不改变识别和执行契约 |
| AI 与 OCR | AiReplyDraftCoordinator、现有决策/读取组件及 OCR 插件；不改变异步回调和确认 |
| 恢复 | UnifiedRecoveryTransactionManager、RecoveryEventDispatcher、RecoveryScheduler、检查点验证器 |
| 图片及拦截 | PrivateChatFollowUpPort、现有 Runtime 与 Momo 适配事务；保持私聊限定及 M04 冻结，招呼不接入 |
| 持久化 | 既有 Repository、DataStore、检查点、联系人账本与配置；不改变格式或生命周期 |

这不是全量状态读写矩阵；大文件内部的归属冲突仍留给相应的只读审计和后续批准迁移。

## 实际工程门禁

`tools/architecture/pp_r00_freeze.json` 从健康基线递归 Git tree（未截断）提取 469 个已跟踪文件的 Git blob SHA：9 个模块 src/main 下 454 个文件（含资源、Manifest、AIDL、历史占位）及 15 个构建/配置文件。无生产文件重写。

- S0（76）：核心手势、节点与事件契约、统一入口、页面观察、操作权与任务协调、统一恢复及关键 Manifest/服务配置。持续冻结，后续职责收敛不得修改其内容或调用约束。
- S1（393）：待迁移业务、平台适配、UI、数据、OCR 及构建配置的施工前完整快照。R00 至等价性门禁建立之前，修改、删除、移动或新增受控文件均失败。S1 并不是永久禁止迁移；不允许仅重算哈希放行。后续仅在用户授权的单独阶段，先建立且验证对应行为等价性及调用契约保护，再显式审查按职责迁移的定位和替代门禁；未迁移文件继续冻结。
- 原 M04 媒体点击文件/区域/顺序断言继续独立执行；不得用本快照替换或放松它。
- `test_pp_r00_freeze.py` 固定基线及清单 blob 身份，读取实际 checkout 文件字节（CI Linux LF），校验修改、缺失、增加、移动及符号链接替换；变更诊断含级别和路径。测试还覆盖合成变更与实际临时目录读取。通过已有 `python3 -m unittest discover -s tools/architecture -p 'test_*.py' -v` 自动进入架构 gate，无需新增工作流。
- 不自动重新生成清单，不以修改断言修复 CI，不修改已存在的 M04 或架构测试。清单的 Git blob SHA 用作回归比较，不是密码学授权机制。CI 不能阻止拥有写权限的人同时改门禁和清单；变更须独立审查。
- 文件内容保护仅证明固定范围源码未变，不是运行时行为等价性证明，也不证明设备运行。文件模式及根目录之外的新增文件仍需 diff/架构检查；本轮不宣称完成 R02。

## 后续准入

1. 本提交完整 CI 和 APK 通过，diff 仅为清单、测试、报告。
2. 建立相同输入、事件序列、页面快照、异步反馈、状态/操作权、数据写入及执行记录的等价性测试；正常/失败/返回续接均覆盖。
3. 任何 S1 迁移先确认对应等价性测试有效，再经用户授权更新相应保护定位；S0 与 M04 继续保护。仅有新路径或行数变化不是验收。
4. 真机回归：私聊遍历及原位置续接、招呼、动态、头像/ID、OCR 迟到回调、文本发送、图片发送、消息拦截、页面切换/操作权、失败恢复及用户停止。M04 以前的点击验收不代表新业务端到端已验收；本轮无真机环境，不能声称完成。
5. 冗余清理（旧 UI 方法、占位、缓存等）与大文件拆分均未实施。
