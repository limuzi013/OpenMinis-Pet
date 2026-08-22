# OpenMinis Pet `v1.00-beta`

发布日期：2026-08-23。**这是 1.00-beta 新版本线的起点**；旧的 `1.12-pet.N` 版本线已从仓库与 GitHub Releases 中移除。
该版本是 Android arm64 开发/自测构建，与 tag [`v1.00-beta`](https://github.com/limuzi013/OpenMinis-Pet/tree/v1.00-beta) 源码对应。

## 下载与校验

- APK：[`OpenMinis-Pet-1.00-beta-arm64-debug.apk`](https://github.com/limuzi013/OpenMinis-Pet/releases/download/v1.00-beta/OpenMinis-Pet-1.00-beta-arm64-debug.apk)
- applicationId：`dev.openminispet.android`
- versionName：`1.00-beta`
- versionCode：36
- ABI：`arm64-v8a`
- 大小：`54,437,205` bytes
- SHA-256：`086ebaf7fc743ded373a1e297f2dbe2ccb653df094de0958760815476aea3a96`
- 签名：Android Debug keystore；APK Signature Scheme v2

## 主要变化

### Minis Web 成为正式 Harness Client Plugin

- 新增正式 DeepSeek Harness Client Plugin `@openminis/minis-client-settings`（源码与可重复构建位于
  `web/minis-client-plugin/`）：通过官方 `settings.section` slot 注册「Minis 控制台」，复用
  `createSnapshotStore` 与 locale 基础设施，完全以 React 投影 Android 权威状态；
- 移除 `minis-control.js/css`、`data-minis-control-host` DOM 桥、MutationObserver 和未托管全局轮询；
  `@deepseek-ai/dsh-client-ui-settings-general` 恢复为上游 rc.8 官方产物；
- 原 12 个控制台页面（overview/providers/skills/mcp/memory/system/scheduled/agent/web/device/
  diagnostics/advanced）全部保留并在 React 树中重建。

### Android Debug / Root 能力增强

- 新增六个高内聚 Agent 工具：`android_capabilities`、`android_app`、`android_ui`、`android_logs`、
  `android_diagnose`、`android_deploy`，全部复用现有 Accessibility、Vision、Shizuku、PRoot、
  ApprovalSeam、JobRegistry、ToolCheckpointStore 与 SpillPolicy；
- 只读能力矩阵（`AndroidCapabilityResolver`）：root/privileged shell/UI/debug/execution/包可见性
  逐项探测并返回 `AVAILABLE / PARTIAL / UNAVAILABLE / REQUIRES_USER_GRANT`；`uid=0` 不再被视为全能力；
- `PrivilegedCommandRunner`：Root `su` backend（被动检测、主动授权探测、uid/gid/groups/CapEff/SELinux）
  与现有 Shizuku 按操作选择；install/uninstall/clear/root 操作经 `ApprovalSeam` 一次性审批；
- Accessibility UI 观察支持 generation/ref + 窗口指纹 + `STALE_UI_REF`；Unicode 输入优先
  `ACTION_SET_TEXT`，失败时走保存/恢复剪贴板的 `ACTION_PASTE`；
- logcat 支持 mark_cursor → 操作 → read since cursor（含 PID 变化与 boot change 检测），
  watch 复用 JobRegistry，大输出复用 SpillPolicy；
- APK 部署只按真实 Gradle output 元数据与 archive 信息发现/检查，不猜固定路径；
  明确拒绝安装自身（self-update continuous execution = UNSUPPORTED）；
- native chroot/mount 仅为实验性 `probe_native_chroot`；PRoot 仍是默认执行环境，不修改全局 SELinux。

## 验证

- JDK 17 / Android SDK 36：`:app:compileDebugKotlin`、`:app:assembleDebug` 通过；
- OpenMinis Web Client Plugin：`tsc --noEmit` 与 vitest 6/6 通过；
- Android 新增工具单元测试 47/47 通过；全量 779 测试仅 14 个既有 OpenAI MockWebServer 环境基线失败；
- Android 16 arm64 真机（Xiaomi 15）：`adb install -r` 成功，MainActivity 启动并进入前台。

## 限制

- 这是 Debug 签名构建，不应作为生产包或应用商店包；
- 只支持 arm64；
- HyperOS 需要用户手动允许后台运行和自启动，否则退到后台后可能冻结网络处理；
- 新增 SAF 外部目录、系统角色、悬浮窗、电池和自启动权限必须在手机系统界面授权；
- 当前沙箱仍为 Alpine + PRoot；native chroot/mount namespace 仅实验性 probe；
- Shizuku/AXManager/Sui 是可选能力桥，不是普通 Agent 或 PRoot 的必需依赖；
- Root 场景（Magisk/KernelSU/APatch 授权、SELinux 拒绝、capability 缺位）与完整 Debug Loop
  的设备级验证仍在进行中。
