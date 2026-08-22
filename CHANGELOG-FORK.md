# 分叉改动清单

相对官方 OpenMinis `1.12`（versionCode 24）。

---

## 2026-08-22：DSH 设置融合、App/Web 双向数据与 URL 导入

- 将原独立 Minis 控制台改为 DSH 设置面板内的原生 section，移除第二层全屏弹窗与悬浮入口，
  并以 DSH 设计变量重写响应式样式；登录页同步改为同一视觉语言；
- DSH Workspace 映射 Android 原生会话分组，补齐创建、重命名、删除、排序、移动会话和归档；
  浏览器可见时每 5 秒重取会话/工作区权威基线，手机侧改动无需刷新页面；
- DSH Goal 映射 `AgentStateStore`，主题、语言、权限和 Agent preset 映射真实 App 设置；设置写入
  增加 revision 冲突检查，避免浏览器用旧值覆盖手机刚完成的修改；
- 新增 `skills.importUrl` 与 `mcp.importUrl`；只允许公开 HTTPS/443，限制重定向与内容大小，
  DNS 与实际连接都拒绝 localhost、私网、链路本地及 CGNAT，防止公网 Remote 变成 SSRF；
- 修复 DSH 模型选择响应返回显示名而非模型 Entry ID 的问题；App 原生会话绑定继续作为权威；
- Android 16 真机通过 Skill/MCP URL 导入及清理回归、Workspace 原生 Repository round-trip、
  DSH 核心响应与 stale settings revision 仪器测试；版本更新为 `1.12-pet.15-SNAPSHOT`
  （versionCode 35）。

## 2026-08-22：Cloudflare Tunnel 真机修复

- 修复部分移动网络虽然通过 QUIC 预检、却持续丢弃 UDP/7844 控制流，导致隧道长时间停在
  “connecting”的问题；named tunnel 连接固定使用 HTTP/2，不再等待约一分钟的自动降级；
- Android 16 arm64 真机验证：首条连接约 2.5 秒注册、4 条连接约 6 秒全部注册，公网 HTTPS
  登录页返回 200；版本更新为 `1.12-pet.14-SNAPSHOT`（versionCode 34）。

---

## 2026-08-21：Web/App 管理能力对齐、数字助手与宠物 P0

- Web 控制中心接通 Provider、API Key、模型、技能、MCP、环境变量的真实增删改；会话模型与
  思考强度继续写入共享 `ChatViewModel`，失败明确显示，不再用只读卡片冒充可管理能力；
- 加入共享目录与已有 SAF 外部挂载清单/管理。新增系统目录权限仍由手机原生选择器完成；
- 修复记忆文件行被通用 `span` 样式挤成窄列的问题，并为记忆/SOUL/编辑器建立稳定响应式网格；
- 默认数字助手改用标准 `RoleManager.ROLE_ASSISTANT`，补齐 VoiceInteraction Session、
  Recognition bridge、正确 bind permission 与 `ACTION_ASSIST` 入口；
- 桌面宠物补齐授权返回恢复、异步加载代际保护、位置/吸边持久化、屏幕边界限制和真实 Agent
  状态恢复；宠物提问在网络调用前写入历史，失败/取消不再伪装成没有发生。

---

## 2026-08-21：Harness RC8 source-adapted Web 工作台与事件流

- 以本地官方 DeepSeek Harness `0.1.0-rc.8` 的 **MIT 源码**为信息架构、会话事件与
  交互细节的参照；原生改写 `index.html`、`app.css`、`app.js`，不复制或捆绑其
  React/Cordis bundle；
- 聊天工作台采用 AppFrame 的阅读优先原则：会话栏可收起、Details 默认关闭、工作区与
  控制中心按需打开；消息、工具行和检查器均用稳定 id 局部更新；
- Web Remote 首次以快照建立页面，随后从认证 WebSocket
  `/api/events/session` 接收带单调 `seq` 的 `session/event`。重连带 `afterSeq`，
  事件缺口或过期游标会重新取得快照，不再定时轮询整段会话；
- 事件桥接的是 Android 正在运行的 `ChatViewModel`：网页与手机共享流式文本、思考、
  工具状态、会话模型和思考强度；
