# OpenMinis 的 Linux 沙箱、Ubuntu、Root 与 Shizuku

状态：架构说明；更新于 2026-08-22。本文讨论 **App 内沙箱**，不讨论替换手机启动内核。

## 当前实现

Android 版当前使用：

```text
Android 正在运行的 Linux kernel
└─ OpenMinis App（普通 Android UID，SELinux 约束）
   └─ PRoot（路径、UID 与 rootfs 视图转换）
      └─ Alpine Linux arm64 rootfs
```

源码中的 `PRootKernel` 是历史命名。它是 PRoot 配置和命令构造器，不是可以单独启动、替换的
kernel；源码注释也明确说明 PRoot 是 process-per-command/persistent-shell runtime。

因此，当前沙箱里的 `uname`、系统调用、网络栈、调度和驱动都来自 Android 手机内核。Alpine
提供的是 `/bin`、musl、包管理器和其他用户空间文件。

## “换成 Ubuntu”有三种不同含义

| 目标 | 技术路线 | 独立 kernel | Root | 当前实现 |
|---|---|---:|---:|---|
| 使用 Ubuntu 软件仓库和用户空间 | PRoot + Ubuntu Base arm64 rootfs | 否 | 否 | 未实现 |
| 减少 PRoot 翻译、获得更接近原生的容器 | `su` + mount namespace + chroot + Ubuntu rootfs | 否 | 是 | 未实现 |
| 沙箱真正运行 Ubuntu kernel | QEMU/KVM 虚拟机 + kernel/initrd/disk | 是 | 视 KVM 权限而定 | 未实现 |

### PRoot + Ubuntu rootfs

这是风险最低、最符合现有架构的方案。需要新增 rootfs profile，而不是覆盖当前 Alpine：

1. 下载并校验固定版本的 Ubuntu Base arm64 tarball；
2. 安装到独立目录，例如 `files/ubuntu-rootfs`；
3. 将 Minis 的 `/var/minis/*`、DNS、代理、时区和 native-offload wrapper 应用到新 rootfs；
4. 为 Alpine/Ubuntu 分别维护 marker、升级、重置和用户数据迁移；
5. 回归 apt、glibc、Python、Node、Skills、MCP、持久 Shell 和文件映射。

它不需要 Root 或 Shizuku，但仍受 App UID 和 Android SELinux 约束。

### Root + namespace/chroot

如果设备已经通过 Magisk、KernelSU 或 APatch Root，App 可以直接调用 `su`。第一次调用由对应
Root 管理器向用户显示授权弹窗；Android 本身没有可在 Manifest 中声明的标准 Root 权限。

这条路线不依赖 Shizuku。建议的实现不是拼接任意 `su -c "..."` 字符串，而是：

1. 只在手机原生设置页提供“Root 后端”开关；
2. 使用固定参数协议启动经过 hash 校验的最小 privileged helper；
3. helper 创建私有 mount namespace，再 bind `/dev`、`/proc` 和允许的 Minis 目录；
4. chroot 到 Ubuntu rootfs；根据模式保留或主动丢弃 capability/UID；
5. 使用 Unix socket + `SO_PEERCRED` 限定只有本 App UID 能访问 helper；
6. 公网 Web/Tunnel 开启时默认禁用 unrestricted Root shell。

Root UID 不等于自动绕过 SELinux。Magisk/KernelSU 的授权 profile 与设备策略仍可能限制 mount、
其他 App 数据、系统服务和内核接口。

## Shizuku 的位置

Shizuku 是 Android shell/Binder 能力桥，不是 Linux rootfs 或 kernel：

- 普通 Shizuku 通常是 `shell` UID（2000）；
- Sui 可以由 Root 提供 Shizuku 协议；
- 当前 OpenMinis 已集成 Shizuku/AXManager/Sui 兼容后端；
- 普通 Agent、PRoot 和 Ubuntu PRoot profile 都不必依赖 Shizuku；
- 若实现直接 `su` helper，可为相关 Android 操作增加 Root backend，Shizuku 只作为非 Root
  设备的可选回退。

## 真正独立的 Ubuntu kernel

容器、chroot 和 PRoot 都共享 Android kernel。若要求 App 沙箱运行自己的 Ubuntu kernel，必须
运行虚拟机，例如 QEMU `virt` 机器配 Ubuntu arm64 kernel、initrd 和 virtio 磁盘。

- QEMU TCG 可以纯软件模拟，但移动设备上的性能和耗电通常较差；
- KVM 接近原生性能，但需要设备内核启用虚拟化、存在可访问的 `/dev/kvm`，并通过 SELinux；
- Root 可能帮助开放设备节点和策略，但不能保证厂商 kernel 提供可用 KVM；
- Android Virtualization Framework/pKVM 也不等于普通第三方 App 自动获得 VM 权限。

`kexec` 会切换整台手机正在运行的 kernel，不属于“App 内独立沙箱”，不应作为这条路线使用。

## 当前结论

- 当前发布版仍是 **Alpine + PRoot**；
- Ubuntu PRoot profile、直接 `su`/chroot backend 和 QEMU/KVM backend 均未交付；
- 已 Root 的手机可以让未来版本直接向 Root 管理器请求授权，不必依赖 Shizuku；
- 任何 Root 能力都不得通过现有公网 Web RPC 暴露为任意命令执行。
