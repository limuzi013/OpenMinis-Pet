# OpenMinis Pet

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/limuzi013/OpenMinis-Pet/releases)
[![Release](https://img.shields.io/badge/release-v1.12--pet.15-blue)](https://github.com/limuzi013/OpenMinis-Pet/releases/tag/v1.12-pet.15)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#安装)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

OpenMinis Pet 是 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 的非官方 Android 分支。
它保留原生 Agent、Provider、会话和 PRoot 沙箱，在此基础上加入桌面宠物、默认数字助手，以及与
Android 数据共用同一后端的 Minis Web 远程工作台。

> 本项目与 DeepSeek 没有产品关联或官方合作关系。Minis Web 基于 DeepSeek Harness
> `0.1.0-rc.8` 的 MIT 源码进行 source-adapted 移植，并保留必要的内部模块 ID、版权和许可证。
>
> 问题请提交到[本仓库 Issues](https://github.com/limuzi013/OpenMinis-Pet/issues)，不要向上游
> OpenMinis 或 DeepSeek 报告本分支特有问题。

## 当前版本

| 项目 | 值 |
|---|---|
| Release | [`v1.12-pet.15`](https://github.com/limuzi013/OpenMinis-Pet/releases/tag/v1.12-pet.15) |
| Android 版本 | `1.12-pet.15-SNAPSHOT`（versionCode 35） |
| applicationId | `dev.openminispet.android` |
| ABI | `arm64-v8a` |
| APK | `OpenMinis-Pet-minis-web-pet15-arm64-debug.apk` |
| 大小 | `54,442,918` bytes |
| SHA-256 | `ed8355f6b4ccd0416d7edc82bc3729dc4398e98315b80664a7c9571bf8209fc0` |

**当前 APK 使用 Android Debug 签名，只适合开发、自测和源码对应验证，不是生产发布包。**
生产分发必须关闭 DebugServer、改用长期保管的 release keystore，并完成独立安全验收。

- [下载 APK](https://github.com/limuzi013/OpenMinis-Pet/releases/download/v1.12-pet.15/OpenMinis-Pet-minis-web-pet15-arm64-debug.apk)
- [查看对应源码](https://github.com/limuzi013/OpenMinis-Pet/tree/v1.12-pet.15)
- [查看发布说明](RELEASE-NOTES.md)

## 它是什么

```text
Android 原生 App（唯一运行时与数据源）
├─ ChatViewModel / Agent Loop / Room / Repository
├─ Alpine arm64 rootfs + PRoot（复用 Android 手机内核）
├─ 桌面宠物与默认数字助手
├─ 可选 Shizuku/AXManager/Sui Android 能力桥
└─ RemoteAccessServer
   ├─ 登录、Host 校验、RPC allow/deny policy
   ├─ DshApiAdapter / SessionEventHub
   └─ assets/minis/（默认 Minis Web）
```

浏览器不是第二套 Agent。网页中的会话、模型、思考等级、Workspace、Goal、Skills、MCP、记忆、
定时任务和设置最终都映射到 Android 现有的 ViewModel、数据库或 Repository。`assets/remote/`
只保留旧路径和许可证兼容资源；`/`、`/remote/`、`/dsh/` 最终都进入默认 Minis Web。

## 主要功能

### Android Agent 与沙箱

- 多 Provider、模型组、OAuth/API Key、图片输入、会话历史和工具调用；
- 每个会话复用持久 PRoot Shell，工作区、附件、产出和共享目录分层挂载；
- 文件编辑支持并发串行化、revision 校验、重叠编辑拒绝及大输出落盘；
- Goal、Todo、Plan、产出文件、反馈、提问卡片和子代理工具使用原生状态源；
- Skills、MCP、记忆/SOUL、环境变量、外部 SAF 挂载和定时任务管理。

### Minis Web

- 统一的登录页、会话列表、消息/推理/工具事件和响应式设置界面；
- Workspace 映射 Android 会话分组，Goal 映射 `AgentStateStore`；
- Provider、模型、Skills、MCP、记忆/SOUL、环境、挂载、定时任务和 Agent 设置；
- Skills/MCP 可粘贴配置或从公开 HTTPS URL 导入；导入器限制 HTTPS/443、重定向、大小，
  并拒绝 localhost、私网、链路本地和 CGNAT；
- Session/Workspace 可见页定期与 Android 权威基线对账，设置写入带 revision 冲突检测；
- 本地监听、显式 LAN 模式和 Cloudflare named tunnel；移动网络 tunnel 固定 HTTP/2。

### Android 集成

- 通用 ZIP 桌面宠物包、悬浮窗、状态动画、模型直聊与语音配置复用；
- Android `ROLE_ASSISTANT`、VoiceInteraction Session/Recognition 服务及系统助手入口；
- Shizuku、AXManager 或 Sui 是**可选**的 Android shell/Binder 能力桥，普通聊天和 PRoot
  沙箱不依赖它们。

## 安全边界

Web Remote 可能通过 Tunnel 暴露到公网，因此默认坚持最小权限：

- 未设置登录密码时拒绝启动；登录使用 PBKDF2-HMAC-SHA256 和 HttpOnly Session Cookie；
- 默认只监听 `127.0.0.1`，LAN 访问必须显式开启；LAN 模式是明文 HTTP，不应在不可信网络使用；
- RPC 使用 allowlist，并额外拒绝 Provider 凭据导入/导出、日志正文和崩溃正文等敏感方法；
- Provider Key、环境变量值以及 MCP Header/环境值不通过读取接口返回；
- Web 不开放截图、点击、输入注入、设备 UI 控制、任意 Shell、任意文件访问或 Root；
- 默认 Workspace Write 只允许网页写 `/var/minis/workspace`；Full Access 必须在设置中二次确认；
- 新增外部目录必须由用户在 Android SAF 系统选择器中授权，网页只能管理已有挂载。

详细协议与设计见 [Web Remote RPC](docs/WEB-REMOTE-RPC.md) 和
[安全加固设计](docs/DESIGN-HARDENING-2026-08-21.md)。

## Linux、Ubuntu、Root 与 Shizuku

当前源码中的 `PRootKernel` 名称是历史命名，它**不是 App 自带的 Linux 内核**。现状是：

```text
Android 手机内核 → OpenMinis App UID → PRoot → Alpine rootfs
```

| 方案 | 当前状态 | 是否需要 Root | 是否有独立内核 |
|---|---|---:|---:|
| Alpine + PRoot | 已实现 | 否 | 否 |
| Ubuntu rootfs + PRoot | 未实现，可增加为可选 profile | 否 | 否 |
| Ubuntu rootfs + `su`/namespace/chroot | 未实现 | 是 | 否 |
| QEMU/KVM + Ubuntu kernel/rootfs | 未实现，需单独评估 | KVM 通常需要 | 是 |

设备通过 Magisk、KernelSU 或 APatch Root 后，App 可以直接执行 `su`，由 Root 管理器向用户弹出
授权，不必依赖 Shizuku；但本仓库目前**没有直接 `su`/chroot 后端**。Android 也不存在可写进
Manifest 的标准“Root 权限”。Root 模式若实现，必须留在手机本地并与公网 Web 隔离。

完整概念和实现边界见 [App 沙箱、Ubuntu 与 Root](docs/LINUX-SANDBOX-ROOT-AND-UBUNTU.md)。
Xiaomi 15 `dada` 的手机系统级移植评估另见
[Xiaomi 15 Linux 评估](docs/LINUX-ON-XIAOMI-15-DADA.md)；那不是本 APK 的安装步骤。

## 安装

要求 Android 8.0（API 26）或更高版本的 arm64 设备。

```bash
adb install -r OpenMinis-Pet-minis-web-pet15-arm64-debug.apk
```

`applicationId` 与官方版不同，因此可以和官方 OpenMinis 同时安装。首次使用建议：

1. 在 Provider 设置中添加 API Key 或完成对应 OAuth；
2. 如需桌面宠物，在 Android 系统界面授予悬浮窗权限；
3. 如需 Web Remote，先设置强密码，再选择仅本机、LAN 或 Cloudflare Tunnel；
4. HyperOS 用户在系统设置中允许后台运行并开启自启动，否则退到后台后可能冻结 Tunnel；
5. 系统角色、电池豁免、自启动和 SAF 目录都必须由用户在手机系统界面授权。

## 从源码构建

构建环境：Linux/WSL、JDK 17、Android SDK 36、NDK r28+、CMake 3.22.1。
仓库只构建 `arm64-v8a`。

```bash
git clone --recurse-submodules https://github.com/limuzi013/OpenMinis-Pet.git
cd OpenMinis-Pet

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # 按本机版本调整

./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh

cd src/android
./gradlew :app:assembleDebug --no-daemon
```

APK 输出：`src/android/app/build/outputs/apk/debug/app-debug.apk`。

`deps/proot` 是固定 commit 的 Git submodule；两个 Android ELF loader 是仓库中明确保留并校验
SHA-256 的 vendored Termux 构建。Alpine rootfs 和 fork-built PRoot 二进制属于可重建产物，
不提交到 Git。完整说明见 [BUILD-CN.md](BUILD-CN.md) 或 [BUILDING.md](BUILDING.md)。

## 验证状态与已知限制

pet.15 已完成的验证包括：

- JDK 17 / SDK 36 下 `:app:assembleDebug`；
- `DshApiAdapterTest` 与更新版本排序回归测试；
- Android 16 arm64 真机上的 DSH strict response、settings revision 和 Workspace Repository
  round-trip（2/2）；
- Skill/MCP HTTPS 导入、私网拒绝和测试数据清理；
- APK Signature Scheme v2、覆盖安装保留数据、本机服务和公网登录入口。

仍需注意：

- 当前只有 arm64 debug APK，不支持 32 位设备和 x86 模拟器；
- 38 项 Provider 测试依赖公开仓库未提供的 OAuth 定制值或网络 fixture；
- HyperOS 的后台冻结不能由 App 静默解除，需用户手动设置电池与自启动策略；
- Cloudflare 客户端下载流程目前尚未固定版本与 SHA-256，生产发布前应补齐供应链固定；
- Ubuntu rootfs、直接 Root 后端、独立 VM kernel 都尚未实现；
- Shizuku/Root、系统助手、悬浮窗、通知、电池和外部目录权限均不能由网页代替用户授权。

## 仓库与文档

| 路径 | 内容 |
|---|---|
| `src/android/` | Android App、Compose UI、Room、Provider 与 Remote 后端 |
| `src/android/app/src/main/assets/minis/` | 默认 Minis Web bundle、登录页和控制台 |
| `src/android/app/src/main/assets/remote/` | 旧 Web 兼容资源与第三方许可证 |
| `src/shared/` | Android 构建复用的共享规则/资源 |
| `deps/` | PRoot submodule 与原生依赖构建脚本 |
| `releases/` | 明确发布且与源码对应的 APK |
| [`docs/README.md`](docs/README.md) | 当前文档索引及历史文档分类 |
| [`CHANGELOG-FORK.md`](CHANGELOG-FORK.md) | 按时间记录的 Fork 改动历史 |
| [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) | 第三方许可证与来源 |
| [`README-upstream.md`](README-upstream.md) | 上游 OpenMinis 原始说明存档 |

## 与上游的关系和许可证

| | 官方 OpenMinis | OpenMinis Pet |
|---|---|---|
| applicationId | `com.openminis.app` | `dev.openminispet.android` |
| 平台 | Android + iOS | Android only |
| 安装关系 | — | 可与官方版共存 |
| Kotlin namespace | `com.openminis.app` | 保持不变，便于合并上游 |

本仓库是 OpenMinis 的派生作品，整体继续按 [GPL-3.0](LICENSE) 分发。分发修改后的 APK 时必须
同时提供对应源码。DeepSeek Harness、主题、PRoot、Alpine 及 Android 依赖的许可信息见
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
