# FRDM PP R09-F — APK 完整构建与真机全业务回归（部分验收）

## 基线及实物核验
- PP 838cd09ebe6cee60cf3bbc34d65d38a165880b4d；CI 46131057917 五项 success。用户免除本地工作区检查，远端 tree/diff/HEAD 核验。施工计划 R09-F 要求完整 APK 及真机全业务回归。
- 下载 CI 工件 15258111688、15258111791、15257676332，读取内层 APK，ZIP CRC 全部通过。自编只读 APK v2 校验（RSA PKCS#1 SHA-256 签名及分块内容摘要）三件均通过；不是 Android SDK apksigner 全版本安装验收。
- APK 实际大小、SHA256：
  - app-debug.apk: 5595612 bytes, 28 dex, 4295184b0eb9df81db82c61d7e0df7c1aae3d8f00687c6d3ae0e76f9aec1c49f
  - freedom-slim-debug.apk: 5593806 bytes, 1 dex, 7895a22cd60d9b9e6e6d7e46da5a5725b5f3ef2d247288cad326561942b8111f
  - ocr-plugin-debug.apk: 235200980 bytes, 12 dex, e687178622a2f2e7eab855f44f9198022a1d489e37b286774cb02d02d0020d08
- 实际二进制 Manifest：主包 com.freedom，插件 com.freedom.ocr.plugin；versionCode 1，versionName 1.0.0，minSdk 26，targetSdk 35；主程序无障碍服务使用 BIND_ACCESSIBILITY_SERVICE；插件 wake/activity 及 service 使用 com.freedom.permission.BIND_OCR_PLUGIN，主程序声明 signature (0x2)。OCR 内含 arm64-v8a/armeabi-v7a/x86/x86_64 四种 ABI、每种四个 so，PP-OCRv6 small det/rec/config 三项与内置 catalog SHA256 一致。
- **阻断缺陷**：主程序两个版本证书指纹 9f0ff2abdde3a30dc139812f7e3bb2be1d1a3b983b3adf4bc56e5633028d0276；OCR 9cc6db7db936868a5419afe1ca4276fc74373c2dade716583e1f4025216b9250。两个独立 runner 的默认 debug 签名不同，现有签名级权限绑定要求不满足。不是设备实测故障。旧三工件不应作为配套安装包。
- 精简 APK 实际仅少 1806 bytes，DEX 压缩总量减少不代表 APK 体积等幅减少；外层 artifact ZIP 大小不可代替内层 APK 大小。不在本阶段调整打包优化。

## 限定 CI 修复
- 仅 android-build.yml：正式普通/精简/OCR APK 在主程序同一 runner 上依次构建，共用当地默认 debug keystore；保留独立 OCR 测试及构建 job，不再从独立 runner 发布不配套插件。正式工件原名保留，普通 APK 先保存，防止被 shrink build 覆盖。
- 三件上传前用 Android SDK 35.0.0 apksigner verify --verbose --print-certs，要求每件签名有效且唯一 signer SHA256 摘要完全一致；不一致立即失败，不发布任何正式 APK。无私钥上传、跨 runner 传输或入库。不更改 Android Manifest、签名权限、Gradle 默认配置、release signing、现有测试和 CodeQL。
- R01 仅精确登记工作流 blob 及新增 R09-F 门禁测试，原清单及 S0 不变。新增测试校验构建顺序、签名 gate、唯一工件上传、独立 OCR test/build，并模拟移除 gate/重复上传。完整新 CI 与实际配套工件重新验签后方可判定 APK 交付通过。debug 签名跨不同 run 可能变化，不能保证覆盖安装已有版本；若签名不同，不卸载/清数据以绕过，需用户确认备份或使用原合法签名构建。

## 真机回归清单（全部待执行）
当前无 Android SDK/adb/真实设备，不能宣称安装、绑定或真机业务通过。用同一新 CI run 的正式配套普通 APK 与 OCR APK，在用户授权的测试设备和测试账号执行；精简版单独复测，不同版本不可同时安装。记录设备/系统/ABI、目标应用版本、APK SHA256、运行会话及脱敏日志。只使用授权测试会话，避免向真实联系人意外发送消息。

| ID | 场景 | 通过条件及证据 |
|---|---|---|
| F01 | 安装、启动、无障碍及悬浮权限、OCR 插件绑定 | 两件安装及正常启动、插件实际识别；签名及授权检查通过、日志无 bind/security failure |
| F02 | 私聊遍历、进入详情、返回 | 返回原会话锚点附近，保持方向、无重新从顶部开始、无重复处理；记录返回前后可见锚点/游标 |
| F03 | 招呼列表、筛选、详情、返回续接 | 过滤与进度隔离；返回原游标；无重复处理或跨任务手势 |
| F04 | 动态详情、图片横向翻页、返回 | 图片逐张确认、返回原列表位置；记录页码及滚动前后证据 |
| F05 | 头像与 ID 采集 | 新快照重新定位头像，读取平台 ID 后返回；故障只读重检不旧坐标/整行兜底；日志脱敏 |
| F06 | OCR 迟到及页面变化 | 切换会话/任务后旧结果拒绝；新窗口可正常识别 |
| F07 | AI 草稿、正常发送及空输出降级 | 仅测试会话；正确上下文，异步旧结果不写入，确认发送后记账一次；A/B 同名历史不混用 |
| F08 | 私聊图片阈值、消息拦截 | 测试会话中图片实际气泡确认、开关新快照确认、返回原位置；招呼不触发私聊媒体流程 |
| F09 | 三业务六方向手动切换 | 旧任务释放租约，页面未确认时只读，新任务唯一写权；关联租约/代次证据 |
| F10 | UNKNOWN、失败、Service 中断重连 | 保留检查点，重检恢复，旧回调不得写；未知发送结果不盲重试 |
| F11 | 用户停止 | 所有业务写权撤销，迟到回调无动作，停止后不自动复活 |
| F12 | 持久化、重启、精简 APK | 数据不丢失、不重复计数、原任务按验证恢复；精简版重复上述核心场景 |

每项记录 PASS/FAIL/BLOCKED/NOT_RUN、测试时间、前置条件、可观察结果、脱敏日志定位。当前均 NOT_RUN；无设备/用户提供日志不得填 PASS。R09-G 前必须明确真机结果或由用户明确批准延期；不以本报告或全绿 CI 替代真机验收。

## R09-F CI follow-up

- CI 46132065471: architecture, independent OCR test/build, paired APK build and signature gate passed; all three signer SHA-256 digests were 2015ddeba4ddbc3107d1af012026fae58f2f0d2a3baa3352958adddf381498ff. App test failed only ArchitectureBoundaryTest.ciRunsTheArchitectureGateBeforeAndroidBuild: OCR APK must be uploaded. The old assertion was tied to the independent runner rather than the new paired delivery runner.
- Relocate exactly the existing OCR upload assertion to the actual delivery job, retain the independent OCR build assertion, additionally require independent OCR tests, paired OCR build, signature gate preceding all three uploads and no unpaired official OCR upload. R01 records the exact revised test blob; no removed test, no loosened assertion, no workflow or production change in this correction. New full CI required; true device tests remain NOT_RUN.
