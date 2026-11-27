# FRDM PP — M04-A 已验收媒体点击链路冻结

- 验收基线：`2c624b372532a379036192cb939e65c514f03b85`（PP；CI run 45897734896）。
- 真机记录：2026-11-27 图片发送此前已验证；消息拦截 05:28:17.281 → 05:28:23.377，5 次实际派发均完成，回到原聊天详情页。入口有一次编译拒绝、开关有一次暂未就绪，均未实际重复派发。
- 验收范围为点击能力，不代表图片送达、拦截最终开关状态、正式业务恢复或生产接入已验收。

## 冻结执行能力

图片：`openImageAlbum` → `openImageAlbumDirectory` → `inspectFreedomDirectory` / `openFreedomAlbum` → `selectFreedomImage` → `sendFreedomImage`。
保留唯一 Freedom 图片选择控件、单选发送、实时窗口及目标重新定位，不扩展多图选择。

拦截：`chat_toolbar_avatar` → `setting_layout_harass` → `btn_switch` → `interceptionDismissBounds` → `interceptionBackBounds`。
保留现有控件类型、目标区域和点击顺序，禁止用固定坐标或节点 Action 替代。

公共：`submitVerifiedAlbumTap`、当前窗口快照、`MomoAlbumGesturePermit`、相册与图片入口解析均沿用现有统一手势通道及租约。无需复制点击代码或创建新架构。

## 强制回归

`tools/architecture/m04_media_click_freeze.json` 记录上述验收基线的专用文件和精确方法区域指纹（UTF-8，SHA-1 仅作回归比较，不作安全签名），以及现有调用顺序和拦截解析表达式。
`tools/architecture/test_m04_media_click_freeze.py` 由现有 arch-guard 的 unittest discover 自动执行；修改、删除或重复冻结方法、修改专用解析器或改变现有调用顺序会使 CI 失败。原有架构检查及 Android 回归测试保持不变。
不得为通过 CI 自动重算冻结指纹、跳过测试或降低断言。确需迁移调用位置时，单独审计并经授权更新调用顺序定位方式；已验收实际操作实现仍应复用。

## 后续允许接入

只接入 `AI_REPLY_PRIVATE_MESSAGES`。正式业务通过文本发送确认后的既有账本触发；阈值为 0 表示关闭，同阈值先图片后拦截；所有动作完成后使用原返回及遍历流程。结果证据、联系人绑定、账本生命周期、检查点、统一恢复、调度和测试入口清理由后续批次处理。
收到的招呼不接入、不改变其原有回复及返回行为；后续账本接入批次须隔离其现有 `completeConfirmedGreetingSend()` 调用 `recordSuccessfulSend()` 的计数路径，本批不修改。
不得把测试一次性授权、测试终态或局部重试直接当作正式事务的成功/恢复状态。
