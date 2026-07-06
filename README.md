# AgentHub

**Multi-Agent 移动端远程控制应用** —— 用 Kotlin + Jetpack Compose 原生开发，连接并远程操控多种 AI Agent（Hermes / OpenCode / OpenAI 兼容 / 本地模型）。

> ⚠️ 项目已重构：早期基于 PWA + Capacitor 的 Web 实现已废弃，当前仓库为纯 Android 原生应用。
> 旧版说明见 [`docs/legacy-pwa.md`](docs/legacy-pwa.md)。

---

## 技术栈

| 层 | 选型 |
|:---|:---|
| 语言 / UI | **Kotlin 2.1** + **Jetpack Compose**（Material 3） |
| 架构 | MVVM（`AndroidViewModel` + `StateFlow`）+ 单向数据流 |
| 网络 | **Ktor**（WebSocket / HTTP + SSE） |
| 持久化 | **Room**（会话 / 消息 / 配置 / 活动）+ **DataStore**（设置） |
| 构建 | Gradle Kotlin DSL（`gradlew`），AGP + KSP |
| 目标 | `minSdk` 24，`targetSdk` 最新；支持手机 / 平板 / 折叠屏自适应布局 |

---

## 连接方式（多协议）

传输层由 `provider/AgentTransport`（sealed interface）统一抽象，`TransportFactory` 按 `AgentType` 路由：

| AgentType | 传输实现 | 协议 |
|:---|:---|:---|
| Hermes / OpenClaw / OpenCode | `WebSocketTransport` | `ws://host/ws`（带鉴权帧 + 自动重连） |
| OpenAI / OpenRouter / Xiaomi MiMo | `OpenAIHttpTransport` | `POST /v1/chat/completions`（HTTP + SSE 流式） |
| LocalModel（Ollama / LM Studio / llama.cpp） | `OpenAIHttpTransport` | 同上（本地端点暴露 OpenAI 格式） |

> SSE 解析：优先 `stream:true` 解析 `data:` 增量；遇到不支持 SSE 的端点自动回退为单次 JSON 完成包解析。

---

## 功能

- 💬 **多会话聊天**：流式增量渲染、消息反应、搜索、滑动切换会话
- 🔌 **多协议连接**：见上表，向导式接入，配置持久化
- 🖥️ **本地模型**：`LocalModelManager` 自动发现 Ollama / LM Studio / llama.cpp 端点
- 🔔 **前台保活**：`AgentConnectionService` 前台服务 + 通知内联回复（RemoteInput）
- 📱 **小米超级岛**：`SuperIslandManager` 灵动岛样式实时状态
- 📤 **系统分享**：接收外部分享文本一键发问
- 🎙️ **语音**：`VoiceInputManager` / `VoiceChatManager` 语音输入与语音对话模式
- 🎨 **主题**：浅色 / 深色 / Liquid Glass 三套
- 🧩 **插件**：`PluginManager` 展示插件入口（执行引擎规划中）

---

## 模块结构

```
android/app/src/main/java/com/agenthub/app/
├── MainActivity.kt
├── App.kt
├── AgentConnectionService.kt          # 前台服务 + 通知内联回复
├── data/
│   ├── AppModule.kt                    # 依赖装配（Repository 单例）
│   ├── model/                          # AgentConfig, AgentType, Message, Session, ConnectionState
│   ├── repository/ChatRepository.kt
│   ├── local/AppDatabase.kt            # Room（Session / Message / AgentConfig / Activity）
│   ├── settings/SettingsDataStore.kt   # DataStore
│   └── plugin/PluginManager.kt         # 插件（展示，执行待实现）
├── provider/
│   ├── AgentTransport.kt               # 传输契约（sealed interface）
│   ├── WebSocketTransport.kt           # Hermes / OpenClaw / OpenCode
│   ├── OpenAIHttpTransport.kt          # OpenAI / Ollama / LM Studio / MiMo（HTTP + SSE）
│   └── TransportFactory.kt             # 按 AgentType 路由
├── ui/
│   ├── chat/                           # ChatScreen, ChatViewModel
│   ├── sessions/                       # SessionsScreen
│   ├── settings/                       # SettingsScreen
│   ├── workflow/                       # WorkflowScreen
│   ├── agents/                         # AgentsScreen
│   └── theme/                          # light / dark / liquid_glass
├── navigation/AppNavigation.kt
└── util/
    ├── LocalModelManager.kt            # 本地模型自动发现
    ├── SuperIslandManager.kt           # 小米超级岛
    ├── VoiceInputManager.kt / VoiceChatManager.kt
    └── PerformanceMonitor.kt
```

---

## 构建与运行

```bash
# 调试包
npm run build:apk
# 或
cd android && ./gradlew assembleDebug

# 发布包（需配置 agenthub.keystore 与 KEYSTORE_* 环境变量）
cd android && ./gradlew assembleRelease
```

用 Android Studio 打开 `android/` 目录即可开发；最低要求 JDK 17 + Android SDK。

---

## 许可证

MIT © Hinana0325
