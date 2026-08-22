# Building Minis for Android

This document describes the current `master` branch. The project is Android-only and builds
`arm64-v8a`. Chinese instructions are in [BUILD-CN.md](BUILD-CN.md).

## Requirements

| Tool | Version / notes |
|---|---|
| Host | Linux or WSL2 recommended |
| JDK | 17 |
| Android SDK | compileSdk 36, targetSdk 35, minSdk 26 |
| Android NDK | r28+ (`28.0.13004108` used by the release build) |
| CMake | 3.22.1 |
| Node.js | 22+ (only for the Minis Web client plugin build) |
| Shell tools | bash, curl, tar, make, awk, sed, sha256sum |

Gradle 8.11.1, AGP 8.7.3, and Kotlin 2.1.0 are selected by the repository. Do not install a separate
Gradle distribution.

## Clone

```sh
git clone --recurse-submodules https://github.com/limuzi013/minis-for-android.git
cd OpenMinis-Pet
```

For an existing non-recursive clone:

```sh
git submodule update --init --recursive
```

`deps/proot` is a pinned Git submodule. A source archive downloaded from GitHub does not include the
submodule contents; clone recursively or populate it manually before building.

## SDK and build customization

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # adjust locally

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Empty customization values are valid for API-key based providers. Features that require an omitted
OAuth customization value fail explicitly at runtime; no private defaults are stored in this repository.

## Build the sandbox assets

```sh
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
```

The first command builds PRoot from the pinned `OpenMinis/proot` source and writes the generated
`proot-aarch64`/`libproot.so` artifacts. The two Android ELF loaders are pinned vendored Termux builds
tracked by Git because the exact package has been retired and fork-built loaders regress on some
ROMs; `build_proot.sh` verifies their SHA-256 values.

The second command downloads the pinned Alpine 3.21.3 arm64 minirootfs, verifies its SHA-256, and checks
that all required PRoot artifacts exist.

Generated PRoot and Alpine artifacts are ignored by Git and must be recreated on a clean checkout.

## Build the Minis Web client plugin

The Minis Console is a formal DeepSeek Harness client plugin; its source lives in
`web/minis-client-plugin/` and its generated `client.js` ships in the APK assets.

```sh
cd web/minis-client-plugin
npm install
npm run check    # tsc --noEmit
npm test         # vitest
npm run build    # writes plugins/@openminis/minis-client-settings/client.js
                 # and updates assets/minis/index.html boot graph
```

`npm run build` is the only supported way to update the browser bundle or the boot graph; never edit the
generated `client.js` or the `__MINIS_BOOT__` JSON by hand.

## Build the APK

```sh
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

Output:

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected arm64 device with either:

```sh
./gradlew :app:installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Tests

Focused public-repository regression baseline:

```sh
cd src/android
./gradlew :app:testDebugUnitTest \
  --tests com.openminis.app.remote.DshApiAdapterTest \
  --tests com.openminis.app.data.UpdateCheckerVersionTest

./gradlew :app:assembleDebugAndroidTest
```

Use `./gradlew :app:connectedDebugAndroidTest` only with an explicitly authorized device. Thirty-eight
Provider tests require OAuth customization or network fixtures that are intentionally absent from the
public repository; do not remove them merely to produce a green unconfigured run.

## Troubleshooting

### `deps/proot/src` is missing

Run `git submodule update --init --recursive`.

### Android NDK not found

Set `ANDROID_NDK_HOME` to an installed r28+ directory containing `toolchains/llvm/prebuilt`.

### The app starts but sandbox commands fail

Rebuild and verify the sandbox artifacts:

```sh
./deps/build_proot.sh
./scripts/prepare_android_sandbox.sh
unzip -l src/android/app/build/outputs/apk/debug/app-debug.apk \
  | grep -E 'libproot|alpine-minirootfs'
```

The APK needs `libproot.so`, the 64-bit PRoot loader, and the Alpine rootfs. Starting the App alone is
not a valid sandbox test; execute a shell command and assert exit code 0.

## Signing and production releases

The current `release` build type still uses debug signing, and debug APKs start DebugServer. This is
acceptable only for development/self-test. A production release requires a protected release keystore,
a release variant that does not start DebugServer, a complete security/test pass, an immutable source
tag, and published artifact hashes.

See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) for the PRoot, loader, Alpine, Harness, and Android
dependency licensing notes.