- `ChatToolTrajectoryNormalizer` 统一 `toolUse/toolResult` 与历史 snake_case 工具块，
  并加 JVM 回归测试；`/api/messages` 可按需返回 reasoning；
- 加入 DeepSeek Harness MIT notice，保留 DeepSeek theme BSD-3 notice。项目不与
  DeepSeek 关联或获其背书。

---

## 新增文件

### 桌面宠物 `app/src/main/java/com/openminis/app/pet/`

| 文件 | 作用 |
|---|---|
| `PetOverlayService.kt` | 悬浮窗服务：手势、状态机、语音、屏幕开关联动 |
| `PetOverlayView.kt` | 宠物窗口内容：气泡 + 长按菜单 |
| `PetChatWindowView.kt` | 独立的聊天小窗 |
| `PetChatEngine.kt` | 直连模型问答、回复压短、写入会话历史 |
| `PetBehavior.kt` | 自主行为：巡游、边缘吸附、贴边隐藏 |
| `PetSpriteView.kt` | 精灵图集动画渲染 |
| `PetPackageManager.kt` | 宠物包 ZIP 导入与校验 |
| `PetModels.kt` | `pet.json` 解析与图集几何 |
| `PetPreferences.kt` | 宠物相关偏好 |
| `PetBridge.kt` | 给 App 其它部分调用的窄接口 |
| `PetControlActivity.kt` / `PetControlScreen.kt` | 宠物设置界面 |

### Web 远程控制 `app/src/main/java/com/openminis/app/remote/`

`RemoteAccessServer.kt`、`RemoteAccessService.kt`、`RemoteAccessPrefs.kt`、
`CloudflareTunnelManager.kt`，前端在 `app/src/main/assets/remote/`。

---

## 修改到的官方文件

| 文件 | 改动 |
|---|---|
| `build.gradle.kts` | `applicationId` → `dev.openminispet.android`，版本号加 `-pet.N` 后缀 |
| `AndroidManifest.xml` | 注册宠物服务/Activity、Web Remote 服务，加 `FOREGROUND_SERVICE_SPECIAL_USE` |
| `MinisApp.kt` | 进程重建后恢复宠物与 Web Remote |
| `AgentForegroundService.kt` | 把 Agent 状态推给宠物 |
| `ui/settings/SettingsScreen.kt` | 「外观」加桌面宠物入口 |
| `ui/settings/SystemPermissionsScreen.kt` | 加「显示在其他应用上层」权限行 |
| `offload/AlarmReceiver.kt` | 开机广播里恢复 Web Remote |
| `sandbox/NativeOffload.kt` | abstract socket 名带上 applicationId（见下） |
| `res/values*/strings.xml` | 应用名 → OpenMinis Pet |

---

## 值得单独说的几个修复

下面这些不是「加功能」，是踩到坑之后的修复，记下来是为了别再踩第二遍。

### 1. 与官方版共存会互相打死

**现象**：两个 App 同时装，后启动的那个在 `Application.onCreate()` 里直接崩。

**原因**：PRoot 的 native offload 用 **abstract socket**。抽象 socket 属于内核级全局命名
空间，**不随应用沙盒隔离**，两个 App 用同一个名字必然抢。

**改法**：socket 名带上 applicationId。

### 2. Cloudflare Tunnel 失败时看不到真正原因

**现象**：界面显示 `cloudflared stopped (exit 255): HandlerEntry contains 31 bytes in 2 blocks (ref 0) 0xb...`。

**原因**：那串根本不是错误信息，是 **PRoot 的 talloc 在进程退出时打印的内存分配表**。
`drainProcess` 里用 `last = 每一行` 记录「最后输出」，cloudflared 退出时 talloc 一口气吐
几十行，把真正的 `Provided Tunnel token is not valid.` 冲掉了。

**改法**：跳过 talloc / proot 噪声行，并优先保留含 error/invalid/failed 的那一行。

### 3. 重启手机后 Web Remote 再也起不来

**原因**：`RemoteAccessService.start()` 全工程**只有设置页那个开关调用**。宠物有
`PetBridge.startIfEnabled()` 负责进程重建后恢复，Web Remote 没有对应的东西。而远程管理
最需要的恰恰是「人不在手机旁边也能连上」。

