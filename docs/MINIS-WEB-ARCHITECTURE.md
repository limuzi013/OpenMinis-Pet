# Minis Web 架构与 DeepSeek Harness 边界

状态：当前架构说明；更新于 2026-08-22。

## 定位

Minis Web 是 OpenMinis Pet Android App 的已认证远程投影，不是第二套 Agent runtime。
Android 的 `ChatViewModel`、Room、Repository、`SessionEventHub`、AlarmManager 和原生设置仍是
唯一状态权威。

默认前端位于 `src/android/app/src/main/assets/minis/`。它基于 DeepSeek Harness
`0.1.0-rc.8` 的 MIT 源码和 wire schema 进行 source-adapted 移植：React/Cordis 静态 bundle
随 APK 分发。OpenMinis 管理能力现在由正式 Client Plugin
（`web/minis-client-plugin/` → `plugins/@openminis/minis-client-settings/client.js`）承载：
它复用 DSH 的 `settings.section` slot、`ctx.slots.inject`、`createSnapshotStore` 与 locale
基础设施，在 Settings 注册「Minis 控制台」页并完全用 React 投影 Android 状态；
`@deepseek-ai/dsh-client-ui-settings-general` 已恢复为上游 rc.8 官方产物，不再承担
OpenMinis 功能。用户可见品牌为 Minis；内部 `@deepseek-ai/dsh-*` 模块 ID 是加载和协议
兼容所需，不应盲目改名。

旧 `assets/remote/` 只保留兼容页面和许可证。服务器将 `/remote/` 与 `/dsh/` 重定向到 `/`。

## 运行链路

```text
Minis Web
├─ strict unary API / mux WebSocket
└─ authenticated management RPC
        ↓
RemoteAccessServer
├─ auth / Host / Origin / request-size policy
├─ RPC allowlist + deny list
└─ DshApiAdapter
        ↓
Android authoritative state
├─ ChatViewModel / HeadlessChatRunner
├─ SessionEventHub / Room session_events
├─ Provider、Skill、MCP、Chat Repository
├─ AgentStateStore / QuestionCenter / ApprovalSeam
└─ ScheduledTaskManager / Android settings
```

## 会话事件

客户端先调用 `session.history` 取得有界历史与水位，再订阅 `/api/events.mux`。核心会话事件保持
严格 `{seq,time,type,data}` 形状，并由 mux 包装为 server request。文本、reasoning、工具调用、
审批、问题和 turn/step 状态按稳定 ID 增量投影。

`SessionEventHub` 为每个 session 保留最多 2,048 条事件，热缓存最多 48 个 LRU journal，并把
同一有界窗口异步镜像到 Room。客户端重连时带最后的 `seq`；游标早于保留窗口时必须重新读取
history/snapshot，不能猜测遗漏事件。

## Android 数据映射

| Web 概念 | Android 权威数据 |
|---|---|
| Session / message / model / thinking | `ChatViewModel`、Chat Repository、session model binding |
| Workspace | 原生 `FolderEntity` 会话分组 |
| Goal / Todo / Plan / deliverable | `AgentStateStore` |
| Theme / locale / permission | Android 设置与 `RemotePermissionPolicy`（逐能力开关，SharedPreferences 为唯一事实源） |
| Provider / model groups | `ProviderRepository` |
| Skills / MCP / memory / SOUL | 对应原生 Repository/Store |
| Scheduled tasks | `ScheduledTaskManager` / AlarmManager |

网页可见时会定期刷新 Session/Workspace baseline，以接收手机侧修改。`settings.update` 带
`expectedRevision`，避免旧网页状态覆盖手机刚写入的新值。

## 管理控制台

Minis 控制台是 DSH Settings 中的普通 DOM section，不再使用第二层 Modal。它覆盖 Provider、
模型、Skills、MCP、记忆/SOUL、环境变量、已有挂载、定时任务、Agent 设置、Web/Tunnel 设置和
脱敏诊断元数据。

Skill/MCP URL 导入统一经过 `SafeRemoteImporter`：只允许公开 HTTPS/443，逐跳验证重定向，
限制响应大小，并在 DNS 和实际连接层拒绝 localhost、私网、链路本地及 CGNAT。

## 安全边界

- 登录密码、HttpOnly Cookie、Host/Origin 校验和请求大小限制在适配器之前执行；
- Web RPC 改为“方法 → 能力”显式映射（`RemoteCapabilityCatalog`），不再使用前缀白名单：未登记/未来新增的方法默认拒绝；
- API Key、环境变量、MCP Header/环境值采用 write-only 或 `hasValue` 元数据；
- 工作区写入默认开启且始终限定在 `/var/minis/workspace`；工作区以外路径需要默认关闭的 `sandbox.fs` 能力；
- 设备控制（`device.view`/`device.control`/`ui.inspect`）、凭据导出、诊断正文、管理员操作默认关闭，且必须由用户在手机或网页“权限”页逐项开启；
- `permission.manage` 在 Web 端关闭后，Web 无法再修改任何能力开关（含重新开启它自己），只能回手机恢复；
- Android SAF、系统角色、电池、自启动、悬浮窗等授权必须由用户在手机系统界面完成。

## 不作出的声明

- 不声称 OpenMinis 是 DeepSeek Harness 或能加载任意 Harness 插件；
- 不声称与 DeepSeek 存在产品关系或获得其背书；
- 不把 strict schema 的空兼容响应描述为已经实现的 Subagent/Job/Queue 功能；
- 不声称存在完整、通用、持久化的 Harness Job runtime；
- 不把旧 `assets/remote/` 描述成当前默认 Web UI。

## 主要源码

- `src/android/app/src/main/assets/minis/`
- `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt`
- `src/android/app/src/main/java/com/openminis/app/remote/DshApiAdapter.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/SafeRemoteImporter.kt`
- [WEB-REMOTE-RPC.md](WEB-REMOTE-RPC.md)
