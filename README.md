# Minis for Android

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/limuzi013/minis-for-android/releases)
[![Release](https://img.shields.io/badge/release-v1.00--beta-blue)](https://github.com/limuzi013/minis-for-android/releases/tag/v1.01-beta)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#安装)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Minis 是一个 **Android 原生 Agent 运行时**:手机上运行完整的 LLM Agent(工具调用、文件编辑、
持续 shell、网页浏览、目标管理),内置 **Alpine Linux + PRoot** 沙箱作为默认执行环境,
并提供与 Android 共用同一数据源的 **Minis Web** 远程工作台。

```text
Android 原生 App（唯一运行时与数据源）
├─ Agent Loop / Room / Repository / 会话状态
├─ Alpine rootfs + PRoot（复用 Android 手机内核）
├─ 桌面宠物与默认数字助手
├─ 可选 Shizuku / Sui 权限桥
└─ RemoteAccessServer
   ├─ 登录、Host 校验、逐能力 RPC 授权
   ├─ DshApiAdapter / SessionEventHub
   └─ assets/minis/（Minis Web,含正式 Client Plugin 控制台）
```

浏览器不是第二套 Agent:网页中的会话、模型、Workspace、Goal、Skills、MCP、记忆、定时任务和设置
最终都映射到 Android 现有的 ViewModel、数据库或 Repository。

## 当前版本

| 项目 | 值 |
|---|---|
| Release | [`v1.01-beta`](https://github.com/limuzi013/minis-for-android/releases/tag/v1.01-beta) |
| Android 版本 | `1.01-beta`(versionCode 37) |
| applicationId | `dev.openminispet.android` |
| ABI | `arm64-v8a` |
| APK | `OpenMinis-Pet-1.01-beta-arm64-debug.apk` |
| 大小 | `54478422` bytes |
| SHA-256 | `388a843bbb63c4f6d6c5373fde4656330651d5ed27a2d499caa4e966e697f909` |

**当前 APK 使用 Android Debug 签名,仅供开发、自测与源码对应验证,不是生产发布包。**
生产分发必须关闭 DebugServer、改用长期保管的 release keystore,并完成独立安全验收。

- [下载 APK](https://github.com/limuzi013/minis-for-android/releases/download/v1.01-beta/OpenMinis-Pet-1.01-beta-arm64-debug.apk)
- [查看对应源码](https://github.com/limuzi013/minis-for-android/tree/v1.01-beta)
- [发布说明](RELEASE-NOTES.md)

## 主要功能

### Agent 与沙箱

- 多 Provider、模型组、OAuth/API Key、图片输入、会话历史和工具调用;
- 每个会话复用持久 PRoot Shell;工作区、附件、产出和共享目录分层挂载;
- 文件编辑支持并发串行化、revision 校验、重叠编辑拒绝及大输出落盘;
- Goal、Todo、Plan、产出文件、反馈、提问卡片和子代理工具使用原生状态源;
- Skills、MCP、记忆/SOUL、环境变量、外部 SAF 挂载和定时任务管理;
- 工具超时、执行意图检查点、后台作业、Token 计量、上下文压力、结果修剪/落盘、
  一次性审批和危险命令策略。

### Minis Web

- 统一的登录页、会话列表、消息/推理/工具事件和响应式设置界面;
- 「Minis 控制台」是正式 DeepSeek Harness Client Plugin(`web/minis-client-plugin/` →
  `assets/minis/plugins/@openminis/minis-client-settings/client.js`):走官方
  `settings.section` slot、`createSnapshotStore` 与 locale 基础设施,纯 React 投影
  Android 权威状态,不依赖 DOM 覆盖层;
- Provider、模型、Skills、MCP、记忆/SOUL、环境、挂载、定时任务和 Agent 设置;
- Skills/MCP 可粘贴配置或从公开 HTTPS URL 导入;导入器限制 HTTPS/443、重定向、大小,
  并拒绝 localhost、私网、链路本地和 CGNAT;
- Session/Workspace 可见页定期与 Android 权威基线对账,设置写入带 revision 冲突检测;
- 本地监听、显式 LAN 模式和 Cloudflare named tunnel;移动网络 tunnel 固定 HTTP/2。

### Android Debug 工具链

- `android_capabilities` / `android_app` / `android_ui` / `android_logs` /
  `android_diagnose` / `android_deploy` 六个高内聚 Agent 工具;
- 只读能力矩阵:root、Shizuku、Accessibility、截图、调试器、执行环境逐项真实探测,
  `uid=0` 不会被当作全能力;
- Accessibility UI 观察带 generation/ref 与 `STALE_UI_REF`;API 30+ 系统截图;
- logcat 游标(mark → 操作 → read since),watch 复用作业系统,大输出自动落盘;
- APK 按真实 Gradle output 元数据发现/部署,不猜固定路径;
- 支持 Android 原生 SDK API、Shizuku 与主动授权后的 Root `su`。

### Android 集成

- 通用 ZIP 桌面宠物包、悬浮窗、状态动画、模型直聊与语音配置复用;
- Android `ROLE_ASSISTANT`、VoiceInteraction Session/Recognition 服务及系统助手入口;
- Shizuku、AXManager 或 Sui 是**可选**的 Android shell/Binder 能力桥,普通聊天和 PRoot
  沙箱不依赖它们。

## 安全边界

Web Remote 可能通过 Tunnel 暴露到公网,因此默认坚持最小权限:

- 未设置登录密码时拒绝启动;登录使用 PBKDF2-HMAC-SHA256 和 HttpOnly Session Cookie;
- 默认只监听 `127.0.0.1`,LAN 访问必须显式开启;LAN 模式是明文 HTTP,不应在不可信网络使用;
- RPC 使用「方法 → 能力」显式映射表(`RemoteCapabilityCatalog`),未登记/未来新增的方法默认拒绝;
  日志与崩溃正文受 `diagnostics.content`(默认关闭)保护;
- Provider Key、环境变量值以及 MCP Header/环境值不通过读取接口返回;
- 设备控制(截图/点击/滚动/输入)、界面检查、凭据导出、任意路径文件访问与管理员操作默认关闭,
  需在手机或网页「权限」页逐项开启;
- 工作区文件读写默认开启且始终限定在 `/var/minis/workspace`;工作区以外路径需要默认关闭的
  `sandbox.fs`;
- `permission.manage` 在 Web 端关闭后,网页无法再修改任何能力开关,只能回手机恢复;
- 新增外部目录必须由用户在 Android SAF 系统选择器中授权,网页只能管理已有挂载;
- 安装/卸载/清空日志/Root 授权等有副作用操作经过一次性审批(ApprovalSeam);
- Web 不暴露裸 shell/root/tap/install 等设备直控接口。

详细协议见 [Web Remote RPC](docs/WEB-REMOTE-RPC.md) 与 [安全设计](docs/SECURITY.md)。

## 执行环境:PRoot、Root 与 Shizuku

源码中的 `PRootKernel` 名称是历史命名,它**不是 App 自带的 Linux 内核**。现状是:

```text
Android 手机内核 → OpenMinis App UID → PRoot → Alpine rootfs
```

| 方案 | 当前状态 | 是否需要 Root | 是否有独立内核 |
|---|---|---:|---:|
| Alpine + PRoot | 已实现(默认) | 否 | 否 |
| Ubuntu rootfs + PRoot | 未实现,可增加为可选 profile | 否 | 否 |
| Root `su` 直接执行 | 已实现(主动探测后端) | 是 | 否 |
| Native chroot/mount namespace | 实验性 `probe_native_chroot` | 是 | 否 |
| QEMU/KVM + Ubuntu kernel/rootfs | 未实现,需单独评估 | KVM 通常需要 | 是 |

- Root 设备通过 `su` 工作,不依赖 Shizuku,兼容 Magisk / KernelSU / APatch;
  Root provider 名称只作为诊断信息,能力判断以真实探测为准;
- 被动能力查询(如 `android_capabilities get`)不会触发 Root 授权弹窗;只有显式
  `active_root_probe` 才请求授权并返回 uid/gid/groups/CapEff/SELinux;
- native chroot/mount 仅实验性;通过完整 parity tests 前不会替换 PRoot;
  不修改全局 SELinux 策略,chroot 不是容器,构建脚本原则上默认非 root 执行。

完整边界见 [执行环境](docs/EXECUTION-ENVIRONMENT.md)。

## 安装

要求 Android 8.0(API 26)或更高版本的 arm64 设备。

```bash
adb install -r OpenMinis-Pet-1.01-beta-arm64-debug.apk
```

首次使用建议:

1. 在 Provider 设置中添加 API Key 或完成对应 OAuth;
2. 如需桌面宠物,在 Android 系统界面授予悬浮窗权限;
3. 如需 Web Remote,先设置强密码,再选择仅本机、LAN 或 Cloudflare Tunnel;
4. HyperOS 用户在系统设置中允许后台运行并开启自启动,否则退到后台后可能冻结;
5. 系统角色、电池豁免、自启动和 SAF 目录都必须由用户在手机系统界面授权。

## 从源码构建

构建环境:Linux/WSL、JDK 17、Android SDK 36、NDK r28+、CMake 3.22.1。
仓库只构建 `arm64-v8a`。完整步骤见 [BUILD-CN.md](BUILD-CN.md) 或 [BUILDING.md](BUILDING.md)。

```bash
git clone --recurse-submodules https://github.com/limuzi013/minis-for-android.git
cd OpenMinis-Pet

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"

./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh

cd src/android
./gradlew :app:assembleDebug --no-daemon
```

APK 输出:`src/android/app/build/outputs/apk/debug/app-debug.apk`。
Minis Web Client Plugin 单独构建:`cd web/minis-client-plugin && npm install && npm run build`
(生成 browser bundle 并与 Android assets 同步)。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `src/android/` | Android App、Compose UI、Room、Provider 与 Remote 后端 |
| `src/android/app/src/main/assets/minis/` | 默认 Minis Web bundle、登录页与官方插件产物 |
| `web/minis-client-plugin/` | Minis Client Plugin 源码、测试与可重复构建脚本 |
| `src/android/app/src/main/java/com/openminis/app/tools/android/` | Android Debug 工具链 |
| `src/shared/` | Android 构建复用的共享规则/资源 |
| `deps/` | PRoot submodule 与原生依赖构建脚本 |
| `releases/` | 明确发布且与源码对应的 APK |
| [`docs/README.md`](docs/README.md) | 文档索引 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本变更记录(自 1.01-beta 起) |
| [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) | 第三方许可证与来源 |

## 起源与许可证

本项目作为独立项目发布,首版为 `v1.01-beta`。其代码谱系包含:

- [OpenMinis](https://github.com/OpenMinis/OpenMinis)(GPL-3.0,派生基础);
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) `0.1.0-rc.8`
  (MIT,Minis Web 的 source-adapted 前端与 wire schema);
- PRoot、Alpine、Termux 与各 Android 依赖(见
  [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) 与
  [README-upstream.md](README-upstream.md))。

本项目整体按 [GPL-3.0](LICENSE) 分发。分发修改后的 APK 时必须同时提供对应源码。

> 本项目与 DeepSeek 没有产品关联或官方合作关系。问题请提交到
> [本仓库 Issues](https://github.com/limuzi013/minis-for-android/issues)。
