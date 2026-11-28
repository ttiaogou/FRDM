# 正文容器版 CI 共享契约修复

## 基线

本轮严格以上一轮交付的 `Freedom-full-source-text-container.zip` 为基线。
失败任务：https://github.com/ttiaogou/FRDM/actions/runs/44237689998/job/134577706291
CI 源码提交：a35740d1484e066fe66bf3a127613ff589ceaa6b。

## 确认的失败原因

CI 在 `:app:compileDebugKotlin` 报：
`MomoChatOcrCandidateMapper.kt:21:20 Unresolved reference 'classificationOnly'`。
已读取上述提交的两个实际源文件，确认映射器引用了 classificationOnly，但仓库的 ChatMessageOcrTarget 没有该字段。
上一轮完整源码包含该共享字段，但增量包没有携带 ChatMessage.kt，导致覆盖后共享模型与调用方可能不一致。这是上一轮增量交付的依赖遗漏。
本次不是通过删除字段保护、修改 CI 或清理测试来规避错误。

## 两份源码的实际修复

1. `app/src/main/java/com/freedom/features/aireply/domain/message/ChatMessage.kt`
   - 覆盖包现在完整携带该共享模型，包含缺失的 classificationOnly 默认兼容字段。
   - 在已有字段基础上增加公开只读属性 `isMessageTextTarget`，值严格等于 `!classificationOnly`。
   - 普通正文目标的默认行为不变；旧分类目标仍不得变成消息正文。当前 Reader 不创建分类探针。
2. `app/src/main/java/com/freedom/adapters/social/momo/MomoChatOcrCandidateMapper.kt`
   - 通过共享模型的 `isMessageTextTarget` 契约判断正文用途，不再直接访问 classificationOnly 字段。
   - 判断与上一轮逻辑完全等价，没有新增卡片分类或生成前置条件。

正文容器正向采集、双方消息方向、消息顺序、OCR 范围、模型策略、历史查询、输入节奏和发送行为均保持上一轮实现。
原测试、架构断言、CI、Gradle 配置、项目规范均未修改，未删除原项目文件。

## 实际验证

使用 Kotlin 2.0.21、JVM target 17 按模块分别输出独立 JAR，而非将生产代码放在同一编译单元中验证：

- core：52 份生产源码编译通过。
- gesture_api：3 份生产源码编译通过，依赖独立 core JAR。
- business_ci_before：以 CI 提交中缺字段的实际 ChatMessage.kt 替换模型，业务模块自身可编译。
- mapper_ci_before：映射器只依赖旧 business JAR，稳定复现 `Unresolved reference classificationOnly`。
- business：63 份修复后业务源码编译通过。
- app_pure：11 份映射器及相关纯 JVM 适配器源码依赖修复后的独立 business JAR 编译通过。
- business_tests：46 份测试源码按业务模块 friend 配置编译通过。
- app_tests：17 份测试源码按 App 纯 JVM 模块 friend 配置编译通过。
- JUnit：63 个测试类，540 项全部通过。
- Python 架构测试：12 项通过。
- `python3 tools/architecture/verify_boundaries.py --root . --show-advisories`：通过。
- 所有既有测试文件逐字节保留，没有删除测试或放宽安全断言。
- 两个 ZIP 校验 CRC、唯一相对路径、原文件保留、覆盖后一致性。

## 验证边界

本轮分模块编译验证了此次失败的共享契约和映射器调用，不等同于完整 Android Gradle/SDK APK 构建。
未重新触发远端 CI，未推送或修改 GitHub 仓库，未进行真机输入验证。因此不能宣称完整 Android CI、APK 或手机输入已经通过。

## 交付与使用

- `Freedom-full-source-text-container-ci-fixed.zip`：最新完整源码。
- `Freedom-text-container-ci-modified-files.zip`：仅本轮两份实际修改的源码及本说明。
- 两个包均保留 `Freedom/` 顶层及原完整相对目录结构；覆盖包应解压到原 Freedom 目录的父目录后覆盖同名文件。
- 本轮覆盖包必须整体应用，特别是 ChatMessage.kt 和 MomoChatOcrCandidateMapper.kt 必须一起覆盖。
- 若此前只应用过不连续版本的增量包，建议直接使用本次完整源码，避免残留不一致的共享契约。
- 删除文件清单：无。
