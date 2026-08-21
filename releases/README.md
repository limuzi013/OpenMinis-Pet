# APK artifacts

This directory contains deliberately published, source-corresponding APKs. Other local Gradle APK
outputs remain ignored.

| File | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---:|---|
| [`OpenMinis-Pet-dsh-remote-rc9-arm64-debug.apk`](OpenMinis-Pet-dsh-remote-rc9-arm64-debug.apk) | debug | arm64-v8a | 54,194,427 | `d5c5bfacd80a0bac517d3c63c664d77df42430d1217e94fbed61ac0e1c00d1c4` |

The APK uses the Android debug keystore and is intended for development and self-testing. Verify it
before installation, for example with `sha256sum` or PowerShell `Get-FileHash -Algorithm SHA256`.
