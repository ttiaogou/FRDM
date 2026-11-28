# Gesture — PP 现行模块边界与依赖

> 核查基线 `9919a0b30a9cfd19235d691ee4ccfee5ec664564`；全工程九模块与规范入口见 [ARCHITECTURE.md](ARCHITECTURE.md) / [AGENTS.md](../AGENTS.md)。本页不是算法或架构变更授权。

## 当前编译归属

| 模块 | 物理目录 | 直接项目依赖 | 职责 |
| --- | --- | --- | --- |
| gesture-api | gesture/gesture-api/src/main/kotlin | core (api) | 唯一公开 GestureApi、业务意图 |
| gesture-core | gesture/gesture-core/src/main/kotlin | core (api) | 几何及内部运动模型 |
| gesture-runtime | gesture/gesture-runtime/src/main/kotlin | core (api), gesture-api (api), gesture-core (implementation) | 策略、请求/命令验证、编译、队列与执行协调 |
| gesture-android | gesture/gesture-android/src/main/kotlin | core, gesture-runtime (implementation) | Android 派发及可视化 |

business 使用自己的源码根，只依赖 core 和 gesture-api；app 为 Android 平台与组装层，依赖 runtime/android 不代表普通 Adapter 可以绕过公开 API。OCR 两模块独立于 Gesture；四个 Gesture 模块本身不生成独立 APK，也不另建 AccessibilityService。

实际源文件已位于各自模块，不再由 app/src/main/java 共享编译。旧 GesturePolicyPort.kt 的无声明占位位于 core/src/main/kotlin，显式排除；保留 R09-A/B/G 的精确保护及旧排除路径，不恢复旧接口。

## 执行与验证边界

GestureApi → GesturePolicyPipeline → GesturePolicyValidator 请求验证 → 既有 GesturePolicyManager → GestureCommandCompiler → validateAndApprove → GestureExecutionCoordinator → AndroidGestureDispatcher。运行代次、窗口、坐标快照、租约和可视化继续由既有验证及执行链处理。业务不构造原始命令、不获得平台派发能力，手工校准等仍受既有独立门禁。Android / Social Adapter 暂未独立成 Gradle 模块，保留现有源码检查，不据此新建模块。

详细专业说明仍各有独立用途：[策略](GESTURE-POLICY-MANAGER.md)、[编译管线](GESTURE-RUNTIME-PIPELINE.md)、[强类型验证](GESTURE-STRONG-TYPED-VALIDATION.md)、[FIFO 执行队列](GESTURE-EXECUTION-QUEUE.md)、[算法覆盖](GESTURE-ALGORITHM-COVERAGE.md)、[CI 检测](GESTURE-ARCHITECTURE-GUARDRAILS.md)。旧参数与行为保护以实际代码、现行规范及冻结测试为准。当前文档更新不改变任何参数。

## 历史资料

历史正文已移至 [归档副本](archive/legacy/GESTURE-MODULE-BOUNDARIES.original.md)；[固定提交原文](https://github.com/ttiaogou/FRDM/blob/9919a0b30a9cfd19235d691ee4ccfee5ec664564/docs/GESTURE-MODULE-BOUNDARIES.md) 可核对。历史目标、旧路径、旧验证结果均不构成现行施工依据。其他历史证据见 [归档索引](archive/README.md)。
