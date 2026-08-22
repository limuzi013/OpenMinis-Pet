# Web Remote:RPC 与会话事件协议

状态:实现契约。

## 1. 设计边界

Web Remote 是 Android 聊天会话的已认证远程投影,不是第二个 Agent runtime。

- 浏览器和原生聊天页通过同一个 session 的 `ChatViewModel` 工作;Agent Loop、模型选择、
  思考强度和取消状态都由该共享绑定持有;
- HTTP RPC 用于用户发起的命令(发送消息、模型/思考设置、文件操作、控制中心操作);
- WebSocket 只负责 server-to-browser 的会话事件和恢复;它只读,不承载设备控制;
- 安全边界 = 登录会话 + 同源校验 + 逐能力开关(`RemoteCapabilityCatalog`):每个
  RPC/HTTP/DSH 方法映射到唯一能力,未登记/未来方法默认拒绝;设备控制(截图/点击/滚动/
  输入)、凭据导出、诊断正文等默认关闭。

## 1.1 能力开关(单事实源)

- 定义:`RemoteCapabilityCatalog`(稳定 id + 中文 label/说明 + 风险 + 默认值 +
  RPC/HTTP/DSH 映射),纯 Kotlin,JVM 可测;
- 状态:`RemotePermissionPolicy`(SharedPreferences,`cap.<id>` 键;旧 `preset` 键保留
  兼容:`workspace-write`=全部默认,`danger-full-access`=全部开启);
- 两端互通:Android 设置页与 Minis Web 控制台读写同一份 SharedPreferences(Web 经
  `settings.capabilities.get/set` 或 `/api/permissions`),改动立即生效;
  `rpc.discover` 在 Web 侧只返回「已映射且已开启」的方法并附 `capability` 字段;
- 自锁保护:Web 端关闭 `permission.manage` 后,所有能力写入(含重新开启它自己)被服务端
  拒绝,只能回手机恢复。

## 2. 浏览器资源

默认工作台位于 APK 的 `assets/minis/`。会话 UI 是基于 MIT 上游静态产物进行 source-adapted
的 React/Cordis bundle;App 管理功能由正式 Client Plugin
(`assets/minis/plugins/@openminis/minis-client-settings/client.js`,源码与构建见
`web/minis-client-plugin/`)通过 DSH 官方 `settings.section` slot 提供,React 直接从已认证
RPC seam 投影 Android 状态;旧 `assets/remote/` 不再是默认前端。用户可见品牌为 Minis,
内部 `@deepseek-ai/dsh-*` 模块 ID 和协议名为加载兼容所必需并继续保留。

## 3. 会话事件 WebSocket

### 3.1 建连

```text
GET /api/events.mux                 # Minis 会话事件(默认全局 fan-out)
GET /api/events.host                # 审批、用户问题等 host 请求
GET /api/events/session?sessionId=<id>[&afterSeq=<seq>]  # 旧的单会话兼容端点
Upgrade: websocket
```

连接须通过 Remote 登录认证。使用 cookie 的浏览器升级还须通过严格同源检查;请求 Host 还要
通过本机地址、已配置域名或可信本机 Cloudflare connector 校验。普通 HTTP 请求这些路径会
得到 `426 websocket upgrade required`。

Minis 客户端用 `session.history` 获取尾页及投影水位,再按事件 `seq` 追加 mux 帧。历史和
实时帧共享 Android journal 的同一序号空间。

### 3.2 Wire frames

`/api/events/session` 兼容端点直接发送下表帧;`events.mux`/`events.host` 会再包一层
`{type:"server-request", method, payload}`,其中 payload 承载对应 mux/host 帧。

| `type` | 必要字段 | 含义 |
|---|---|---|
| `session/snapshot` | `sessionId`, `lastSeq`, `snapshot` | 初始或强制重水合的完整投影。`lastSeq` 是这份快照的水位。 |
| `session/subscribed` | `sessionId`, `lastSeq`, `oldestAvailableSeq`, `reset` | 回放订阅结果。`reset=true` 表示给定游标已经早于保留窗口。 |
| `session/event` | `sessionId`, `event` | 一个递增的会话事件;`event` 的形状为 `{seq,time,type,data}`。 |

事件外层保留 `sessionId`,以便一个客户端明确校验其所投影的会话;`seq` 是该 session 内的
单调序号,不应与其他 session 比较。

### 3.3 浏览器应用规则

1. `session/snapshot` 替换本地会话投影,并将 `lastSeq` 记为水位;
2. `session/event` 仅在 `event.seq` 严格大于已处理水位时应用;重复事件被忽略;
3. 连续事件用稳定 message/tool id 局部更新 DOM。文本和 reasoning 追加到对应内容块,
   工具状态更新到同一工具卡,而不是重绘整个 transcript;
4. 若 `seq` 出现缺口、收到 `session/subscribed` 的 `reset=true`、或解码到不能安全投影的
   reset 控制帧,浏览器重新请求快照,不猜测遗漏内容;
5. 网络断开后浏览器以最后已应用 `seq` 作为 `afterSeq` 重连。

### 3.4 事件类型

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

桥接层还可能发送有限的 Android 状态扩展,例如 `turn/status`、`tool/status`、
`assistant/placeholder` 或 `assistant/replace`。语义事件保留适用的 `turn` / `step`、
`messageId` 和 `callId`。前端为这些事件提供兼容处理,但核心的排序、快照和回放规则不变。

