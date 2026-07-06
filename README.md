# AgentHub

📱 Mobile AI Agent Controller — PWA + Capacitor 7 (Android/iOS)

> v2.0.0 · Kotlin 2.1 + Compose · Capacitor 7 · Liquid Glass Design · Multi-Agent Mesh · 47-unit test suite

## Supported Agents

| Agent | Protocol | Features |
|-------|----------|----------|
| 🚀 **Hermes Agent** | OpenAI SSE | Sessions, Runs, Skills, Toolsets |
| ⚡ **OpenCode** | REST + SSE | Sessions, Agents |
| 🦞 **OpenClaw** | WebSocket | Sessions, Skills, Agents |
| 🤖 **OpenAI Compatible** | OpenAI SSE | Ollama / LM Studio / vLLM |
| 📱 **Xiaomi MiMo** | OpenAI SSE | MiMo-V2.5 系列模型 |

## Highlights

### 📱 Android Tablet Adaptation (v2.0.0)
- **自适应布局系统** — 三级断点：Compact <600dp / Medium 600-839dp / Expanded ≥840dp
- **折叠屏检测** — Medium 宽度 + 短屏或 600-720dp 范围自动识别
- **导航自适应** — Expanded + Medium 横屏使用 NavigationRail，其余使用 BottomBar
- **ChatScreen** — 平板左侧 Sessions 侧边栏（240-280dp）+ 右侧聊天区，输入栏限宽居中
- **SessionsScreen** — Expanded/横屏双栏布局：列表 + 消息详情预览
- **ActivityScreen** — Expanded 双栏：时间线 + 活动详情面板
- **SettingsScreen** — Expanded 分栏：分类导航 + 设置详情
- **AgentsScreen** — Expanded 自适应网格（2-3 列）
- **横屏感知** — 所有屏幕支持 landscape 优化

### 🔧 Connection UX (v2.0.0)
- **连接反馈** — Connect 按钮显示加载动画 + "Connecting..." 文字
- **错误提示** — 连接失败在向导内显示红色错误条，不再默默消失
- **重试限制** — 最多 3 次重试，显示进度 "retry 1/3"
- **连接成功** — 自动关闭向导进入聊天
- **防重复** — 新连接自动取消上一次尝试

### 🎨 Liquid Glass Theme (Android 17 Design)
- 1,256 行完整设计系统 — 设计令牌、5 级模糊、4 级阴影、GPU 动画
- 18 个组件重写：Top Bar、Bottom Nav、Input Bar、消息气泡、Modal、FAB 等
- `glass-components.js` — 弹簧物理引擎、动态光源、玻璃波纹、Live Updates
- 设备性能三级自适应 (low/mid/high)、backdrop-filter fallback
- 2025 AI-Native 配色方案：Electric Sapphire + Emerald + Thinking Amber

### 🧠 Agent Mesh (Multi-Agent Orchestration)
- **连接向导** — 渐进式引导、Agent 类型选择、智能错误诊断
- **Activity 时间线** — Agent 思维过程可视化，工具调用详情、耗时、状态
- 并行对比 (`sendParallel`) + 链式编排 (`orchestrateChain`)
- Agent 状态栏，实时延迟指示器

### 📱 Native Mobile Integration
- **Android Share Sheet** — 接收系统分享文本/图片
- **Foreground Service** — 后台持久连接，防止系统杀死
- **Home Screen Widget** — 桌面小组件，快速启动
- 小米 Super Island 集成 · 本地通知 · 手势导航
- Viewport 可访问性合规 (WCAG AA)

### 🏗️ Modern Architecture
- **响应式 Store** (`store.js`) — Proxy-based，自动持久化，0 依赖
- **PWA 离线** — Network-First / Cache-First / Stale-While-Revalidate 三层策略
- SW 更新提示 — 内联 Toast + 一键重载
- 连接状态栏、自动重连、离线队列

### 🔒 Security
- XSS 防护：escapeHtml 全面覆盖、Markdown URL scheme 白名单、事件委托
- E2E 加密 (AES-256-GCM)
- `javascript:` / `data:` / `vbscript:` 协议过滤
- 无 CSP 违规的纯本地存储

