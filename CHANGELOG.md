# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.1.0] - 2026-07-20

### Added — UX 用户体验改进

- **Loading Skeleton 组件**: 双端新增骨架屏组件（Android `SkeletonBox.kt` + `SkeletonListItems.kt` / iOS `SkeletonView.swift`），包含 `SessionSkeletonItem` / `MessageSkeletonItem` / `AgentCardSkeletonItem` 三种列表项骨架屏，shimmer 动画。
- **统一 ErrorView**: 双端新增 `ErrorStateView` 组件（Android `ErrorStateView.kt` / iOS `ErrorStateView.swift`），包含错误图标、标题、描述和可选重试按钮。
- **iOS 触觉反馈接入**: `HapticFeedback.swift` 此前已封装 7 种触觉反馈但全项目零调用，本次在 5 个文件中接入 14 处调用：ChatView（发送/接收/错误/删除）、AgentsView（连接/删除）、SessionsView（创建/删除/置顶）、TasksView（完成/删除）、ContentView（Tab 切换）。
- **ChatScreen Snackbar 反馈**: Android ChatScreen 新增 `SnackbarHostState`，复制消息/删除消息/清空聊天操作后显示 Snackbar 反馈。ChatViewModel 新增 `lastAction` 状态字段。
- **搜索防抖**: Android Marketplace 搜索新增 300ms debounce，避免每次按键触发 API 调用。
- **Agent 表单实时验证**: Android `AgentFormDialog` 新增字段级实时验证：name（非空）、serverUrl（格式校验）、apiKey（长度校验），错误时字段边框变红 + supportingText 提示，保存按钮需所有验证通过。
- **iOS 列表项动画**: SessionsView / TasksView / AgentsView 列表项增删添加 `.transition(.move(edge: .trailing).combined(with: .opacity))` + `.animation(.easeInOut(duration: 0.25))`。

### Changed

- **版本号**: Android versionCode 27 → 28 / versionName 3.0.0 → 3.1.0；iOS 同步。

## [3.0.0] - 2026-07-20

### Added — P3 长期演进

- **Analytics 埋点系统**: 双端新增 `AnalyticsManager`（Android Hilt Singleton / iOS @Observable），纯本地 ring buffer（1000 条），支持事件类型枚举（SCREEN_VIEW / BUTTON_CLICK / AGENT_CONNECT 等），JSON 导出，隐私优先不集成第三方 SDK。可通过设置开关启用/禁用。
- **Feature Flag 系统**: 双端新增 `FeatureFlagManager`（Android Hilt Singleton / iOS @Observable），支持 9 个功能标志（WORKFLOW_ENGINE / MARKETPLACE / DEVICE_SYNC / INSIGHTS / COMPARE_MODE / MCP_SERVERS / CUSTOM_THEME / VOICE_INPUT / E2E_ENCRYPTION），DEVICE_SYNC 默认关闭（开发中），其余默认开启。用户可通过 DataStore / UserDefaults 覆盖默认值。
- **云备份/恢复增强**: 双端新增 `BackupManager`，支持明文和加密备份（KeystoreManager / KeychainManager AES-256-GCM），`BackupData` 包含 sessions / messages / agentConfigs / settings 完整快照，`BackupSchedule` 枚举（DAILY / WEEKLY / MANUAL）。
- **Renovate 依赖自动化**: 新增 `.github/renovate.json`（schedule: before 6am on Monday，minor/patch 分组，major 需 approval）和 `.github/dependabot.yml`（gradle + github-actions 生态，weekly）。
- **Android Shortcuts**: 新增 `shortcuts.xml`（3 个静态快捷方式：新建聊天/新建 Agent/设置），`ShortcutRouter` 桥接 Activity 与 Compose 导航，2 个矢量图标 drawable。
- **iOS App Intents**: 新增 `AgentControlCenterIntents.swift`（3 个 AppIntent + AppShortcutsProvider），`IntentRouter.swift` 监听通知并路由，`ShortcutRelay` 处理冷启动暂存。
- **韩文翻译补齐**: shortcuts 相关字符串。

### Changed