## 4. 快照、materialized tail 与有界重放

`SessionEventHub` 是每 session 的追加 journal。保留窗口每 session 最多 2,048 条事件,
热缓存最多 48 个 LRU journals。它在同一同步边界内分配序号、发布热事件并捕获 snapshot
watermark;因此从 `lastSeq + 1` 继续是明确的边界,不会把已纳入快照的 token 重复投影,
也不会丢掉快照期间生成的 token。

长期消息仍以消息表为基础。journal 另保留有界的 materialized event tail,将最近尚在流式中的
文本、reasoning 和工具转换叠加到初始快照。热内存 journal 用于即时 fan-out;同一有界事件
窗口异步镜像到 Room 的 `session_events` 表(主键 `session_id`, `seq`),供重连和进程重启后
的 replay 使用。游标早于可用窗口时,服务端明确返回 reset,客户端重新水合。

该窗口是恢复机制,不是无限的聊天日志或永久事件归档。

## 5. 主要 HTTP 能力

| 能力 | 路径 / 方法 | 说明 |
|---|---|---|
| 认证与服务状态 | `/api/auth/*`, `/api/status`, `/api/settings` | 登录、注销、服务与远程设置状态。 |
| 会话快照 | `GET /api/sessions`, `GET /api/messages`, `GET /api/session/status` | 列表与非实时初始投影。 |
| 会话控制 | `POST /api/prompt`, `/api/cancel`, `/api/compact`, `/api/session/new`, `/api/session/title`, `/api/session/delete` | 用户请求的会话命令。 |
| 共享 model / thinking | `POST /api/session/model`, `POST /api/session/thinking` | 直接更新目标 session 的共享 `ChatViewModel` 绑定。 |
| 模型与统计 | `GET /api/models`, `GET /api/usage` | 读取可用模型和用量。 |
| DSH Workspace | `workspace.*`, `host.listDirectory`, `host.createDirectory` | 映射原生会话分组和限定的虚拟工作区目录;不开放任意文件读写或 Shell。 |
| Minis unary RPC | `POST /api/{method}` | 严格 schema 的会话、模型、Goal、设置和 Workspace 兼容适配。 |
| 管理 RPC | `POST /api/rpc` | 经过明确方法→能力映射的 native Debug RPC 适配,不是通用 Debug RPC 代理。 |
| URL 导入 | `skills.importUrl`, `mcp.importUrl` | 公开 HTTPS/443、逐次重定向与地址校验、限制下载大小;拒绝本机与私网目标。 |

控制台作为 DSH Settings 的正式插件 section,当前映射 Provider/模型/模型组、Skills、MCP、
记忆与 SOUL、环境变量、共享目录与挂载、完整定时任务 CRUD/立即运行/历史、Agent 设置与作业、
Web 服务/Tunnel 设置及诊断元数据。主题、语言、远程权限、DSH Workspace/Goal 与 Android
使用同一数据源;可见网页会定期重取会话/分组基线以观察手机侧改动。Provider API Key 和
环境变量值都是只写字段;MCP 的 Header/环境值在 Web 响应中也只返回 `hasValue` 元数据。

`storage.shared.list` 返回 `/var/minis/shared`、`/var/minis/skills`、`/var/minis/memory`;
`storage.mounts.list` 返回手机已经通过 SAF 授权的外部目录。新授权必须发生在 Android 系统
目录选择器中,因此 Web 只显示「在手机中添加」引导,不提供虚假的新增 RPC。

## 6. 方法映射与默认拒绝

每个方法必须出现在 `RemoteCapabilityCatalog` 的映射表中,否则被拒绝。允许前缀并不自动
意味着每个方法安全:`provider.export/import`、诊断开关、日志正文和崩溃正文读取由
`credentials.export`/`diagnostics.content` 单独控制;MCP 读取结果还会递归脱敏。可写的
技能、记忆、MCP、定时任务、agent 或 settings 方法仍需要用户配置的 Remote 登录边界;
它们不获得设备 UI 控制权限。Web 不提供截图、点击、输入、Shell、任意文件读写或凭据
导入导出。

## 7. 作业能力的真实边界

控制面可以显示 App 当前已经登记的项目,也可以调用已经存在且被允许的 Agent RPC。但
Web Remote 不应被描述为已实现通用、持久化、可恢复的 Harness job runtime。本文不承诺
完整 `JobRegistry` 生命周期,也不把 `job_output`、`job_list` 或 `job_kill` 当作已经交付的
通用接口。

## 8. 相关源码

- `src/android/app/src/main/assets/minis/` — 默认 Minis Web bundle、登录页及插件产物;
- `web/minis-client-plugin/` — OpenMinis Client Plugin 源码、测试与可重复构建;
- `src/android/app/src/main/java/com/openminis/app/remote/DshApiAdapter.kt` — strict unary
  schema 与原生 journal 事件翻译;
- `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt` — 认证、
  HTTP route、WebSocket、方法→能力映射;
- `src/android/app/src/main/java/com/openminis/app/debug/SafeRemoteImporter.kt` —
  Skill/MCP HTTPS 导入的 SSRF 与大小边界;
- `src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt` — 追加
  journal、watermark、materialized tail 和 Room replay;
- `src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt` — 获取共享
  会话 ViewModel 并构造远程投影。
