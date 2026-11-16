# Freedom 架构自检与整改基线（2026-11-15）

## 目的与判定原则

本文件记录架构自检框架首次运行的基线。规范依据为项目 `AGENTS.md` 导航入口及其要求读取的 `docs/agent/parts/00-07`。检测器区分：

- **硬性违规**：依赖方向、状态所有权、平台入口职责、规范原文完整性等违反明确约束的项目。
- **结构性风险提示**：大文件/大类只触发人工复核，不因行数本身判定违规。
- **未验证项**：静态扫描无法证明运行时行为，必须由 Kotlin/Gradle 测试补充。

## 首次检测结果

首次运行 `python3 tools/architecture/verify_boundaries.py --show-advisories`：**13 项硬性问题、11 项结构性风险提示**。

### 硬性问题

1. `docs/agent/parts/00-07` 拼接结果与 `docs/agent/source/AGENTS.original.md` 不一致。归档 42,890 bytes，片段拼接 42,894 bytes。差异不只是空白：还涉及手势并发相关文字片段的缺失/替换。受保护规范未被自动修改。
2. Adapter/平台实现直接 import Data 实现：
   - `MonitorOverlay.kt` → `MonitorStoreRepository`
   - `MomoChatMessageOcrProcessor.kt` → `OcrReviewStore`
   - `MomoChatSessionSampleStore.kt` → `RunDiagnosticsManager`
   - `MomoSayHiTraversalCheckpointStore.kt` → Data checkpoint 类型/实现
   - `MomoSocialAdapter.kt` → `RunDiagnosticsManager`、`LocalConversationRepository`
   - `BootRecoveryReceiver.kt`、`MonitorKeepAliveService.kt` → `MonitorStoreRepository`
3. `FreedomAccessibilityService` 创建 `RunSessionCoordinator` 并 import Data 实现，违反 AGENTS 3.1 与 4.5。
4. `MainActivity` import/创建 Data 实现，违反 UI 只呈现状态、提交意图以及 AppCompositionRoot 负责组装的边界。
5. `AndroidAccountBrowseWorkflow.kt` 直接引用 `AccountBrowseSessionState`，违反 AccountBrowse 状态唯一所有者规则。

### 结构性风险提示（不是自动违规）

- `MomoSocialAdapter.kt`（5,589 行）
- `AiReplyObservationRuntime.kt`（5,114 行）
- `AndroidAccountBrowseWorkflow.kt`（2,748 行）
- `MainActivity.kt`（2,442 行）
- `MomoChatSessionSampleStore.kt`（1,691 行）
- `AndroidAccessibilityGateway.kt`（1,545 行）
- `RunDiagnosticsManager.kt`（1,145 行）
- `MomoChatMessageReader.kt`（1,099 行）
- `ConfigurationHome.kt`（1,091 行）
- `NodeInspectorDialog.kt`（1,126 行）
- `MonitorOverlay.kt`（1,076 行）

## P1-P3 整改顺序

### P1：先恢复硬边界

1. 建立 `app/AppCompositionRoot`，将 Service 与 Activity 的 Data/Runtime 具体实现创建迁出；Service 只保留生命周期、系统回调、短期窗口能力和事件转交。
2. 将 AccountBrowse 的可变状态收回 Feature Runtime/Controller；Adapter 仅提交命令并消费结果。
3. 为 Adapter/Data 交互定义或复用 Application Port；Data 实现只在组装入口创建。
4. 将 Activity 中 OCR、导出、仓库访问流程迁移到 Use Case/Controller，Activity 只接系统 Activity Result 和 UI 导航。

### P2：拆解聚合类，保留状态所有权

1. `MomoSocialAdapter` 按 Browse、Chat、Message、Session、Page Inspection 拆内部实现；可保留门面组合，不再集中承载所有实现。
2. `AndroidAccountBrowseWorkflow` 按导航、滚动、弹窗、接管、恢复拆执行协作者；不得让协作者持有/修改 Feature Session。
3. `AiReplyObservationRuntime` 按消息观察、会话遍历、人工接管、Received Greetings、启动就绪拆流程协作者；AI Reply 状态所有权仍集中在 Feature。
4. `MomoChatSessionSampleStore` 分离采集、数据映射、持久化、归档；Android 采集留 Adapter，持久化留 Data。

### P3：内部协作者拆分

- `AndroidAccessibilityGateway`：保留 Gateway 门面，拆 Reader、NodeAction/gesture dispatch、截图/窗口上下文等内部协作者。
- `RunDiagnosticsManager`：拆 Archive、Report、Recovery、Retention 等协作者，保持 Data 层边界。
- `MonitorOverlay`：拆 Overlay Host、Menu、触摸交互、Watchdog/状态协调。
- `ConfigurationHome`、`NodeInspectorDialog`：按 UI 子功能/状态呈现拆分。

## 模块策略

当前仍遵循 AGENTS 1.1：保持单主 APK 和模块化单体；不新增 Gradle Module。允许在明确收益和边界稳定后再评估。允许在逻辑包内部拆分、合并或缩减实现，但不得删减 AGENTS 规定的目标组件/职责。

## 自检入口

```bash
python3 -m unittest discover -s tools/architecture -p 'test_*.py' -v
python3 tools/architecture/verify_boundaries.py --root . --show-advisories
```

检测器应在 CI 的 architecture job 中先于 Android 构建运行。硬性问题未清零时必须失败；行数提示不阻断构建。

## 本轮实施状态

- 已扩展静态检查器：AGENTS 原文完整性、全源码 Feature/Adapter/Application/UI 依赖扫描、Service/Activity 组装职责、AccountBrowse 状态泄漏、大文件风险提示。
- 现有 CI 已有独立 architecture job，会自动执行 Python 单元测试和架构检查；无需新增 Gradle Module。
- 本轮尚未迁移运行时代码和 Data 实现，首次检查的硬性问题仍会使架构 gate 失败。这是有意暴露的真实基线，不以放宽规则伪造通过。
- Kotlin/Gradle 构建未在本地执行；需在具备 Gradle/Android SDK 的环境中验收。
