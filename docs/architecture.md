# AgentHub Architecture

## Overview
AgentHub is a native Android app built with Kotlin + Jetpack Compose, following MVVM architecture with unidirectional data flow.

## Layers

### UI Layer (Compose)
- `ui/chat/` - Chat screen, message bubbles, input bar, search overlay
- `ui/sessions/` - Session list with swipe gestures
- `ui/settings/` - Settings with dual-pane layout
- `ui/agents/` - Agent management with CRUD
- `ui/theme/` - Liquid Glass theme system

### ViewModel Layer
- `ChatViewModel` - Chat state, message handling, transport connection
- `SettingsViewModel` - Theme, font, E2E settings
- `AgentsViewModel` - Agent CRUD, import/export
- `ActivityViewModel` - Activity log
- `InsightsViewModel` - Data insights

### Data Layer
- `data/repository/ChatRepository` - Single source of truth for sessions/messages
- `data/local/AppDatabase` - Room database (sessions, messages, agents, activities, plugins)
- `data/settings/SettingsDataStore` - Preferences storage
- `data/insights/DataInsightsManager` - Analytics

### Provider Layer (Transport)
- `provider/AgentTransport` - Unified interface for agent communication
- `provider/WebSocketTransport` - Hermes/OpenClaw/OpenCode
- `provider/OpenAIHttpTransport` - OpenAI/LocalModel/XiaomiMiMo (HTTP + SSE)
- `provider/TransportFactory` - Routes AgentType to Transport implementation

### DI Layer (Hilt)
- `di/DatabaseModule` - Provides Room database, DAOs, SettingsDataStore
- `di/RepositoryModule` - Provides ChatRepository

### Security
- `util/KeystoreManager` - Android Keystore AES-256-GCM encryption
- `util/CryptoManager` - E2E message encryption (AES-256-GCM + PBKDF2)

## Data Flow
```
User Input → ViewModel → Repository → Room DB
                              ↓
                         Transport → Agent Server
                              ↓
                    ViewModel ← Transport Events
                              ↓
                      UI State → Compose UI
```
