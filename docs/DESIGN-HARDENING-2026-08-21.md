# 设计分享：安全加固与可靠性修复（2026-08-21）

> 本文档分享 2026-08-21 对 OpenMinis Pet 的一次全量审查修复（5 CRITICAL / 20 HIGH /
> 47 MEDIUM / 70+ LOW）中沉淀下来的设计决策。每条都写清了「为什么这样设计」和
> 「对使用者有什么影响」。原始逐项审查工作底稿没有纳入公开仓库；本文与 Git 历史是公开的
> 设计记录。
>
> 代码改动：41 个文件，`./gradlew :app:compileDebugKotlin` 零错误，706 个单测
> 无新增失败（基线 42 个失败经干净 worktree 对比确认与本次改动无关）。

---

## 一、凭据存储：fail-closed 取代「明文兜底」

**背景**：`EncryptedPrefsFactory` 原来的自愈逻辑在 EncryptedSharedPreferences
连续两次创建失败后，会回退到**明文** SharedPreferences（`*_plain_fallback`）。
所有调用方都是凭据（Provider API Key、OAuth、Web Remote 的登录盐/哈希、CLI token、
Cloudflare Tunnel token），一旦触发，全部凭据明文落盘——而且 `allowBackup=true`
下还会随备份外泄。更糟的是失败路径里的 wipe 会删除**全局唯一**的 AndroidKeyStore
主密钥别名和**共享**的 Tink keyset 文件，一个 store 出问题会让所有加密 store
级联失效并逐一落入明文。

**新设计（fail-closed）**：

1. 首次失败 → 只删除**该 store 自己的**加密 XML（不碰共享 keyset、不碰全局
   主密钥）→ 重建一次。
2. 重建仍失败 → 返回**空的内存 store**：读返回默认值、写被丢弃（有日志）。
   凭据表现为「未配置」——Web Remote 因 `hasPassword=false` 拒绝启动，模型
   需要重新录入 Key。**绝不写明文文件。**

**为什么这样是对的**：加密不可用时的两种失败模式——「功能不可用，要求重新
录入」vs「功能照常，凭据裸奔」——前者才是安全系统该有的行为。崩溃循环的担忧
由「空 store 不抛异常」解决：调用方看到的是空配置，不是崩溃。

**对使用者**：极端情况（Keystore 失效，如三星 One UI / Android 16 备份恢复或
生物识别重录入后）需要重新录入凭据；logcat 会打印
`encrypted prefs unavailable for <name>` 便于排查。

---

## 二、DebugServer：所有连接都要 token（含 loopback）

**背景**：调试服务器监听 0.0.0.0:5321，旧逻辑对 loopback 连接**免 token**。
但 Android 上 127.0.0.1 不是可信边界——任意本机进程或网页都能摸到。而这个
RPC 面能读文件、导出 API Key、跑 shell、驱动 UI，loopback 豁免等于给设备上
所有 App 留了一扇无锁后门。项目发布的是 debug 构建 APK，问题被放大到发布形态。

**新设计**：

- `isAuthorized` 删掉 loopback 分支——**一律**要求 `X-Minis-Token` /
  `Authorization: Bearer`（恒时比较）。
- 移除响应里的 `Access-Control-Allow-Origin: *`（浏览器跨域调用被拒）。
- Content-Length 上限 4 MiB、请求行/header 行 16 KiB 上限、header 数上限 100
  （原来 `CharArray(contentLength)` 一次分配，2 GB 声明即 OOM 杀进程）。
- 连接协程包 `catch(Throwable)` + scope 挂 `CoroutineExceptionHandler`
  （原来一条连接异常能崩整个进程）。
- token 值不再打进 logcat。
- `minis-debug` CLI 同步更新：从 `filesDir/debug_server_token` 读 token，
  每个请求带 `X-Minis-Token` 头（它在 app 进程内运行，读文件无需任何权限）。

**开发者工作流变化**：adb 调试不再免 token——先
`adb shell run-as <applicationId> cat files/debug_server_token` 拿到 token，
客户端带 `X-Minis-Token: <token>` 头；`minis-debug` CLI 无需手动处理（已内置）。

---

## 三、Web Remote 白名单：前缀放行 + deny 列表

