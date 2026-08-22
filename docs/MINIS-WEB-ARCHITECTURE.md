# DeepSeek Harness RC8：Web Remote 的 source-adapted 设计记录

> 审计日期：2026-08-21
> 参照：本机官方 DeepSeek Harness `dsh-v0.1.0-rc.8` 源码（MIT）
> 范围：OpenMinis Pet Android Web Remote 的会话工作台与事件传输；不是对 Harness 运行时的移植宣言。

## 结论与边界

OpenMinis Pet 借鉴的是 Harness 的公开、MIT 许可的会话投影和工作台设计：一个会话有可排序的事件历史，客户端先取得一个一致快照，再按顺序消费增量事件。Android 原生聊天仍是实际运行时与状态权威；Web Remote 是同一状态的远程投影。

这不是把 Harness 打包进 APK：本项目没有捆绑其 React/Cordis 客户端或把它作为 Android 的 Agent runtime。`index.html`、`app.css`、`app.js` 与 Android 桥接代码都是本项目的 source-adapted 实现。许可证文本在 `src/android/app/src/main/assets/remote/LICENSE-deepseek-harness-MIT`；本项目不与 DeepSeek 关联，也不获得其背书。

## 从 Harness RC8 采用的契约

### 1. Session 是按序事件的权威历史

Harness 的 `SessionEvent` 是带单调 `seq`、`time`、`type` 与 `data` 的追加事件。会话 UI 不应把“当前整段文本”当作唯一传输单位；它应把消息、模型输出块、工具调用和回合结束视为可投影的事件。

这一点决定了恢复语义：客户端保存已处理的 `seq`，只请求其后的事件；若历史出现缺口或游标早于保留窗口，则重新建立快照，而不是猜测漏掉的内容。

### 2. 浏览器消费 SessionEvent，而非另一套聊天 delta DTO

Harness 的 browser transport 使用下行 WebSocket。Host 层把 `MuxFrame` 置于 RPC 载体中，`session/event` frame 携带 `{sessionId, event}`；`event` 保持核心 `SessionEvent` 形状。客户端运行时会先水合历史，按序去重，发现缺口时重新取得历史。

对流式回答，`assistant/chunk` 中的内容块就是正式事件：例如 `text-delta`、`reasoning-delta`、工具调用/结果增量。最终的 `assistant/message` 把局部块收束成稳定消息；UI 只更新该消息或工具节点。

### 3. 布局服务于会话，而不是相反

Harness 的 AppFrame 思路是阅读优先：聊天保持主区，较低频的细节、轨迹、工作区和控制面按需打开。OpenMinis 采用这一信息架构，但没有复制 Harness 的组件实现或像素资源。

## OpenMinis Pet 的实现映射

| Harness 概念 | OpenMinis Pet 的 source-adapted 实现 |
|---|---|
| `SessionEvent` | `SessionEventHub` 生成 `{seq,time,type,data}`，Web 外层为 `{type:"session/event",sessionId,event}`。 |
| 初始投影 | `/api/events/session` 发送 `session/snapshot`，其中包含同一会话水位 `lastSeq` 与快照。 |
| 增量订阅 | 认证 WebSocket 按 `seq` 推送事件；浏览器以稳定消息 id 就地追加文本、思考和工具状态。 |
| 重连 | 浏览器传入 `afterSeq`。服务器回复 `session/subscribed`，并回放严格晚于该游标的保留事件。 |
| 缺口恢复 | `session/subscribed.reset=true` 表示游标不再可回放；浏览器丢弃不完整投影并重新水合快照。 |
| partial → settled | `assistant/chunk` 增量修改局部块；`assistant/message` 作为最终消息覆盖/收束；`turn/end` 清除运行态。 |
| conversation-first frame | 可收起会话栏、默认关闭 Details Inspector，活动、设置和 Workspace 只在需要时打开。 |

核心 DSH-shaped 事件是 `user/message`、`turn/start`、`assistant/chunk`、`tool/call`、`tool/result`、`assistant/message` 和 `turn/end`。语义事件保留 `turn` / `step` 以及适用的 `messageId` / `callId`。为反映 Android 运行态，桥接层也可发出小范围扩展（例如 `turn/status`、`tool/status`、`assistant/placeholder` 或 `assistant/replace`）；这些扩展不改变核心序列和重放规则。

## 快照与重放的一致性

`SessionEventHub` 为每个会话维护有界的追加 journal：每个会话最多保留 2,048 个事件，热缓存最多保留 48 个 LRU journals。事件分配序号、热路径发布和快照水位在同一同步边界内完成，因此快照的 `lastSeq` 描述的是一个明确的边界：客户端只需从其后一条事件继续，既不会重复已在快照中的 token，也不会漏掉快照生成期间到达的 token。

消息表仍是长期会话内容的基础。journal 同时维护一个有界的 materialized event tail，用于把尚未反映到 Compose/持久消息投影中的原始模型块和工具状态叠加到快照；这个 tail 在热 journal 淘汰后仍参与快照。热 journal 用于即时 fan-out；同一有界窗口异步镜像到 Room v12 的 `session_events` 表（`session_id`, `seq` 为主键），供重连和进程重启后的回放使用。保留窗口不足时，协议明确要求 reset + snapshot，而非静默跳过。

这条路径的目的只有一个：令网页和手机看到同一场正在进行的 turn。它不会定时抓取、替换完整会话，也不会为了网页另建一个 Agent、模型选择或思考强度状态。

## 共享 Android 状态

`HeadlessChatRunner` 获取的是目标 session 的共享 `ChatViewModel`。因此网页发送消息、取消、选择模型或思考强度时，作用在与原生聊天页相同的会话绑定上；从模型输出到工具状态的事件也来自同一执行链。

Web 层保持“投影”职责：

- 以消息 id 局部更新 DOM，保留正在展开的工具详情、滚动位置和输入状态；
- 对未知事件保持前向兼容，不把错误的全量刷新当作恢复手段；
- 工具轨迹兼容 `toolUse` / `toolResult` 及历史 snake_case 块，并展示可用的输入、输出和状态；
- 文件、编辑和 Shell 仍通过现有受权限策略保护的 Remote API，不能借此获得 DebugServer 的设备控制能力。

## 不作出的声明

- 不声称 OpenMinis 是 Harness、能加载 Harness 插件，或与 DeepSeek 存在产品关系。
- 不声称 WebSocket 协议是 Harness Host/Mux 的逐字节替代品；它采用相同的 session-event 语义，并使用本项目自己的认证与 HTTP 服务边界。
- 不声称 App 已拥有完整、通用、持久化的 Harness-style job system。界面可以呈现 App 已登记的项目，但这里不把 `JobRegistry`、`job_output`、`job_list` 或 `job_kill` 记为已完成能力。
- 不把规划、检查点、审批、输出保留、重试或其他 Harness 包列为“已移植”，除非它们有独立的实现、验证和发布记录。

## 主要源码入口

- Web projection：`src/android/app/src/main/assets/remote/app.js`
- HTTP/WS boundary：`src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt`
- Event journal and replay：`src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt`
- Shared native-session bridge：`src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt`
- Remote protocol details：[WEB-REMOTE-RPC-2026-08-20.md](WEB-REMOTE-RPC-2026-08-20.md)