### 🧪 Testing
- 47 单元测试覆盖 8 个核心模块
- `node tests.js` 零依赖运行

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Kotlin | 2.1.0 |
| UI | Jetpack Compose + Material3 | BOM 2025.01.01 |
| Navigation | Navigation Compose | 2.8.9 |
| Lifecycle | ViewModel + LiveData Compose | 2.8.7 |
| Database | Room (KSP) | 2.7.1 |
| Preferences | DataStore | 1.1.2 |
| Network | Ktor Client (WebSocket + SSE) | 3.0.3 |
| Serialization | Gson | 2.10.1 |
| Web Framework | Capacitor | 7.0.0 |
| Build | AGP + Gradle | 8.7.3 / 8.11.1 |
| Min SDK | Android | 24 (7.0) |
| Target SDK | Android | 36 |

## Quick Start

```bash
git clone https://github.com/Hinana0325/agenthub.git
cd agenthub
```

### PWA (Browser)
```bash
npx serve .
# Open http://localhost:3000
```

### Android APK
```bash
npm install
npx cap sync android
cd android && ./gradlew assembleDebug
# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

### Run Tests
```bash
node tests.js
```

## Project Structure

```
agenthub/
├── index.html            # Main page (4 views + connection wizard)
├── store.js              # Reactive state store (172 lines)
├── app.js                # Init, events, sessions, runs
├── core.js               # State, settings, theme, utilities
├── connection.js         # Health check, auto-reconnect
├── chat.js               # Streaming chat, Markdown, search
├── ui.js                 # Gestures, voice, keyboard
├── agents.js             # Multi-agent orchestration
├── features.js           # 17 feature modules
├── providers.js          # 5 Agent protocol adapters
├── plugins.js            # Plugin system
├── crypto.js             # E2E encryption
├── wizard.js/css         # Connection onboarding wizard
├── glass-components.js   # Liquid glass interaction system
├── styles.css            # Light/dark themes
├── liquid-glass.css      # Liquid glass design system (1,256 lines)
├── wizard.css            # Wizard styles
├── sw.js                 # Service Worker (3-tier cache)
├── tests.js              # 47-unit test suite
├── build.sh              # CLI build script
├── manifest.json         # PWA manifest
├── capacitor.config.ts   # Capacitor config
├── package.json          # Dependencies
├── icons/                # App icons
├── www/                  # Capacitor web root
└── android/              # Android native project (Kotlin + Compose)
    └── app/src/main/java/com/agenthub/app/
        ├── MainActivity.kt              # Compose entry + Share Sheet
        ├── AgentConnectionService.kt    # Foreground Service
        ├── AgentHubWidget.kt            # Home Widget
        ├── App.kt                       # Application class
        ├── provider/
        │   └── AgentProvider.kt         # WebSocket agent connection
        ├── data/
        │   ├── AppModule.kt             # DI singleton
        │   ├── local/                   # Room database + DAOs
        │   ├── model/                   # Data models
        │   ├── repository/              # ChatRepository
        │   └── settings/                # DataStore preferences
        ├── navigation/
        │   ├── AppNavigation.kt         # NavHost + Rail/BottomBar
        │   └── Screen.kt               # Route definitions
        └── ui/
            ├── adaptive/
            │   └── AdaptiveUtils.kt     # Window size, panels, breakpoints
            ├── chat/                    # ChatScreen + ChatViewModel
            ├── sessions/                # SessionsScreen
            ├── activity/                # ActivityScreen
            ├── agents/                  # AgentsScreen + AgentsViewModel
            ├── settings/                # SettingsScreen + SettingsViewModel
            └── theme/                   # Color, Theme, Typography