**背景**：RPC 白名单按前缀放行（`provider.`、`chat.`、`skills.` …）。
前缀匹配对「新方法自动暴露」是双刃剑，而 `provider.export` 会把 base64 包裹的
API Key 原样返回——Web Remote 可以经 Cloudflare Tunnel 发布到公网，等于把密钥
送出去，与文件头 "No API-key/provider secrets are ever returned" 的承诺直接矛盾。

**新设计**：保留前缀白名单（形状控制），叠加**精确 deny 列表**（凭据控制）：

```kotlin
private val RPC_DENIED_METHODS = setOf(
    "provider.export", "provider.import",   // 携带完整 API Key / 任意 provider 配置
    "debug.logs.setEnabled",                // 写操作，不是只读诊断
)
```

注释里明确要求：向 DebugRPCHandler 新增敏感方法时必须同步维护该列表。

---

## 四、文件路径：canonical 前缀校验 + 拒绝 symlink 逃逸

**背景**：`PRootKernel.resolveSessionHostPath` 是裸 `File(parent, tail)`
拼接，Web 端 `/api/file` 的 `..` 词法检查拦不住 symlink——Agent 在沙盒里
`ln -s /data/data/... ` 就能让 host 侧文件读写逃出会话目录（读 app 数据库、
写任意路径）。

**新设计**（`RemoteAccessServer.resolveSessionFile`）：

1. 词法检查 `..`（保留）。
2. 解析后取 `canonicalFile`（跟随 symlink 的真实路径）。
3. 校验真实路径仍在预期根内：`/var/minis/<subdir>` → 会话宿主目录；
   其它 → PRoot rootfs 根。越界即拒绝。
4. `/api/file` POST/PUT 与 `/api/edit` 在调用工具层**之前**先过同一守卫
   （原来只有 GET 有守卫，写路径反而裸奔）。

---

## 五、权限预设：从装饰品变成真实闸门

**背景**：`RemotePermissionPolicy.allows()` 全仓库零调用方——设置页让用户选
「Workspace Write / Danger Full Access」并二次确认，但两种模式行为完全相同。

**新设计**：默认 Workspace Write 模式下，Web 端文件写入/编辑**仅允许**
`/var/minis/workspace` 前缀的路径；Danger Full Access 放开全部路径。

```kotlin
if (RemotePermissionPolicy.preset(appContext) == RemotePermissionPolicy.PRESET_WORKSPACE_WRITE &&
    !b.optString("path").startsWith("/var/minis/workspace")
) {
    throw IllegalArgumentException("workspace-write preset: writes are limited to /var/minis/workspace")
}
```

**对使用者**：默认模式下网页文件面板写非 workspace 路径会报错——这是预期行为，
不是 bug；需要全路径写权限时在设置里切换到 Full Access。

---

## 六、LLM 请求日志脱敏

**背景**：`debug.llmRequests` 把请求头原样返回，而请求头里必然有
`Authorization: Bearer <key>` / `api-key: <key>`。配合 C1 的无认证面，
一次调用即可批量导出全部 API Key。

**新设计**：`LLMRequestLog.add()` 统一剥离 7 类敏感头的**值**（保留名字，
请求形状仍可诊断）：authorization、x-api-key、api-key、cookie、set-cookie、
x-minis-token、proxy-authorization。在记录层统一处理，所有 provider 自动覆盖。

---

## 七、子代理：超时真取消 + 回答截断

**背景**：`HeadlessChatRunner.prompt(wait=true)` 的超时只是「不再等待」——
底层 LLM 流继续生成，继续消耗 API 配额；子代理回答也不设上限，几百 KB 的
回答会撑爆父会话上下文。

**新设计**：

- 超时分支改为 `?: run { vm.cancelStream(); false }`——先取消底层流再报超时
  （prompt 与 retry 两处）。
- `SubagentTool` 把子代理回答截断到 60,000 字符并附 `[truncated]` 提示，
  模型能意识到自己拿到的是部分结果。
- `SubagentTool` / `PetChatEngine` / `VisionGroupResolver` 的
  `runCatching`/裸 `catch(Exception)` 全部改为先重抛
  `CancellationException`——被取消的请求不再以「失败」形态覆盖新请求的 UI。

