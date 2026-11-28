# Freedom OCR 插件架构、安装及交付（PP）

> 核查基线：9919a0b30a9cfd19235d691ee4ccfee5ec664564。本页整合根目录 OCR 旧说明；规范及现行实现以 AGENTS.md、Gradle、Manifest 和源码为准。真机验收未完成。

## 目标与边界

Freedom 的 OCR 引擎以独立 Android APK 安装、启用、禁用、更新和卸载。主 APK 只打包轻量 OCR 插件 API（AIDL + Parcelable）、插件发现/绑定客户端和现有业务适配，不打包 PaddleOCR、ONNX Runtime、OpenCV 原生库或模型文件。

本次以 AutoJs6 的 OCR 插件协议、发现/选择机制和 Paddle OCR 插件运行时为移植基础；只把主机权限、包名、模型存储和 Freedom 的业务回调替换为本项目实现。Freedom 原有配置页、窗口、菜单、手势和陌陌消息 OCR 后处理保持由 Freedom 自己管理。

```text
Freedom.apk (~host application)
  ├─ ocr-plugin-api (small AIDL/Parcelable contract only)
  ├─ OcrPluginHost / OcrPluginInstaller / OcrManager
  └─ existing MomoChatMessageOcrProcessor
       │ query service action/category; bind AIDL
       │ transfer image via ParcelFileDescriptor
       ▼
Freedom OCR plugin.apk (independent installation)
  ├─ OcrPluginService (AutoJs6 IOcrPlugin contract)
  ├─ OcrImageFdDecoder (adapted from AutoJs6 ImageFdDecoder)
  ├─ OcrEngineHolder (adapted from AutoJs6 PpOcrV5EngineHolder)
  ├─ PaddleOCR + ONNX Runtime + OpenCV
  └─ OcrModelManager
       ├─ APK assets/models/ppocrv6_small/ (build-time pinned model)
       └─ plugin-private storage / noBackupFilesDir/ocr/models/
            └─ ppocrv6_small/
```

## 移植来源

来源版本、许可证及适配说明见 [`docs/third-party/AUTOJS6-PLUGIN-PORTING-NOTICE.md`](../third-party/AUTOJS6-PLUGIN-PORTING-NOTICE.md)。关键移植点：

- AutoJs6 `plugin-api/common-plugin-api` 的 `PluginInfo` 和公共能力键。
- AutoJs6 `plugin-api/paddle-ocr-api` 的 `IOcrPlugin`、`OcrOptions`、`OcrResult`、OCR 能力键和图片传输字段。
- AutoJs6 `OcrPluginHost` / `OcrPluginSelection` 的 Action + Category 服务发现、PluginInfo 读取、版本兼容和 metadata 驱动的插件选择。
- AutoJs6 Paddle OCR 插件的服务接口、引擎缓存/释放策略和 FD 图片解码方式。

没有移植 AutoJs6 整个应用插件中心，也没有替换 Freedom 的设置页面和交互布局。Freedom 继续使用配置页原有入口，以对话框提供 OCR 插件列表、启用/禁用、更新、卸载和详情操作。

## 插件发现与通信

主机和插件共用 `:ocr-plugin-api` 模块。插件按 AutoJs6 OCR 约定声明：

- Action：`org.autojs.plugin.PADDLE_OCR`
- Category：`paddle-ocr`
- Binder API：`org.autojs.plugin.paddle.ocr.api.IOcrPlugin`

主机通过 `PackageManager.queryIntentServices()` 查找服务，不再把某一个插件包名或 Service 类名作为发现条件。绑定后调用 `getInfo()`，读取插件名称、版本、engine、variant、能力和最低主机版本要求；选择时优先适配的 engine，再按 AutoJs6 的 variant / recommended-default / 包名规则选择默认实现。

图片使用 `ParcelFileDescriptor` 传输，避免将 Bitmap 或图像字节数组直接放进 Binder transaction。插件负责在自己的进程解码图像、初始化/复用 OCR 引擎和返回识别结果。

Freedom 与 AutoJs6 的唯一重要安全协议适配是 Service 权限：

- Freedom 主程序声明 `com.freedom.permission.BIND_OCR_PLUGIN`，保护级别为 `signature`。
- Freedom OCR 插件声明 uses-permission，并在 exported Service 上要求该权限。
- 主程序和插件 APK 必须使用同一证书签名。普通第三方应用不能仅通过声明公开 Action 冒充可信插件。

`:ocr-plugin-api` 只含 IPC 数据结构和接口声明；它不依赖 PaddleOCR、ONNX Runtime、OpenCV 或模型。`:app` 依赖该轻量 API 模块，但不依赖 `:ocr-plugin`。

## 插件安装、更新和卸载

插件目录由主 APK 的 `OCR_PLUGIN_CATALOG_URL` 提供，目录采用版本化 JSON，按 packageName 列出插件信息及发行版本。安装器会：

