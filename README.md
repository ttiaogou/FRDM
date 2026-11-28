# FRDM / Freedom

Freedom 是 Android 无障碍辅助交互项目，包含独立的业务 Feature、平台适配、任务及 UI 操作权协调、手势执行和可选 OCR 插件。此页是项目导航，不是施工授权。

## PP 当前工程入口

- [AGENTS.md](AGENTS.md)：唯一项目级施工入口；先实际读取现行第 0 节，再按任务阅读相关规范分片。
- [现行架构](docs/ARCHITECTURE.md)：九个物理 Gradle 模块、运行链路和职责边界。
- [接口与契约说明](docs/ARCHITECTURE-CONTRACTS-3.0.md)：现行边界索引与 3.0 迁移历史的区分。
- [手势模块](docs/GESTURE-MODULE-BOUNDARIES.md) · [架构检测与 CI](docs/GESTURE-ARCHITECTURE-GUARDRAILS.md)。
- [OCR 插件架构与安装](docs/ocr/OCR-PLUGIN-ARCHITECTURE.md) · [OCR 设备验收](docs/ocr/OCR-PLUGIN-DEVICE-ACCEPTANCE.md)。
- [R09-G 工程验收](docs/audits/FRDM-PP-R09-G-FINAL-ENGINEERING-ACCEPTANCE.md) · [G01-A 文档引用审计](docs/audits/FRDM-PP-G01-A-DOCUMENT-INTEGRITY-AUDIT.md)。

历史修复与审计记录不是当前施工任务。需要追溯时查阅 [历史文档归档索引](docs/archive/README.md)。当前规范与代码不一致时，按照 AGENTS.md 记录冲突，不以旧设计重建架构。

## 构建与交付

JDK 17、Gradle 8.9、Android SDK（compileSdk 35）；实际 CI 以 [.github/workflows/android-build.yml](.github/workflows/android-build.yml) 为准。

    python3 -m unittest discover -s tools/architecture -p 'test_*.py' -v
    python3 tools/architecture/verify_boundaries.py --root .
    gradle :app:assembleDebug
    gradle :app:testDebugUnitTest

当前 CI 独立执行架构、单元测试、APK、OCR 和 CodeQL 任务。普通、精简主 APK 与 OCR 配套 APK 在同一交付 job 校验签名后分别上传。安装 OCR 时选同一 run 的配套 APK；debug 不代表正式升级签名合同。主 APK 不包含 OCR 引擎/模型；插件独立安装，固定模型随插件 APK 交付。

[上一验收基线 G01-A CI](https://github.com/ttiaogou/FRDM/actions/runs/46134802486) 全部成功；R09-F F01–F12 真机验收仍未执行。不得将工程构建结果当作设备实测。
