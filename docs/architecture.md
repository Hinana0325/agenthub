# AgentHub Architecture

## Overview
AgentHub is a native Android app built with Kotlin + Jetpack Compose, following MVVM architecture with unidirectional data flow and Hilt dependency injection.

## Tech Stack
- **Language**: Kotlin 2.2
- **UI**: Jetpack Compose (Material 3 + Liquid Glass theme)
- **Architecture**: MVVM + UDF (Unidirectional Data Flow)
- **DI**: Hilt (AndroidViewModel + @Inject)
- **Database**: Room (SQLite) with migrations
- **Preferences**: DataStore (key-value)
- **Network**: Ktor (WebSocket + HTTP/SSE)
- **Serialization**: Gson
- **Build**: Gradle Kotlin DSL, AGP 8.9, KSP

## Layers

### UI Layer (Compose)
```
ui/
├── chat/          # ChatScreen, ChatViewModel, MessageBubble, CommandPalette, MarkdownParser
├── sessions/      # SessionsScreen (dual-pane, swipe gestures)
├── agents/        # AgentsScreen (CRUD, import/export)
├── compare/       # CompareScreen (side-by-side agent comparison)
├── settings/      # SettingsScreen (theme, font, E2E, backup)
├── activity/      # ActivityScreen (timeline log)
├── insights/      # InsightsScreen (data analytics)
├── marketplace/   # AgentMarketScreen (discover agents)
├── plugin/        # PluginScreen (plugin management)
├── workflow/      # WorkflowScreen (multi-agent orchestration)
├── sync/          # DeviceSyncScreen (cross-device sync)
├── theme/         # Liquid Glass theme, GlassModifier, GlassBackdrop
├── adaptive/      # AdaptiveConfig (3 breakpoints, foldable detection)
└── components/    # Shared components (ErrorSnackbar, PressAnimation)
```

### ViewModel Layer
```
ViewModels:
├── ChatViewModel      # Chat state, messages, transport, commands, edit, reply
├── SettingsViewModel  # Theme, font, E2E encryption, backup/restore
├── AgentsViewModel    # Agent CRUD, import/export
├── ActivityViewModel  # Activity log
├── InsightsViewModel  # Data analytics
├── CompareViewModel   # Side-by-side agent comparison
```

### Data Layer
```
data/
├── repository/ChatRepository  # Single source of truth (sessions, messages, agents)
├── local/AppDatabase          # Room DB (5 entities, 5 DAOs, 6→7 migrations)
├── local/entity/              # SessionEntity, MessageEntity, AgentConfigEntity, etc.
├── local/dao/                 # SessionDao, MessageDao, AgentConfigDao, etc.
├── settings/SettingsDataStore # DataStore preferences (theme, font, E2E key)
├── plugin/PluginManager       # Plugin system
├── insights/DataInsightsManager # Analytics engine
├── marketplace/MarketplaceClient # API client for agent marketplace
├── sync/DeviceSyncManager     # Cross-device sync
├── collab/CollaborationManager # Real-time collaboration (experimental)
└── notification/SmartNotificationManager # Notification management
```

### Provider Layer (Transport)
```
provider/
├── AgentTransport        # Sealed interface (connect, sendMessage, disconnect, events)
├── WebSocketTransport    # Hermes/OpenClaw/OpenCode (WebSocket protocol)
├── OpenAIHttpTransport   # OpenAI/LocalModel/XiaomiMiMo (HTTP + SSE)
└── TransportFactory      # Routes AgentType → Transport implementation
```

### DI Layer (Hilt)
```
di/
└── DatabaseModule        # @Provides AppDatabase, DAOs
```
- ChatRepository: @Inject constructor (auto-created by Hilt)
- SettingsDataStore: @Inject constructor
- ViewModels: @HiltViewModel + @Inject

### Security Layer
```
util/
├── KeystoreManager    # Android Keystore AES-256-GCM (hardware-backed)
├── CryptoManager      # E2E message encryption (AES-256-GCM + PBKDF2)
```

## Data Flow
```
User Input → ChatViewModel → ChatRepository → Room DB
                                      ↓
                                Transport → Agent Server
                                      ↓
                            ChatViewModel ← Transport Events
                                      ↓
                              UI State → Compose UI
```

## Navigation
- Phone: BottomNavigationBar + NavHost
- Tablet/landscape: NavigationRail + NavHost
- Routes: Chat, Sessions, Activity, Settings (tabs) + Agents, Marketplace, Insights, Compare, Workflow, Plugins, DeviceSync

## Adaptive Layout
- **Compact** (< 600dp): Phone portrait, bottom bar, single column
- **Medium** (600-839dp): Foldable/small tablet, bottom bar or rail based on orientation
- **Expanded** (≥ 840dp): Large tablet, navigation rail, dual-pane layouts
