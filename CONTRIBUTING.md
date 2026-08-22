# Contributing to OpenMinis Pet

OpenMinis Pet is an unofficial Android fork of OpenMinis. Fork-specific bugs and feature requests belong
in this repository, not in the upstream OpenMinis or DeepSeek trackers.

## Report an issue

Open: <https://github.com/limuzi013/OpenMinis-Pet/issues>

Please include:

- OpenMinis Pet version/versionCode and Android version;
- device model/ROM and whether it is rooted;
- exact steps, expected result, and actual result;
- selected Provider/model when relevant;
- sanitized App logs or crash metadata.

Never paste API keys, OAuth tokens, Web Remote passwords, Cloudflare tunnel tokens, DebugServer tokens,
private file contents, or unrelated phone data. Reproduce against the App itself; do not inspect other Apps.

## Pull requests

Small, focused pull requests are welcome. Before opening one:

1. base it on the current `master` branch;
2. keep Android behavior authoritative—do not add a second Web-only Agent/runtime/database;
3. preserve Web Remote allow/deny policy and secret redaction;
4. do not expose screenshot, input injection, arbitrary Shell/file, credentials, `su`, or Root through Web;
5. preserve third-party notices and required `@deepseek-ai/dsh-*` compatibility IDs;
6. update tests and the relevant current documentation, not only the historical changelog;
7. do not commit generated rootfs/PRoot/Gradle outputs or private customization values.

## Build and test

Follow [BUILDING.md](BUILDING.md) or [BUILD-CN.md](BUILD-CN.md). The minimum public-repository checks are:

```bash
cd src/android
./gradlew :app:testDebugUnitTest \
  --tests com.openminis.app.remote.DshApiAdapterTest \
  --tests com.openminis.app.data.UpdateCheckerVersionTest
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:assembleDebug
```

Provider tests that require private OAuth customization or external network fixtures must be reported as
such; do not delete or weaken them merely to make an unconfigured build green.

## License

Contributions are distributed under [GPL-3.0](LICENSE), consistent with the repository. By submitting a
change, you confirm that you have the right to provide it under that license. Third-party code must include
its source and license information in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
