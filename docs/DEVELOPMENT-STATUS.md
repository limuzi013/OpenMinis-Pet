# OpenMinis Pet 开发状态

更新：2026-08-22。本文记录当前 `master` 的工程状态；发布使用者请先阅读根目录
[README](../README.md)。

## 仓库与发布

- Repository：`https://github.com/limuzi013/OpenMinis-Pet`
- Branch：`master`
- pet.15 主功能提交：`da47c7b`
- pet 版本排序修复：`6641d03`
- Release tag：`v1.12-pet.15`
- Package：`dev.openminispet.android`
- Version：`1.12-pet.15-SNAPSHOT`（versionCode 35）
- APK：`releases/OpenMinis-Pet-minis-web-pet15-arm64-debug.apk`
- Size：`54,442,918` bytes
- SHA-256：`ed8355f6b4ccd0416d7edc82bc3729dc4398e98315b80664a7c9571bf8209fc0`

当前 APK 是 arm64 Debug 签名开发包，不是生产 release。

## 已交付

### Minis Web

- `assets/minis/` 是 `/` 的唯一默认 Web UI；`/remote/`、`/dsh/` 重定向到 `/`；
- DSH strict unary schema、mux/host WebSocket、session history 和有界事件重放；
- 登录页、Minis 品牌/PWA、DSH Settings 内嵌管理控制台；
- Workspace 映射原生会话分组，Goal/设置映射 Android 权威数据；
- Session/Workspace 可见页 baseline 对账和 settings revision 冲突保护；
- Provider、模型、Skills、MCP、记忆/SOUL、环境、挂载、定时任务、Agent 与 Web 设置；
- Skills/MCP 公开 HTTPS URL 导入及 SSRF/重定向/大小防护；
- Cloudflare named tunnel 固定 HTTP/2。

### 安全

- Web 登录、per-IP 限流、Host/DNS rebinding 和 HTTP header/request size 限制；
- Provider、环境和 MCP secret 不通过读取响应返回；
- Web RPC allowlist + 精确 deny list；
- canonical path containment 和 Workspace Write 权限策略；
- Web 不开放 DebugServer 的截图、点击、输入、任意 Shell、任意文件或 Root 能力；
- EncryptedPreferences 失败时 fail-closed，不回退明文。

### Android

- 桌面宠物、默认数字助手、原生 Agent 状态条、提问/反馈/计划等同步；
- PRoot Alpine 沙箱、持久会话 Shell、共享目录和 SAF 外部挂载；
- Shizuku/AXManager/Sui 为可选 Android privileged bridge；普通 Agent 不依赖它。

## 验证记录

- `:app:assembleDebug`：通过；
- `:app:assembleDebugAndroidTest`：通过（已修复旧测试对 `mountedSessionId` 的引用）；
- `DshApiAdapterTest`：通过；
- `UpdateCheckerVersionTest`：通过；
- pet.15 device instrumentation：DSH response/settings revision 和原生 Workspace round-trip 2/2；
- Skill/MCP HTTPS 导入、私网拒绝和清理回归：通过；
- APK v2 signature、覆盖安装保留数据、本机服务和前台公网入口：通过；
- 38 项 Provider 测试仍依赖公开仓库没有的 OAuth customization/network fixture。

`ExecutionCoordinatorInstrumentedTest` 已按 per-session PersistentShell 架构更新，不再引用已删除的
`mountedSessionId`。

## 尚未交付

- Ubuntu PRoot profile；
- Magisk/KernelSU/APatch 直接 `su` + namespace/chroot backend；
- QEMU/KVM 独立 Ubuntu kernel backend；
- 完整 DSH Subagent 目录、通用持久 Job runtime 和队列编辑；
- production release keystore、关闭 DebugServer 的正式 release APK；
- cloudflared 固定版本与 SHA-256；
- Provider 全量离线 fixture。

## 设备相关限制

- HyperOS 未授予后台无限制时，即使前台服务进程存在，也可能在退到后台约 20 秒后冻结网络处理；
- 电池豁免、自启动、系统角色、悬浮窗和 SAF 授权必须由用户在 Android 系统界面完成；
- Shizuku 不是 Root，也不是 Ubuntu/内核前置条件；Root 设备未来可直接使用 `su` backend，
  但当前源码尚未实现。

## 关键入口

| 目的 | 路径 |
|---|---|
| 默认 Web | `src/android/app/src/main/assets/minis/` |
| Remote server | `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt` |
| DSH adapter | `src/android/app/src/main/java/com/openminis/app/remote/DshApiAdapter.kt` |
| Session journal | `src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt` |
| Safe URL import | `src/android/app/src/main/java/com/openminis/app/debug/SafeRemoteImporter.kt` |
| PRoot runtime | `src/android/app/src/main/java/com/openminis/app/sandbox/PRootKernel.kt` |
| Rootfs manager | `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt` |
| Build | [`../BUILDING.md`](../BUILDING.md) |
| Docs index | [`README.md`](README.md) |
