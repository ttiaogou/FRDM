# OCR 插件真机验收清单

这份清单用于 Android Gradle 构建通过后的设备验收。静态测试通过不代表以下项目已验证；每项应在实际目标设备上记录设备型号、Android 版本、ABI、主 APK/插件 APK 版本和结果。

## 1. APK 分离与主包体积

- [ ] 单独下载并安装 `Freedom-debug-apk` 或 `Freedom-release-apk`，不下载 OCR 插件 Artifact。
- [ ] 检查 Freedom 主 APK 中不存在 `libonnxruntime.so`、`libonnxruntime4j_jni.so`、`libopencv_java4.so` 和 `.onnx` 模型文件。
- [ ] 记录主 APK 实际文件大小；与 OCR 插件 APK 分开统计，不使用两个 APK 的 Artifact ZIP 总大小代替主 APK 大小。

可用 `apkanalyzer files list Freedom.apk` 或解压 APK 检查 `lib/`、`assets/` 内容。

## 2. 独立安装、发现和唤醒

- [ ] 仅安装 Freedom 时，OCR 插件管理页显示无已安装插件；非 OCR 业务和原有窗口/手势流程正常。
- [ ] 通过管理页安装同一发布批次的 OCR 插件 APK；系统安装器要求用户确认安装。
- [ ] 安装完成后重新打开管理页，插件按 `org.autojs.plugin.PADDLE_OCR` + `paddle-ocr` 被发现，显示名称、版本、engine 和 variant。
- [ ] 首次绑定时覆盖插件包处于 stopped 状态的场景；确认无界面 Wake Activity 能启动插件进程并成功完成 Binder 绑定。
- [ ] 检查服务只允许 Freedom 签名权限访问；不同签名的非可信服务不能通过 OCR Host 校验。

## 3. AIDL 接口与图片传输

- [ ] `getInfo()` 返回的 `PluginInfo` 字段及 capabilities 可被主程序正确读取。
- [ ] `detect(ParcelFileDescriptor, OcrOptions)` 和 `recognizeText(...)` 均可往返调用。
- [ ] 普通编码图片可正确解码；raw ARGB 图片路径（如外部 OCR 客户端使用）能按 width/height/stride 校验并解码。
- [ ] 识别结果中的 text、confidence、bounds 和 `extras.quad` 能映射回 Freedom 原有识别结果结构。
- [ ] 大图、多条文本和长时间识别没有 Binder transaction size、FD 泄漏或图片写入线程卡死。

## 4. 引擎和 Binder 生命周期

- [ ] 首次 OCR 自动下载 PP-OCRv6 Small 模型到插件私有目录；下载失败、文件大小不符或 SHA-256 不符时不能启用不完整模型。
- [ ] 第二次及连续 OCR 调用复用相同模型配置的引擎，不重复初始化 ONNX Runtime/OpenCV。
- [ ] 更换模型版本或推理参数后旧引擎先释放，再初始化新配置。
- [ ] OCR 连续调用期间连接复用；最后一次调用后空闲约 30 秒，主程序解除 Binder 绑定，插件 `onUnbind()` 释放引擎。
- [ ] 强制停止插件进程或模拟 Binder death 后，下一次 OCR 调用能重新发现并绑定；失败时提示明确，不导致主进程崩溃。

## 5. 管理操作

- [ ] 禁用插件后，默认选择逻辑不再调用该插件；重新启用后可恢复使用。
- [ ] 有新版本时显示更新入口；下载前后校验 APK SHA-256、包名、versionName 和 versionCode。
- [ ] 更新 APK 后服务元数据和版本号刷新，已下载模型按版本/校验策略复用或重新校验。
- [ ] 从系统安装界面卸载 OCR 插件后，Freedom 主 APK 仍可启动；OCR 请求提示需要安装插件。
- [ ] 再次安装插件后无需重装或覆盖安装 Freedom 主 APK。

## 6. Freedom 回归

- [ ] 陌陌聊天消息截图、正文裁剪、目标区域坐标映射、质量分类、样本归档结果与迁移前一致。
- [ ] 监控窗口、配置页面、抽屉/菜单、悬浮窗显示/隐藏、返回行为和手势执行无回归。
- [ ] OCR 超时、插件缺失、插件禁用、模型下载失败和 Binder 异常时，悬浮窗及截图资源能恢复/释放。
