# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.1] - 2026-07-19

### Fixed (Code Review Hotfix)

#### P0 - Crash-level
- **OpenAIHttpTransport**: SSE streaming was broken — `bodyAsText()` buffered the entire response, defeating streaming and delaying first-token latency. Replaced with `bodyAsChannel()` + line-by-line `readUTF8Line()` reading.
- **Widget**: `EditText` in `agent_hub_widget.xml` is not supported by RemoteViews, making the quick-input feature non-functional. Replaced with `TextView` pending a proper Widget input Activity in v2.2.0.
- **AndroidManifest**: Removed declarations of non-existent `WidgetSendReceiver` / `WidgetVoiceReceiver` receivers that caused `ClassNotFoundException` on broadcast dispatch.
- **WebSocketTransport**: `disconnect()` closed the `HttpClient`, making the transport permanently unusable on subsequent `connect()` calls. The `client` is now long-lived; only the session is closed.
- **WebSocketTransport**: Auth frame built via string concatenation (`"{\"type\":\"auth\",\"key\":\"$apiKey\"}"`) — a malformed API key containing `"` or `\` could break the JSON frame or smuggle extra keys. Now built via `JsonObject` + Gson.

#### P1 - Severe
- **DI Cleanup**: Deleted dead-code `AppModule.kt` (manual singleton factory bypassing Hilt). `PluginManager`, `PluginScreen`, `SettingsScreen` migrated to proper Hilt injection; `DatabaseModule` now provides `PluginDao`.
- **InsightsViewModel**: Was bypassing Hilt via `AppDatabase.getInstance(application)`. Now uses `@Inject constructor(messageDao, sessionDao)` and extends `ViewModel` instead of `AndroidViewModel`.
- **AppDatabase**: Removed `fallbackToDestructiveMigration()` — silent data loss on missing migrations is now an explicit `IllegalStateException` instead.
- **AndroidManifest**: `allowBackup` changed from `true` to `false` to prevent API keys / E2E keys from being backed up to cloud.
- **Network Security Config**: `10.0.0.0` / `192.168.0.0` entries did not match any actual LAN IP (Android NSC matches hostnames, not CIDR). Replaced with explicit entries for common LAN IPs (`10.0.0.1-10`, `192.168.0.1-10`, `192.168.1.1-10`); added IPv6 `ip6-localhost`.
- **OpenAIHttpTransport**: SSE `trim()` stripped significant leading whitespace from `data:` payloads (code indentation lost). Now only `\r` is stripped; exactly one leading space is removed after `data:` per SSE spec.
- **VoiceInputManager / VoiceChatManager**: Missing `RECORD_AUDIO` runtime permission check caused silent failure on Android 6+.

#### P2 - Medium
- **WebSocketTransport**: `session` field race condition across `connect()` / `sendMessage()` / `disconnect()` — now guarded by a `Mutex`. `sendMessage` no longer silently swallows send errors (emits `AgentEvent.Error`).
- **OpenAIHttpTransport**: `probeEndpoint` treated 5xx as reachable; now only 2xx / 401 / 403 / 404 count as "service exists". `emitDelta` / `emitFull` now log JSON parse errors via `Log.w` instead of swallowing them.
- **Navigation**: `NavHost` was duplicated for tablet/phone branches (82 lines copy-pasted). Extracted into shared `AppNavHost(...)` composable. Hardcoded route strings `"device_sync"` / `"plugins"` replaced with `Screen.DeviceSync.route` / `Screen.Plugins.route` (added to `Screen.kt`).
- **Notification IDs**: `AgentConnectionService` (1001) and `SuperIslandManager` (1001) collided. Changed to 2001 and 3001 respectively.
- **CryptoManager**: PBKDF2 iterations raised from 65536 to 600000 per OWASP 2023. `decryptOrRaw` falls back to plaintext for old data.
- **KeystoreManager**: `decryptOrRaw` now distinguishes "old plaintext" (no `AKS:` prefix, returned as-is) from "corrupted ciphertext" (has prefix, decryption failed — logs warning and returns empty string).
- **WorkflowEngine**: Added cycle detection (requeue counter, max 100) — throws `IllegalStateException` on suspected cycles instead of looping forever. Converted `WorkflowNode` mutable `var` fields to `val`.
- **SettingsViewModel**: Removed permanent `while (isActive) { refresh(); delay(3000) }` loop from `init`. Exposed `refreshPerformanceMetrics()` for `SettingsScreen` to call via `LaunchedEffect` while visible.
- **ProGuard**: Added missing OkHttp / okio keep rules. Commented out overly broad `androidx.appcompat.**` / `com.google.android.material.**` keep rules (these libraries ship their own consumer rules).

#### P3 - Code Quality
- **WebSocketTransport**: Reconnect now uses exponential backoff (1s → 2s → 4s → … → 30s cap) instead of a fixed 5s interval.
- **OpenAIHttpTransport**: SSE parser now supports multi-line `data:` fields per spec (consecutive `data:` lines joined with `\n`, dispatched on blank line).
- **ChatViewModel**: Removed dead commented-out code; `localModelManager` is now `private`.
- **PerformanceMonitor**: Removed unused `ActivityManager.MemoryInfo` allocation in `updateMemoryUsage()`.
- **AppDatabase**: `exportSchema` flipped to `true`; `ksp { arg("room.schemaLocation", ...) }` added to `build.gradle` for migration testing.
- **Build**: Enabled parallel Gradle builds (`org.gradle.parallel=true`).

### Changed
- **AppModule.kt**: Deleted (dead code).
- **Screen.kt**: Added `DeviceSync` and `Plugins` data objects.

---

## [2.1.0] - 2025-07-19

### Added
- **Hilt Dependency Injection**: @HiltAndroidApp, @AndroidEntryPoint, @HiltViewModel on all 6 ViewModels, @Inject constructor for ChatRepository and SettingsDataStore
- **Model Comparison**: CompareScreen for side-by-side agent response comparison (CompareViewModel, CompareScreen)
- **Message Reply**: Long-press → Reply, input bar shows quoted message
- **Unit Tests**: 14 test files, 75+ test cases (MarkdownParser, TransportFactory, CryptoManager, WorkflowEngine, AgentType, Message, Session, ConnectionState, AgentConfig, UpdateManager, CompareViewModel, AgentConnectionState, AgentEvent, Screen)
- **Accessibility**: contentDescription on Chat, Sessions, Agents, Compare screens (4 languages)
- **CI**: GitHub Actions build-apk.yml (assembleDebug + testDebugUnitTest) with Gradle caching
- **ProGuard**: Targeted rules for Room, Ktor, Gson, coroutines, ViewModels

### Fixed
- **CompareViewModel**: Transport leak on error, no timeout (added 60s), race condition on cancel (isCancelled flag), hardcoded session IDs (dynamic timestamps)
- **CompareScreen**: Hardcoded English strings → i18n, NavHostController parameter → callback pattern, regex optimization
- **Performance**: LazyColumn seenMessageIds now dynamically updated, splash preloads most recent session, 10MB attachment size limit
- **sendMessage()**: Indentation fix in ChatViewModel

### Changed
- **ProGuard**: Removed blanket `-keep class com.agenthub.app.** { *; }` rule, replaced with targeted rules
- **Room**: Version 6→7 (replyToId migration)

---

## [2.0.1] - 2025-07-17

### Security

- **KeystoreManager**: Hardware-backed AES-256-GCM encryption for API keys and E2E keys via Android Keystore
- **Network Security**: Cleartext traffic denied by default; only local model endpoints (127.0.0.1, 10.x, 192.168.x) allow HTTP
- **API Key Masking**: Password visual transformation on API key input fields
- **Permissions**: Documented REQUEST_INSTALL_PACKAGES usage for self-update

### Added

- **Hilt Dependency Injection** [REVERTED in final release due to KSP build issue]
  - @HiltAndroidApp Application class
  - @AndroidEntryPoint on MainActivity
  - @HiltViewModel on ChatViewModel, SettingsViewModel
  - @Inject on all 5 ViewModels
  - DatabaseModule (Room, DAOs, SettingsDataStore)
  - RepositoryModule (ChatRepository)

- **Unit Tests** (7 → 10 files, 50+ test cases)
  - MarkdownParserTest (30 tests: headings, code, bold/italic, links, lists, tables)
  - TransportFactoryTest (9 tests: AgentType routing)
  - WorkflowEngineTest (12 tests: nodes, edges, templates)
  - CryptoManagerTest, AgentConfigTest, AgentTypeTest, MessageTest, SessionTest, ConnectionStateTest, UpdateManagerTest

- **UX Improvements**
  - Message edit via long-press context menu (User messages only)
  - Session search bar in SessionsScreen (filters by title)
  - Message reactions via double-tap

- **Performance**
  - Room composite index on messages(sessionId, timestamp)
  - Image auto-compression for attachments > 1MB (720p, JPEG 85%)
  - PerformanceMonitor periodic refresh (memory, uptime, latency)

- **Accessibility**
  - ContentDescription on key interactive elements
  - Architecture documentation (docs/architecture.md)

- **i18n**
  - Full 4-language support (EN/ZH/JA/KO) for all screens
  - 30+ new string keys per language

### Fixed

- **Message duplication** in ChatViewModel (handleAgentEvent + simulateResponse)
- **Tablet voice/attachment** callbacks missing in TabletChatLayout
- **URL normalization** breaking HTTP endpoints (ws:// now only for WebSocket agents)
- **Search overlay** overlapping with AgentHub title (wrapped in Box)
- **ChatTopBar** streaming text overlapping with title (restructured layout)
- **OfflineBanner** layout shift (added AnimatedVisibility)
- **Inner Scaffold** double-padding (contentWindowInsets = WindowInsets(0))
- **New Agent title** always showing 'Edit Agent' (fixed condition)
- **ExposedDropdownMenuBox** not responding to clicks (interactionSource + menuAnchor)
- **Hardcoded strings** in SettingsScreen, AgentMarketScreen, PluginScreen, WorkflowScreen
- **Pull-to-refresh** not actually refreshing data
- **handleSharedText** missing isStreaming flag

### Changed

- **CollaborationManager** indicator disabled (pending v2.2.0 real implementation)
- **AgentType.values()** → AgentType.entries (deprecation fix)
- **CONTRIBUTING.md** updated for pure Android project (removed PWA/JS references)
- **SECURITY.md** updated with full security feature documentation

### Removed

- Capacitor remnants (capacitor.build.gradle, capacitor.settings.gradle)
- PWA remnants (manifest.json, build.sh, package-lock.json)

---

## [2.0.0] - 2025-07-06

### Changed

- **Kotlin** 1.9.22 → 2.1.0 (K2 compiler)
- **Compose BOM** 2024.01.00 → 2025.01.01, removed separate Compose Compiler
- **kapt → KSP** for Room annotation processing
- **Ktor** 2.3.7 → 3.0.3
- **Capacitor** 6.1.0 → 7.0.0
- **AGP** 8.2.1 → 8.7.3, **Gradle** 8.2.1 → 8.11.1
- **Navigation Compose** 2.7.7 → 2.8.9
- **Lifecycle** 2.7.0 → 2.8.7, **Room** 2.6.1 → 2.7.1
- **AndroidX Activity** 1.9.3 → 1.10.1, **DataStore** 1.0.0 → 1.1.2

### Added

- **Android Tablet Adaptation**
  - Adaptive layout system with 3 breakpoints (Compact / Medium / Expanded)
  - Foldable device detection
  - NavigationRail for tablet landscape + Expanded
  - Dual-pane layouts: Chat, Sessions, Activity, Settings
  - Adaptive grid for Agents screen
  - Dynamic sidebar widths and constrained input bars

- **Interactive Feedback**
  - Haptic feedback: send (light), connect (medium), error (double-pulse)
  - Press animation: send button spring scale (0.95x)
  - Message entrance: fadeIn + slideInVertically animation
  - Connection status: pulsing breathing animation
  - Pull-to-refresh on Sessions and Activity screens
  - Swipe-to-delete and swipe-to-pin on Sessions
  - Long-press context menus on messages and agent cards
  - Message status indicators (sending/sent/received/failed)
  - ErrorSnackbar and SuccessSnackbar components
  - Version tap 5× for developer info Easter egg

- **Connection UX**
  - Loading spinner on Connect button during connection
  - Inline error messages in connection wizard
  - Max 3 retry attempts with progress display
  - Auto-dismiss wizard on successful connection
  - Cancel previous connection on reconnect

- **Splash Screen**
  - Activated AndroidX SplashScreen API
  - 800ms minimum display to avoid flash
  - Proper theme transition via postSplashScreenTheme

### Removed

- Unused `activity_main.xml` (WebView leftover from Capacitor)
- Unused `config.xml` (Capacitor artifact)
- Planning docs: PLAN.md, STORE_LISTING.md, GLASS_UPGRADE_PLAN.md
- Utility scripts: download_apk.py, pull_from_github.py

## [1.0.0] - 2025-01-01

### Added

- Initial release
- PWA + Capacitor (Android/iOS)
- Liquid Glass Theme (Android 17 Design)
- Multi-Agent Mesh orchestration
- 5 agent protocol adapters (Hermes, OpenCode, OpenClaw, OpenAI, Xiaomi MiMo)
- E2E encryption (AES-256-GCM)
- Android Share Sheet, Foreground Service, Home Widget
- 47-unit test suite
