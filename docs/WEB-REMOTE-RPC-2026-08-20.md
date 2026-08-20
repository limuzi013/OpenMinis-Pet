# Web Remote：RPC 与会话事件协议

> 更新：2026-08-21
> 状态：实现契约。本文描述当前 Web Remote 的状态边界；它不是 APK 发布或后台任务完成度的证明。

## 1. 设计边界

Web Remote 是 Android 聊天会话的一个已认证远程投影，不是第二个 Agent runtime。

- 浏览器和原生聊天页通过同一个 session 的 `ChatViewModel` 工作；Agent Loop、模型选择、思考强度和取消状态都由该共享绑定持有。
- HTTP RPC 用于用户发起的命令（发送消息、模型/思考设置、文件操作、控制中心操作）。
- WebSocket 只负责 server-to-browser 的会话事件和恢复；浏览器不经它发起任意设备控制。
- Remote 的 RPC allowlist、登录会话、同源校验和文件权限策略仍是安全边界。浏览器不能通过此面调用 DebugServer 的 tap、输入注入、截图、任意调试文件读写或 shell-execute 方法。

## 2. 浏览器资源

工作台由 APK assets 中的 `index.html`、`app.css` 和 `app.js` 实现，配合本地打包的 Markdown 与净化依赖。会话框架、控制中心和 Workspace 都由 `app.js` 协调；不依赖从 Harness 打包而来的 React/Cordis 组件，也不依赖旧的分栏/标签脚本。

布局遵循 source-adapted Harness RC8 的阅读优先原则：会话 rail 可收起，聊天是默认主区，Details、活动、控制中心和 Workspace 按需显示。该设计参照并不意味着 wire protocol 或客户端 bundle 与 Harness 相同。

## 3. 会话事件 WebSocket

### 3.1 建连

```text
GET /api/events/session?sessionId=<id>[&afterSeq=<non-negative-int>][&snapshot=1][&includeReasoning=true]
Upgrade: websocket
```

连接须通过 Remote 登录认证。使用 cookie 的浏览器升级还须通过同源检查，避免跨站 WebSocket 劫持。普通 HTTP 请求此路径会得到 `426 websocket upgrade required`。

- 没有 `afterSeq`（或显式 `snapshot=1`）时，服务端先发送一致的快照，再订阅新事件。
- 带 `afterSeq` 重连时，服务端回放严格晚于该序号的保留事件。
- `includeReasoning=true` 允许快照包含可用的 reasoning 投影；默认不为每次读取扩大传输。

### 3.2 Wire frames

| `type` | 必要字段 | 含义 |
|---|---|---|
| `session/snapshot` | `sessionId`, `lastSeq`, `snapshot` | 初始或强制重水合的完整投影。`lastSeq` 是这份快照的水位。 |
| `session/subscribed` | `sessionId`, `lastSeq`, `oldestAvailableSeq`, `reset` | 回放订阅结果。`reset=true` 表示给定游标已经早于保留窗口。 |
| `session/event` | `sessionId`, `event` | 一个递增的会话事件；`event` 的形状为 `{seq,time,type,data}`。 |

事件外层保留 `sessionId`，以便一个客户端明确校验其所投影的会话；`seq` 是该 session 内的单调序号，不应与其他 session 比较。

### 3.3 浏览器应用规则

1. `session/snapshot` 替换本地会话投影，并将 `lastSeq` 记为水位。
2. `session/event` 仅在 `event.seq` 严格大于已处理水位时应用；重复事件被忽略。
3. 连续事件用稳定 message/tool id 局部更新 DOM。文本和 reasoning 追加到对应内容块，工具状态更新到同一工具卡，而不是重绘整个 transcript。
4. 若 `seq` 出现缺口、收到 `session/subscribed` 的 `reset=true`、或解码到不能安全投影的 reset 控制帧，浏览器重新请求快照，不猜测遗漏内容。
5. 网络断开后浏览器以最后已应用 `seq` 作为 `afterSeq` 重连。

这使流式回答、工具输出和滚动/展开状态不再依赖定时的整段会话刷新。

### 3.4 事件类型

Remote 优先使用与 Harness 相同的核心命名：

| 事件 | 数据要点 | 浏览器投影 |
|---|---|---|
| `user/message` | `message` | 创建或定稿用户消息。 |
| `turn/start` | `isRunning`, `modelName`, `thinkingLevel`, `turn` | 标记该 session 进入运行态。 |
| `assistant/placeholder` | `message` | 创建正在生成的 assistant 消息。 |
| `assistant/chunk` | `messageId`, `chunk` | 应用 `text-delta`、`reasoning-delta`、`tool-call-delta` 或 `tool-result-delta`。 |
| `tool/call` | `messageId`, `call` | 创建/更新工具调用。 |
| `tool/result` | `messageId`, `result` | 更新同一工具的结果和状态。 |
| `assistant/message` | `messageId`, final content/tool data | 将局部 assistant 消息收束为稳定消息。 |
| `turn/end` | `isRunning`, optional state | 清除运行态。 |

