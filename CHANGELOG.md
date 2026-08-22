# 变更记录

版本变更从 `1.00-beta` 开始记录。早期开发史不在此处,以 Git 历史为准。

## [1.00-beta] — 2026-08-23

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
