# OpenMinis Minis Client Plugin

正式 DeepSeek Harness Client Plugin。Android App 仍是唯一权威数据源；本插件通过 DSH
官方 `settings.section` slot 在 Settings 内注册「Minis 控制台」，用 React 投影 Android
状态，并复用 DSH 的 `createSnapshotStore`/`ctx.slots.inject`/locale 基础设施。

## 结构

```text
src/client/
├─ contract/types.ts                分页/JSON 契约与 TAB 清单
├─ service/MinisApiService.ts       认证 JSON-RPC 传输（React 不直接 fetch）
├─ store/MinisSettingsController.ts 快照 store、生命周期轮询、命令分发
├─ settings/
│  ├─ navigation.ts                 12 个页面的导航
│  ├─ MinisSettings.tsx             官方 slot 注册的 React 投影
│  ├─ MinisSettings.module.css      CSS Modules + --dsw-alias-*（无字面色值）
│  ├─ components/Common.tsx         Card/Button/Form/Toast 等投影组件
│  └─ pages/…                       12 个页面组件
└─ index.ts                         apply：service/store/locale/section 注册
```

React 组件只持数据与回调；所有调用经 controller → MinisApiService → `/api/rpc` 等
已认证 seam。轮询随 section 激活/卸载启停（`activate()`/`dispose()`），不残留全局 timer。

## 构建

前提：已安装 `npm install`（npm 10+ / Node 22+）。依赖为 0.1.0-rc.8 的
`@deepseek-ai/dsh-client-*` 与 `@deepseek-ai/cordis` 4.0.1。

```sh
cd web/minis-client-plugin
npm run check   # tsc --noEmit
npm test        # vitest
npm run build   # 生成 client.js + 更新 assets/minis/index.html boot graph
```

`npm run build` 输出 `src/android/app/src/main/assets/minis/plugins/@openminis/minis-client-settings/client.js`，
并把该行插入 `index.html` 的 `__MINIS_BOOT__` 图（紧随官方 settings-general 之后，
同时修正其 rev）。**禁止手工修改 bundle 或 index.html 中的 rev。**

## 恢复官方 settings-general

此前 OpenMinis 直接把控制台打进 `@deepseek-ai/dsh-client-ui-settings-general`。该
耦合已恢复为上游 rc.8 官方产物（build.sh 会在检测到旧 patch 时报错）：

```bash
curl -L https://registry.npmjs.org/@deepseek-ai/dsh-client-ui-settings-general/-/dsh-client-ui-settings-general-0.1.0-rc.8.tgz | tar -xzO package/lib/client.js \
  > src/android/app/src/main/assets/minis/plugins/@deepseek-ai/dsh-client-ui-settings-general/client.js
```

已验证 SHA-256：`9298ac5b087056555498550e0e0e6dd9d3202dce48bb07b2a2c4bfeed1d63da5`。

## 测试

- `MinisApiService.test.ts`：transport、RPC 错误、401、无效 JSON。
- `MinisSettingsController.test.ts`：分页完整性、并发 stale 丢弃、轮询启停无泄漏。
