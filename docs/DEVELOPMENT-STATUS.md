# 开发状态

本文记录当前 `master` 的工程状态。发布使用者请先阅读根目录 [README](../README.md)。

## 仓库与发布

- Repository: `https://github.com/limuzi013/minis-for-android`
- Branch: `master`
- Release tag: `v1.01-beta.1`
- Package: `dev.openminispet.android`
- Version: `1.01-beta.1`(versionCode 38)
- APK: `releases/OpenMinis-Pet-1.01-beta.1-arm64-debug.apk`
- Size: `54478634` bytes
- SHA-256: `b76277d7956c41dfffcb28edcecfcb71e546df4b8356a9e16abfeb02eae6d147`

当前 APK 是 arm64 Debug 签名开发包,不是生产 release。

## 已交付

### Minis Web

- `assets/minis/` 是 `/` 的唯一默认 Web UI;`/remote/`、`/dsh/` 重定向到 `/`;
- DSH strict unary schema、mux/host WebSocket、session history 和有界事件重放;
- 登录页、Minis 品牌/PWA、DSH Settings 内的正式 OpenMinis Client Plugin(官方
  `settings.section` slot + `createSnapshotStore`,React 投影 Android 权威状态);
- Workspace 映射原生会话分组,Goal/设置映射 Android 权威数据;
- Session/Workspace 可见页 baseline 对账和 settings revision 冲突保护;
- Provider、模型、Skills、MCP、记忆/SOUL、环境、挂载、定时任务、Agent 与 Web 设置;
- Skills/MCP 公开 HTTPS URL 导入及 SSRF/重定向/大小防护;
- Cloudflare named tunnel 固定 HTTP/2。

### 安全

- Web 登录、per-IP 限流、Host/DNS rebinding 和 HTTP header/request size 限制;
- Provider、环境和 MCP secret 不通过读取响应返回;
- Web RPC「方法 → 能力」显式映射(`RemoteCapabilityCatalog`),未登记方法默认拒绝;
- canonical path containment 和 Workspace Write 权限策略;
- Web 不开放 DebugServer 的截图、点击、输入、任意 Shell、任意文件或 Root 能力;
- EncryptedPreferences 失败时 fail-closed,不回退明文;
- 有副作用 Android 操作(安装/卸载/清日志/Root 授权等)走一次性审批。

### Android Debug 工具链

- `android_capabilities`/`android_app`/`android_ui`/`android_logs`/`android_diagnose`/
  `android_deploy` 六个高内聚工具,复用现有 Accessibility、VisionGroupResolver、Shizuku、
  PRoot、ApprovalSeam、JobRegistry、ToolCheckpointStore、SpillPolicy;
- 只读 capability resolver(root/privileged shell/UI/debug/execution/package visibility
  逐项探测,`uid=0` 不等同于全能力);
- `PrivilegedCommandRunner`:Root `su` backend(被动检测 + 主动授权探测,CAP_SYS_CHROOT/ADMIN
  与 SELinux 状态返回)或 Shizuku,按操作选择;
- Accessible UI observation 支持 generation/ref、指纹校验、STALE_UI_REF;Unicode 输入优先
  ACTION_SET_TEXT,失败才走会保存/恢复剪贴板的 PASTE;
- logcat 支持 mark_cursor → 操作 → read since cursor,boot change 检测,watch 走 JobRegistry,
  大输出走 SpillPolicy;
- APK 部署按真实 Gradle output 发现 + archive 元数据,不猜固定路径;明确拒绝自覆盖安装
  (self-update continuous execution UNSUPPORTED);
- native chroot/mount 仅提供实验性 probe,不替换 PRoot,不修改全局 SELinux。

### Android

- 桌面宠物、默认数字助手、原生 Agent 状态条、提问/反馈/计划等同步;
- PRoot Alpine 沙箱、持久会话 Shell、共享目录和 SAF 外部挂载;
- Shizuku/AXManager/Sui 为可选 Android privileged bridge;普通 Agent 不依赖它;
- 工具超时、执行意图检查点、后台作业、Token 计量、上下文压力、结果修剪/落盘。

## 验证记录

- JDK 17 / Android SDK 36:`:app:compileDebugKotlin`、`:app:assembleDebug` 通过;
- OpenMinis Web Client Plugin:`tsc --noEmit` 与 vitest 6/6 通过;
- Android 工具单元测试 47/47 通过(capability/SELinux/CapEff 解析、logcat/崩溃解析、
  APK 发现、UI generation/ref、风险分类);全量 779 测试仅 14 个 OpenAI MockWebServer
  环境基线失败;
- Android 16 arm64 真机(Xiaomi 15, API 36):`adb install -r` 成功,MainActivity 启动、
  前台窗口与进程确认。

## 尚未交付

- Ubuntu PRoot profile;
- Native chroot/mount namespace 完整 backend(当前仅实验性 `probe_native_chroot`,
  未启用为默认执行环境,未通过 parity tests);
- Root su backend 的设备级授权验证(需真实 Magisk/KernelSU/APatch 场景);
- QEMU/KVM 独立 Ubuntu kernel backend;
- 完整 DSH Subagent 目录、通用持久 Job runtime 和队列编辑;
- production release keystore、关闭 DebugServer 的正式 release APK;
- cloudflared 固定版本与 SHA-256;
- Provider 全量离线 fixture。

## 设备相关限制

- HyperOS 未授予后台无限制时,即使前台服务进程存在,也可能在退到后台约 20 秒后冻结
  网络处理;
- 电池豁免、自启动、系统角色、悬浮窗和 SAF 授权必须由用户在 Android 系统界面完成;
- Shizuku 不是 Root;Root 设备直接走 `su` 后端(主动探测),与 Shizuku 相互独立。

## 关键入口

| 目的 | 路径 |
|---|---|
| 默认 Web | `src/android/app/src/main/assets/minis/` |
| Minis Client Plugin 源码/构建 | `web/minis-client-plugin/` |
| Android Debug 工具 | `src/android/app/src/main/java/com/openminis/app/tools/android/` |
| Remote server | `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt` |
| DSH adapter | `src/android/app/src/main/java/com/openminis/app/remote/DshApiAdapter.kt` |
| Session journal | `src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt` |
| Safe URL import | `src/android/app/src/main/java/com/openminis/app/debug/SafeRemoteImporter.kt` |
| PRoot runtime | `src/android/app/src/main/java/com/openminis/app/sandbox/PRootKernel.kt` |
| Rootfs manager | `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt` |
| Build | [`../BUILD-CN.md`](../BUILD-CN.md) |
| Docs index | [`README.md`](README.md) |
