# Minis Web 架构与 DeepSeek Harness 边界

状态:当前契约说明。

## 定位

Minis Web 是 Android App 的已认证远程投影,不是第二套 Agent runtime。
Android 的 `ChatViewModel`、Room、Repository、`SessionEventHub`、AlarmManager 和原生设置
仍是唯一状态权威。

默认前端位于 `src/android/app/src/main/assets/minis/`,基于 DeepSeek Harness
`0.1.0-rc.8` 的 MIT 源码与 wire schema 进行 source-adapted 移植:React/Cordis bundle
随 APK 分发,OpenMinis 管理能力由正式 Client Plugin
(`web/minis-client-plugin/` → `plugins/@openminis/minis-client-settings/client.js`)
承载。它通过官方 `settings.section` slot 在 Settings 注册「Minis 控制台」,复用
`createSnapshotStore`/`ctx.slots.inject`/locale 基础设施,用 React 投影 Android 状态;
`@deepseek-ai/dsh-client-ui-settings-general` 为上游官方产物。用户可见品牌为 Minis;
内部 `@deepseek-ai/dsh-*` 模块 ID 是加载与协议兼容所需。

旧 `assets/remote/` 只保留兼容页面与许可证。服务器将 `/remote/` 与 `/dsh/` 重定向到 `/`。

## 运行链路

```text
Minis Web
├─ strict unary API / mux WebSocket
└─ authenticated management RPC
        ↓
RemoteAccessServer
├─ auth / Host / Origin / request-size policy
├─ RPC「方法 → 能力」映射 + 默认拒绝
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

客户端先调用 `session.history` 取得有界历史与水位,再订阅 `/api/events.mux`。核心会话事件
保持严格 `{seq,time,type,data}` 形状,并由 mux 包装为 server request。文本、reasoning、
工具调用、审批、问题和 turn/step 状态按稳定 ID 增量投影。

`SessionEventHub` 为每个 session 保留最多 2,048 条事件,热缓存最多 48 个 LRU journal,
并把同一有界窗口异步镜像到 Room。客户端重连时带最后的 `seq`;游标早于保留窗口时必须重新
读取 history/snapshot,不能猜测遗漏事件。

## Android 数据映射

| Web 概念 | Android 权威数据 |
|---|---|
| Session / message / model / thinking | `ChatViewModel`、Chat Repository、session model binding |
| Workspace | 原生 `FolderEntity` 会话分组 |
| Goal / Todo / Plan / deliverable | `AgentStateStore` |
| Theme / locale / permission | Android 设置与 `RemotePermissionPolicy`(逐能力开关,SharedPreferences 为唯一事实源) |
| Provider / model groups | `ProviderRepository` |
| Skills / MCP / memory / SOUL | 对应原生 Repository/Store |
| Scheduled tasks | `ScheduledTaskManager` / AlarmManager |

网页可见时会定期刷新 Session/Workspace baseline,以接收手机侧修改。`settings.update` 带
`expectedRevision`,避免旧网页状态覆盖手机刚写入的新值。

## Minis 控制台(正式 Client Plugin)

- 插件在 `apply` 中注册 service(`minisApi`)、快照 controller、locale 与
  `settings.section` 条目;没有 module-level side effect;
- React 组件只接收数据与回调:所有调用经 controller → `MinisApiService` →
  已验证 `/api/rpc` seam;组件不直接 fetch、不接触关闭的 ctx;
- 状态使用 DSH `createSnapshotStore`(zustand+immer)与 `snapshot`/`hooks` 面;
- 轮询随 section 激活/卸载启停(controller 生命周期,非全局 timer);
- 覆盖 12 个页面:overview、providers、skills、mcp、memory、system、scheduled、agent、
  web、device、diagnostics、advanced;
- Web 构建产物只由 `npm run build` 生成(含 boot graph rev),禁止手改 bundle。

## 安全边界

- 登录密码、HttpOnly Cookie、Host/Origin 校验和请求大小限制在适配器之前执行;
- Web RPC 改为「方法 → 能力」显式映射(`RemoteCapabilityCatalog`),未登记/未来新增方法
  默认拒绝;
- API Key、环境变量、MCP Header/环境值采用 write-only 或 `hasValue` 元数据;
- 工作区写入默认开启且始终限定在 `/var/minis/workspace`;其他路径需要默认关闭的
  `sandbox.fs`;
- 设备控制(`device.view`/`device.control`/`ui.inspect`)、凭据导出、诊断正文、管理员操作
  默认关闭,且必须由用户在手机或网页「权限」页逐项开启;
- `permission.manage` 在 Web 端关闭后,Web 无法再修改任何能力开关(含重新开启它自己),
  只能回手机恢复;
- Android SAF、系统角色、电池、自启动、悬浮窗等授权必须由用户在手机系统界面完成。

## 不作出的声明

- 不声称能加载任意 Harness 插件或获得 DeepSeek 产品背书;
- 不把 strict schema 的空兼容响应描述为已经实现的 Subagent/Job/Queue 功能;
- 不声称存在完整、通用、持久化的 Harness Job runtime;
- 不把旧 `assets/remote/` 描述成当前默认 Web UI。

## 主要源码

- `src/android/app/src/main/assets/minis/`
- `web/minis-client-plugin/`
- `src/android/app/src/main/java/com/openminis/app/remote/RemoteAccessServer.kt`
- `src/android/app/src/main/java/com/openminis/app/remote/DshApiAdapter.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt`
- `src/android/app/src/main/java/com/openminis/app/debug/SafeRemoteImporter.kt`
- [WEB-REMOTE-RPC.md](WEB-REMOTE-RPC.md)
