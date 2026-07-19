# AgentHub v2.1.0

**Multi-Agent 移动端远程控制应用** —— 用 Kotlin + Jetpack Compose 原生开发，连接并远程操控多种 AI Agent（Hermes / OpenCode / OpenAI 兼容 / 本地模型）。

> ⚠️ 项目已重构：早期基于 PWA + Capacitor 的 Web 实现已废弃，当前仓库为纯 Android 原生应用。
> 旧版说明见 [`docs/legacy-pwa.md`](docs/legacy-pwa.md)。

---

## 技术栈

| 层 | 选型 |
|:---|:---|
| 语言 / UI | **Kotlin 2.2.0** + **Jetpack Compose**（Material 3） |
| 架构 | MVVM（`AndroidViewModel` + `StateFlow`）+ 单向数据流 |
| 网络 | **Ktor**（WebSocket / HTTP + SSE） |
| 持久化 | **Room**（会话 / 消息 / 配置 / 活动）+ **DataStore**（设置） |
| 依赖注入 | **Hilt**（`@HiltAndroidApp`、`@HiltViewModel`、`@Inject`） |
| 测试 | 14 个单元测试文件，75+ 测试用例（`src/test/`） |
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

## 技术特性

- 💉 **Hilt 依赖注入**：全量迁移至 Hilt（`@HiltAndroidApp`、`@AndroidEntryPoint`、`@HiltViewModel`），6 个 ViewModel + Repository/DataStore 均使用 `@Inject constructor`
- ✅ **单元测试**：14 个测试文件、75+ 测试用例，覆盖 MarkdownParser、TransportFactory、CryptoManager、WorkflowEngine、CompareViewModel 等核心模块
- ♿ **无障碍 (Accessibility)**：Chat、Sessions、Agents、Compare 等屏幕均添加 `contentDescription`，支持 4 语言
- 🔄 **CI/CD**：GitHub Actions `build-apk.yml`（`assembleDebug` + `testDebugUnitTest`），带 Gradle 缓存
- 🛡️ **ProGuard**：精细化规则覆盖 Room、Ktor、Gson、coroutines、ViewModels
- 🔐 **硬件加密**：Android Keystore AES-256-GCM 硬件后端加密存储
- 🌐 **国际化 (i18n)**：完整支持英文 / 中文 / 日文 / 韩文

## v2.1.0 更新内容

- 💉 **Hilt 依赖注入全面落地**：从手动 DI 迁移至 Hilt，所有 ViewModel 和 Repository 均使用 `@Inject`
- 🆚 **模型对比**：新增 CompareScreen，支持 side-by-side 模型响应对比
- ↩️ **消息回复**：长按消息 → 回复，输入栏显示引用消息
- ✅ **单元测试扩充**：10 → 14 个测试文件，50+ → 75+ 测试用例
- ♿ **无障碍优化**：核心屏幕添加 contentDescription（4 语言）
- 🔄 **CI 流水线**：GitHub Actions 自动构建 + 测试
- 🐛 **CompareViewModel 修复**：传输泄漏、60s 超时、竞态条件、动态会话 ID
- ⚡ **性能优化**：LazyColumn 动态更新、Splash 预加载、10MB 附件限制
- 📦 **ProGuard 精细化**：移除全量 keep 规则，改为针对性规则
- 🗄️ **Room 迁移**：v6 → v7（replyToId 字段）

## v2.0.1 更新内容

- 🔐 **KeystoreManager 硬件加密**：API 密钥与端到端密钥均使用 Android Keystore 硬件后端加密存储
- 🛡️ **网络安全加固**：默认禁止明文流量（`cleartextTrafficPermitted=false`）
- ✅ **单元测试**：10 个测试文件、50+ 测试用例，覆盖模型、传输、加密、工作流等核心模块
- 🌐 **国际化 (i18n)**：完整支持英文 / 中文 / 日文 / 韩文
- 📊 **性能监控面板**：`PerformanceMonitor` 实时展示性能指标
- 🐛 **Bug 修复**：消息重复发送、平板布局异常、搜索遮罩层、TopBar 布局错位

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
├── di/
│   └── DatabaseModule.kt               # Hilt @Provides AppDatabase, DAOs
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
    ├── KeystoreManager.kt              # 硬件加密（API/E2E 密钥）
    ├── CryptoManager.kt                # 加密管理
    └── PerformanceMonitor.kt           # 性能监控面板
```

### 单元测试

```
android/app/src/test/java/com/agenthub/app/
├── data/
│   ├── model/
│   │   ├── AgentConfigTest.kt
│   │   ├── AgentTypeTest.kt
│   │   ├── ConnectionStateTest.kt
│   │   ├── MessageTest.kt
│   │   └── SessionTest.kt
│   └── update/
│       └── UpdateManagerTest.kt
├── provider/
│   └── TransportFactoryTest.kt
├── ui/chat/
│   └── MarkdownParserTest.kt
└── util/
    ├── CryptoManagerTest.kt
    └── WorkflowEngineTest.kt
```

### 国际化

```
android/app/src/main/res/
├── values/strings.xml        # English
├── values-zh/strings.xml     # 中文
├── values-ja/strings.xml     # 日本語
└── values-ko/strings.xml     # 한국어
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
