# Xiaomi 15 (`dada`) 上运行 Ubuntu / 更换内核的可行性

评估日期：2026-08-22。本文只讨论 Xiaomi 15 的**手机系统级**移植；本轮没有刷写 `boot`、
`init_boot`、`vendor_boot`、`dtbo`、`vbmeta`，也没有让手机进入 fastboot。若问题是替换 App
内的 Alpine 用户空间、直接申请 Root 或运行独立 VM kernel，请改看
[`LINUX-SANDBOX-ROOT-AND-UBUNTU.md`](LINUX-SANDBOX-ROOT-AND-UBUNTU.md)。

## 结论

**可以做，但要先区分“Ubuntu 用户空间”和“替换手机内核”。**

- Ubuntu 不是一种独立于 Linux 的“Ubuntu 内核”；Ubuntu 是用户空间、软件仓库与一套经过
  Ubuntu 配置的 Linux kernel 发行组合。
- Android 当前本身就在运行 Linux。测试机报告的是 arm64 Qualcomm SM8750、Android GKI
  6.6 系列，并采用 A/B、动态分区和 AVB。
- 把 OpenMinis 的 PRoot 根文件系统从 Alpine 换成/增加 Ubuntu arm64：**可行且低风险**，
  但进程仍是 Android App UID，仍受 Android SELinux 与权限沙箱约束，不会因此获得 Root、
  摄像头、触摸、电话或其他 App 数据权限。
- 用现有 Android kernel + vendor 驱动启动 Ubuntu Touch/Droidian 风格用户空间：**理论可行，
  但属于完整设备移植项目**，需要 Halium/libhybris、initramfs、分区和图形/音频/电源适配。
- 直接刷一个通用 Ubuntu/mainline kernel：**不能即插即用**。即便能到 early console，触摸、
  UFS、显示、GPU、Wi-Fi、蓝牙、充电、休眠、摄像头和基带都取决于 SM8750 与 `dada` 的
  device tree、固件、vendor modules 和用户空间适配。

“已经有人在手机上成功运行 Ubuntu”通常属于以下四类之一，不能只凭桌面截图判断：

1. Termux/PRoot/chroot/container（Android kernel 没换）；
2. Android kernel + Ubuntu rootfs/桌面（内核没换或只换成兼容 Android kernel）；
3. Halium/Ubuntu Touch/Droidian（保留大量 Android vendor 栈）；
4. postmarketOS/mainline/UEFI 真原生启动（设备专门移植，硬件完成度因机型而异）。

## 这台设备为什么比普通锁机更有希望

测试机的启动状态显示 Bootloader 已解锁、AVB 为 orange；另外公开社区已经存在：

- Xiaomi 官方 `Xiaomi_Kernel_OpenSource` 的 `dada-v-oss` 分支；
- `dada-devel/device_xiaomi_dada`；
- `dada-devel/device_xiaomi_sm8750-common`；
- `dada-devel/device_xiaomi_dada-kernel`。

这些资料证明 `dada` 已有自定义 Android/GKI 构建基础，**不等于已有可日用 Ubuntu port**，
但比完全没有 device tree、kernel modules 和 firmware 索引的设备起点好很多。

## Android GKI 带来的限制

GKI 把通用内核与厂商模块分开。手机能工作的关键不只是 `Image`，还包括匹配 KMI/ABI 的
vendor modules、DTB/DTBO、固件、ramdisk、启动参数和 Android vendor 分区。Ubuntu 的通用
arm64 kernel 通常不与这些模块 ABI 匹配；只替换 `boot.img` 中的 kernel 很可能在启动早期失败，
或者启动后没有显示、存储、网络和触摸。

因此，第一条真正可走的系统路线通常不是“把通用 Ubuntu kernel 刷进去”，而是：

