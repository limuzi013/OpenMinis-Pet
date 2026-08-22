# 文档索引

本文按“当前契约”和“历史资料”分类，避免把旧设计稿误认为 1.00-beta 已交付能力。

## 使用与构建

| 文档 | 状态 | 内容 |
|---|---|---|
| [`../README.md`](../README.md) | 当前 | 项目入口、下载、功能、安全边界、Linux/Root 说明 |
| [`../RELEASE-NOTES.md`](../RELEASE-NOTES.md) | 1.00-beta | 当前发布说明、校验值和限制 |
| [`../BUILD-CN.md`](../BUILD-CN.md) | 当前 | 中文完整构建步骤 |
| [`../BUILDING.md`](../BUILDING.md) | 当前 | English build and troubleshooting guide |
| [`../releases/README.md`](../releases/README.md) | 当前 | 仓库内 APK 清单与哈希 |

## 当前工程契约

| 文档 | 内容 |
|---|---|
| [`MINIS-WEB-ARCHITECTURE.md`](MINIS-WEB-ARCHITECTURE.md) | Minis Web、Harness 边界和 Android 数据映射 |
| [`WEB-REMOTE-RPC.md`](WEB-REMOTE-RPC.md) | Web Remote HTTP/RPC/WebSocket 契约与安全边界 |
| [`LINUX-SANDBOX-ROOT-AND-UBUNTU.md`](LINUX-SANDBOX-ROOT-AND-UBUNTU.md) | App 沙箱、Ubuntu rootfs、直接 Root 和独立 VM kernel 的区别 |
| [`DEVELOPMENT-STATUS.md`](DEVELOPMENT-STATUS.md) | 当前已交付、验证和未交付项 |
| [`specs/minis-url-scheme.md`](specs/minis-url-scheme.md) | `minis://` 方案草案；包含上游 iOS 历史表述，不能当 Android 完整契约 |
| [`specs/debug-server-api.md`](specs/debug-server-api.md) | 上游混合平台 Debug API 长文档；以 Android `rpc.discover` 和源码为最终依据 |

## 审计与历史记录

| 文档 | 状态 | 内容 |
|---|---|---|
| [`DESIGN-HARDENING-2026-08-21.md`](DESIGN-HARDENING-2026-08-21.md) | 历史审计 | 2026-08-21 安全/可靠性设计决策 |
| [`LINUX-ON-XIAOMI-15-DADA.md`](LINUX-ON-XIAOMI-15-DADA.md) | 设备评估 | Xiaomi 15 系统级 Linux 移植评估，不是 App 沙箱安装指南 |
| [`../CHANGELOG-FORK.md`](../CHANGELOG-FORK.md) | 历史日志 | 按时间记录当时状态；较早章节可能已被后续实现取代 |
| [`archive/ios/`](archive/ios/) | 已归档 | 从上游保留的 iOS/iSH 设计资料，本 Android-only 分支不实现 |
| [`../README-upstream.md`](../README-upstream.md) | 上游快照 | 原 OpenMinis README 存档 |

## 真实性规则

- 当前行为优先级：运行源码与测试 → 当前契约文档 → README/发布说明 → 历史 Changelog；
- `assets/minis/` 是默认 Web UI；`assets/remote/` 是兼容资源；
- PRoot/容器没有独立 kernel；当前仅实现 Alpine rootfs；
- Shizuku 是可选 Android privileged bridge；当前没有直接 `su` backend；
- Strict schema 的空兼容响应不代表完整 Subagent、Job 或 Queue 功能已经交付；
- Debug APK、生产 release、真机系统授权和 Xiaomi 15 系统移植必须分开描述。
