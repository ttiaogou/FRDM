# FRDM P1｜无障碍服务重连后的 Activity 身份取证审计

- 审计范围：`ttiaogou/FRDM` 的 **P1**；基线 HEAD：`986cfafce1bc3ed7ddd1094452ebd2f2743ca942`。
- 日期：2026-11-22。
- 性质：源码与既有真机日志的根因审计；**不调整生产算法、权限要求、页面指纹、租约、安全门禁、手势或恢复节奏**。
- 注意：Android 的 `onServiceConnected()` 不是目标应用的 `TYPE_WINDOW_STATE_CHANGED` 事件，系统也不保证重放正在显示的 Activity 事件。不能把“没有新事件”误写为“事件处理器丢弃了已到达的事件”。

## 1. 结论（区分两种测试）

**A. 手工关闭/重新开启 Freedom 无障碍服务：Activity 事件并非完全缺失。** 用户真机记录 `无障碍服务重连测试53513.txt` 的 15:34:28.041 显示 `TYPE_WINDOW_STATE_CHANGED`，来源 `com.immomo.momo`，类名 `com.immomo.momo.maintab.MaintabActivity`。同次重连在 15:34:26.104 更早出现 `任务协调[REJECTED]`、`activeTask=NONE`、`runGeneration=2`；私人消息任务无法获取 UI 租约。该场景实际阻塞点首先是**业务租约与运行代次**，不能定性为无障碍 Activity 事件根本没有到达。后续仍需独立审计进程级 TaskCoordinator 与服务级 RunSessionCoordinator 之间的代次关系；本任务禁止顺手修改这一独立问题。

**B. ADB 崩溃后不切换陌陌页面、且无 Usage Access：缺乏可接受的 Activity 证据。** `杀死后台测试.txt` 的 15:42:32.252、15:43:17.794 出现 `ACTIVITY_EVIDENCE_PENDING reason=USAGE_ACCESS_NOT_GRANTED`；该段日志没有新的 `TYPE_WINDOW_STATE_CHANGED`，并持续 `APP_NOT_READY`。2026-11-22 16:02 的授权后对照日志则出现 `ACTIVITY_EVIDENCE_VERIFIED source=USAGE_ACTIVITY_RESUMED` → `STARTING->READY` → `RECOVERY_EXECUTION_READY`，随后真实滑动成功。说明授权后的额外取证路径有效，但**不证明 Android 每次都必须授予 Usage Access 才能恢复**：若后来有新鲜、可信的目标 Activity 事件，现有事件通道可以更新身份。

## 2. 入口、状态与数据流

`FreedomAccessibilityService.onServiceConnected()`
→ `applyTargetPackageFilter()`
→ `AndroidAccessibilityServiceRecoveryCoordinator.onServiceConnected()`
→ AI 模式 `clearActivityContext()`
→ `AndroidWindowContextTracker.clearActivityClass()`
→ `RunSessionCoordinator.onAccessibilityServiceConnected()`，读取任务和检查点
→ 再次应用订阅
→ `AndroidForegroundActivityRecoveryCoordinator.restart()`（Usage Events 的**只读**身份验证）
→ 若目标应用仍在同一 Activity，等待新的有效 Android 事件或有授权的只读 Usage 证据
→ `AndroidAccessibilityGateway` 的 READY、页面路由、双快照及 UI 操作权分别验证
→ 执行恢复。

**真实 Android 事件路径：**

`AccessibilityService.onAccessibilityEvent()`
→ `AndroidAccessibilityEventForwarder.accept()`，先校验目标包名并通知窗口切换
→ `AndroidAccessibilityEventNormalizer.normalize()`
→ `AndroidAccessibilityEventPipeline.processNormalized()`
→ 对来自目标包的 `TYPE_WINDOW_STATE_CHANGED`，仅在 `isActivityWindowClassName(className)` 通过时写入 `AndroidWindowContextTracker`
→ `RunSessionCoordinator.dispatchActivityTransition()`、页面观察和业务重检。

事件子类型职责：`TYPE_WINDOW_CONTENT_CHANGED`、`TYPE_VIEW_SCROLLED` 可以触发结构观察，但不能凭控件类名、节点树或单次滚动推断 Activity；`TYPE_WINDOWS_CHANGED` 只提供窗口变化信号，本实现不据此制造 Activity 身份。

## 3. 代码证据与边界

| 路径与代码区间 | 已确认事实 | 审计判断 |
| --- | --- | --- |
| `app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt:350-390` | 重连时重新订阅、重建运行时，显式启动只读取证；没有调用 Android API 主动请求重放旧 Activity 事件 | 缺失的平台事件不能由 `onServiceConnected` 自动补发 |
| `app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityServiceRecoveryCoordinator.kt:38-63` | AI 重连主动清空 Activity 运行时上下文，再连接业务运行时 | 有意不复用断连前可能过期的身份 |
| `app/src/main/java/com/freedom/app/AppCompositionRoot.kt:246-302` | Activity tracker 和 recovery coordinator 属于服务作用域；恢复取证依赖 `AndroidUsageActivityEvidenceReader.probe()` | 没有发现第二条“无授权且无需新事件”的可信 Activity 探针 |
| `app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilitySubscriptionPolicy.kt:25-64,71-85` | AI 模式明确订阅 `TYPE_WINDOW_STATE_CHANGED`，并仅允许目标包的普通事件进入 | **没有证据表明订阅规则漏掉该事件** |
| `app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventForwarder.kt:22-69` | 合格的目标窗口事件在继续进入 pipeline 前触发 windowTransition；实际消费仍需运行请求和模式 | 有事件时有合法入口；系统未发事件则不能从此处产生身份 |
| `app/src/main/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipeline.kt:132-175,230-248` | Activity 类名更新发生在普通事件日志去重之前；`TYPE_WINDOW_STATE_CHANGED` 且类名需匹配 Activity 规则 | 未发现“普通去重吞掉 Activity 身份更新”的证据 |
| `app/src/main/java/com/freedom/adapters/accessibility/android/AndroidWindowContextTracker.kt:19-55,62-73` | 清空旧类名；只接受合格窗口 Activity 事件或有窗口/代次绑定的恢复证据 | 防止从旧节点、历史窗口臆测 Activity |
| `app/src/main/java/com/freedom/adapters/accessibility/android/AndroidUsageActivityEvidenceReader.kt:77-141` | 无 Usage Access 返回 `USAGE_ACCESS_NOT_GRANTED`；对有权限证据还二次核对前后 windowId 和 generation | 失败关闭是现有安全行为，不建议绕过 |
| `app/src/main/java/com/freedom/adapters/accessibility/android/AndroidForegroundActivityRecoveryCoordinator.kt:25-104` | 不满足可靠取证则退避保持只读；有两次匹配的 Activity/window/generation 才回填身份 | 权限缺失且无新事件时会持续等待，不会盲目执行 |

