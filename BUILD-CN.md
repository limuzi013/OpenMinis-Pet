# Minis for Android 构建说明

本文对应当前 `master`。项目只构建 Android `arm64-v8a`。

## 已验证环境

| 工具 | 版本/要求 |
|---|---|
| 操作系统 | Linux 或 WSL2 |
| JDK | 17 |
| Gradle | Wrapper 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Android SDK | compileSdk 36,targetSdk 35,minSdk 26 |
| NDK | r28+(当前构建使用 `28.0.13004108`) |
| CMake | 3.22.1 |
| Node.js | 22+(仅 Minis Web Client Plugin 构建需要) |
| ABI | `arm64-v8a` |

Windows 原生的非 ASCII/NTFS 路径可能触发 Gradle、CMake、符号链接和性能问题,推荐 WSL 的
ASCII 路径。

## 1. 克隆源码

```bash
git clone --recurse-submodules https://github.com/limuzi013/OpenMinis-Pet.git
cd OpenMinis-Pet
```

`deps/proot` 是固定 commit 的 submodule;源码压缩包不含其内容,必须递归克隆或手动初始化。

## 2. 配置 SDK 与定制

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # 按本机版本调整

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

空值对 API Key 类 Provider 合法;需要省略 OAuth 定制值的功能会在运行时显式失败。

## 3. 构建沙箱资源

```bash
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
```

- 第一条从固定的 `OpenMinis/proot` 源码构建 PRoot,产出 `proot-aarch64` 与 `libproot.so`;
  两个 Android ELF loader 是仓库中明确保留并校验 SHA-256 的 vendored Termux 构建;
- 第二条下载固定的 Alpine 3.21.3 arm64 minirootfs、校验 SHA-256,并检查 PRoot 产物齐全;
- 生成的 PRoot 与 Alpine 产物不提交 Git,干净 checkout 后必须重新生成。

## 4. 构建 Minis Web Client Plugin

「Minis 控制台」是正式 DeepSeek Harness Client Plugin;源码在 `web/minis-client-plugin/`,
生成的 `client.js` 随 APK assets 分发。

```bash
cd web/minis-client-plugin
npm install
npm run check    # tsc --noEmit
npm test         # vitest
npm run build    # 生成 plugins/@openminis/minis-client-settings/client.js
                 # 并更新 assets/minis/index.html 的 boot graph
```

`npm run build` 是更新浏览器 bundle 与 boot graph 的唯一受支持方式;禁止手工修改生成的
`client.js` 或 `__MINIS_BOOT__` JSON。

## 5. 构建 APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

输出:`src/android/app/build/outputs/apk/debug/app-debug.apk`

安装到已连接的 arm64 设备:

```bash
./gradlew :app:installDebug
# 或
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. 测试

```bash
cd src/android
./gradlew :app:testDebugUnitTest \
  --tests com.openminis.app.remote.DshApiAdapterTest \
  --tests com.openminis.app.data.UpdateCheckerVersionTest

./gradlew :app:assembleDebugAndroidTest
```

只有明确授权的设备才运行 `./gradlew :app:connectedDebugAndroidTest`。38 项 Provider 测试需要
公开仓库不提供的 OAuth 定制值或网络 fixture;不要为了绿跑删除它们。

## 常见问题

### `deps/proot/src` 不存在

执行 `git submodule update --init --recursive`。

### 找不到 Android NDK

将 `ANDROID_NDK_HOME` 指向包含 `toolchains/llvm/prebuilt` 的 r28+ 目录。

### App 能启动但沙箱命令失败

重建并核验沙箱产物:

```bash
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
unzip -l src/android/app/build/outputs/apk/debug/app-debug.apk \
  | grep -E 'libproot|alpine-minirootfs'
```

APK 需要 `libproot.so`、64 位 PRoot loader 与 Alpine rootfs。仅启动 App 不是有效的沙箱测试;
执行一条 shell 命令并断言退出码为 0。

### 更改 versionName/versionCode

编辑 `src/android/app/build.gradle.kts` 中的 `versionCode`/`versionName`,重新构建,并把 APK
与哈希同步到 `releases/README.md` 与 `RELEASE-NOTES.md`。

## 发布要求

当前 `release` 构建类型仍使用 debug 签名,debug APK 会启动 DebugServer,只适合开发自测。
生产发布需要长期保管的 release keystore、不启动 DebugServer 的 release 变体、完整安全验收、
不可变源码 tag 与已发布的产物哈希。

许可证与来源见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