```

## Architecture

```
┌────────────────────────────────────────────────┐
│                  index.html                     │
├────────────────────────────────────────────────┤
│  store.js   → Proxy reactive state             │
│  app.js     → init, events, sessions, wizard    │
│  core.js    → theme, settings, navigation       │
│  chat.js    → streaming, markdown, XSS-safe     │
│  ui.js      → gestures, voice, accessibility    │
│  agents.js  → multi-agent orchestration         │
│  features.js→ error boundary, backup, reactions │
│  plugins.js → plugin registry                   │
│  crypto.js  → AES-256-GCM                       │
│  wizard.js  → connection onboarding             │
├────────────────────────────────────────────────┤
│  connection.js → health check, reconnect        │
│  providers.js  → 5 protocol adapters            │
│  island.js     → Super Island bridge            │
│  notify.js     → notification bridge            │
│  glass-components.js → Spring/Light/Ripple      │
├────────────────────────────────────────────────┤
│  styles.css       → light/dark themes           │
│  liquid-glass.css → glass theme (1,256 lines)   │
│  sw.js            → 3-tier cache strategy       │
└────────────────────────────────────────────────┘

Android Native Layer (Kotlin 2.1 + Compose):
┌────────────────────────────────────────────────┐
│  MainActivity → setContent { AppNavigation() }  │
├────────────────────────────────────────────────┤
│  AdaptiveUtils   → 3 breakpoints, panel config  │
│  AppNavigation   → Rail / BottomBar routing     │
│  ChatScreen      → Wizard + Messages + Sidebar  │
│  SessionsScreen  → List + Detail dual-pane      │
│  ActivityScreen  → Timeline + Detail panel      │
│  SettingsScreen  → Category + Detail split      │
│  AgentsScreen    → Grid / List adaptive         │
├────────────────────────────────────────────────┤
│  ChatViewModel   → State, connection, messages  │
│  AgentProvider   → WebSocket, reconnect, events  │
│  ChatRepository  → Room + DAO abstraction       │
│  AppDatabase     → Room (KSP), 4 entities       │
│  DataStore       → Preferences persistence      │
├────────────────────────────────────────────────┤
│  AgentConnectionService → Foreground Service    │
│  AgentHubWidget         → Home Screen Widget    │
│  SuperIslandManager     → Xiaomi integration    │
│  LocalNotificationManager → Notifications       │
└────────────────────────────────────────────────┘
```

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl/⌘ + Enter` | Send / Stop |
| `Escape` | Stop / Close overlays |
| `Ctrl/⌘ + N` | New chat |
| `Ctrl/⌘ + K` | Search |
| `Ctrl/⌘ + ,` | Settings |
| `1 - 4` | Switch tabs |

## Slash Commands

| Command | Action |
|---------|--------|
| `/clear` | Clear chat |
| `/new` | New conversation |
| `/model [name]` | Switch model |
| `/theme` | Toggle theme |
| `/stats` | Chat statistics |
| `/export` | Export JSON |
| `/reconnect` | Reconnect |
| `/help` | Show commands |

## Changelog

### v2.0.0 (2025-07-06)

**Upgrade**
- Kotlin 1.9.22 → 2.1.0 (K2 compiler)
- Compose BOM 2024.01.00 → 2025.01.01, removed separate Compose Compiler
- kapt → KSP (Room code generation)
- Ktor 2.3.7 → 3.0.3
- Capacitor 6.1.0 → 7.0.0
- AGP 8.2.1 → 8.7.3, Gradle 8.2.1 → 8.11.1
- Navigation Compose 2.7.7 → 2.8.9
- Lifecycle 2.7.0 → 2.8.7, Room 2.6.1 → 2.7.1
- AndroidX Activity 1.9.3 → 1.10.1, DataStore 1.0.0 → 1.1.2

**Tablet Adaptation**
- Adaptive layout system with 3 breakpoints (Compact / Medium / Expanded)
- Foldable device detection
- NavigationRail for tablet landscape + Expanded
- Dual-pane layouts: Chat, Sessions, Activity, Settings
- Adaptive grid for Agents screen
- Dynamic sidebar widths and constrained input bars

**UX Fix**
- Connection wizard: loading spinner, inline errors, retry limits
- AgentProvider: cancel previous connection on reconnect, max 3 retries

### v1.0.0
- Initial release

## License

MIT
