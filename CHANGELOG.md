# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
