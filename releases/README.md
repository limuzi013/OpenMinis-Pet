# APK artifacts

This directory contains deliberately published, source-corresponding APKs. Other local Gradle APK
outputs remain ignored.

| File | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---:|---|
| [`OpenMinis-Pet-minis-web-pet15-arm64-debug.apk`](OpenMinis-Pet-minis-web-pet15-arm64-debug.apk) | debug | arm64-v8a | 54,442,918 | `ed8355f6b4ccd0416d7edc82bc3729dc4398e98315b80664a7c9571bf8209fc0` |

The APK uses the Android debug keystore and is intended for development and self-testing. Verify it
before installation, for example with `sha256sum` or PowerShell `Get-FileHash -Algorithm SHA256`.