---

## 八、登录限流：per-IP 分桶 + 原子计数

**背景**：全局 `failedLogins += 1` 非原子（并发可绕过锁定），且是全局单计数器
（邻居打 5 个错密码就能把真实用户锁死 60 秒）。

**新设计**：`failedLoginsByIp: ConcurrentHashMap<String, AtomicInteger>`——
按来源 IP 分桶计数，5 次失败锁 60 秒；成功登录清空全部桶。配合登录请求里新增的
`remoteAddress` 字段（来自 socket）。顺带：登录成功时惰性清理过期会话条目，
`sessions` 表不再无限增长。

---

## 九、宠物运行时：五处行为修复

- **取消语义**：`PetChatEngine.ask` 的 `runCatching` 吞掉取消导致旧请求的
  失败回调覆盖新请求状态（FAILED 钉 1.4 秒 + 错误气泡）——改为重抛取消。
- **响应超时**：`provider.sendMessage` 包 `withTimeoutOrNull(60s)`——原来
  网络挂起时气泡和「想想…」能卡住 10 分钟。
- **主线程解码**：spritesheet 解码挪到 `Dispatchers.IO`（原来每次冷启动在
  主线程解码 11.5 MB 图集，ANR 风险）。
- **密度采样修正**：采样条件原来对默认 192px 图集数学上永不成立（需要
  density ≤ 0.67），永远全尺寸加载——现在按实际绘制尺寸逐级降采样。
- **语音双击竞态**：异步启动窗口内的第二次点击现在视为取消（`voiceStarting`
  标志 + 启动前复查），协程体整体 `runCatching` 防崩溃。

---

## 十、其它值得记住的设计

| 设计 | 说明 |
|---|---|
| **原子写** | `MessageFeedbackStore` 改 tmp+rename 原子替换；损坏文件先备份 `.corrupt` 再写，不再静默清空历史反馈 |
| **fuzzy 边界** | `FileEditEngine` fuzzy 匹配统一尾部换行规则（正文比较 + 换行数差 ≤1），替换区域不再吞掉尾随空行 |
| **截断器** | `ShellOutputTruncator` 行数 off-by-one 修正；字节切点非行首才丢弃前缀行（完整行不再被白白删掉） |
| **GoalTools 契约** | create_goal 已存在报错、update_goal 不存在报错、空文本=清除目标——对齐 DeepSeek Harness 语义 |
| **TodoTool 校验** | status 白名单（pending/in_progress/completed/skipped），非法值回退 pending |
| **附件净化** | 文件名只取末段 + 字符白名单 + 64 字符截断；单附件 16 MiB / 批 32 MiB / 最多 8 个 |
| **启动恢复** | 宠物补上开机恢复路径（与 Web Remote 对称）；`startIfEnabled` 加 in-flight 去重防三开 |
| **FGS 判据** | `AgentForegroundService` 统一用 `subsystemsReady()`（isSafeMode 会翻回 false 导致崩溃环） |
| **Ghost 闹钟** | 迁移失败保留 blob 待重试，不再无条件清空（后台 startActivity 被拦时旧闹钟不丢失） |
| **服务去重/重建** | `RemoteAccessServer` stop 后同一实例可再 start（scope 重建）；DebugServer start/stop 同步化 |

---

## 尚未处理（有意保留）

- **LAN 明文传输**：Web Remote 开启局域网访问时是明文 HTTP，登录密码/Cookie
  可被同网段嗅探。建议用法：默认 127.0.0.1 + Cloudflare Tunnel（TLS 终止在
  Cloudflare）；如需直连 LAN，应等待后续自签 HTTPS 或一次性 PIN 方案。
- **FGS mediaPlayback 类型**：用于非媒体用途，sideload 可接受；上架 Play 前必须改。
- **Android 15 BOOT_COMPLETED 的 FGS 类型限制**：需真机验证。
- **`/api/*` runBlocking**：每个长时 unary 请求会占一个 IO 线程；当前公网 Web 已不开放
  Shell，但提示、模型测试或网络导入仍可能是长请求。改成完整 suspend server 属架构级重构，
  留待后续。
