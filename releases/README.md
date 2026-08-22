# APK artifacts

Only explicitly published, source-corresponding artifacts belong here. Local Gradle outputs remain ignored.

| File | Source tag | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---|---:|---|
| [`OpenMinis-Pet-1.01-beta-arm64-debug.apk`](OpenMinis-Pet-1.01-beta-arm64-debug.apk) | [`v1.01-beta`](https://github.com/limuzi013/minis-for-android/tree/v1.01-beta) | debug | arm64-v8a | 54478422 | `388a843bbb63c4f6d6c5373fde4656330651d5ed27a2d499caa4e966e697f909` |

GitHub Release download:
[`v1.01-beta`](https://github.com/limuzi013/minis-for-android/releases/tag/v1.01-beta).

The APK uses an Android Debug keystore and is intended for development and self-testing. It is not a
production release. Verify it before installation:

```bash
sha256sum OpenMinis-Pet-1.01-beta-arm64-debug.apk
```

PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\OpenMinis-Pet-1.01-beta-arm64-debug.apk
```
