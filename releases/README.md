# APK artifacts

Only explicitly published, source-corresponding artifacts belong here. Local Gradle outputs remain ignored.

| File | Source tag | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---|---:|---|
| [`OpenMinis-Pet-1.00-beta-arm64-debug.apk`](OpenMinis-Pet-1.00-beta-arm64-debug.apk) | [`v1.00-beta`](https://github.com/limuzi013/OpenMinis-Pet/tree/v1.00-beta) | debug | arm64-v8a | 54,437,205 | `086ebaf7fc743ded373a1e297f2dbe2ccb653df094de0958760815476aea3a96` |

GitHub Release download:
[`v1.00-beta`](https://github.com/limuzi013/OpenMinis-Pet/releases/tag/v1.00-beta).

The APK uses an Android Debug keystore and is intended for development and self-testing. It is not a
production release. Verify it before installation:

```bash
sha256sum OpenMinis-Pet-1.00-beta-arm64-debug.apk
```

PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\OpenMinis-Pet-1.00-beta-arm64-debug.apk
```