**改法**：新增 `RemoteAccessService.startIfEnabled()`，在 `MinisApp.onCreate()` 和开机广播
里各调一次。恢复时仍然检查「开关是开的」**且**「设置了登录密码」，不会因为自动恢复就把无
密码的远程控制暴露出去。用 `runCatching` 包住——开机启动前台服务属于后台启动，部分 ROM 会
抛 `ForegroundServiceStartNotAllowedException`，不接住会连累整个 App 启动失败。

### 4. 宠物聊天框点屏幕别处关不掉

**原因**：`FLAG_WATCH_OUTSIDE_TOUCH` 只对 `NOT_FOCUSABLE` 的窗口派发 `ACTION_OUTSIDE`，
而打字必须让窗口可获焦点，两者互斥。

**改法**：聊天区拆成独立小窗——宠物本体始终不抢焦点，聊天窗单独获焦，失焦即关，返回键也关。

### 5. 熄屏后宠物还在原地跑

**原因**：没有任何屏幕状态处理。熄屏后精灵每 110ms 仍在 `invalidate()`，巡游定时器还在挪
窗口，随机性格动作照常触发——屏幕是黑的，这些一帧都不会被看到，纯耗电。

**改法**：运行时注册 `ACTION_SCREEN_ON/OFF`（manifest 静态注册收不到这两个广播），熄屏时
停掉动画、巡游和心情定时器，亮屏恢复。恢复用的是专门的 `resumeTimers()` 而不是 `reset()`
——后者会清掉贴边状态，让本来藏在边缘的宠物自己蹦出来。

### 6. Web 端 Markdown 原样显示

**原因**：消息只做 `esc()` 转义就塞进 DOM。

**改法**：最终用 **marked**(MIT) + **DOMPurify**(MPL-2.0/Apache-2.0) 渲染，
`md.js` 只剩薄封装。两者以单文件 UMD 随 APK 分发 —— 页面在严格 CSP 下从 assets 出，
CDN 一律不可达。

先前自己写过一版解析器，后来换掉了：这类东西没有自造的价值，成熟库在边界情况上
稳得多。不过那一版暴露的两个 bug 仍值得记，同类实现都会踩：

- **代码高亮把自己的 HTML 吐成了文本**：关键字表里有 `class`，关键字替换把前面生成的
  `<span class="tok-str">` 里的 `class` 又包了一层，标签就碎了。改成先把字符串和注释藏进
  占位符再做关键字替换。
- **引用块识别不到**：先 `esc()` 再解析，`>` 早已变成 `&gt;`。顺带把 `render` 拆成「转义」
  和「解析」两层，否则引用递归会双重转义。

换库后同样跑了浏览器实测：列表/粗体/代码块/表格/引用/链接全部渲染，`<script>` 被移除、`onerror` 被剥离。

### 7. Web 端没有流式、还闪

**历史原因**：早期页面用整段会话刷新驱动 UI，导致流式文本不连续，并破坏选中、展开状态和
滚动位置。

**当前改法**：页面先建立带水位的会话快照，再订阅认证 WebSocket
`/api/events/session` 的单调 `seq` 事件。`assistant/chunk`、工具调用/结果和回合状态都按稳定
消息 id 局部投影；断线以 `afterSeq` 回放，游标失效则重取快照。页面不再以定时整段会话刷新作为
流式路径。

### 8. 其它

- 宠物渲染每帧 `new Rect + new RectF` → 复用成员变量
- `frame % animation.frameCount` 在畸形宠物包下可能除零 → `coerceAtLeast(1)`
- 1536×1872 图集在 ARGB_8888 下约 11.5 MB，低密度屏或最小尺寸档按需减半采样
- Web 端文件面板硬编码 `/var/minis/workspace`，sandbox 没初始化过就整块报错 → 回退到根目录
- 打补丁脚本 `apply_patch.py` 在 Windows 上会把整份文件的 LF 改写成 CRLF（`Path.write_text`
  默认 `newline=None` → `os.linesep`），一行改动显示成全文件 diff → 统一按 LF 写回
- 同一脚本的锚点正则 `(?m)^(\s*)`，`\s` 含换行会把上一行的换行也吃进 indent 分组，导致插入
  的每一行前面都多一个空行 → 收紧为 `[^\S\n]*`