- **版本号**: Android versionCode 26 → 27 / versionName 2.8.0 → 3.0.0；iOS 同步。
- **SettingsDataStore**: 新增 `analyticsEnabled` / `autoBackupSchedule` / `featureFlagOverrides` Flow 和对应 setter。
- **AgentControlCenterApplication**: 初始化 AnalyticsManager，记录 app_launch 事件。
- **AndroidManifest**: MainActivity 添加 shortcuts meta-data。
- **AppState (iOS)**: 新增 analyticsManager / featureFlagManager / backupManager / pendingShortcutDestination。
- **ContentView (iOS)**: 监听 pendingShortcutDestination 并路由。

## [2.8.0] - 2026-07-20

### Security

- **MCP 数据加密**: `McpViewModel` 的服务器列表（含 URL 等敏感配置）现在通过 `KeystoreManager.encrypt()` AES-256-GCM 加密后存储到 SharedPreferences，`loadInitialState()` 使用 `decryptOrRaw()` 兼容旧版明文数据。
- **Widget 数据加密**: `WidgetDataProvider` 的 `KEY_PENDING_INPUT`（用户输入）和 `KEY_AGENT_NAME`（Agent 名称）现在通过 KeystoreManager 加密存储，`consumePendingInput()` 和 `getAgentName()` 使用 `decryptOrRaw()` 兼容旧版明文数据。
- **ProGuard 强化**: 新增 `-allowaccessmodification`、`-repackageclasses ''`、`-obfuscationdictionary` 等混淆强化规则，配合 `obfdict.txt` 词典文件，提升反编译难度。

### Added

- **Detekt 静态分析**: Android 项目集成 detekt 1.23.7（build.gradle classpath + plugin + config），配置文件 `config/detekt.yml` 针对项目特点定制规则（放宽 wildcard import、magic number 等噪声规则）。
- **SwiftLint 配置**: 新增 `.swiftlint.yml`，配置 line_length、type_body_length 等规则阈值，启用 `empty_count`、`unused_import` 等 opt-in 规则。
- **iOS BGTaskScheduler 注册**: `AgentControlCenterApp.swift` 新增 `registerBackgroundTasks()` 方法，注册 `app.refresh` 和 `app.processing` 两个后台任务，使 Info.plist 中的 `BGTaskSchedulerPermittedIdentifiers` 声明实际生效。
- **韩文翻译补齐**: `values-ko/strings.xml` 新增 60+ 条缺失翻译（导航、工作流、命令、语音、自定义主题、MCP、硬件信息等）。

### Changed

- **版本号**: Android versionCode 25 → 26 / versionName 2.7.0 → 2.8.0；iOS 同步。

## [2.7.0] - 2026-07-20

### Added

- **HTTP 重试与指数退避**: Android `OpenAIHttpTransport` 和 iOS `OpenAIHTTPTransport` 新增 3 次重试机制，对 5xx 服务端错误和网络异常进行指数退避重试（1s/2s/4s），4xx 客户端错误不重试。
- **WebSocket 主动心跳**: Android 和 iOS 的 `WebSocketTransport` 新增每 30 秒主动发送 ping 帧，检测连接活性，避免长连接假死。
- **Android 通知权限请求**: `MainActivity` 新增 `POST_NOTIFICATIONS` 运行时权限主动请求（Android 13+），此前仅声明权限但从未请求，导致通知被静默丢弃。
- **Onboarding 首次启动引导**: 双端新增 3 页引导（欢迎/多设备协同/安全与隐私），通过 DataStore/`@AppStorage` 的 `onboarding_completed` 标记控制是否显示。

### Changed

- **iOS 版本号**: `CFBundleShortVersionString` 2.6.0 → 2.7.0 / `MARKETING_VERSION` 2.6.0 → 2.7.0 / `CURRENT_PROJECT_VERSION` 24 → 25。
- **Android 版本号**: versionCode 24 → 25 / versionName 2.6.0 → 2.7.0。
- **SettingsDataStore**: 新增 `onboardingCompleted` Flow 和 `setOnboardingCompleted()` 方法。
- **SettingsViewModel**: 新增 `onboardingCompleted` StateFlow 和 `markOnboardingCompleted()` 方法。
- **MainActivity**: Onboarding 未完成时显示引导页，完成后再加载 AppNavigation 和 share intent 处理。

