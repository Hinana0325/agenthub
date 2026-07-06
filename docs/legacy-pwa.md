# 旧版 PWA / Capacitor 实现（已废弃）

> 本文件仅用于归档。**AgentHub 已重构为纯 Kotlin + Jetpack Compose 的 Android 原生应用**，
> 下列描述对应早期的 Web/PWA 版本，代码已从仓库移除，请勿按其实现新功能。

## 旧版形态

- 技术：PWA + Capacitor 7（`capacitor.config.ts`，`webDir: www`）
- UI：Liquid Glass 设计（`liquid-glass.css`）
- 网络层：浏览器 `fetch` / WebSocket
- 加密：声称的 E2E（`crypto.js`，AES-256-GCM）
- 协议适配器：声称支持 Hermes / OpenCode / OpenClaw / OpenAI / MiMo 五套适配器
- 插件：声称支持 weather / search 等插件
- 测试：声称 47 个测试文件

## 重构对照（原生版现状）

| 旧版声称 | 原生版现状 |
|:---|:---|
| 5 种协议适配器 | `AgentTransport` sealed interface + `TransportFactory` 按类型路由（WebSocket / OpenAI-HTTP+SSE 已实现） |
| E2E AES-256-GCM | `e2e_enabled` / `e2e_key` 开关保留，实际加解密待实现 |
| 插件系统 | `PluginManager` 展示层，执行引擎规划中 |
| 47 个测试 | 原生版单元测试规划中（见升级方案 P2） |
| PWA 安装 | 原生 APK / AAB 安装 |

具体升级路线见仓库升级方案文档（`agenthub_upgrade_plan.md`，位于开发工作区）。