### 9. 自动恢复在冷启动时被系统拒绝

**现象**：给 Web Remote 加了进程重建/开机自动恢复之后，重装 App 再打开，
端口依然没有监听。

**日志**：

```
W RemoteAccessService: restore failed: startForegroundService() not allowed
  due to mAllowStartForeground false
```

**原因**：`MinisApp.onCreate()` 执行时进程状态是 `CEM` 而不是 `TOP`，
Android 12+ 把这算作后台启动前台服务，直接拒绝。`runCatching` 接住了异常
所以没崩，但服务也就没起来。

**改法**：恢复点补到 `MainActivity.onResume()` —— 那一刻 App 确定在前台，
启动一定被允许。Application 与开机广播里的两处调用保留：前者覆盖进程被杀后
重建、后者有系统豁免，三处合起来才覆盖全部路径。

### 10. Web 端复用 App 内部的 RPC

网页要管模型和供应商，与其为 `provider.instances.create`、`models.refresh`、
`groups.setDefault` 等二十来个方法逐一写 REST 路由，不如把 App 已有的
`DebugRPCHandler` 转发出去 —— 一条 `/api/rpc` 就接管了全部能力。

但**必须白名单**：`debug.` 族下面是 `tap` / `inputText` / `screenshot` /
`writeFile`，合起来等于远程操控这台手机。Web Remote 可以经隧道暴露到公网，
所以那一族整个挡在外面，只放行 `provider.` / `chat.` / `rpc.discover`。
要用那些方法，仍然只能走本机的 127.0.0.1:5321 调试端口。

### 11. Web 端补上技能 / 记忆 / MCP / 定时任务管理页

Web Remote 之前只覆盖了模型、用量、压缩与会话管理；手机端已有的技能、记忆、MCP、
定时任务四个设置入口在网页上没有对应页面。

**改法**：沿用第 10 条的思路——不为这些页面新造 REST 路由，而是在 `DebugRPCHandler`
里各加一组薄封装，把 App 仓库层的能力直接转发出去：

| 前缀 | RPC | 能力 |
|---|---|---|
| `skills.` | `list` / `get` / `toggle` / `delete` | 技能列表、查看 SKILL.md、启用/停用、删除 |
| `memory.` | `files.list` / `files.read` / `files.write` / `files.delete`、`globalToggle` / `setGlobalEnabled` | 记忆文件浏览/编辑/删除、全局记忆开关 |
| `soul.` | `get` / `save` | SOUL.md 人设的读取与保存 |
| `mcp.` | `list` / `toggle` / `delete` | MCP 服务器列表、启用/停用、删除 |
| `scheduled.` | `list` / `toggle` / `delete` / `run` | 定时任务列表、启停、删除、立即运行 |

当前控制中心由主工作台 `app.js` 统一协调：切换到相应控制面后才读取其数据，不再依赖旧的
分标签静态脚本。新建/导入类操作仍然保留在手机端；网页只做现有 RPC 所支持的查看、编辑、启停
和删除，避免把管理面扩展成第二套运行时。

**白名单**：`RPC_ALLOWED_PREFIXES` 放行 `skills.` / `memory.` / `soul.` / `mcp.` /
`scheduled.`，这四个族内部都做了参数校验（技能/服务器/任务 id 必须存在，记忆文件名
拒绝路径穿越）；另放行只读诊断方法 `debug.appInfo` / `debug.logs.*` / `debug.crash.*`。
`debug.tap` / `debug.inputText` / `debug.screenshot` / `debug.writeFile` 这类等于远程
操控手机的交互式方法，仍被整体挡在 Web 端之外。

### 12. 主代理 / 子代理设置 + 提问卡片 + 会话全文搜索

参考 DeepSeek Harness（`dsh-agent-presets` / `dsh-subagent` / `dsh-client-ui-subagent`）
做了三件事：

1. **主代理 / 子代理设置**：模型页新增「代理设置」卡片。`provider.groups.list`
   返回 `defaultSubGroupId` 与每组 `isSub`；新增 `provider.groups.setSubDefault`
   （`null` = 继承主代理）；新增 `agent.settings.get/set` 配置子代理委派深度
   （1–5 层）与单任务超时（1–30 分钟），`SubagentTool` 由硬编码改为读
   `SubagentLimits`。改动只影响之后新建的会话，运行中会话保持原配置。
