# 正式文本容器统一采集与回复

## 基线与本次明确授权的行为

基线为上一轮交付的 `Freedom-full-source-profile-card-fixed.zip`。
根据用户明确要求，改为“只认正式消息文本容器，非正文卡片不进入回复消息集合”，不再逐种卡片分类，不以卡片是否识别成功作为生成回复的前置条件。
本说明取代前一份资料卡特判说明中的运行流程描述；原说明文件按不删除要求保留。

## 当前统一流程

1. 对当前可见消息行按现有正式文本容器资源名进行限定查询，主锚点为 `message_tv_layouttextview`。既有别名集中复用原 RowCandidatePolicy 的集合，不添加卡片名称表。
2. 无正式文本容器的行直接跳过，不读取其卡片正文、不展开卡片分类、不创建卡片 OCR 目标。卡片能否被识别不影响已有文字进入回复集合。
3. 有正式容器时，结合其最近 `message_root` 和 `message_content_layout` 层级选择该容器或其子节点的文字，普通消息布局中的其它标签不再成为正文。
4. 原 `MomoChatMessageDirectionPolicy.resolveForBody` 保持不变，仍使用同一消息根中的头像容器及正文祖先布局区分自己/对方，不猜测方向。
5. 正文暴露文字则读取；正文未暴露时，仅使用正式文本容器的边界建立现有本地 OCR 目标。原坐标转换、裁剪边界和表情保护保持不变。
6. OCR 输出按正文来源作为 TEXT，不再根据整个消息行里出现的 game_content、卡片按钮等节点把该文本容器的内容改判为互动卡。
7. 在正式文字消息集合中沿用原有消息顺序和生成逻辑，再通过已有输入通道分段写入并回读。没有可用正文不编造回复；最新正式文字为自己发出或方向不明时，原处理规则仍保留。

## 本轮实际修改

- `app/src/main/java/com/freedom/adapters/social/momo/MomoChatMessageOcrProcessor.kt`
- `app/src/main/java/com/freedom/adapters/social/momo/MomoChatMessageReader.kt`
- `app/src/main/java/com/freedom/adapters/social/momo/MomoChatOcrCandidateMapper.kt`
- `app/src/main/java/com/freedom/adapters/social/momo/MomoChatRowCandidatePolicy.kt`
- `app/src/main/java/com/freedom/adapters/social/momo/MomoSocialAdapter.kt`

新增的正文选择规则 `MomoChatTextContainerPolicy.kt` 为纯 Kotlin 代码，便于直接回归。
新增日志 `AI 正文容器读取：扫描行=...；正式正文行=...；正文OCR=...`，不输出消息内容。
原先资料卡分类调用、资料卡 OCR 探针和资料卡专用 OCR 分支已退出活动运行流程。
由于用户禁止未经同意删除原文件，旧 `MomoChatProfileCardPolicy.kt`、旧测试和兼容字段保留，但 Reader、OCR Processor、SocialAdapter 不再调用该专用类或创建分类探针。
未删除任何原项目文件，未修改任何原测试文件、安全断言、CI 或项目规范。
未调整模型拒绝策略、历史身份查询、运行停止控制、输入节奏、手势算法或发送行为；仍不自动发送。

## 利用用户节点树的验证

新增回放测试直接取自上传诊断包中的 `page_016.json` 与 `page_018.json`，仅保留资源 ID、相对路径、祖先关系和类型，不保存昵称或真实消息正文。
回放覆盖三个正式文本容器：自己一条、对方两条，其方向与原读取结果一致；五个非正文行不存在正式文本容器，均被排除，包含资料展示和动态等内容。
通用测试还覆盖 101 种未知组件名、卡片文字比正文更长、同层非正文、跨消息根、正文容器旁出现 game_content、自己最新文字及方向不明等情况。
生成到输入的回归使用实际草稿控制器，模型和 Android 输入字段使用测试替身；验证上下文只包含正式文字并能分段写入测试回复。

## 实际验证结果

- Kotlin 2.0.21 / JVM target 17：129 个纯 JVM 生产文件及 62 个测试文件编译通过；另增的节点树回放测试单独编译通过。
- JUnit 4.13.2：63 个测试类，540 项全部通过，包括原有架构、消息方向、OCR、草稿、历史及输入回归。
- Python 架构测试：12 项通过。
- `python3 tools/architecture/verify_boundaries.py --root . --show-advisories`：通过。原有大文件职责提示不属于失败。
- 所有基线中的原测试文件逐字节保留，没有通过删除测试或放宽断言消除失败。
- 完整包/覆盖包验证 CRC、目录唯一、原文件保留和覆盖一致性。

## 尚未验证与实际边界

未运行完整 Android Gradle/SDK APK 构建、未在真机运行此次版本、未调用手机上的 Paddle OCR 插件或远端模型。
直接 JVM 编译和节点树回放不等同于 Android Reader 的完整运行，限定资源 ID 查询及真实写入仍需重新编译安装后验证。
原有读取预算、文本完整性、方向不明及运行停止等检查仍保持，不能承诺平台节点失效或正文读取失败时也必然生成内容。
如果陌陌未来更换正式文本容器本身的资源契约，应更新正文锚点；新增无正文卡片不需要添加分类规则。

## 覆盖方式

- `Freedom-full-source-text-container.zip`：最新完整源码，包含以前已交付的修复和本次统一正文采集。
- `Freedom-text-container-modified-files.zip`：只包含相对于上一轮资料卡版的本轮实际修改及新增文件，完整保留 `Freedom/` 相对目录。
- 覆盖包解压至原 `Freedom/` 的父目录，允许覆盖同名文件；若现有源码不是上一轮资料卡版，使用完整源码包。
- 原项目文件删除清单：无。
