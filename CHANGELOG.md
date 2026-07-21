# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [4.6.0] - 2026-07-21

### Fixed — iOS 26 Liquid Glass 升级 PR 修复

修复 `19208f2` PR 的多个阻断性问题，使 Liquid Glass 工作可在 iOS 18 基线上正确构建与运行。

- **部署目标回退**: iOS 26.0 → iOS 18.0。原 PR 将 `IPHONEOS_DEPLOYMENT_TARGET` 从 17.0 直接跳到 26.0，会切断 30-40% 用户。`project.yml` 三个 target 全部回退；`xcodeVersion` 26.0 → 16.0。
- **CI 配置修复**: `build-ios.yml` 原 `XCODE_VERSION: "26.0"` / `IOS_VERSION: "26.0"` 在 `macos-15` runner 上不存在（Xcode 26 需要 macOS 26 Tahoe），CI 永远跑不起来。改为 `XCODE_VERSION: "16.4"` / `IOS_VERSION: "18.4"`，并加入 Xcode 选择降级逻辑（找不到指定版本时自动选最新 16.x）和模拟器列表输出便于排查。
- **Liquid Glass API 守卫**: `Theme/GlassTokens.swift` 中 `regularVariant` / `interactiveVariant` 标注 `@available(iOS 26, *)`；`Theme/GlassPresets.swift` 全部 View 扩展改为 `@ViewBuilder` + `if #available(iOS 26, *)` 双分支，iOS 18 回退到 `.ultraThinMaterial`。
- **新增兼容包装**: `glassTinted(_:in:)` 包装 `Glass.tint(_:)` + `.glassEffect(_:in:)`；`glassMorphID(_:in:)` 包装 `.glassEffectID(_:in:)` 用于 `GlassEffectContainer` 内的 morph 形变，iOS 18 回退为无 morph。
- **调用点全量替换**: `ContentView` / `ChatView` / `VoiceChatView` / `WorkflowView` / `CompareView` 中所有直接 `.glassEffect` 调用改为 `.glassTinted`；所有 `.glassEffectID` 改为 `.glassMorphID`。
- **测试兼容**: `GlassPresetsTests.swift` 中 `regularVariant` / `interactiveVariant` 访问测试标注 `@available(iOS 26, *)`；新增 `glassTinted` 构造性测试。

### Changed — Swift/SwiftUI 审计改进（SW-M2/M3/M4/M6/M7）

- **SW-M2 `.onAppear → .task`**: 7 个 View 的数据加载 `.onAppear` 改为 `.task`。涉及 `PluginView` / `TasksView` / `McpView` / `DeviceSyncView` / `ActivityView` / `SettingsView` / `ChatView`。
- **SW-M3 `try?` → Logger**: 4 个 Service 的静默 `try?` 改为 `do/catch` + `os.Logger` 上报。涉及 `WorkflowEngine` / `ChatRepository` / `LocalModelManager` / `CollaborationManager`。
- **SW-M4 `DateFormatter` → `FormatStyle`**: 7 个文件的 `DateFormatter` 改为 `Date.FormatStyle`。涉及 `ChatRepository` / `CollaborationManager` / `DeviceSyncManager` / `BackupManager` / `DataInsightsManager` / `DeviceSyncView` / `AppTheme`。
- **SW-M6 CollaborationManager 异步 I/O**: `sessionStore` 计算属性拆分为 `nonisolated static func`；改用 `Task.detached` 避免阻塞 MainActor。
- **SW-M7 移除冗余 MainActor.run**: `WorkflowView` / `ChatView` / `CompareView` / `TasksView` 中 `await MainActor.run { }` 包装移除。

### Fixed — Android 代码审计修复

- **!! 非空断言修复**: 8 处高风险 `!!` 替换为安全调用。`LocalModelManager` 流读取改用 `?: continue`，`DataInsightsManager` 日期解析用 try-catch 包裹，`MarketplaceClient` 用 `?: RuntimeException()` 回退，`PluginScreen` 用局部 val 捕获快照。
- **Glass 残留命名重命名**: 12 个标识符在 19 个文件中重命名（107 处）。`GlassCard`→`AppCard`、`GlassTopAppBar`→`AppTopAppBar`、`GlassNavigationBar`→`AppNavigationBar` 等。`LocalIsGlass` 和 `LocalGlass*` 保留向后兼容。

### Added — Android 工具抽取 + CI + 安全

- **runSafely 工具**: 新增 `core/util/RunSafely.kt`，统一 try-catch 模板（CancellationException 正确再抛）。CompareViewModel 和 AgentsViewModel 已接入。
- **DateTimeUtils 工具**: 新增 `core/util/DateTimeUtils.kt`，ThreadLocal 缓存 SimpleDateFormat。TasksScreen 已接入。
- **CI detekt + lint**: build-apk.yml 新增 detekt 和 lint 两个 job，上传报告 artifact。build job 依赖两者（`continue-on-error` 报告模式）。
- **证书锁定框架**: 新增 `CertificatePinnerFactory.kt`，支持公网 API 端点证书锁定。`OpenAIHttpTransport` 新增 `enableCertificatePinning` 参数。默认关闭，pin map 为空，后续填入 SHA-256 哈希即可启用。

### Changed — 版本号

- **版本号**: `version.properties` / `project.yml`（3 个 target）/ `Info.plist` 同步 4.5.0 → 4.6.0 / build 35 → 36。
- **README 修订**: `ios/README.md` 关键配置表与「最低部署目标」小节更新为 iOS 18.0 基线 + Liquid Glass iOS 26 增量特性描述。

## [4.5.0] - 2026-07-21
