# APK artifacts

Only explicitly published, source-corresponding artifacts belong here. Local Gradle outputs remain ignored.

| File | Source tag | Variant | ABI | Bytes | SHA-256 |
|---|---|---|---|---:|---|
| [`OpenMinis-Pet-minis-web-pet15-arm64-debug.apk`](OpenMinis-Pet-minis-web-pet15-arm64-debug.apk) | [`v1.12-pet.15`](https://github.com/limuzi013/OpenMinis-Pet/tree/v1.12-pet.15) | debug | arm64-v8a | 54,442,918 | `ed8355f6b4ccd0416d7edc82bc3729dc4398e98315b80664a7c9571bf8209fc0` |

GitHub Release download:
[`v1.12-pet.15`](https://github.com/limuzi013/OpenMinis-Pet/releases/tag/v1.12-pet.15).

The APK uses an Android Debug keystore and is intended for development and self-testing. It is not a
production release. Verify it before installation:

```bash
sha256sum OpenMinis-Pet-minis-web-pet15-arm64-debug.apk
```

PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 .\OpenMinis-Pet-minis-web-pet15-arm64-debug.apk
```