1. 从 HTTPS 目录读取插件列表，并在网络不可用时使用上次缓存目录。
2. 选择当前设备 ABI 对应 APK（若目录只提供 universal 包则使用 universal）。
3. 将 APK 下载到 Freedom 私有缓存，限制大小并校验 SHA-256。
4. 使用 Android PackageManager archive metadata 校验 APK 包名、versionName 和 versionCode。
5. 通过 Android 系统安装器交给用户确认安装；不会尝试静默安装。

插件管理页从设备已安装的 OCR Action/Category 服务中发现插件，并与目录合并展示。用户可启用/禁用（主机偏好状态）、安装/更新或跳转系统卸载界面。卸载插件不会卸载 Freedom 主程序；卸载 Freedom 也不要求将 OCR 引擎合并到主 APK。

### 使用与当前交付

单独安装 Freedom 主 APK，打开配置页 OCR 插件管理；需要 OCR 时安装并启用同批签名匹配的插件。目录安装由系统安装器确认；也可从同一 CI run 下载配套 APK。禁用或卸载插件不等于卸载主程序；非 OCR 业务仍由主程序提供，实际设备行为须验收。

当前 PP 的 [android-build.yml](../../.github/workflows/android-build.yml) 在同一个 build-freedom-apk job 构建普通、shrink-only 主 APK 和配套 OCR APK，使用 apksigner 验证三者签名一致后分别上传 Freedom-debug-apk、Freedom-slim-debug-apk、Freedom-OCR-plugin-debug-apk。独立 OCR job 负责插件测试/构建，不是正式配套工件的另一来源。不同 run 的 debug 签名不保证一致，不要混装或盲目卸载旧版本。正式升级需一致的长期签名。

历史说明中 tag Release 自动发布主/插件 APK、目录和兼容性清单的流程，在当前 PP 工作流树中未发现对应发布 job；仅保留为历史信息，不承诺现有 tag 自动发布。目录示例见 [release-manifest.example.json](release-manifest.example.json)。

## OCR 运行时和模型

Freedom 首发插件通过 AutoJs6 `IOcrPlugin` API 暴露 PP-OCRv6 Small。引擎使用移植适配后的 `OcrEngineHolder`，按模型版本和推理配置缓存引擎；配置变化时先释放旧引擎，再初始化新引擎。FD 解码器支持编码图片及 AIDL 的 raw ARGB 图片格式。

当前固定模型（PP-OCRv6 Small，modelId ppocrv6_small）由 [ocr-plugin/build.gradle.kts](../../ocr-plugin/build.gradle.kts) 的 prepareBundledOcrModels 根据 [内置目录](../../ocr-plugin/src/main/assets/ocr-model-catalog.json) 在构建时获取/复用缓存，校验最小文件大小与固定 SHA-256，并将检测/识别 ONNX 及识别 YAML 放入插件 APK 的 assets/models/ppocrv6_small/。模型不进入主 APK。

运行时 OcrModelManager 优先从插件内置 assets 安装到插件私有 noBackupFilesDir/ocr/models/，校验大小和 SHA-256 后以 staging 目录替换，已有有效模型按版本/校验复用。固定内置模型的首次识别不要求手机连接模型源；源码仍有远端目录/无内置 assets 的网络下载分支，不将任意外部模型都宣称为离线可用。引擎缓存与失败处理保持现状。

## Freedom 业务接入和行为保留

陌陌 OCR 业务继续使用 `MomoChatMessageOcrProcessor`：截图、裁剪、批次/超时控制、坐标映射、质量分类、彩色符号处理和样本归档均保留。它只通过 `OcrManager.recognize(bitmap)` 获取识别结果；主程序不引用 PaddleOCR SDK 或 ONNX/OpenCV 类型。

配置页既有窗口和菜单流程不变；OCR 插件管理作为原配置入口内部的列表/操作对话框，不移植 AutoJs6 整套窗口或插件中心页面。

## 构建与验证

```bash
gradle :app:assembleDebug
gradle :ocr-plugin:assembleDebug
```

正式版要求 Freedom 主 APK 和 OCR 插件 APK 使用同一份长期 keystore。两个模块支持 releaseStoreFile、releaseStorePassword、releaseKeyAlias、releaseKeyPassword Gradle 属性；当前 PP CI 验收为 debug 配套签名，不宣称已配置正式发布或长期升级签名。

静态边界验证确保：主 APK 不依赖 `:ocr-plugin`，不声明 ONNX Runtime/OpenCV，不包含 ONNX 模型；主 APK 与插件 APK 只共享 `:ocr-plugin-api`。G01-A CI 已通过两应用 Gradle 构建、自动测试及三份 debug APK 签名配套检查；实际设备上的服务绑定、安装、权限和模型推理仍未验收。具体设备验收步骤见 [`OCR-PLUGIN-DEVICE-ACCEPTANCE.md`](OCR-PLUGIN-DEVICE-ACCEPTANCE.md)。静态脚本通过不等同于 Gradle 编译或真机验收。
