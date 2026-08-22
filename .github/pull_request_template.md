## Summary

<!-- What changes, and why? -->

## Scope

- [ ] Android native behavior
- [ ] Minis Web / Remote API
- [ ] Minis Client Plugin (`web/minis-client-plugin/`)
- [ ] PRoot sandbox / native build
- [ ] Documentation only

## Verification

<!-- List exact Gradle/tests/manual checks. Do not include credentials or unrelated device data. -->

- [ ] `git diff --check`
- [ ] Relevant JVM tests
- [ ] `:app:assembleDebugAndroidTest` when Android tests changed
- [ ] `:app:assembleDebug` when production source/assets changed
- [ ] `cd web/minis-client-plugin && npm run check && npm test && npm run build` when plugin changed

## Security and compatibility

- [ ] No API key, OAuth token, tunnel token, DebugServer token, password, or private fixture is committed
- [ ] RPC capability mapping and write-only secret fields remain intact
- [ ] No screenshot, input injection, arbitrary Shell/file, `su`, or Root capability is exposed through Web
- [ ] Side-effect operations keep one-time approval / checkpoint semantics
- [ ] Third-party notices and required `@deepseek-ai/dsh-*` compatibility IDs are preserved
- [ ] Current docs/release metadata are updated where applicable

See [CONTRIBUTING.md](../CONTRIBUTING.md).
