# 参与贡献

欢迎为 Minis for Android 提交 Issue 与 Pull Request。

## 报告问题

打开: <https://github.com/limuzi013/minis-for-android/issues>

请包含:

- 应用版本/versionCode 与 Android 版本;
- 设备型号/ROM,以及是否 Root(最好注明 Magisk/KernelSU/APatch,仅作诊断信息);
- 精确重现步骤、期望结果与实际结果;
- 相关 Provider/模型;
- 已脱敏的应用日志或崩溃元数据。

**禁止粘贴**:API Key、OAuth token、Web Remote 密码、Cloudflare Tunnel token、DebugServer
token、私密文件内容或无关手机数据。请针对应用本身重现,不要检查其他应用。

## 提交 Pull Request

小的、聚焦的 PR 受欢迎。提交前:

1. 基于当前 `master` 分支;
2. 保持 **Android 为唯一权威数据源** — 不要新增第二套 Web-only Agent/runtime/数据库;
3. 保留 Web Remote 的「方法→能力」映射与 secret 只写策略;
4. 不要通过 Web 暴露截图、输入注入、任意 Shell/文件、凭据、`su` 或 Root 能力;
5. 保留第三方声明与必需的 `@deepseek-ai/dsh-*` 兼容 ID;
6. 更新测试与当前文档,而不是只改历史日志。

## 架构红线

- 不重复实现第二套 AccessibilityService、Shizuku、PRoot、Job 系统、审批/检查点/Token 计量;
- 不把 Root、Shizuku、Accessibility、PRoot/chroot 混成一条「权限等级链」;
- 能力判断必须来自真实探测(`uid=0` 不代表全能力),Root provider 名称只作诊断;
- native chroot/mount 只允许作为实验性后端;通过完整 parity tests 前不得替换 PRoot;
  禁止为可用性关闭全局 SELinux;
- 不手改生成的 Web bundle 或 `__MINIS_BOOT__`;Web 产物只能由
  `web/minis-client-plugin` 的 `npm run build` 生成;
- 有副作用操作(install/uninstall/clear/root/mount/chroot)必须接入一次性审批,
  并复用检查点/ToolResult 治理;
- 不伪造测试结果;无法设备验证的能力在文档中明确标注。

## 验证检查

提交前至少运行与你改动相关的最窄检查:

- Kotlin:`cd src/android && ./gradlew :app:compileDebugKotlin --no-daemon`
- JVM 单测:改动相关 `--tests` 过滤;
- 修改了 Android 测试时:`./gradlew :app:assembleDebugAndroidTest`
- 修改了生产源码/资源时:`./gradlew :app:assembleDebug`
- 修改了 Web 插件:`cd web/minis-client-plugin && npm run check && npm test && npm run build`

详细构建与环境见 [BUILD-CN.md](BUILD-CN.md);安全边界见 [docs/SECURITY.md](docs/SECURITY.md)。