1. 先保留/重编译 `dada` Android GKI 与 vendor modules；
2. 用最小 initramfs 验证串口或 USB gadget、存储和 SSH；
3. 再启动 Ubuntu rootfs；
4. 选择 Halium/libhybris 复用 Android vendor 栈，或逐项推进 mainline 驱动；
5. 最后才处理图形桌面、电话、待机、摄像头等日用能力。

## Shizuku 到底是不是必须

**不是。** Shizuku 只适用于“继续运行 Android，同时让普通 App 经用户授权调用部分 shell/
Binder 能力”的中间档方案：

| 路线 | 是否需要 Shizuku | 能力边界 |
|---|---|---|
| 普通 App + PRoot Ubuntu | 否 | 仍是 App UID/SELinux 沙箱 |
| Android + Shizuku | 可选 | `shell` 级部分服务，不是 Root |
| Android + Root 守护进程 | 否 | Root 能力；风险和责任显著提高 |
| Ubuntu Touch/Droidian/原生 Linux | 否 | 权限由新系统决定，Shizuku 不存在 |

OpenMinis 如果继续作为 Android App，Shizuku 是比 Root 更容易撤销的一种增强方式；它并不是
换 rootfs 或换内核的前置条件。设备通过 Magisk、KernelSU 或 APatch Root 后，未来的 App 后端
可以直接调用 `su`，由 Root 管理器弹窗授权，不必依赖 Shizuku；但 pet.15 尚未实现该
`su`/namespace/chroot 后端。

## 推荐的安全推进顺序

### A. App 运行环境（可在本仓库实施）

1. 保留当前 Alpine，不破坏已有会话；
2. 增加可选 Ubuntu arm64 rootfs profile，而不是原位覆盖；
3. 校验 rootfs 版本、SHA-256、空间需求和回滚；
4. 跑 shell、Python/Node、Skills/MCP 回归；
5. 明确 UI 提示：更换用户空间不会增加 Android 权限。

### B. 真系统移植（必须独立项目、单独授权）

1. 准备与当前系统版本严格匹配的完整官方 fastboot ROM；
2. 保存 `boot`、`init_boot`、`vendor_boot`、`dtbo`、`vbmeta` 等恢复材料，并先验证恢复流程；
3. 确认该机型能否使用 `fastboot boot` 做**临时启动**；能临时启动就不要先 flash；
4. 从官方 `dada-v-oss` 与社区 Lineage device tree 建最小 kernel/initramfs；
5. 首个里程碑只要求 USB 网络/SSH，不碰日常系统分区；
6. 再评估 Halium 与 mainline 两条路线的显示、触摸、UFS、Wi-Fi 和电源完成度；
7. 在 recovery/EDL 恢复路径没有验证前，不做持久刷写。

SM8750 新平台的 EDL 往往有厂商认证限制。Bootloader 已解锁能降低正常 fastboot 实验门槛，
但不能保证硬砖后可自行 EDL 救回。

## 本轮决定

- 不把“Ubuntu PRoot”描述成“更换内核”；
- 不为获得手机权限而盲目刷通用 Ubuntu kernel；
- 不在 OpenMinis APK 更新流程中夹带 boot image 或自动刷写；
- 如要继续真系统移植，应新建独立仓库和恢复清单，并再次取得用户明确授权。

## 参考资料

- Android Generic Kernel Image：<https://source.android.com/docs/core/architecture/kernel/generic-kernel-image>
- Halium porting first steps：<https://docs.halium.org/en/latest/porting/first-steps.html>
- UBports porting introduction：<https://docs.ubports.com/en/latest/porting/introduction/Intro.html>
- postmarketOS device porting：<https://wiki.postmarketos.org/wiki/Porting_to_a_new_device>
- Xiaomi kernel source：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/tree/dada-v-oss>
- `dada` device tree：<https://github.com/dada-devel/device_xiaomi_dada>
- SM8750 common device tree：<https://github.com/dada-devel/device_xiaomi_sm8750-common>
- `dada` kernel tree：<https://github.com/dada-devel/device_xiaomi_dada-kernel>
