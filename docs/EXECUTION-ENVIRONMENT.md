# 执行环境:PRoot、Root、Shizuku 与 chroot

本文描述 App 内沙箱与特权执行的真实现状。**不讨论**替换手机启动内核。

## 当前执行链

```text
Android 手机内核（正在运行）
└─ OpenMinis App（普通 Android UID,SELinux 约束）
   └─ PRoot（路径、UID 与 rootfs 视图转换）
      └─ Alpine Linux arm64 rootfs
```

源码中的 `PRootKernel` 是历史命名:它是 PRoot 配置与命令构造器,不是可单独启动的内核。
沙箱里的 `uname`、系统调用、网络栈、调度与驱动全部来自 Android 手机内核;Alpine 提供
`/bin`、musl、包管理器与其他用户空间文件。

| 方案 | 状态 | 需要 Root | 独立内核 |
|---|---:|---:|---:|
| Alpine + PRoot | 已实现(默认) | 否 | 否 |
| Ubuntu rootfs + PRoot | 未实现,可做可选 profile | 否 | 否 |
| Root `su` 直接执行 | 已实现(主动探测后端) | 是 | 否 |
| Native chroot/mount namespace | 实验性 `probe_native_chroot` | 是 | 否 |
| QEMU/KVM + Ubuntu kernel/rootfs | 未实现,需单独评估 | KVM 通常需要 | 是 |

## 三条独立能力,不是权限链

```text
UI capability    AccessibilityService（android-a11y-cli 现有实现）
Privileged shell Root su（主动探测）或 Shizuku（非 Root 兼容路径）
Execution env    PRoot+Alpine（默认）· native chroot（实验）
```

普通 Android SDK API(PackageManager/ActivityManager/AccessibilityService/UsageStats)
由对应 Android service 直接使用,不包装成特权后端。

## Root

- 设备通过 Magisk、KernelSU 或 APatch Root 后直接执行 `su`,不依赖 Shizuku;
- `AndroidCapabilityResolver` 被动读取永远不弹授权窗;只有
  `android_capabilities active_root_probe` 会请求 `ApprovalSeam` 一次性批准并返回:
  - 有效 uid/gid、groups
  - `/proc/self/status` 的 `CapEff`(按位解析,经 `LinuxCapabilityParser`)
  - SELinux context(`id -Z` / `/proc/self/attr/current`)与 mode(`getenforce`)
- Root provider 名称仅作诊断元数据;能力判断只看探测结果;
- 探测结果按 TTL 缓存;任何实际 Root 操作前检查授权与所需 capability 位。

## Shizuku

- 仅作为**非 Root 设备的 privileged shell 兼容路径**;
- 完全复用现有 `ShizukuManager`/`ShizukuBackend`(binder-first 识别官方 Shizuku、
  AXManager、Sui;Sui 标签仅展示,不参与状态判断);
- 需要 privileged shell 的操作统一经 `PrivilegedCommandRunner`,业务代码不散落
  `Shizuku.isXxx` 判断。

## PRoot(默认执行环境)

- 每个会话复用持久 /bin/sh(interactive PTY 由终端使用);`shell_execute` 每次
  `/bin/sh -c`;
- 会话工作区、附件、产出、共享目录、用户 SAF 挂载分层绑定到 `/var/minis/*`;
- 只读挂载有 shell wrapper 与文件工具双重写保护;
- 构建/安装/运行继续优先使用 `terminal_execute`/`shell_execute` + PRoot,
  不建立并行执行器。

## Native Chroot(实验)

`android_capabilities probe_native_chroot` 在 Root 已授权时隔离探测:

```text
chroot / /system/bin/true
unshare -m /system/bin/true
mount --bind <probe> <probe>（随后卸载）
```

- 返回 `chroot`/`namespace`/`bindMount` 三者的真实退出码与整体状态;不假设可用;
- 任何偏差都标为 `PARTIAL`,不会声称容器级安全;
- 未通过完整 parity tests(文件属主、PTY、信号、进程清理、网络/DNS、包管理、
  Gradle/SDK 可见性、namespace 清理)前,**不得替换 PRoot 为默认 backend**;
- 不自动 `setenforce 0`,不修改全局 SELinux,不默认 bind 整个可写 `/sys`;
- Gradle/npm/pip/项目脚本等不可信构建内容:尽可能 drop privileges,禁止默认以
  root 执行;
- chroot **不是 container/sandbox**,Root 只负责环境搭建。

## 自更新限制

覆盖安装 Minis 自身会杀死当前 Agent 进程。`android_deploy`/`android_app install`
对自身包名显式返回:

```text
UNSUPPORTED: installing OpenMinis over itself kills the current Agent process
```

连续自更新执行(install new APK → 原进程继续)与独立 Debug Companion 设计仍未实现,
不会被描述为已支持。