### 审计结果分类

- **已证实**：Service 重连会清空旧 Activity 身份；单纯滚动/页面内容事件不能补齐；Usage Access 缺失时，只读恢复探针返回 Pending；真实的目标 Activity 窗口状态事件可以更新身份。
- **已证实的旁支故障**：手动关闭/重开无障碍服务的一次真机测试里，私人消息任务在 Activity 事件出现前已经因租约拒绝而暂停。
- **未证实**：Android 明明回调了可信 `TYPE_WINDOW_STATE_CHANGED`，但 Freedom 在回调入口将其丢弃。现有源码、测试及用户日志未支持这一指控。
- **平台限制**：重连时若目标应用页面静止，没有可靠的现时 Activity 事件且没有其他可信取证来源，就不能保证无需 Usage Access 的自动恢复；不能用控件 ID、滚动事件或旧页面指纹代替 Activity 身份安全条件。

## 4. 测试与补强

现有相关测试：

- `AndroidAccessibilityEventPipelineTest`：验证 AI target window state 事件可到达业务观察；Activity 更新在可见日志去重之前执行；有单后缀的 `PersonalProfileActivityK` 被接受。
- `AndroidAccessibilitySubscriptionPolicyTest`：验证订阅及目标包回调隔离。
- `AndroidWindowContextTrackerTest`：验证上下文清理、Usage 恢复 windowId/generation 绑定及新 Activity 事件覆盖。
- `AndroidForegroundActivityRecoveryCoordinatorTest`：验证双样本、window 变化作废、Usage Access 不可用时只读退避。
- `AndroidAccessibilityServiceRecoveryCoordinatorTest`：验证 AI 重连先清空 Activity 后连接 runtime。
- `ActivityRecoveryDeviceFixtureTest`：仅针对剪裁后的真机样本做约束检查，不能证明所有厂商都能生成窗口状态事件。

**本轮唯一测试修改**：`app/src/test/java/com/freedom/adapters/accessibility/android/AndroidAccessibilityEventPipelineTest.kt` 新增 `clearedActivityAfterReconnectRequiresANewTargetWindowStateEvent`，在同一边界模拟清空身份后，验证内容/滚动事件、控件类窗口事件、非目标包窗口事件均不恢复身份，而真正的目标 Activity 状态事件可恢复。无需修改生产代码，也不放宽任何断言。

**建议 CI 验证**：`:app:testDebugUnitTest`（包含上述测试）、`:core:test`、`:business:test`、`:gesture-core:test`、`:gesture-runtime:test`，完整 `:app:assembleDebug`、`:ocr-plugin:assembleDebug`，以及原有 Python 架构检查器、资源 XML 和 CodeQL。真实设备测试仍须分别覆盖“原页面静止无权限”“手动真实 Activity 切换无权限”“授予 Usage Access 后静止页面”“无障碍手动关/开且同进程任务租约”四种情况。

## 5. 后续最小实施建议（本轮不实施）

1. **优先单独修复租约代次**：核验旧 Service 的 RunSessionCoordinator 与进程级 TaskCoordinator 不一致时的重连，保证旧 capability 不能复用、新 generation 可合法接管，不能放宽代次检查。该问题来自不同链路，不应混入 Activity 事件修复。
2. **保留两种 Activity 取证路径**：收到真正可信的窗口状态事件则使用该事件；如果没有事件且已获用户授权，可用 Usage Events 双样本验证。不向系统伪造 window-state 事件。
3. **无授权且无新可靠 Activity 事件时**：维持只读恢复、定期复核与明确诊断，不通过重放旧 className、猜测节点、降低 READY 门槛或重试危险手势求进展。
4. 如将来产品要求“无 Usage Access 且应用界面完全静止也保证恢复”，需要**独立论证新可信 Activity 证据源**和设备兼容性；现有无障碍 API 不保证提供所需信息。

## 6. 交付与未验证

- **生产代码**：0 改。
- **测试**：1 个既有测试文件新增 1 个针对性回归用例；不删改原有用例。
- **文档**：新增本审计报告 1 个文件；未删除文件。
- **本地执行环境限制**：GitHub 远端读取、提交，未能在执行环境从 `github.com` clone 仓库（DNS 无法解析）；没有本地 Git 工作区，不能实际执行 Gradle/JDK/Android SDK 的单测与完整 APK 构建，也不能报告本地 `git status`。通过 P1 push 触发原有 GitHub Actions，全流程结果须以该提交对应的 CI 为准；未完成不可宣称通过。
- **安全与兼容性**：全部生产源码/构建脚本/CI/页面指纹/手势/安全断言/恢复节奏保持不变。
