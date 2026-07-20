# Agent Control Center 架构文档

> 仓库：`Hinana0325/Agent-Control-Center`
> 包名：`com.agentcontrolcenter.app`（Android）/ `com.agentcontrolcenter.app.ios`（iOS）
> 当前版本：v2.1.3

## 1. 总览

Agent Control Center 是一款**双原生**多 Agent 移动端控制中心。Android（Kotlin + Jetpack Compose）与 iOS（Swift + SwiftUI）各自采用平台原生技术栈独立实现，两端通过位于 `protocol/` 的**永久统一协议层**对齐数据模型与传输格式。项目已从早期 PWA + Capacitor 架构完全重构，不再共享任何跨平台 UI 代码，只统一协议、不强求代码复用。

```
┌─────────────────────────────────────────────────┐
│                  Protocol Layer                  │
│   10 JSON Schema + 4 Transport Protocol (共享)   │
│        android/  ←  ios/  ←  protocol/           │
├────────────────────┬────────────────────────────┤
│   Android (Kotlin)  │       iOS (Swift)          │
│   Jetpack Compose   │       SwiftUI              │
│   Hilt + Coroutines │   @Observable + async/await│
│   Room + DataStore  │   SwiftData + UserDefaults │
│   Ktor + OkHttp     │   URLSession + WS Task     │
│   Android Keystore  │   CryptoKit + Keychain     │
└────────────────────┴────────────────────────────┘
```

## 2. 共享协议层（protocol/）

协议层是两端的**唯一事实来源**。所有数据模型字段名、类型、枚举值以 JSON Schema 文件为准，传输格式（HTTP/SSE/WebSocket/MCP）的线上字节流两端必须完全一致。

### 2.1 数据契约：10 个 JSON Schema（JSON Schema 2020-12）

| Schema | 职责 |
|-------|------|
| `agent-schema.json` | Agent 标识 + 信息模型 + 配置 |
| `session-schema.json` | 会话模型 |
| `message-schema.json` | 消息模型 + 角色枚举 + 附件 |
| `task-schema.json` | 异步任务，5 类型 / 5 状态 |
| `workflow-schema.json` | DAG 工作流，4 节点 / 8 变换 |
| `event-schema.json` | 6 种实时事件（判别联合） |
| `error-codes.json` | 37 个错误码，10 个类别 |
| `plugin-schema.json` | 插件协议（HttpCall/Broadcast/Workflow） |
| `mcp-schema.json` | MCP 桥接（JSON-RPC 2.0） |
| `file-transfer-schema.json` | 文件传输（v1 内联 / v2 分块） |

### 2.2 传输协议：4 份规范文档

| 文档 | 协议 |
|------|------|
| `transport/http-api.md` | HTTP REST（OpenAI 兼容 `/v1/chat/completions`） |
| `transport/sse-protocol.md` | SSE 流式输出（`text/event-stream`） |
| `transport/websocket-protocol.md` | WebSocket 双向通信 |
| `transport/auth.md` | 认证与令牌（Bearer / `AKS:` 静态存储 / `AH1:` E2E 传输） |

### 2.3 跨平台实现映射

| 维度 | Android (Kotlin) | iOS (Swift) |
|------|-------------------|-------------|
| 数据模型 | `data class` + `@Serializable` | `struct` + `Codable` |
| 枚举 | `enum class` | `enum` + `String` rawValue |
| 可空类型 | `String?` | `String?` |
| 时间戳 | `Long`（Unix 毫秒） | `Int64`（Unix 毫秒） |
| 序列化 | Gson | `JSONEncoder` / `JSONDecoder` |
| Keystore | AndroidKeyStore + KeystoreManager | Keychain + KeychainManager |
| WebSocket | Ktor WebSocket | `URLSessionWebSocketTask` |
| SSE | Ktor SSE | `URLSession` + 自实现 EventSource |

