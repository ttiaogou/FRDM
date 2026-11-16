# Gesture 强类型验证边界（步骤 4）

## 目标

把业务层传入的 `GestureIntentRequest` 明确视为不可信输入；只有完成请求级验证、计划编译和几何/路径验证后，Runtime 才能获得可交给执行协调器的类型化能力。

## 类型转换链

```text
GestureIntentRequest                 // 公开 API 输入，不代表已验证
        |
        v
GestureRequestValidationResult
        |
        +-- Rejected(reason)
        |
        `-- Accepted(ValidatedGestureRequest)
                         // Runtime 模块内部类型，私有构造
                         |
                         v
                 GestureCommand
                         // Runtime 内部创建，不可由外部模块构造/复制
                         |
                         v
             GestureCommandValidationResult
                         |
                         +-- Rejected(reason)
                         |
                         `-- Approved(ApprovedGestureCommand)
                                                  // Runtime 模块内部能力类型
                                                  |
                                                  v
                                     GestureExecutionCoordinator
                                                  |
                                                  v
                                     执行前重新校验实时上下文与几何
                                                  |
                                                  v
                                     Android 执行端口
```

## 验证职责

1. `GesturePolicyValidator.validateRequest()` 在编译前检查请求 ID、描述、执行上下文、目标区域、持续时间、双击间隔和 FeedSwipe 有效参数。
2. `GesturePolicyPipeline` 只允许 `ValidatedGestureRequest` 进入 `compile()`；原始业务请求不能直接进入编译器。
3. `GesturePolicyValidator.validateAndApprove()` 只有在完整几何校验通过时才签发 `ApprovedGestureCommand`。
4. `GestureExecutor` / `InterruptibleGestureExecutor` 只接受 `ApprovedGestureCommand`，不再接受普通 `GestureCommand`。
5. `GestureExecutionCoordinator` 在可视化和 Android 派发前再次验证动态窗口、运行代次、屏幕尺寸、轨迹和时间结构，处理编译后环境变化。

## 构造与可见性

- `ValidatedGestureRequest`、`ApprovedGestureCommand` 及其构造入口仅在 `:gesture-runtime` 模块内部可见。
- 两个类型使用私有构造函数；签发入口由 Validator 调用，架构测试约束生产代码中唯一的签发位置。
- `GestureCommand` 是供 Runtime 和 Android 可视化模块读取的只读命令视图；构造函数为 `internal`，并移除 data class 自动生成的 `copy()`，阻止其他 Gradle 模块复制/伪造命令。
- GestureCommand 对 `GestureSpec` 中的路径列表进行防御性复制和不可变包装，避免验证后通过共享可变列表改变轨迹。

## 保持不变的行为

本步骤不修改轨迹算法、点击采样、五阶段速度比例、手势时长策略、随机策略、队列行为、可视化要求或 Android 派发通道。现有失败仍以 `GestureSubmission.REJECTED` 返回；校验原因通过 Runtime 诊断记录，不把失败请求送入执行器。

## 安全边界说明

Kotlin `internal` 是 Gradle/Kotlin 模块级编译边界，私有构造和类型化令牌用于防止正常业务代码绕过 Runtime。它不是对恶意反射、Java 字节码篡改或重新打包 APK 的安全沙箱。模块外部无法通过普通 Kotlin 源码构造这些类型，但项目仍需结合架构测试和 CI 检查维持边界。
