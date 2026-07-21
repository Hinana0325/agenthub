# Agent Control Center — iOS

Apple 原生技术栈实现的 Agent 管理客户端，与 Android 端共享永久统一协议层。

## 技术栈

| 层 | 技术 |
|----|------|
| 开发语言 | Swift 6.0 |
| UI 框架 | SwiftUI（iOS 18+ 基线 / Liquid Glass 为 iOS 26 增量特性） |
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

- macOS 14.0+（Sonoma）
- Xcode 16.0+（包含 Swift 6.0、iOS 18 SDK；若需调试 Liquid Glass 增量特性需 Xcode 26+ / macOS 26 Tahoe）
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
| 版本 | 4.6.0 (build 36) | 与 Android 对齐 |
| 最低部署 | iOS 18.0 | Liquid Glass 为 iOS 26 增量特性，通过 `if #available` 守卫 |
| 设备 | iPhone + iPad | `TARGETED_DEVICE_FAMILY=1,2` |
| 语言 | 简体中文 (zh-Hans) | `developmentLanguage` |
| 并发检查 | complete | `SWIFT_STRICT_CONCURRENCY` |
| ATS | 允许本地网络 | 本地 Agent 连接需要，外部强制 HTTPS |
| 后台模式 | fetch/processing | 与 Android 对齐 |

## 最低部署目标

iOS 18.0+

Liquid Glass 作为 iOS 26 增量特性，在 `Theme/Glass*.swift` 中通过 `if #available(iOS 26, *)` 守卫：
- iOS 26+：走原生 `.glassEffect(_:in:)` / `GlassEffectContainer` / `glassEffectID` 完整能力
- iOS 18+：回退到 `.ultraThinMaterial` + tint 色块叠加，保留浮动控件的视觉层次感（无 lensing / morph 形变）

## 液态玻璃（Liquid Glass）

iOS 端采用 iOS 26 原生 `Liquid Glass` API 作为增量特性，与 Android 端 `GLASS_UPGRADE_PLAN.md` 视觉对齐，但实现策略不同。部署目标为 iOS 18.0+，iOS 26 专属 API 通过 `if #available` 守卫。

### 与 Android 实现差异

| 维度 | Android | iOS |
|------|---------|-----|
| 玻璃引擎 | 自研 `GlassModifier` / `GlassMotion` / `GlassBackdrop` 三件套 | iOS 26 原生 `.glassEffect(_:in:)` + `GlassEffectContainer`（iOS 18 回退 `.ultraThinMaterial`） |
| 色散 / 边缘光 / 动态光泽 | 自绘 `drawBehind` + `Brush.linearGradient` | 系统级 lensing 自动渲染 |
| 模糊背景 | `GlassBackdrop` 多层径向渐变 | 系统壁纸透镜 |
| 玻璃应用范围 | 气泡 / 卡片 / 列表 cell / 顶部栏 / 导航栏 / Sheet 全覆盖 | **仅浮动控件**（HIG 限制：玻璃不得用于内容层） |
| 自定义主题 | 移除，液态玻璃常驻 | 同步：液态玻璃常驻，无开关 |

### HIG 合规性

严格遵循 WWDC 2025 Session 219/323：

1. **玻璃仅用于导航层** —— 顶部栏 / TabBar / 工具栏在 Xcode 26 重编译后自动获得液态玻璃
2. **不用玻璃于内容层** —— 聊天气泡 / 列表 cell / 卡片保持实体色（与 Android 不同）
3. **不玻璃叠玻璃** —— 多个玻璃元素必须用 `GlassEffectContainer` 包裹
4. **浮动控件用 `.glassEffect()`** —— 状态条 / 命令面板 / 录音按钮 / 发送按钮

### 玻璃引擎文件

| 文件 | 职责 |
|------|------|
| `Theme/GlassTokens.swift` | `Glass` variant（`@available(iOS 26, *)`）/ 间距 / 弹簧 / 形状令牌 |
| `Theme/GlassPresets.swift` | `.glassPill()` / `.glassFloating()` / `.glassInteractive(in:)` / `.glassStatic(in:)` / `.glassTinted(_:in:)` / `.glassMorphID(_:in:)` View 扩展，均通过 `if #available(iOS 26, *)` 守卫并提供 iOS 18 回退 |
| `Theme/GlassContainer.swift` | `GlassEffectContainer` 便捷封装；iOS 18 回退为直接渲染内容 |

### 已玻璃化的组件

| 组件 | 玻璃形态 | 文件 |
|------|----------|------|
| 顶部连接状态条 | `.glassTinted(_:in:)`（Capsule + 状态色 tint） | `ContentView.swift` |
| 命令面板 | `.glassInteractive(in: sheetShape)` | `Features/CommandPaletteView.swift` |
| 录音按钮 | `.glassTinted(_:in:)`（Circle） | `Features/VoiceChatView.swift` |
| 发送 / 停止按钮 | `.glassTinted(_:in:)` + `glassMorphID` morph | `Features/ChatView.swift` |
| 语音输入按钮 | `.glassTinted(_:in:)` + `glassMorphID` | `Features/ChatView.swift` |
| 工作流执行 / 停止按钮 | `.glassTinted(_:in:)`（RoundedRectangle） | `Features/WorkflowView.swift` |
| 对比开始 / 停止按钮 | `.glassTinted(_:in:)`（Circle） | `Features/CompareView.swift` |

### 形变动画（Morph）

`ChatView` 输入栏的三个浮动按钮（语音 / 发送 / 停止）放入同一个 `GlassContainer`，通过 `@Namespace` + `glassMorphID` 实现：

- 语音按钮：固定 ID `"voice"`
- 发送 ↔ 停止：共享 ID `"action"`，`isWaiting` 切换时玻璃自然形变过渡，而非交叉淡入
- iOS 18 回退：`glassMorphID` 直接返回原视图，无 morph 形变（按钮正常显示）

```swift
@Namespace private var inputGlassNS

GlassContainer {
    voiceInputButton
        .glassMorphID("voice", in: inputGlassNS)
    if isWaiting {
        stopButton.glassMorphID("action", in: inputGlassNS)
    } else {
        sendButton.glassMorphID("action", in: inputGlassNS)
    }
}
```

### 测试

`AgentControlCenterTests/GlassPresetsTests.swift` 覆盖 `GlassTokens` / `GlassPresets` / `GlassContainer` 三类共 11 个测试用例，验证令牌可访问性、modifier 可构造性、Container 可包裹多个子视图。

