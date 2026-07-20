# 旧版 PWA / Capacitor 实现（已废弃）

> 本文件仅用于归档。**Agent Control Center 已重构为双原生移动应用（Android Kotlin + Jetpack Compose、iOS Swift + SwiftUI），两端共享协议层**。
> 下列描述对应早期的 Web/PWA 版本，相关代码已从仓库移除，请勿据此实现新功能。

## 旧版形态

- 技术栈：PWA + Capacitor 7（`capacitor.config.ts`，`webDir: www`）
- UI：Liquid Glass 设计（`liquid-glass.css`）
- 网络层：浏览器原生 `fetch` / WebSocket
- 加密：声称的端到端加密（`crypto.js`，AES-256-GCM）
- 协议适配器：声称支持 Hermes / OpenCode / OpenClaw / OpenAI / MiMo 五套适配器
- 插件：声称支持 weather / search 等插件
- 测试：声称 47 个测试文件

## 重构对照（双原生版现状）

| 旧版声称 | 双原生版现状 |
|:---|:---|
| 5 种协议适配器 | `AgentTransport` sealed interface + `TransportFactory` 按类型路由（WebSocket / OpenAI-HTTP+SSE 已实现），协议层定义由 Android 与 iOS 共享 |
| E2E AES-256-GCM | `e2e_enabled` / `e2e_key` 开关保留，实际加解密待实现 |
| 插件系统 | `PluginManager` 展示层，执行引擎规划中 |
| 47 个测试 | 原生版单元测试持续补齐中（详见升级方案 P2） |
| PWA 安装 | 原生 APK / AAB（Android）与 Xcode 工程（iOS）安装 |
| 单端 Web 应用 | Android（Kotlin + Compose）与 iOS（Swift + SwiftUI）双端原生，共享协议层 |

## 仓库与包名

- GitHub 仓库：`Hinana0325/Agent-Control-Center`
- Android 包名：`com.agentcontrolcenter.app`
- iOS 工程通过 XcodeGen（`project.yml`）生成 Xcode 工程

具体升级路线见仓库升级方案文档（`agentcontrolcenter_upgrade_plan.md`，位于开发工作区）。
