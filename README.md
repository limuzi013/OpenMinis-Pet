# OpenMinis Pet

> **这是 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 的非官方分支，只做了一点二次创作。**
>
> Agent、PRoot 沙盒、模型接入、整个 App 的骨架全部是原作者的功劳。本仓库只是在官方源码上
> 做了面向 Android 的桌面伴侣、系统助手和远程工作台增强，并且**只做 Android**。
> 有问题请在本仓库反馈，**不要去打扰上游维护者**。

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

基线：官方 `1.12`（versionCode 24）。上游原始说明见 [README-upstream.md](README-upstream.md)。

## 下载已验证 APK

[下载 `OpenMinis-Pet-dsh-remote-rc9-arm64-debug.apk`](releases/OpenMinis-Pet-dsh-remote-rc9-arm64-debug.apk)

- 版本：`1.12-pet.11-SNAPSHOT`（versionCode 31）
- 架构：`arm64-v8a`
- 大小：`54,194,427` bytes
- SHA-256：`d5c5bfacd80a0bac517d3c63c664d77df42430d1217e94fbed61ac0e1c00d1c4`
- 签名：debug keystore，仅供开发、自测和源码对应验证

下载后可用 `adb install -r OpenMinis-Pet-dsh-remote-rc9-arm64-debug.apk` 安装。这个 APK
对应下面所述的 Harness 风格事件流工作台、完整 Web 设置管理、默认数字助手修复和桌面宠物
P0 迭代；后续快照如更换二进制，会同步更新文件名、字节数和校验值。

## 和官方版的关系

| | 官方 OpenMinis | 本分支 |
|---|---|---|
| applicationId | `com.openminis.app` | `dev.openminispet.android` |
| 应用名 | Minis | OpenMinis Pet |
| 平台 | Android + iOS | **只有 Android** |
| 能否共存 | — | **可以同时安装** |

Android 以 `applicationId` 作为安装身份，所以装了这个不会覆盖官方版。Kotlin namespace
仍保持 `com.openminis.app`，是为了避免对整棵源码树做一次高风险的包名重命名。

