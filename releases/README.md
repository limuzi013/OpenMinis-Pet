# APK artifacts

This directory contains deliberately published, source-corresponding APKs. Other local Gradle APK
outputs remain ignored.

| File | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---:|---|
| [`OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk`](OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk) | debug | arm64-v8a | 51,563,132 | `3dcc514ebded6f7d706b9d7e703ca0bc28002880e4ad8e247e6dba8cb1fb145f` |

The APK uses the Android debug keystore and is intended for development and self-testing. Verify it
before installation, for example with `sha256sum` or PowerShell `Get-FileHash -Algorithm SHA256`.