2. **模型提问卡片**：新增模型工具 `ask_user_question`（暂停回合、等待用户回答），
   Web Remote 通过已有 `chat.question.*` RPC 读取并提交回答（单选/多选/自定义/跳过），
   `chat.question.answer` 恢复回合；超时/跳过会明确告知模型。
3. **会话全文搜索**：新增 `chat.search`，复用 `minis-sessions-cli search`
同一套参数化 LIKE + Kotlin 侧文本抽取（工具元数据误命中会被过滤），
词项 AND、按会话分组、每个会话最多 3 条命中；Web 侧边栏提供搜索框与结果面板。

### 13. DeepSeek Harness 好功能批量移植

把 DeepSeek Harness（`dsh-goal` / `dsh-tool-todo` / `dsh-plan-mode` /
`dsh-client-ui-deliverables` / `dsh-message-feedback` / `dsh-permission-presets` /
`dsh-repeat-tool-reminder` / `dsh-attachment`）里适合 Web Remote 的部分一次搬过来：

1. **目标条**：`agent.goal.get/set/setActive` + 模型工具 `get_goal` /
   `create_goal` / `update_goal`；Web 在输入框上方渲染 🎯 目标条，
   可暂停/恢复/清除。
2. **待办条**：`agent.todo.get/replace` + 模型工具 `todo_write`（整表替换）；
   Web 渲染 ☑ 待办条（pending / in_progress / completed 状态）。
3. **计划模式（软）**：`agent.plan.get/set`；Web 显示 Plan 横幅、
   输入框 placeholder 切换为「描述你的任务以生成计划…」，可一键退出。
4. **产出文件行**：文件写入/编辑成功时在 [AgentStateStore] 记录
   `agent.deliverables.list/clear`；Web 在输入框上方显示 📄 本轮产出，
   点击直接打开文件。
5. **消息反馈**：`chat.feedback.put/delete/listForMessages` + JSON sidecar；
   Web 每条 assistant 消息带 👍/👎。
6. **权限预设**：`settings.permissionPreset.get/set` + `settings.sandbox.get`，
   `RemotePermissionPolicy` 作为唯一策略归属；workspace-write（默认）下网页
   文件写入/编辑仅限 `/var/minis/workspace`，danger-full-access 放开全部路径；
   Web 设置页加选择卡，选 Full Access 需二次确认。
7. **重复工具提醒**：ChatViewModel 内统计连续完全相同参数的工具调用，
   第 4 次起在结果前注入提醒，阻止模型死循环重试。
8. **附件**：复用 `chat.prompt` 既有 `attachments` 契约，Web composer
   加 ＋ 附件按钮，发送时转 base64 随消息提交（无需新后端）。

状态均为进程内（AgentStateStore / MessageFeedbackStore），不改数据库；
`rpc.discover` 已同步注册全部新方法。

### 14. 网页端功能同步到手机 App

第 13 节的功能之前在网页端独有，手机 App 缺少对应界面（尤其模型调用
`ask_user_question` 时手机会一直挂着等不到回答）。本轮把同一套状态源接到
App 原生 UI：

1. **提问卡片**：新增 `ChatAgentStateUI.kt` 的 `AskUserQuestionDialog`，
   使用同一个 `QuestionCenter`，手机直接弹卡回答（单选即答、多选/自定义/跳过），对话恢复。
2. **目标 / 待办 / 计划 / 产出文件条**：`AgentStateBars` 浮在输入框上方，
   使用同一个 `AgentStateStore`，可暂停/恢复/清除目标，显示待办与产出。
3. **消息反馈 👍/👎**：`MessageFeedbackRow` 挂到每条 assistant 消息底部，
   写入同一个 `MessageFeedbackStore`。
4. **权限预设**：Web 远程设置页新增「权限预设」分区（Workspace Write /
   Danger Full Access 两行，勾选当前值；Full Access 需 MinisAlertDialog
   二次确认），与网页端共用 `RemotePermissionPolicy`。

UI 全部复用 App 现有原语（MinisAlertDialog 同款 Dialog+Surface 壳、
MinisTextButton、SettingsRow/SettingsSection、MaterialTheme 排版），
与源码 UI 保持一致。

