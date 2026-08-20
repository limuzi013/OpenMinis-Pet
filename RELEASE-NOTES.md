# OpenMinis Pet release notes

## Unreleased · `1.12-pet.11-SNAPSHOT`

The verified APK is tracked with the source at
[`releases/OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk`](releases/OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk).
It is a development/debug-signed artifact rather than a production Play release.

### Web Remote: source-adapted Harness workbench

- The Web Remote workbench is a source-adapted implementation informed by the local official DeepSeek Harness `0.1.0-rc.8` **MIT** source. It follows the reading-first AppFrame idea: a collapsible session rail, a conversation-first main area, and on-demand details, activity, settings, and workspace surfaces.
- It does not ship DeepSeek Harness's React/Cordis client bundle and is not a DeepSeek product or endorsed integration.
- Browser and Android operate one session through the same `ChatViewModel`, agent loop, model selection, and thinking-level binding. Changing a model or thinking level in the browser changes the active native-chat binding for that session; it does not create a Web-only copy.
- The initial session snapshot establishes the browser projection. An authenticated WebSocket at `/api/events/session` then delivers ordered `session/event` frames with a monotonic per-session `seq`. Reconnects use `afterSeq`; a cursor outside the retained replay window receives a reset signal and rehydrates a snapshot.
- Core event names follow the Harness-shaped vocabulary (`user/message`, `turn/start`, `assistant/chunk`, `tool/call`, `tool/result`, `assistant/message`, `turn/end`). Text, reasoning, and tool deltas patch their existing message nodes rather than replacing the complete transcript.
- Tool history accepts the current `toolUse` / `toolResult` representation and historical snake_case blocks. The conversation keeps a compact trajectory; details expose available tool input, output, and status.

### Existing remote capabilities and boundaries

- The workspace exposes the app's existing file, editor, and shell endpoints subject to the configured remote permission policy. Device-control Debug RPCs such as tap, text injection, screenshots, and arbitrary Debug-server file writes remain outside the Web Remote allowlist.
- Skills, memory/SOUL, MCP, scheduled-task, and settings surfaces are backed by the App's existing allowed RPC groups. Their availability depends on the corresponding native subsystem and permission policy.
- Provider instances, write-only API credentials, custom models, Skills and MCP servers now have real create/edit/delete flows in the Web control center. Environment-variable values are also write-only: Web can replace or clear them, while list responses expose only `hasValue`.
- The storage surface exposes the fixed shared folders and already-authorized external mounts. Existing mounts can be renamed, made read-only/read-write, opened, or removed. Creating a mount still requires the Android SAF picker and is intentionally not faked by Web.
- The workbench may show items already registered by the App, but it is **not** a completed, general-purpose persistent background-job platform. In particular, these notes do not claim a fully functioning `JobRegistry` or generic `job_output` / `job_list` / `job_kill` workflow.

### Security and packaging

- Web Remote authentication and the RPC allowlist remain the authority for remote access; exposing a device through a tunnel requires a strong configured password.
- The app remains an Android-only, arm64-v8a OpenMinis fork with application id `dev.openminispet.android`, so it can coexist with the upstream application.
- This branch is GPL-3.0 as an OpenMinis derivative. Third-party notices, including the DeepSeek Harness MIT notice and DeepSeek theme BSD-3 notice, are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

### Android assistant and desktop pet

- The default-assistant declaration now uses the standard `RoleManager.ROLE_ASSISTANT`, a complete VoiceInteraction Session/Recognition contract, and an `ACTION_ASSIST` fallback. OEM role policy may still require a manual selection in system settings.
- The floating pet now retries after overlay-permission return, clamps restored positions against the actual sprite size, invalidates stale asynchronous sprite loads, persists drag/snap positions, restores the current Agent state, and keeps failed/cancelled pet questions truthful in chat history.

## Build status

Verified debug build: `OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk`

- Variant: `debug` (debug keystore; development/self-test only)
- ABI: `arm64-v8a`
- Size: `51,563,132` bytes
- SHA-256: `3dcc514ebded6f7d706b9d7e703ca0bc28002880e4ad8e247e6dba8cb1fb145f`
- Repository path: [`releases/OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk`](releases/OpenMinis-Pet-dsh-remote-rc8-arm64-debug.apk)

Built with `./gradlew :app:assembleDebug --no-daemon` after the WebSocket event-log and
tool-trajectory regression tests passed.
