# APK artifacts

Only explicitly published, source-corresponding artifacts belong here. Local Gradle outputs remain ignored.

| File | Source tag | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---|---:|---|
| [`OpenMinis-Pet-1.00-beta-arm64-debug.apk`](OpenMinis-Pet-1.00-beta-arm64-debug.apk) | [`v1.00-beta`](https://github.com/limuzi013/minis-for-android/tree/v1.00-beta) | debug | arm64-v8a | 54949540 | `bb017abb06c5ca20c3d072fc728e1c2a2f6f321819cb426a2171ed48d6dcb359` |

GitHub Release download:
[`v1.00-beta`](https://github.com/limuzi013/minis-for-android/releases/tag/v1.00-beta).

The APK uses an Android Debug keystore and is intended for development and self-testing. It is not a
production release. Verify it before installation:

```bash
sha256sum OpenMinis-Pet-1.00-beta-arm64-debug.apk
```

PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\OpenMinis-Pet-1.00-beta-arm64-debug.apk
```
