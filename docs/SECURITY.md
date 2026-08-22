# 安全设计

本文描述 Minis for Android 当前的安全模型与边界。公网可能通过 Cloudflare Tunnel 触达
Web Remote,因此默认坚持最小权限。

## 1. 凭据存储:fail-closed

- `EncryptedPrefsFactory` 在 Tink/EncryptedSharedPreferences 连续创建失败时**不得回退明文**;
  失败路径不会删除全局唯一的 AndroidKeyStore 主密钥别名或共享 Tink keyset 文件;
- Provider API Key、OAuth token、Web Remote 登录盐/哈希、DebugServer token、Cloudflare
  Tunnel token 全部经该工厂落盘;
- `allowBackup=false`;Web Remote 密码使用 PBKDF2-HMAC-SHA256 派生,浏览器只持有 HttpOnly
  Session Cookie。

## 2. Web Remote 认证与传输

- 未设置登录密码时拒绝启动服务;
- 默认只监听 `127.0.0.1`;LAN 模式必须显式开启,且为明文 HTTP,不应在不可信网络使用;
- 登录 per-IP 限流;Host/DNS rebinding 检查;HTTP header 与请求体大小限制;
- WebSocket 升级(Browser→Server 方向)要求严格同源;所有变更请求同源校验,无宽松 CORS;
- Cloudflare named tunnel 固定 HTTP/2(移动网络不再信任 QUIC 预检)。

## 3. Web RPC 授权:方法 → 能力

- `RemoteCapabilityCatalog` 是唯一映射表:每个 RPC/HTTP/DSH 方法映射到一个稳定能力 id
  (`chat`、`files.*`、`device.view`、`device.control`、`ui.inspect`、`providers.*`、
  `credentials.export`、`skills.manage`、`memory.manage`、`mcp.manage`、`environments.manage`、
  `storage.manage`、`scheduled.manage`、`agent.manage`、`permission.manage`、`diagnostics.*`、
  `admin`…),未登记/未来新增方法**默认拒绝**;
- 凭据、环境变量、MCP Header/环境值只写或返回 `hasValue` 元数据,绝不回显;
- `permission.manage` 在 Web 侧被关闭后,一切能力写入(含重新开启它自己)被服务端拒绝,
  只能回手机恢复;
- Web 不提供裸 `POST /root`、`/shell`、`/tap`、`/install`、`/mount` 等设备直控接口;
  Remote Web 是 Agent 控制面,不是裸 Android 远控面板。

## 4. 文件与沙箱边界

- 工作区读写默认开启且限定 `/var/minis/workspace`;其他路径需要默认关闭的
  `sandbox.fs`;
- canonical path containment:所有 Web 文件路径经真实路径归一化与根目录校验;
- 外部 SAF 目录只能由用户在 Android 系统选择器中授权;网页只能管理已有挂载;
- 只读挂载在 shell/文件工具两侧都有写保护(wrapper + 工具层校验);
- 凭据导出/导入(provider.export/import)默认关闭。

## 5. 设备控制与诊断

- 截图(`device.view`)、点击/滚动/输入(`device.control`)、界面检查(`ui.inspect`)、
  浏览器脚本(`browser.execute`)、诊断正文(`diagnostics.content`)、管理员操作(`admin`)
  默认关闭,需用户逐项开启;
- 设备操作要求 Minis App 处于前台;UI 观察只返回语义摘要,不默认回传整棵 UI 树;
- `debuggerd`、tombstone、ANR trace 等按真实权限返回 `AVAILABLE/PARTIAL/UNAVAILABLE`,
  不假定可用。

## 6. Agent 侧安全治理

- **一次性审批**(`ApprovalSeam`):政策 `ask|never`,危险 shell 命令与所有有副作用的
  Android 操作(install/uninstall/clear logs/root 授权/mount/chroot)在批准前不执行;
  无人应答超时视为取消,不运行;
- **危险命令策略**(`DangerousCommandPolicy`):明确破坏性的模式才拦截,避免误伤;
- **执行意图检查点**(`ToolCheckpointStore`):工具体执行前记录 intent,执行后标记;
  进程被杀后下轮注入 `TOOL_OUTCOME_UNKNOWN`,不盲目重试可能有副作用的操作;
- **工具超时**:`AgentToolDefinition.timeoutMs` 产生结构化 `TOOL_TIMEOUT` 结果;
- **结果治理**:`ToolResultPruner` 管理上下文修剪,`SpillPolicy` 把超大结果落盘为
  `/var/minis/offloads` 指针;`TokenMeter`/`ContextPressure` 只作告警,不作门禁;
- 环境变量值通过 `EnvVarRedactor` 在模型可见前脱敏。

## 7. Root / Shizuku / SELinux

- Root 探测区分被动(检测 `su` 存在,不弹窗)与主动(需要用户批准,返回真实
  uid/gid/groups/CapEff/SELinux context/mode);
- `uid=0` 不等于全能力;集成断言 `CAP_SYS_CHROOT`/`CAP_SYS_ADMIN` 后再谈 chroot/mount;
- **禁止** `setenforce 0`、修改全局 SELinux 策略、默认 bind 整个可写 `/sys`;
- chroot 不是容器;构建脚本默认以非 root 执行,不可信项目脚本不会默认获得 root;
- Root provider 名称(Magisk/KernelSU/APatch/Sui)只作为诊断元数据,不参与能力判断。

## 8. 已知边界

- LAN 模式是明文 HTTP,不应在不可信网络使用;
- Debug APK 会启动 DebugServer(全连接 token 认证),仅限开发自测;
- HyperOS 后台冻结、SAF、角色、悬浮窗、电池豁免必须由用户在系统界面授权;
- Root 场景(provider 授权弹窗、SELinux 拒绝、capability 缺位)需要真机验证;
- 完整设备级边界以 `android_capabilities get` 的真实探测结果为准。