iOS 相关代码（`src/ios/`、iSH 沙盒、FFmpeg/LAME）已从本仓库移除——只做 Android，留着
徒增体积和困惑。要 iOS 版请用[官方仓库](https://github.com/OpenMinis/OpenMinis)。

## 2026-08-21 安全加固与设计分享

一次全量审查（5 CRITICAL / 20 HIGH / 47 MEDIUM / 70+ LOW）驱动的大修，核心设计：

- **凭据 fail-closed**：加密存储失败时**绝不回退明文**（返回空内存 store，凭据视为
  未配置、功能拒绝启动）——宁可要求重新录入，也不让 API Key 裸奔
- **调试端口全连接 token**：DebugServer（0.0.0.0:5321）**loopback 也要 token**
  （Android 上 127.0.0.1 任何进程/网页都能摸，旧豁免等于无锁后门）；同步去 CORS、
  加请求上限、单连接异常不再崩进程
- **Web 白名单 deny 列表**：`provider.export` / `provider.import`（携带完整
  API Key）等敏感方法永久封禁，不再经公网隧道外泄
- **文件路径 canonical 守卫**：词法 `..` 检查之外加真实路径（跟随 symlink）
  归属校验——沙盒内建个链接也逃不出会话目录
- **权限预设真实生效**：默认 Workspace Write 模式下网页写入/编辑仅限
  `/var/minis/workspace`，Full Access 才放开
- **LLM 请求日志脱敏**：authorization / x-api-key 等 7 类敏感头的值在记录时剥离
- **子代理超时真取消**：不再"假装超时、底层继续烧 token"；回答 60k 截断
- **登录限流 per-IP 分桶**：并发绕过和邻居反锁两个洞一起堵

每条设计的动机、实现、对使用者的影响见
[docs/DESIGN-HARDENING-2026-08-21.md](docs/DESIGN-HARDENING-2026-08-21.md)；
完整审查报告见 [docs/find-fault-report.md](docs/find-fault-report.md)。

**给使用者的行为变化**：

- Web Remote 默认模式下网页文件面板**只能写 `/var/minis/workspace`**，需要全路径
  写权限请切 Danger Full Access
- 极端情况下（系统 Keystore 失效）需要重新录入 API Key / 重设 Web Remote 密码
- 调试端口（5321）不再免 token：`adb shell run-as dev.openminispet.android cat
  files/debug_server_token` 获取，`minis-debug` CLI 已自动处理

---

## 加了什么

### 一、桌面宠物

通用的宠物运行时——不把某一只宠物硬编码进 APK，而是导入 ZIP 宠物包：

```text
my-pet.zip
├── pet.json          # id / displayName / spritesheetPath
└── spritesheet.webp  # 默认 8 列 × 9 行，单格 192×208
```

- **点一下就能聊天**：点宠物弹出输入框，回答显示在气泡里，同时写进 App 的会话历史
  （会话名「桌面宠物」），不会聊完就没了
- **语音**：复用 App 自己的 Voice Input / Voice Output 配置，不另起一套 API 设置
- **会自己动**：空闲时随机巡游、拖动后吸附边缘、久置贴边隐藏只露一点，点一下滑回来
- **跟着 Agent 状态走**：`running / waiting / review / failed / idle`，任务完成时招手说一句
- **熄屏就停**：动画、巡游、随机动作全部暂停，亮屏恢复
- **恢复不飘走**：授权页返回会自动重试，换宠物包/缩放/重启后会重新限制到屏幕范围，
  拖拽前后位置都持久化；加载失败不会留下一个看不见但常驻的前台服务
- 悬浮窗权限并入官方的「设置 → 权限 → 系统权限」页，宠物页只做提示

宠物对话直连当前默认模型，**不跑 Agent 工具链**：能问答能总结，不能执行命令或读写文件。
真要干活还是在 App 里开正常会话。

### 二、默认数字助手

App 可以申请 Android 标准的 `ROLE_ASSISTANT`。设为系统默认助手后，长按 Home、电源键助手
手势或 ROM 提供的助手入口会通过 `VoiceInteractionSession` 把现有 OpenMinis 任务拉到前台。
声明中包含真实的 Session 与 Recognition 服务；识别桥会复用设备已有的系统 ASR，并避免递归
调用自己。部分 ROM 仍只允许用户在系统设置页手动选择，App 不会也不能静默抢占默认助手。
本次发布已在 Android 16 / API 36 小米设备上完成实机验证：系统成功绑定 OpenMinis 的会话与
识别服务，助手按键可以拉起 App，触发后无崩溃。

### 三、编码可靠性增强

移植了 Pi 风格 coding-agent 里几项高价值能力，改的是 Agent 动文件和跑命令时的行为，
都是防止「改坏东西」和「撑爆上下文」的。

**没有**把 Pi 整套东西搬过来——Persistent PRoot Shell 仍然是 OpenMinis 原来那套，只是把
更合理的输出与上下文处理接了进去。会话分支导航、扩展系统这些大型改造刻意没做：那会从
「增强 OpenMinis」变成「把它重写成 Pi」，以后再想跟官方更新合并会非常痛苦。

**文件编辑**（`FileEditEngine`）

- 一次提交多处修改，全部基于同一份原始快照匹配
- 编辑区域重叠会直接报错拒绝，不会猜着改
- 支持保守的 fuzzy match，同时统计模糊命中数量供上层判断
- 保留文件原有的 BOM 和 CRLF 行尾——不会因为改一行就把整个文件的行尾换掉
- 返回 unified diff，改了什么一目了然

**并发写入**（`FileMutationQueue` + `FileRevision`）

同一文件的写入串行化，避免 Agent 并发操作互相覆盖；编辑前用 SHA-256 校验文件版本，
被别人改过就拒绝盲写。

**大输出不撑爆上下文**（`ShellOutputTruncator`）

构建日志、`find /` 这种巨量输出，上下文里只保留 2000 行 / 50 KiB，完整内容落盘到
`/var/minis/offloads/tools/`，Agent 后面还能用 `file_read` 翻回来。截断按 Unicode
码点回退，不会从一个 UTF-8 字符或 emoji 中间砍断。

**子代理委派**（`subagent`）

把一个自包含的子任务丢给独立会话去跑：子代理有自己的上下文和完整工具链
（复用 App 自己的 Agent 循环与 Persistent Shell），父会话只拿到最终答案。
「读 40 个文件然后告诉我 X 在哪里」这种长探索烧的是子代理的上下文，不会撑爆
当前会话。委派深度上限 3 层，单个子任务超时 10 分钟，子会话会以「↳ 标题」的
形式出现在会话列表里，方便事后查看。

### 四、Web 远程控制

浏览器里管手机上的 Agent，和 Android 原生界面**共享同一个 Session、同一
`ChatViewModel`、Agent Loop 和 Persistent Shell**——不是另开一套会话。网页修改模型或
思考强度时，改的是该会话在原生聊天页正在使用的绑定，而不是网页自己的副本：

- 会话列表、对话、Markdown 渲染（代码块带复制按钮、表格、列表、公式）
- **真实会话事件流**：初始只取一次会话快照，随后通过认证 WebSocket 接收带单调 `seq`
  的 `session/event`；文本、思考、工具调用和完成状态按事件增量更新对应节点。断线以
  `afterSeq` 回放，发现保留窗口外的游标时重新取快照，而不会定时拉整段消息或重建聊天 DOM
- 文件浏览 / 在线编辑、Shell 执行
- **Harness 风格会话工作台**：目标、计划、待办与产出以按需 Details Inspector 呈现；
  网页可直接编辑并与 Android 聊天页共享同一状态源，不会产生另一份网页专用任务。
  作业区只呈现 App 当前已登记的项；它不是一个已完成的通用、持久化后台作业平台
- **执行轨迹与工具检查器**：工具调用/结果兼容当前 `toolUse` 与历史
  `tool_use` 持久化格式，聊天流中保持紧凑，点开即可看输入、输出、状态并从该步骤
  重跑；回答支持复制、重试和反馈
- **工作区**：文件树、在线编辑、Persistent Shell、交付物与最近 Agent Activity
  通过「工作区」按需全屏打开，不会永久挤窄聊天区；支持文件拖放或从剪贴板粘贴附件
- 技能、记忆（含 SOUL.md）、MCP 服务器、定时任务四个管理页签：列表、启停、
  删除都在网页上完成；技能和 MCP 还可新建/编辑或导入配置，记忆文件可以直接在线编辑，
  定时任务还能一键「立即运行」
- **模型与 Provider 管理**：网页直接调用手机端 `ProviderRepository`，可添加、编辑、测试、
  启停和删除 Provider/API Key，也可新增、修改、隐藏和删除模型；密钥只写入手机加密存储，
  不会从列表接口回传
- **环境与存储**：环境变量可安全增删改；共享目录 `/var/minis/{shared,skills,memory}` 和
  已授权的 `/var/minis/mounts/*` 可在工作区打开，现有外部挂载可改名、切换写权限或移除
- 登录鉴权：PBKDF2-HMAC-SHA256（210k 轮）+ 12 小时 HttpOnly Session Cookie，
  **没设密码就拒绝启动**；默认只监听 `127.0.0.1`，要开局域网得显式打开
- Cloudflare Tunnel 管理，没有公网 IP 也能用域名访问
- 重启手机后自动恢复

Web 端复用的是 App 内部的 RPC 通道，但**按前缀白名单放行**：`provider.` /
`chat.` / `skills.` / `memory.` / `soul.` / `mcp.` / `scheduled.` 以及少量诊断
方法（`debug.logs.*`、`debug.crash.*`、`debug.appInfo`）。`debug.tap`、
`debug.inputText`、`debug.screenshot`、`debug.writeFile` 这类等于远程操控手机的
方法被整个挡在网页之外，要用只能走本机 127.0.0.1:5321 调试端口。

页面根据本地官方 DeepSeek Harness `0.1.0-rc.8` 的 **MIT 源码**做了 source-adapted
实现：会话栏可收起为 56px rail，聊天始终是默认主区，Details Inspector 默认关闭并在
空间不足时优先收起；任务、轨迹、设置和文件工作区均按需打开。没有把 Harness 的
React/Cordis bundle 塞进 APK，也不冒充与 DeepSeek 有关联；主题与交互资源随 APK 内置，
离线可用。

## 装了之后

1. 「设置 → 权限 → 系统权限 → 显示在其他应用上层」授权
2. 「设置 → 外观 → 桌面宠物」导入宠物包 ZIP，启动宠物
3. 想让宠物说话，先在设置里配好默认模型（Provider + API Key）
4. 想用系统手势唤起，在「设置 → 默认数字助手」申请角色；部分 ROM 会跳到系统设置手动选择

Web 远程控制在「设置 → Web 远程控制」，**必须先设登录密码**才能启动。

## 构建

详见 [BUILD-CN.md](BUILD-CN.md)。必须在 Linux / WSL 里，需要 JDK 17 + Android SDK 36
+ NDK r28，只出 `arm64-v8a`。

```bash
git clone --recursive https://github.com/limuzi013/OpenMinis-Pet.git
```

`--recursive` 不能省——`deps/proot` 是 submodule，缺了它构建不出沙盒。

## 已知限制

- **语音识别依赖设备或云端引擎**。部分国产 ROM 的系统识别不可用
  （`SpeechRecognizer.isRecognitionAvailable()` 返回 `false`），这时要在
  「设置 → 语音」给 Voice Input 组绑一个云端 ASR 模型，宠物的麦克风才能用。
- 宠物对话没有工具调用能力（见上）。
- Android 的外部目录授权必须由手机上的 SAF 系统选择器完成；网页可以管理和打开已有挂载，
  不能替用户在浏览器里授予新的系统目录权限。
- 只构建 `arm64-v8a`，不支持 32 位设备和 x86 模拟器。
- Release 里的 APK 用 debug 签名（沿用上游配置），仅供自用。

完整改动清单和踩坑记录见 [CHANGELOG-FORK.md](CHANGELOG-FORK.md)。
2026-08-21 安全加固与可靠性修复的设计决策见 [docs/DESIGN-HARDENING-2026-08-21.md](docs/DESIGN-HARDENING-2026-08-21.md)。

## License

跟随上游，**GPL-3.0**。分发修改后的 APK 同样受 GPL-3.0 约束，需要一并提供对应源码——
本仓库即是。

第三方组件许可见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
原项目版权归 OpenMinis 作者所有。
