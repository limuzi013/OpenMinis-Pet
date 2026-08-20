# APK artifacts

This directory contains deliberately published, source-corresponding APKs. Other local Gradle APK
outputs remain ignored.

| File | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---:|---|
| [`OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk`](OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk) | debug | arm64-v8a | 51,600,188 | `9b58bb09b617eb1d6722f4afb30fa797017f2958eb713cc17231f4f5b2c891cf` |

The APK uses the Android debug keystore and is intended for development and self-testing. Verify it
before installation, for example with `sha256sum` or PowerShell `Get-FileHash -Algorithm SHA256`.
