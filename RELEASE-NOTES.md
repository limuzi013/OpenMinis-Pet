# OpenMinis Pet `v1.12-pet.15`

发布日期：2026-08-22。该版本是 Android arm64 开发/自测构建，与 tag
[`v1.12-pet.15`](https://github.com/limuzi013/OpenMinis-Pet/tree/v1.12-pet.15) 源码对应。

## 下载与校验

- APK：[`OpenMinis-Pet-minis-web-pet15-arm64-debug.apk`](https://github.com/limuzi013/OpenMinis-Pet/releases/download/v1.12-pet.15/OpenMinis-Pet-minis-web-pet15-arm64-debug.apk)
- applicationId：`dev.openminispet.android`
- versionName：`1.12-pet.15-SNAPSHOT`
- versionCode：35
- ABI：`arm64-v8a`
- 大小：`54,442,918` bytes
- SHA-256：`ed8355f6b4ccd0416d7edc82bc3729dc4398e98315b80664a7c9571bf8209fc0`
- 签名：Android Debug keystore；APK Signature Scheme v2

## 主要变化

### Minis Web 与 Android 共用数据

- `assets/minis/` 成为 `/` 的默认 Web UI；旧 `/remote/`、`/dsh/` 重定向到 `/`；
- Minis 控制台嵌入 DSH Settings，登录页、主题、PWA 和移动端布局统一；
- Workspace 映射原生会话分组，Goal 映射 `AgentStateStore`；
- 主题、语言、权限、模型和会话设置使用 Android 权威数据；
- Session/Workspace 可见页定期 reconciliation，settings 更新带 revision 冲突保护；
- 修复模型显示名与 Entry ID、严格响应字段、会话 fork、图片 prompt、标题和目录边界等问题。

### 管理能力

- Provider、模型、Skills、MCP、记忆/SOUL、环境变量、已有挂载、定时任务和 Agent 设置；
- Skills/MCP 支持粘贴配置和公开 HTTPS URL 导入；
- URL 导入逐跳限制 HTTPS/443、重定向和内容大小，并拒绝 localhost、私网、链路本地与 CGNAT；
- 定时任务支持 CRUD、启停、立即运行和运行历史。

### 安全与可靠性

- 登录 per-IP 限流、Host/DNS rebinding 检查和 HTTP header/request size 限制；
- Provider、环境和 MCP secret 不从读取响应返回；日志/崩溃正文不经 Web RPC 暴露；
- Web 不开放 DebugServer 的截图、点击、输入、任意 Shell、任意文件或 Root 能力；
- Cloudflare named tunnel 固定 HTTP/2，避免部分移动网络长时间等待 QUIC 降级；
- 修复 Fork 更新比较丢失 `pet.N` 的问题，后续 pet 版本可以被正确发现。

## 验证

- JDK 17 / Android SDK 36：`:app:assembleDebug` 通过；
- `DshApiAdapterTest` 与 `UpdateCheckerVersionTest` 通过；
- Android 16 arm64 真机：DSH core/settings revision 与 Workspace Repository round-trip 2/2；
- Skill/MCP HTTPS 导入、私网拒绝和临时测试数据清理通过；
- 覆盖安装保留 App 数据；本机 Web、Tunnel 前台入口和 APK v2 signature 验证通过。

## 限制

- 这是 Debug 签名构建，不应作为生产包或应用商店包；
- 只支持 arm64；
- HyperOS 需要用户手动允许后台运行和自启动，否则退到后台后可能冻结 Tunnel；
- 新增 SAF 外部目录、系统角色、悬浮窗、电池和自启动权限必须在手机系统界面授权；
- 当前沙箱仍为 Alpine + PRoot；Ubuntu rootfs、直接 `su`/chroot 和 VM 独立 kernel 尚未实现；
- Shizuku/AXManager/Sui 是可选能力桥，不是普通 Agent 或 PRoot 的必需依赖。