## 3. Android 架构（Kotlin + Jetpack Compose）

包名 `com.agentcontrolcenter.app`，采用 MVVM + 单向数据流（UDF）+ Hilt 依赖注入。

### 3.1 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Kotlin 2.2.0 |
| UI | Jetpack Compose（Material 3 + Liquid Glass 主题） |
| 编译 | AGP 8.9 / compileSdk 36 / KSP |
| DI | Hilt 2.56.2（`@HiltAndroidApp` + `@HiltViewModel`） |
| 持久化 | Room 2.7.2（6 实体 / 6 DAO / 迁移）+ DataStore 1.1.7 |
| 网络 | Ktor 3.2.3（HTTP / SSE / WebSocket）+ OkHttp 引擎 |
| 序列化 | Gson |
| 安全 | Android Keystore（AES-256-GCM）+ PBKDF2 600000 轮 |
| 导航 | Navigation Compose 2.9.2 |
| 最低版本 | minSdk 24 |

### 3.2 分层

```
com.agentcontrolcenter.app/
├── [App Shell]   MainActivity / App / AgentControlCenterApplication(@HiltAndroidApp)
│                 AgentConnectionService(前台保活) / AgentControlCenterWidget
├── navigation/   Screen(路由) + AppNavigation(NavHost)
├── feature/      Chat / Sessions / Agents / Settings / Activity / Insights /
│                 Compare / Workflow / Marketplace / Plugin / Sync（Screen + ViewModel）
├── ui/
│   ├── theme/        Theme / Color / Type / GlassModifier / GlassBackdrop / GlassMotion
│   ├── components/   PressAnimation / Snackbar
│   └── adaptive/     AdaptiveUtils(3 断点 + 折叠屏检测)
├── core/
│   ├── security/     KeystoreManager(AKS:) / CryptoManager(AH1:)
│   ├── database/     AppDatabase + 6 DAO + 6 Entity + Converters
│   ├── datastore/    SettingsDataStore
│   └── common/       Extensions / PerformanceMonitor
├── data/
│   ├── repository/   ChatRepository(单一数据源)
│   ├── model/         Session / Message / ActivityItem / ChatBackup / MarketplaceAgent
│   ├── insights/      DataInsightsManager
│   ├── collab/        CollaborationManager
│   ├── sync/          DeviceSyncManager
│   ├── update/        UpdateManager(GitHub Releases 自更新)
│   ├── notification/  SmartNotificationManager
│   └── marketplace/   MarketplaceClient
├── transport/
│   ├── protocol/      AgentTransport(sealed interface)
│   ├── http/          OpenAIHttpTransport(HTTP + SSE)
│   ├── websocket/     WebSocketTransport
│   └── TransportFactory + ConnectionRepository
├── runtime/
│   ├── agent/         AgentManager + AgentRegistry
│   ├── session/       SessionManager
│   ├── task/          TaskManager
│   ├── workflow/      WorkflowEngine(Kahn 拓扑排序 + 环检测)
│   └── notification/  StatusNotificationManager / LocalNotificationManager
├── mcp/
│   ├── model/         McpModels
│   ├── registry/      McpRegistry
│   ├── client/        McpClient(JSON-RPC 2.0)
│   └── bridge/        McpBridge
├── plugin/
│   ├── api/           Plugin + PluginAction
│   └── runtime/       PluginExecutor + PluginManager
├── di/                DatabaseModule(@Provides)
├── localmodel/        LocalModelManager(Ollama / LM Studio 发现)
└── widget/            WidgetDataProvider + WidgetInputActivity
```

### 3.3 数据流

```
用户输入 → ViewModel → ChatRepository → Room DB
                      ↓
                 Transport → Agent Server
                      ↓
              ViewModel ← Transport Events
                      ↓
                UI State → Compose UI
```

### 3.4 自适应布局