桥接层还可能发送有限的 Android 状态扩展，例如 `turn/status`、`tool/status`、`assistant/placeholder` 或 `assistant/replace`。语义事件保留适用的 `turn` / `step`、`messageId` 和 `callId`。前端为这些事件提供兼容处理，但核心的排序、快照和回放规则不变。

## 4. 快照、materialized tail 与有界重放

`SessionEventHub` 是每 session 的追加 journal。每个 session 的保留窗口最多为 2,048 个事件，热缓存最多容纳 48 个 LRU journals。它在同一同步边界内分配序号、发布热事件并捕获 snapshot watermark；因此从 `lastSeq + 1` 继续是明确的边界，不会把已纳入快照的 token 重复投影，也不会丢掉快照期间生成的 token。

长期消息仍以消息表为基础。journal 另保留有界的 materialized event tail，将最近尚在流式中的文本、reasoning 和工具转换叠加到初始快照；tail 即使在热 journal 淘汰后仍参与该快照。热内存 journal 用于即时 fan-out；同一有界事件窗口异步镜像到 Room v12 的 `session_events` 表（主键为 `session_id`, `seq`），供重连和进程重启后的 replay 使用。游标早于可用窗口时，服务端明确返回 reset，客户端重新水合，而不是默默跳过历史。

该窗口是恢复机制，不是无限的聊天日志或永久事件归档。

## 5. 主要 HTTP 能力

下表只列工作台实际依赖的能力族；字段和错误以服务端实现为准。

| 能力 | 路径 / 方法 | 说明 |
|---|---|---|
| 认证与服务状态 | `/api/auth/*`, `/api/status`, `/api/settings` | 登录、注销、服务与远程设置状态。 |
| 会话快照 | `GET /api/sessions`, `GET /api/messages`, `GET /api/session/status` | 列表与非实时初始投影。 |
| 会话控制 | `POST /api/prompt`, `/api/cancel`, `/api/compact`, `/api/session/new`, `/api/session/title`, `/api/session/delete` | 用户请求的会话命令。 |
| 共享 model / thinking | `POST /api/session/model`, `POST /api/session/thinking` | 直接更新目标 session 的共享 `ChatViewModel` 绑定。 |
| 模型与统计 | `GET /api/models`, `GET /api/usage` | 读取可用模型和用量。 |
| Workspace | `/api/files`, `/api/file`, `/api/edit`, `/api/shell` | 文件浏览、编辑和 shell；写入受 session 路径与远程权限预设约束。 |
| 管理 RPC | `POST /api/rpc` | 经过明确 allowlist 的 native Debug RPC 适配，不是通用 Debug RPC 代理。 |

控制中心当前使用的写能力包括：`provider.instances.*`、`provider.models.*`、
`skills.create/update/toggle/delete`、`mcp.create/update/import/toggle/delete`、
`environments.create/update/delete` 与 `storage.mounts.rename/setWritable/remove`。Provider API Key
和环境变量值都是只写字段：读取接口只返回 `hasCredential` / `hasValue`，不会导出秘密。

`storage.shared.list` 返回 `/var/minis/shared`、`/var/minis/skills`、`/var/minis/memory`；
`storage.mounts.list` 返回手机已经通过 SAF 授权的外部目录。新授权必须发生在 Android 系统
目录选择器中，因此 Web 只显示「在手机中添加」引导，不提供虚假的新增 RPC。

## 6. RPC allowlist

当前 allowlist 覆盖：

```text
provider.  chat.  rpc.discover
skills.  memory.  soul.  mcp.  scheduled.  environments.  storage.
agent.  settings.
debug.logs.  debug.crash.  debug.appInfo
```

允许前缀并不自动意味着每个方法安全。敏感凭据导入/导出以及可变更诊断状态的方法由 deny list 单独拒绝。可写的技能、记忆、MCP、定时任务、agent 或 settings 方法仍需要用户配置的 Remote 登录边界；它们不获得设备 UI 控制权限。

## 7. 作业能力的真实边界

控制面可以显示 App 当前已经登记的项目，也可以调用已经存在且被允许的 Agent RPC。但 Web Remote 不应被描述为已实现通用、持久化、可恢复的 Harness job runtime。本文不承诺完整 `JobRegistry` 生命周期，也不把 `job_output`、`job_list` 或 `job_kill` 当作已经交付的通用接口。

## 8. 相关源码

- `src/android/app/src/main/assets/remote/app.js` — 客户端 snapshot、事件去重、增量 DOM 投影与重连。
- `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt` — 认证、HTTP route、WebSocket、RPC allow/deny policy。
- `src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt` — 追加 journal、watermark、materialized tail 和 Room replay。
- `src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt` — 获取共享会话 ViewModel 并构造远程投影。
- [DSH-DISSECTION-2026-08-21.md](DSH-DISSECTION-2026-08-21.md) — source-adapted Harness RC8 边界与设计依据。
