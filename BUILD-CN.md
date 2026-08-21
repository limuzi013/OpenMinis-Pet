# 构建说明

这是一份**完整、可独立构建**的源码，不需要再打补丁、不需要 `git submodule update`。

## 这份源码是什么

在官方 OpenMinis `1.12`（versionCode 24）基础上合并了三组改造：

- **桌面宠物**：悬浮宠物、点击对话、语音输入输出、巡游与贴边隐藏、Agent 状态联动
- **默认数字助手**：Android Assistant Role、系统助手手势唤起与识别服务桥
- **Pi 风格 Agent**
- **Web Remote**：网页远程管理、Provider/模型/技能/MCP/环境与挂载、登录鉴权、Cloudflare Tunnel

`applicationId` 是 `dev.openminispet.android`，与官方版 `com.openminis.app` **不冲突，可同时安装**。

改动清单与踩坑记录见 [CHANGELOG-FORK.md](CHANGELOG-FORK.md)，项目说明见 [README.md](README.md)。

## 已验证

2026-08-21 在 WSL2 Ubuntu 上完整编译与关键 JVM 回归测试通过，产出 51 MB 的 debug APK。

仓库同时提供这次验证通过的预构建包：
[`releases/OpenMinis-Pet-dsh-remote-rc9-arm64-debug.apk`](releases/OpenMinis-Pet-dsh-remote-rc9-arm64-debug.apk)。
它只支持 `arm64-v8a`，使用 debug 签名；SHA-256 为
`d5c5bfacd80a0bac517d3c63c664d77df42430d1217e94fbed61ac0e1c00d1c4`（`54,194,427` bytes）。

环境：JDK 17（Temurin）、Android SDK Platform 36、Build-Tools 36.0.0、NDK 28.0.13004108、CMake 3.22.1、只构建 `arm64-v8a`。

## 构建步骤

必须在 **Linux 或 WSL** 里构建。Windows 原生路径下 Gradle 与 CMake 会因为路径分隔符和符号链接问题失败。

```bash
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/28.0.13004108
```

### 1. 构建 PRoot（首次必做）

沙盒的核心，产物是 `libproot.so` 与 `assets/proot-aarch64`：

```bash
./deps/build_proot.sh
```

### 2. 准备 Alpine sandbox

会下载 Alpine minirootfs 并放进 assets：

```bash
./scripts/prepare_android_sandbox.sh
```

### 3. 补上构建期配置

公开镜像只带 `.example`，缺了它编译能过、运行到需要该值时才报错：

```bash
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

### 4. 编译 APK

```bash
cd src/android && ./gradlew :app:assembleDebug
```

产物在 `src/android/app/build/outputs/apk/debug/app-debug.apk`。

### 安装

```bash
adb install -r src/android/app/build/outputs/apk/debug/app-debug.apk
```

## 装好之后

1. 打开 App → 设置 → 权限 → 系统权限 → **显示在其他应用上层**，授权。
2. 设置 → 外观 → **桌面宠物** → 导入宠物包 ZIP（内含 `pet.json` + `spritesheet.webp`），启动宠物。
3. 想让宠物能对话，先在设置里配好默认模型（Provider + API Key）。
4. 如需系统手势唤起，在设置页申请「默认数字助手」；OEM 可能要求在系统设置中手动确认。

## 已知限制

- **语音识别依赖设备或云端引擎**。部分国产 ROM 的系统识别不可用（`SpeechRecognizer.isRecognitionAvailable()` 返回 false）。此时要在**设置 → 语音**里给 Voice Input 组绑定一个云端 ASR 模型（例如 OpenAI whisper），宠物的麦克风才能用。
- 宠物对话直连当前默认模型，**不跑 Agent 工具链**：能问答、能总结，不能执行命令或读写文件。真正干活仍然要在 App 里开正常会话。
- 只构建 `arm64-v8a`，不支持 32 位设备和模拟器 x86。

## License

OpenMinis 使用 GPL-3.0。分发修改后的 APK 同样受 GPL-3.0 约束，需要一并提供对应源码。