- **Compact**（< 600dp）：手机竖屏，底部导航栏，单列
- **Medium**（600–839dp）：折叠屏 / 小平板，按方向切换底部栏或导航栏
- **Expanded**（≥ 840dp）：大平板，NavigationRail，双栏布局

## 4. iOS 架构（Swift + SwiftUI）

Bundle ID `com.agentcontrolcenter.app.ios`，采用 MVVM + `@Observable` + Swift Concurrency（async/await + AsyncStream），SwiftData 持久化，Keychain 密钥存储。最低部署 iOS 17.0（SwiftData 要求）。工程由 XcodeGen（`project.yml`）生成，工程文件被 `.gitignore` 忽略。

### 4.1 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Swift 5.9+ |
| UI | SwiftUI |
| 状态 | `@Observable` + `@State` + `@Published` |
| 异步 | async/await + AsyncStream |
| 持久化 | SwiftData（5 `@Model` 实体）+ UserDefaults |
| 网络 | URLSession + `URLSessionWebSocketTask` |
| SSE | URLSession + 自实现 EventSource |
| JSON | Codable |
| 安全 | CryptoKit（AES-256-GCM）+ Keychain |
| 后台 | BackgroundTasks + APNs |

### 4.2 十层架构

```
AgentControlCenter/
├── 1. App Root      AgentControlCenterApp(@main) + AppState(@Observable 容器)
│                    + ContentView(NavigationSplitView)
├── 2. Models        Agent / Session / Message / Task / Workflow / AgentEvent
│                    / Plugin / MCPModels（8 文件匹配 10 schema）
├── 3. Security      KeychainManager(AKS: + AH1:，与 Android 对齐)
├── 4. Transport     AgentTransport(协议 + 工厂) / OpenAIHTTPTransport(HTTP + SSE)
│                    / WebSocketTransport(重连)
├── 5. Runtime       AgentManager(capabilityIndex) / SessionManager / TaskManager
│                    / WorkflowEngine(Kahn 拓扑排序 + 3 模板)
├── 6. MCP           McpRegistry(NSLock) / McpClient(JSON-RPC 2.0) / McpBridge(编排)
├── 7. Plugin        PluginExecutor(HttpCall / Broadcast / Workflow)
├── 8. Persistence   SwiftDataModels(5 @Model) + DataController(@Observable ModelContainer)
├── 9. Features      Sessions / Chat / Agents / Tasks / Mcp / Settings（6 SwiftUI Views）
└── 10. Theme        AppTheme(颜色常量 + timeAgo)
```

### 4.3 数据流

```
用户交互 → Feature View → Runtime Manager → Transport → Agent Server
                          ↓                    ↓
                    SwiftData ←──── AsyncStream Events
                          ↓
                     @Observable → SwiftUI View
```

## 5. 导航

- **Android**：底部导航栏 / NavigationRail + NavHost；路由：Chat、Sessions、Activity、Settings（Tab）+ Agents、Marketplace、Insights、Compare、Workflow、Plugins、DeviceSync
- **iOS**：NavigationSplitView；侧栏：Sessions、Chat、Agents、Tasks、MCP、Settings

## 6. 安全模型（双端对齐）

| 维度 | Android | iOS |
|------|---------|-----|
| 静态存储加密 | AndroidKeyStore，`AKS:` 前缀 | Keychain，`AKS:` 前缀 |
| E2E 传输加密 | CryptoManager，`AH1:` 前缀，PBKDF2 600000 轮 | CryptoKit，`AH1:` 前缀，相同轮数 |
| 备份策略 | `allowBackup=false`，密钥不进云备份 | ATS 允许本地 Agent 连接 |

## 7. 协议层变更约束

1. 新增字段：向后兼容，提供 `default` 值，旧客户端忽略未知字段
2. 移除字段：先标记 `deprecated`，至少一个版本周期后移除
3. 枚举变更：只能新增值，不能移除或重命名已有值
4. 传输格式变更：两端必须同步发布
