# OpenMinis Pet Android 构建说明

本文对应当前 `master`。项目只构建 Android `arm64-v8a`；iOS/iSH 已从本分支移除。

## 已验证环境

| 工具 | 版本/要求 |
|---|---|
| 操作系统 | Linux 或 WSL2 |
| JDK | 17 |
| Gradle | Wrapper 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Android SDK | compileSdk 36，targetSdk 35，minSdk 26 |
| NDK | r28+（pet.15 使用 `28.0.13004108`） |
| CMake | 3.22.1 |
| ABI | `arm64-v8a` |

Windows 原生的非 ASCII/NTFS 路径可能触发 Gradle、CMake、符号链接和性能问题，推荐 WSL 的
ASCII 路径。

## 1. 克隆源码

```bash
git clone --recurse-submodules https://github.com/limuzi013/OpenMinis-Pet.git
cd OpenMinis-Pet
```

如果已经普通克隆：

```bash
git submodule update --init --recursive
```

`deps/proot` 是固定 commit 的 submodule。缺少它时 `deps/build_proot.sh` 无法构建。

## 2. 配置 SDK/NDK

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # 按实际安装目录调整
```

需要的常用命令：`bash`、`curl`、`tar`、`make`、`awk`、`sed`、`sha256sum`。

## 3. 准备公开构建配置

```bash
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

留空也可以编译和使用 API Key Provider。公开仓库未提供的 OAuth 定制值只影响对应 OAuth
功能，缺失时应在运行时明确失败，不会回退到秘密默认值。

## 4. 构建 PRoot

```bash
./deps/build_proot.sh
```

主要产物：

```text
src/android/app/src/main/assets/proot-aarch64
src/android/app/src/main/jniLibs/arm64-v8a/libproot.so
```

两个 ELF loader：

```text
libproot-loader.so
libproot-loader32.so
```

是仓库中明确保留的 vendored Termux 构建；脚本会核对固定 SHA-256。fork-built PRoot 和 Alpine
rootfs 是可重建产物并被 `.gitignore` 排除。

## 5. 准备 Alpine rootfs

```bash
./scripts/prepare_android_sandbox.sh
```

脚本下载固定的 Alpine 3.21.3 arm64 minirootfs 并校验 SHA-256，同时检查 PRoot 产物是否完整。
旧版本脚本使用的 Termux PRoot 包地址已失效；当前流程只从固定 submodule 构建 PRoot。

## 6. 编译

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

输出：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

安装到连接的 arm64 设备：

```bash
./gradlew :app:installDebug
# 或
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 测试

```bash
cd src/android

# 当前 Minis/DSH 与版本排序核心 JVM 回归
./gradlew :app:testDebugUnitTest \
  --tests com.openminis.app.remote.DshApiAdapterTest \
  --tests com.openminis.app.data.UpdateCheckerVersionTest

# 编译 instrumentation APK
./gradlew :app:assembleDebugAndroidTest

# 连接设备后按需运行
./gradlew :app:connectedDebugAndroidTest
```

公开仓库没有部分 OAuth customization 和网络 fixture，因此 38 项 Provider 测试不能作为无配置
环境下的绿色基线。不要用删除测试的方式伪造全绿。

## 常见问题

### `deps/proot/src` 不存在

```bash
git submodule update --init --recursive
```

### 找不到 NDK

确认 `ANDROID_NDK_HOME` 指向包含 `toolchains/llvm/prebuilt` 的 NDK r28+ 目录。

### App 能启动，但所有 Shell 命令失败

检查 APK 内是否存在：

```bash
unzip -l app-debug.apk | grep -E 'libproot|alpine-minirootfs'
```

至少应包含 `libproot.so`、`libproot-loader.so` 和 Alpine rootfs。重新执行：

```bash
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
```

### compileSdk 36 警告 AGP 只测试到 35

这是 AGP 8.7.3 的兼容性警告，不是当前 pet.15 构建失败；升级 AGP 前应单独跑完整 Compose、KSP、
CMake 和 instrumentation 回归。

## 签名与发布

当前 `release` buildType 仍配置 debug signing，且 Debug APK 会启动 DebugServer。它可以用于本地
构建和自测，**不能直接当生产发布方案**。正式发布至少需要：

1. 独立 release keystore 和安全的 CI secret；
2. release variant 关闭 DebugServer；
3. 更新 versionCode/versionName；
4. 完整测试、APK/AAB 签名校验、SHA-256 与对应 source tag；
5. 更新 `README.md`、`RELEASE-NOTES.md` 和 `releases/README.md`。