## [2.6.0] - 2026-07-20

### Security

- **iOS ATS 修复**: 将 `NSAllowsArbitraryLoads=true`（全局禁用 App Transport Security）改为 `NSAllowsLocalNetworking=true`，仅允许本地网络 HTTP 连接（Ollama/LM Studio），外部连接强制 HTTPS。
- **keystore 模板化**: 新增 `keystore.properties.example` 模板文件（含占位符），实际 `keystore.properties` 已在 `.gitignore` 中且未被 git 跟踪。

### Added

- **Sentry 崩溃监控**: 双端集成 Sentry SDK（Android 8.42.0 + iOS SPM 8.x），支持云端崩溃上报。DSN 通过 `SENTRY_DSN` 环境变量注入。
- **本地崩溃日志器**: Android `Thread.setDefaultUncaughtExceptionHandler` + iOS `NSSetUncaughtExceptionHandler`，与 Sentry handler 链式调用，崩溃时本地写入 `crashes/crash_*.log` 作为二级保障。
- **版本号统一**: 新增 `version.properties` 作为双端版本号的单一事实来源，Android `build.gradle` 和 iOS `project.yml`/`Info.plist` 均从该文件读取。
- **DeviceSync 开发中横幅**: Android 和 iOS 的设备同步页面顶部新增「功能开发中」提示横幅，避免用户误以为同步功能已可用。
- **Sentry ProGuard 规则**: 新增 `io.sentry.**` keep 规则。

### Changed

- **iOS 版本号**: `CFBundleShortVersionString` 2.2.0 → 2.6.0 / `MARKETING_VERSION` 2.3.0 → 2.6.0 / `CURRENT_PROJECT_VERSION` 2 → 24。
- **Android 版本号**: versionCode 23 → 24 / versionName 2.5.1 → 2.6.0，统一从 `version.properties` 读取。
- **AndroidManifest**: 新增 Sentry DSN / traces-sample-rate / session-replay meta-data。

## [2.2.0] - 2026-07-20

### Changed

- **项目重命名**: 正式更名为 Agent Control Center，覆盖仓库名、应用显示名、文档与各处引用，清理旧项目名残留。
- **包名迁移**: `com.agenthub.app` → `com.agentcontrolcenter.app`，同步更新 `AndroidManifest.xml`、`build.gradle`、ProGuard 规则及全部 import 路径。
- **签名 keystore**: 启用全新 release 签名 keystore (`agentcontrolcenter.keystore`)，旧 keystore 废弃；后续所有正式构建统一使用新签名。
- **版本号**: Android versionCode 18 → 19 / versionName 2.1.3 → 2.2.0；iOS build 1 → 2。

### Added

- **iOS 项目骨架**: 完成 iOS 端 Swift + SwiftUI 项目骨架（10 层架构 / 26 源文件），与 Android 端共享协议层定义，双端并行开发正式启动。
- **XcodeGen 配置**: 新增 `project.yml`，通过 XcodeGen 生成 Xcode 工程，避免手写 `.pbxproj` 造成的合并冲突。
- **永久统一协议层**: 10 JSON Schema + 4 传输协议文档，双端共享单一事实来源。

### Removed

- 旧项目名相关残留引用（仓库元数据、文档标题、字符串资源等）。

### ⚠️ 升级须知

- 由于包名和签名已变更，**现有用户需卸载旧版再安装新版**（不同签名无法覆盖安装）。
- Room 数据库名从 `agenthub.db` 改为 `agentcontrolcenter.db`，本地数据将重新初始化。
- CI 密钥需更新：GitHub Actions secrets 中的 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 需替换为新 keystore 的值。

---

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
- **ProGuard**: Removed blanket `-keep class com.agentcontrolcenter.app.** { *; }` rule, replaced with targeted rules
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
- **Search overlay** overlapping with Agent Control Center title (wrapped in Box)
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
