# Gesture Architecture 自动审查与 CI 门禁

> PP 现状基线：9919a0b30a9cfd19235d691ee4ccfee5ec664564。施工依据仍为 [AGENTS.md](../AGENTS.md)，完整模块图见 [现行架构](ARCHITECTURE.md)。本文记录已有门禁，不增加规则。

## 目标

本项目的手势入口、审批类型、命令构造和 Android 派发具有明确的所有者。架构检查用于在代码评审/CI 阶段尽早发现意外依赖和绕过路径，避免后续改动逐渐破坏既定边界。

## 本地执行

在仓库根目录运行：

```bash
python3 -m unittest discover -s tools/architecture -p 'test_*.py' -v
python3 tools/architecture/verify_boundaries.py --root .
```

静态检查只使用 Python 标准库，不需要先安装 Gradle 或 Android SDK。

## 自动检查范围

`tools/architecture/verify_boundaries.py` 当前强制检查：

1. **Gradle 模块图**：确认九个模块存在，并校验 `:business`、`:gesture-api`、`:gesture-core`、`:gesture-runtime`、`:gesture-android` 的项目依赖方向；`:gesture-runtime` 对 `:gesture-core` 必须使用 `implementation`，不得通过 `api` 向外暴露几何算法实现。
2. **唯一公开 API**：`GestureApi` 只能在 `:gesture-api` 中声明一次；旧 `GesturePolicyPort` / `InterruptibleGesturePolicyPort` 不得重新声明。
3. **Business 边界**：Feature 不得导入 Android、Adapter、Data 或 Runtime 层，也不得引用原始命令、Android 执行器或 Runtime 验证令牌。
4. **Adapter 消费边界**：Momo、AccountBrowse Android 工作流和校准 Host 通过 `GestureApi` 提交意图，不得构造原始命令、引用审批令牌或直接派发。
5. **强类型能力边界**：`ValidatedGestureRequest` / `ApprovedGestureCommand` 仅能出现在 Runtime；签发调用只能出现在 `GesturePolicyValidator`。
6. **命令构造所有权**：生产 `GestureCommand` 与 `GestureSpec` 的构造集中在 `GestureCommandCompiler`（`GestureSpec` 的数据类型声明仍位于 Accessibility API 契约文件）。
7. **派发通道**：`.dispatchGesture()` 端口调用仅存在于 Coordinator、Gateway 委托和 Android Dispatcher；实际 `service.dispatchGesture()` 只允许出现在 `AndroidGestureDispatcher`。

Kotlin `ArchitectureBoundaryTest` 继续保留对视觉化门控、队列、验证顺序、手势坐标动作以及其他领域边界的回归检查。静态 Python 检查和 Kotlin 架构测试互相补充，不互相替代。

## GitHub Actions 顺序

实际工作流 [android-build.yml](../.github/workflows/android-build.yml) 包含五个 job：

    arch-guard（Python unittest / 硬检测 / Android XML 校验）
      ├─ test（Gradle 单元测试及报告）
      ├─ build-freedom-apk（普通、shrink-only、配套 OCR；签名校验后上传）
      ├─ build-ocr-plugin-apk（独立 OCR 测试/构建）
      └─ codeql（两个 app 及测试源码的编译追踪与分析）

四个下游 job 均依赖 arch-guard。G01-A 的文档引用采集测试也由原 unittest discovery 执行，只报告候选；不将历史引用自动当成违规。R00/R01/R02、M04、后续精确迁移保护、R09 权限/恢复等检测仍然有效，不由本文替换。CodeQL 独立 stub 保留，实际分析属于主工作流。

[基线 CI 46134802486](https://github.com/ttiaogou/FRDM/actions/runs/46134802486) 213 Python 测试及五个 job 全部成功；真机未验收。

## 检查器的边界与限制

- 这是针对当前项目约定的源码/Gradle 静态检查，不是 Kotlin 编译器、Gradle 依赖解析器或安全沙箱。
- 它通过注释/字符串屏蔽后的 Kotlin 源码扫描识别约定调用点；复杂反射、动态代码生成、混淆后的运行时代码或恶意修改构建脚本不属于其证明范围。
- Gradle 实际类型可见性和 Kotlin `internal` 仍由编译器检查；Android Framework 调用行为仍需 Gradle 构建和设备验证。
- 未经用户明确授权不得添加模块、调整职责或新增执行入口。经授权的变化须保留既有保护、记录精确迁移证据并验证；不得为了让检查通过而放宽边界或把实现整体加入白名单。
- CI 运行成功只能证明本次代码通过了列出的自动检查和构建任务，不证明真机系统回调、页面识别或长期运行行为已通过现场验证。

## 当前剩余边界

- Feature 业务源码已由 `:business` 编译，依赖 `:core` 和 `:gesture-api`。
- Android / Social Adapter 消费者仍由 `:app` 编译；当前以 `GestureApi` 契约和架构检查限制其访问，尚未形成独立 Adapter Gradle Module。
- 多模块 Gradle 编译边界用于约束正常工程依赖，不是抵御恶意改包、反射或另建执行入口的绝对安全边界。
