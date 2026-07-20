# Agent Control Center — iOS

Apple 原生技术栈实现的 Agent 管理客户端，与 Android 端共享永久统一协议层。

## 技术栈

| 层 | 技术 |
|----|------|
| 开发语言 | Swift 5.9+ |
| UI 框架 | SwiftUI |
| 架构模式 | MVVM + Clean Architecture |
| 异步 | Swift Concurrency (async/await + AsyncStream) |
| 状态管理 | @Observable + @State + @Published |
| 网络层 | URLSession |
| WebSocket | URLSessionWebSocketTask |
| SSE | URLSession + 自实现 EventSource |
| JSON | Codable |
| 本地数据库 | SwiftData |
| Key-Value | UserDefaults |
| 密钥存储 | Keychain |
| 后台任务 | BackgroundTasks |
| 通知 | APNs |

## 项目结构

```
ios/AgentControlCenter/
├── AgentControlCenterApp.swift     # App 入口
├── ContentView.swift                # 根视图
├── Info.plist                       # 应用配置
├── Models/                          # 数据模型 (匹配 protocol/schemas/)
├── Transport/                       # 网络传输层
├── Security/                        # 加密与密钥管理
├── Runtime/                         # 业务逻辑层
├── MCP/                             # MCP 协议实现
├── Plugin/                          # 插件系统
├── Persistence/                     # SwiftData 持久化
├── Features/                        # SwiftUI 功能视图
├── Navigation/                      # 导航
└── Theme/                           # 主题与样式
```

## 协议契约

所有数据模型和网络协议严格遵循 `/protocol/` 目录下的 JSON Schema 契约。
两端（Android Kotlin / iOS Swift）共享相同的线上格式。

## 开发指南

1. 用 Xcode 创建新的 iOS App 项目（SwiftUI + SwiftData）
2. 将 `AgentControlCenter/` 目录下所有 `.swift` 文件拖入项目
3. 配置 Info.plist（从 `Info.plist` 模板复制）
4. Build & Run

## 最低部署目标

iOS 17.0+（SwiftData 要求）