### 15. 继续补齐手机端同步

- **子代理委派限制设置**：设置 → Agent Runtime 新增「子代理委派限制」行
  （AccountTree 图标），点击弹出与 MinisAlertDialog 同款壳的数字输入对话框，
  读写同一个 `SubagentLimits`（深度 1–5、超时 1–30 分钟），与网页端
  `agent.settings.*` 完全同源。
- **目标可编辑**：聊天页目标条新增「编辑」按钮，弹窗修改目标文本。
- **计划可退出**：计划模式横幅新增「退出」按钮，`planSet(mode="off")`。
- **产出文件可复制**：产出文件路径可点击复制到剪贴板并 Toast 提示。
- 技能 / 记忆 / SOUL / MCP / 定时任务 / 模型组（主/子代理）/ 会话搜索 /
  附件在手机 App 原本已有原生入口，无需重复移植；本次核对确认两端入口齐平。

### 16. Web Remote 按 DeepSeek Harness 布局重写

以本地官方 DeepSeek Harness `0.1.0-rc.8` 的 MIT 源码为参照，按 source-adapted
方式重写本项目工作台，而不是提取或嵌入其前端 bundle：

- 会话 rail 可收起，聊天始终是默认主区；Details Inspector、活动、控制中心和 Workspace
  仅在需要时打开；
- 会话行、消息、工具轨迹和详情面板使用本项目自己的 HTML/CSS/JS；主题 token 与本地
  Markdown/净化资源随 APK 分发；
- 布局在窄屏下优先保住聊天阅读区，并将附属面板切换为临时层；
- 工作台逻辑收敛到主 `app.js`，不再依赖旧的分栏或分标签静态模块。

Harness 贡献的是信息架构、事件语义和交互原则，不是可直接嵌入的 UI 代码。

### 17. 深度对照 DeepSeek 前端：交互细节补齐

本次重点不是复制某一套视觉细节，而是让交互遵从真实会话事件：

1. **流式消息**：`assistant/chunk` 只更新对应文本或 reasoning 块；最终
   `assistant/message` 收束该消息；
2. **工具轨迹**：`tool/call` / `tool/result` 在同一 assistant turn 中更新，详情检查器可
   展示已有输入、输出和状态；
3. **会话操作**：会话菜单、命令入口、Toast 与按需 Workspace/Details 使用稳定 DOM
   节点，不用全量聊天刷新修复状态；
4. **重连**：以 `afterSeq` 回放；缺口或过期游标触发快照恢复。

尚未列为承诺的功能包括通用任务队列、完整轨迹回放、子代理目录树、会话日志 ZIP 导出、
动态命令目录和强制审批工作流。不要把这些待评估项目记为已经由 Harness 移植完成。

### 18. 默认数字助手 + 手机端计划入口同步（1.12-pet.10）

- **默认数字助手**：新增 `voice/AssistService` + `AssistSessionService` +
  `AssistSession`（VoiceInteractionService 三件套），Manifest 声明
  `BIND_VOICE_INTERACTION` / `BIND_VOICE_INTERACTION_SESSION` 与
  `voice_interaction_service.xml`；设置 → Agent Runtime 新增「默认数字助手」
  行，用 RoleManager 请求角色（自动兼容 AOSP `android.app.role.Assist` 与
  MIUI `android.app.role.ASSISTANT`）。设为默认后长按 Home / 语音唤起会打开
  App 主界面，会话随即结束（轻量实现）。
- **计划模式入口**：手机聊天「⋯」菜单新增「计划模式 / 退出计划模式」，
  与网页端共用 `AgentStateStore.planSet`。
- 本版为发布版 `1.12-pet.10`（versionCode 31），发布说明见
  `RELEASE-NOTES.md`。

---

## 未验证 / 已知问题

诚实起见：

- **语音识别在测试机（MIUI）上不可用**。系统引擎 `isRecognitionAvailable()` 返回 `false`，
  云端引擎需要手动绑定 Voice Input 模型。代码路径正确，但没能在真机上跑通一次完整语音对话。
- 宠物的息屏优化经过编译和逻辑验证，**没有做长时间耗电对比实测**。
- Web 端 Markdown 渲染器有单元测试，但**没有在真机浏览器上逐项回归**。


