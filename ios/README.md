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
ios/
├── project.yml                                  # XcodeGen 配置（生成 .xcodeproj）
├── .gitignore                                   # 忽略 Xcode 生成文件
└── AgentControlCenter/
    ├── AgentControlCenterApp.swift              # App 入口 (@main)
    ├── AppState.swift                            # @Observable 中央依赖容器
    ├── ContentView.swift                         # NavigationSplitView 根视图
    ├── Info.plist                                # 应用配置 (BackgroundModes/权限)
    ├── Resources/
    │   └── Assets.xcassets/                      # 资源目录
    │       ├── AppIcon.appiconset/               # 应用图标 (1024×1024)
    │       ├── AccentColor.colorset/             # 主题色 (#214DF6 蓝)
    │       └── LaunchBackgroundColor.colorset/   # 启动屏背景色 (浅/深色)
    ├── Models/                                   # 数据模型 (匹配 protocol/schemas/)
    │   ├── Agent.swift                           # AgentType(6)/AgentProtocol(4)/AgentStatus(4)
    │   ├── Session.swift                         # Session 模型
    │   ├── Message.swift                         # MessageRole (HTTP lowercase / WS enum)
    │   ├── Task.swift                            # TaskType(5)/TaskStatus(5)
    │   ├── Workflow.swift                        # DAG + NodeType(4) + TransformType(8)
    │   ├── AgentEvent.swift                      # 6 事件变体 + 37 错误码
    │   ├── Plugin.swift                          # HttpCall/Broadcast/Workflow + FileTransfer
    │   └── MCPModels.swift                       # JSON-RPC 2.0 + AnyCodable
    ├── Security/                                 # 加密与密钥管理
    │   └── KeychainManager.swift                 # AKS: + AH1: (与 Android 对齐)
    ├── Transport/                                # 网络传输层
    │   ├── AgentTransport.swift                  # 协议 + TransportFactory
    │   ├── OpenAIHTTPTransport.swift             # /v1/chat/completions + SSE 流式
    │   └── WebSocketTransport.swift              # URLSessionWebSocketTask + 重连
    ├── Runtime/                                  # 业务逻辑层
    │   ├── AgentManager.swift                    # @Observable + capabilityIndex
    │   ├── SessionManager.swift                  # 会话管理 (置顶/排序)
    │   ├── TaskManager.swift                     # 任务管理 (提交/取消)
    │   └── WorkflowEngine.swift                  # Kahn 拓扑排序 + 3 模板
    ├── MCP/                                      # MCP 协议实现
    │   ├── McpRegistry.swift                     # 工具注册表 (NSLock)
    │   ├── McpClient.swift                       # JSON-RPC 2.0 客户端
    │   └── McpBridge.swift                       # 编排层 (connect/callTool)
    ├── Plugin/                                   # 插件系统
    │   └── PluginExecutor.swift                  # HttpCall/Broadcast/Workflow
    ├── Persistence/                              # SwiftData 持久化
    │   ├── SwiftDataModels.swift                 # 5 @Model 实体
    │   └── DataController.swift                  # @Observable + ModelContainer
    ├── Features/                                 # SwiftUI 功能视图
    │   ├── SessionsView.swift                    # 会话列表
    │   ├── ChatView.swift                        # 聊天界面 (流式响应)
    │   ├── AgentsView.swift                      # Agent 管理
    │   ├── TasksView.swift                       # 任务列表 (分段过滤)
    │   ├── McpView.swift                         # MCP 服务器管理
    │   └── SettingsView.swift                    # 设置 (E2E/主题/清除)
    └── Theme/                                    # 主题与样式
        └── AppTheme.swift                        # 颜色常量 + timeAgo
```

## 协议契约

所有数据模型和网络协议严格遵循 `/protocol/` 目录下的 JSON Schema 契约。
两端（Android Kotlin / iOS Swift）共享相同的线上格式。

## 快速开始 (XcodeGen)

### 前置要求

- macOS 14.0+
- Xcode 15.0+（包含 Swift 5.9、iOS 17 SDK）
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) 2.0+

### 安装 XcodeGen

```bash
# 方式一：Homebrew
brew install xcodegen

# 方式二：Mint
mint install yonaskolb/xcodegen

# 方式三：Make（从源码编译）
git clone https://github.com/yonaskolb/XcodeGen.git
cd XcodeGen
make install
```

### 生成 Xcode 工程

```bash
cd ios/
xcodegen generate
```

执行后会在 `ios/` 目录下生成 `AgentControlCenter.xcodeproj`，用 Xcode 打开即可：

```bash
open AgentControlCenter.xcodeproj
```

### 配置签名

1. 在 Xcode 中选择 `AgentControlCenter` target
2. 进入 **Signing & Capabilities** 标签
3. 勾选 **Automatically manage signing**
4. 选择你的 **Team**（Apple Developer 账号或免费个人账号）
5. Bundle Identifier 已设为 `com.agentcontrolcenter.app.ios`，可按需修改

### 运行

- 选择目标设备（模拟器或真机）
- `Cmd + R` 运行
- `Cmd + Shift + Y` 切换预览画布

### 重新生成

当新增/删除源文件后，重新执行 `xcodegen generate` 即可。工程文件被 `.gitignore` 忽略，所有项目配置以 `project.yml` 为单一事实来源。

## 关键配置说明

| 配置项 | 值 | 说明 |
|--------|-----|------|
| Bundle ID | `com.agentcontrolcenter.app.ios` | 与 Android `com.agentcontrolcenter.app` 对齐 |
| Display Name | Agent Control Center | 用户可见名称 |
| 版本 | 2.1.3 (build 1) | 与 Android 对齐 |
| 最低部署 | iOS 17.0 | SwiftData 要求 |
| 设备 | iPhone + iPad | `TARGETED_DEVICE_FAMILY=1,2` |
| 语言 | 简体中文 (zh-Hans) | `developmentLanguage` |
| 并发检查 | minimal | `SWIFT_STRICT_CONCURRENCY` |
| ATS | 允许任意加载 | 本地 Agent 连接需要 |
| 后台模式 | fetch/remote-notification/processing | 与 Android 对齐 |

## 最低部署目标

iOS 17.0+（SwiftData 要求）
