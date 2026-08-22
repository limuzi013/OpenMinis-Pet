# 变更记录

版本变更从 `1.01-beta` 开始记录。早期开发史不在此处,以 Git 历史为准。

## [1.01-beta.2] — 2026-08-23

修复 Web 图片气泡把图片块显示成「附加内容块」的嵌套数组 bug。

### 图片

- `nativeMessageToDsh` 把 `resolveImageRefs` 的结果整体 `put` 进 content,导致 DSH
  收到 `[text, [imageBlock]]`(嵌套数组),第二个元素被 `contentParts` 归为未知块,
  渲染成「附加内容块」JSON 而非图片;
- 改为 `appendFlatBlocks` 把每个 image block 摊平为独立 content 元素;
- `DshImageBlockTest` 新增两用例:平铺结构与 DSH `contentParts` 三分分类(text/image/rest)
  钉死,嵌套数组场景回归防复发。

## [1.01-beta.1] — 2026-08-23

修复轮:Web 图片链路两处阻断、原生 DSH 统计投影定稿,并补齐 App/Web 图片协议测试。

### 图片(Web↔App 同一 MediaStore 权威源)

- **修复 live/history image block 死代码**:`nativeEventToMuxFrame` 全部调用点(history +
  两个 mux 推送路径)现在传入宿主 context,`resolveImageRefs` 不再因 context 为 null 而
  丢弃所有图片块;
- **修复 `session.attachment` 协议**:`data` 从 0-255 整数数组改为标准 base64 字符串,
  符合 bundled DSH 的 `data: string()` schema 与 runtime `atob()` 解码;
- 抽出可单测的 `imageAttachmentProto` / `encodeAttachmentData`,新增 `DshImageBlockTest`
  (7 用例,全部通过);
- 其余管线(MediaRef 持久化、attachmentId==MediaRef.id、legacy backfill)沿用 1.01-beta 实现。

### Stats

- 沿 1.01-beta 的 `sessionStats`/`tokenUsage` 投影,本轮仅验证与 bundled DSH StatsLine
  字段逐项一致(原始整数,不预格式化)。

### 测试

- `DshImageBlockTest` 7/7、`DshSessionStatsTest` 6/6、`com.openminis.app.remote.*` 全部通过;
- 全量单测仅 14 个既有 OpenAI MockWebServer 环境基线失败(与本次改动无关)。

## [1.01-beta] — 2026-08-23

首个公开版本,Android arm64 开发/自测构建。详见 [RELEASE-NOTES.md](RELEASE-NOTES.md)。

### Minis Web

- 正式 DeepSeek Harness Client Plugin `@openminis/minis-client-settings`
  (`web/minis-client-plugin/`):官方 `settings.section` slot 注册「Minis 控制台」,
  复用 `createSnapshotStore` 与 locale 基础设施,纯 React 投影 Android 权威状态;
- `@deepseek-ai/dsh-client-ui-settings-general` 使用上游官方产物;无 DOM 桥、
  MutationObserver 或未托管轮询;
- 12 个控制台页(overview/providers/skills/mcp/memory/system/scheduled/agent/web/
  device/diagnostics/advanced)全部保留。

### Android Debug / Root 能力

- 六个高内聚工具:`android_capabilities`、`android_app`、`android_ui`、`android_logs`、
  `android_diagnose`、`android_deploy`;
- 只读能力矩阵(root/privileged shell/UI/debug/execution/包可见性逐项探测,
  `AVAILABLE/PARTIAL/UNAVAILABLE/REQUIRES_USER_GRANT`);
- `PrivilegedCommandRunner`:Root `su` 主动探测后端与 Shizuku 复用,按操作选择;
  install/uninstall/clear/root 操作走一次性审批;
- Accessibility 观察 generation/ref + 窗口指纹 + `STALE_UI_REF`;Unicode 输入优先
  `ACTION_SET_TEXT`,`ACTION_PASTE` 回退会保存并恢复剪贴板;
- logcat 游标(mark → 操作 → read since,含 PID/boot 变更检测),watch 复用作业系统,
  大输出自动落盘;
- APK 部署按真实 Gradle output 元数据发现/检查;明确拒绝安装自身
  (self-update continuous execution 标记 UNSUPPORTED);
- native chroot/mount 仅实验性 `probe_native_chroot`;PRoot 保持默认执行环境。

### 基础能力

- Android Agent 运行时、PRoot+Alpine 沙箱、持久 shell、文件工具、Goal/Todo/Plan、
  Skills/MCP/记忆/定时任务、桌面宠物与数字助手;
- Web Remote:登录、逐能力 RPC 授权、会话事件 WebSocket、Tunnel、URL 导入 SSRF 防护;
- 工具超时、执行意图检查点、作业系统、Token 计量、上下文压力、结果修剪/落盘。
