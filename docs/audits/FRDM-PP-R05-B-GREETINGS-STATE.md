# FRDM PP — R05-B 招呼遍历及导航状态迁移

- PP 基线 `7006836ce4eb3efd24f5a5d46b1c28850d17866e`，[R05-A CI 46059967512](https://github.com/ttiaogou/FRDM/actions/runs/46059967512) 全部成功。
- 复用既有 MomoSayHiNavigationFlowController（10 项入口/卡片待确认、手动只读探测、时间戳和诊断状态），MomoReceivedGreetingsEntrySearchController（1 项搜索窗口标识），MomoSayHiTraversalState（3 项运行期返回行/只读等待/过滤配置诊断状态及 2 个仅运行期凭据集合）。原声明从门面删除，不创建第二份状态、控制器、任务或账本。
- 入口运行状态重置保留原执行位置和原赋值顺序。原 phase、稳定快照、搜索方向、重试、手势请求代次、断点、持久化游标、既有 SEND_CONFIRMED/RETURN_VERIFIED 事实完全保留。
- 发送与返回凭据的记录、消费、清理集中到既有遍历状态对象；调用者仍持有原 adapter monitor（@Synchronized 与 synchronized(this)），未引入新锁、改动锁边界或把历史发送计入新运行额度。清理仅运行期凭据，不清除既有持久化账本。
- 门面仍负责实时 UI 操作权、原节点重检、手势派发及回调；共享非搜索手势忙标志、所选招呼、资料页与点击回退事务暂留待后续专门批次。新归属的跨调用字段仍有直接访问，待后续封装收敛；不宣称所有招呼编排已迁移。
- R05-B 新增 2 项源码等价检测和 2 项 Kotlin 运行回归。实际在提交前按新归属逆向恢复整个门面，逐字符与 R05-A 基线一致。新增检测还恢复三个已有控制器的完整历史 blob。R05-A 源码检测只在原逆向还原之前串联 R05-B 的严格前置哈希校验；原全部断言、七段历史证据及 R04 最终哈希不变，不放宽或删除任何测试。
- R05-A 测试文件指纹 `0601f7476c894baa254767949c3916597612449f` → `df493ddb2d84b33685aa4eeb4e0d745dcff1f18f`，R01 精确登记。S1 精确替换如下：
- MomoSocialAdapter.kt: `0c9bf36aad8f4cba2d996be835ae14d731ecd17d` → `a1cb58a1eaecb734a48a58656e4bdd454b52466d`
- MomoSayHiNavigationFlowController.kt: `a2b8bb566c4e3a819e0ce332c11c130055a0366e` → `a879a36bc84e4a7533a2ada6987c88494d023d21`
- MomoReceivedGreetingsEntrySearchController.kt: `7d344a81f7fd324d09dd41c6520515ab42d4d608` → `45683b9237ef35a3ab7c767fb646f814f2ee3629`
- MomoSayHiTraversalState.kt: `8d5daa83fe133cc685494460d0c786b0d9dd9590` → `b0120b7e9a07170fe5063a529a04867334bc92a9`
- S0/M04 核心冻结区不变；原 M04 区段、媒体 observe、OCR、私聊会话安全边界、动态 feed、动态入口、导航诊断、招呼目标复核、聊天区域刷新和招呼窗口选择源码逐字比对未改变。
- 门面 8424 → 8394 行。无本地工作区及真机，实际 Android 页面进入、手动切换、旧回调、返回凭据和重新运行场景仍须设备回归；本批完整 CI 通过后再开始 R05-C。
