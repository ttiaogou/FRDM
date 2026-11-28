# Freedom OCR 插件化改造说明（AutoJs6 核心移植版）

## 目标

OCR 引擎作为独立 APK 安装、启用、禁用、更新和卸载。Freedom 主 APK 只包含轻量 OCR IPC API 与插件管理/业务适配代码；ONNX Runtime、OpenCV、PaddleOCR 和模型都留在独立插件侧。下载 Freedom 主 APK 不需要同时下载 OCR 插件。

## 这次移植的核心

以 AutoJs6 的插件协议和 OCR 插件实现为基础适配 Freedom：

- 共享 `PluginInfo` Parcelable 和 OCR `IOcrPlugin` AIDL、`OcrOptions`、`OcrResult`。
- 按 `org.autojs.plugin.PADDLE_OCR` Action 与 `paddle-ocr` Category 动态发现插件，不依赖固定的插件包名/Service 类名。
- 通过插件返回的 `PluginInfo` 读取版本、引擎、变体和能力信息，并按引擎/变体规则选择插件。
- 移植 FD 图片解码和引擎缓存/释放模式；OCR 推理仍在插件进程中运行。
- 保留 Freedom 原有配置页、窗口、菜单、手势和陌陌 OCR 业务后处理，只替换 OCR 调用边界。

AutoJs6 上游源码及 MPL-2.0 声明见 `docs/third-party/AUTOJS6-PLUGIN-PORTING-NOTICE.md`。

## 安装后的组成

- **Freedom.apk**：自动化功能、轻量 OCR AIDL/Parcelable、插件发现/安装管理客户端和 `OcrManager`。不打包 OCR 引擎、ONNX Runtime、OpenCV native libraries 或模型。
- **Freedom OCR 插件.apk**：独立应用；提供 AutoJs6 兼容 OCR Service、PaddleOCR/ONNX/OpenCV 运行时。
- **模型文件**：PP-OCRv6 Small 首次识别时下载到插件自己的私有目录，完成 SHA-256 校验后使用；不进入两个 APK。

## 用户如何使用

1. 单独安装 Freedom 主 APK。
2. 打开现有配置页中的“OCR 插件管理”。
3. 从插件目录安装 OCR 插件，由 Android 系统安装器显示确认界面。
4. 插件启用后，陌陌 OCR 等现有业务自动通过插件服务识别。
5. 需要移除 OCR 时，在同一管理入口禁用插件或跳转系统卸载；Freedom 主程序不受影响。

模型首次下载需要网络；模型安装完成后可离线识别。

## GitHub Actions 产物

构建工作流不再把 Freedom 与 OCR 插件合成一个必须整体下载的 ZIP。它分别上传：

- `Freedom-debug-apk` / `Freedom-release-apk`
- `Freedom-OCR-plugin-debug-apk` / `Freedom-OCR-plugin-release-apk`

因此普通用户只使用 Freedom 时，只下载主 APK；需要 OCR 时再下载和安装插件 APK。正式 tag Release 另外分别发布主 APK、插件 APK、插件目录 JSON 和兼容性清单。

## 签名与安全

主程序与插件服务使用 `com.freedom.permission.BIND_OCR_PLUGIN` signature 权限；两个 APK 必须使用同一证书签名。正式升级应长期保留同一份 keystore。APK 下载使用 HTTPS，并校验 SHA-256、包名、versionName 和 versionCode。Android 普通应用不能静默安装插件，最终安装由用户在系统安装器确认。

## 验证状态与边界

本次可运行的静态验证包括架构边界脚本、Python 单元测试、XML/JSON/YAML 解析、workflow shell 语法、AIDL 文件一致性及两个 ZIP 清单/CRC 校验。

本地运行环境没有 Gradle 可执行程序、Gradle Wrapper 或 Android SDK，因而不能声称 Android Gradle 编译、APK 签名、设备安装、Binder 生命周期和 OCR 真机推理已通过。这些项目必须在 Android Studio/GitHub Actions 和实际 Android 设备上完成验证。