---

## 2026-08-21 安全加固与可靠性修复（审查驱动）

一次全量代码审查（5 CRITICAL / 20 HIGH / 47 MEDIUM / 70+ LOW）后的修复批次，
设计决策与完整对照见 [docs/DESIGN-HARDENING-2026-08-21.md](docs/DESIGN-HARDENING-2026-08-21.md)，
审查报告见 [docs/find-fault-report.md](docs/find-fault-report.md)。

**安全**：

- `EncryptedPrefsFactory` **fail-closed**：加密 store 连续失败不再回退明文
  SharedPreferences（改返回空内存 store，凭据视为未配置）；失败时只删自己的
  加密 XML，不再删共享 Tink keyset / 全局主密钥（原来一个 store 故障会让全部
  加密凭据级联落明文）
- `DebugServer`（0.0.0.0:5321）**所有连接一律要求 token**（含 loopback；
  token 在 `files/debug_server_token`，adb run-as 可读）；移除 CORS `*`；
  Content-Length 4 MiB / 行 16 KiB / header 数 100 上限；连接协程 catch(Throwable)；
  token 不进 logcat；`minis-debug` CLI 同步带 `X-Minis-Token`
- Web Remote RPC 白名单新增 **deny 列表**：`provider.export` / `provider.import`
  （携带完整 API Key）/ `debug.logs.setEnabled`
- `LLMRequestLog` 剥离 authorization / x-api-key / api-key / cookie 等敏感头
  的**值**（保留名字）——`debug.llmRequests` 不再泄漏 API Key
- Web 文件读写路径 **canonical 前缀校验 + 拒绝 symlink 逃逸**（GET/POST/edit
  全部前置守卫）；attachments 文件名净化 + 16 MiB / 32 MiB / 8 个上限
- `AlarmReceiver` `exported=false`（任意应用伪造报警通知的入口关闭）
- 登录限流 **per-IP 分桶 + 原子计数**（原全局计数器可并发绕过、可被反锁）
- `EncryptedPrefsFactory` 迁移竞态修复（token 生成加锁）
- 500 错误不再回显内部异常；`/api/sessions|messages` limit 上限

**可靠性**：

- 子代理超时**真取消**（`cancelStream`）+ 回答 60k 截断 + 取消异常重抛
- `QuestionCenter` 已回答/超时卡片即时删除（原残留导致网页重复弹卡）
- `FileReadTool` 50 MB 守卫 / `ReadImageTool` 先读尺寸再降采样
- `FileWriteTool` mount 写入恒真式修正（写前快照旧长度）
- `FileEditEngine` fuzzy 尾部换行规则统一（不再误删尾随空行）；
  `FileEditTool` legacy replace_all 支持 CRLF；diff/回答截断代理对安全
- `ShellOutputTruncator` 行数 off-by-one 修正 + 行边界丢弃条件修正
- `GoalTools` create/update 语义区分 + 空文本清除目标；`TodoTool` status 白名单
- `MessageFeedbackStore` 原子写 + 损坏文件备份（不再静默清空反馈）
- 宠物：取消语义（`CancellationException` 重抛）、60s 响应超时、spritesheet
  IO 解码、密度采样按实际绘制尺寸、语音双击竞态、预览解码去重、气泡动画竞态、
  `pet.json` id 正则收紧（禁 `.`/`..`）
- `AgentForegroundService` 安全门统一 `subsystemsReady()`（修复崩溃环风险）
- ghost 闹钟迁移失败保留 blob；宠物开机恢复路径；`startIfEnabled` 去重；
  `RemoteAccessServer` stop 后可复用（scope 重建）
- 权限预设**真实生效**：workspace-write 模式下 Web 写入/编辑仅限
  `/var/minis/workspace`，Full Access 放开

**其它**：设置页 ON_RESUME 状态同步（通知栏停止后开关不再显示旧值）；
app.js 三处（matchedCount 转义、死代码清理、用量徽标状态接入）；
build.gradle release 签名警告（勿用 debug keystore 分发）；
契约文档与实现字段漂移注记。

验证：`compileDebugKotlin` 零错误；706 单测无新增失败（基线 42 个失败为
配置缺失/环境问题，干净 worktree 对比确认）。
