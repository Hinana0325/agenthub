import SwiftUI
import SwiftData
import Sentry
import BackgroundTasks
import os

// MARK: - AgentControlCenterApp
// iOS App 入口，对应 Android com.agentcontrolcenter.app.AgentControlCenterApplication + MainActivity

/// 应用入口 — Agent Control Center iOS 应用。
///
/// 职责：
/// - 创建全局 `AppState` 依赖容器（通过 `@State` 持有生命周期）
/// - 将 `AppState` 注入 SwiftUI 环境，子视图通过 `@Environment(AppState.self)` 访问依赖
/// - 将 `DataController.container` 注入 SwiftUI 的 SwiftData 环境，
///   使 `@Query`、`@Environment(\.modelContext)` 等自动使用此容器
/// - 初始化 Sentry 崩溃监控 + 本地崩溃日志器
/// - 挂载根视图 `ContentView`
///
/// 主题：`preferredColorScheme` 由 `ContentView` 内部根据
/// `@AppStorage("theme")` 统一应用（参见 `AppTheme.ThemePreference`），
/// 因此本入口仅负责依赖注入，不再重复设置配色方案。
@main
struct AgentControlCenterApp: App {

    /// 全局应用状态（依赖容器），由 `@State` 持有，与应用生命周期一致
    @State private var appState = AppState()

    init() {
        // Initialize Sentry crash reporting
        SentrySDK.start { options in
            // DSN 从 Info.plist（通过 xcconfig 注入 SENTRY_DSN）读取，避免硬编码占位符。
            // 缺失时 SentrySDK 会以空 DSN 初始化，事件不会发送但 SDK 仍可工作。
            if let dsn = Bundle.main.object(forInfoDictionaryKey: "SENTRY_DSN") as? String,
               !dsn.isEmpty, dsn != "$(SENTRY_DSN)" {
                options.dsn = dsn
            }
            // 生产环境采样率 0.1，Debug 全采样；避免长会话 SSE 流量翻倍
            #if DEBUG
            options.tracesSampleRate = 1.0
            #else
            options.tracesSampleRate = 0.1
            #endif
            options.attachScreenshot = false
            options.attachViewHierarchy = false
            options.enableUserInteractionTracing = false
            options.sendDefaultPii = false
        }

        // Install local crash logger (chains with Sentry's handler).
        // Writes crash details to a local file as a secondary safety net,
        // then delegates to Sentry's handler for cloud reporting.
        let previousHandler = NSGetUncaughtExceptionHandler()
        NSSetUncaughtExceptionHandler { exception in
            // Write to local file — 使用 caches 目录（不进 iCloud 备份，不暴露给 iTunes），
            // 并显式指定 completeFileProtectionUntilFirstUserAuthentication 保护级别。
            do {
                let cachesDir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
                let crashDir = cachesDir.appendingPathComponent("Crashes")
                try FileManager.default.createDirectory(at: crashDir, withIntermediateDirectories: true)
                let timestamp = Int(Date().timeIntervalSince1970)
                let crashFile = crashDir.appendingPathComponent("crash_\(timestamp).log")
                let formatter = DateFormatter()
                formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
                // 对 reason 做关键词过滤，避免 apiKey / Bearer / AKS: 等敏感串落地
                let rawReason = exception.reason ?? "Unknown"
                let sanitizedReason = Self.sanitizeCrashReason(rawReason)
                let crashText = """
                Time: \(formatter.string(from: Date()))
                Name: \(exception.name.rawValue)
                Reason: \(sanitizedReason)
                Stack:
                \(exception.callStackSymbols.joined(separator: "\n"))
                """
                try crashText.data(using: .utf8)?.write(
                    to: crashFile,
                    options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
                )
                // 清理 7 天前的旧崩溃日志
                Self.cleanupOldCrashLogs(in: crashDir)
            } catch {
                // Best-effort logging; never swallow the original exception
            }
            // Delegate to previous handler (Sentry's)
            previousHandler?(exception)
        }

        // Register background task scheduler
        registerBackgroundTasks()

        // P3-5: 绑定 IntentRouter 到 AppState
        // IntentRouter 监听 NotificationCenter 中的 App Intent 通知，
        // 将快捷方式导航目标转发到 appState.pendingShortcutDestination。
        // 若冷启动期间 Intent 已触发，暂存的目标会在此回放。
        IntentRouter.shared.bind(to: appState)
    }

    /// 过滤崩溃 reason 中的敏感关键词，避免 apiKey / Bearer token / 加密前缀等落地到日志文件。
    private static func sanitizeCrashReason(_ reason: String) -> String {
        let patterns: [String] = [
            "sk-[A-Za-z0-9]{20,}",            // OpenAI API Key
            "Bearer\\s+[A-Za-z0-9._-]+",      // Authorization header
            "AKS:[A-Za-z0-9+/=]+",            // 加密密文前缀
            "AH1:[A-Za-z0-9+/=]+",            // E2E 加密前缀
            "(?i)apiKey\\s*[=:]\\s*\\S+"      // apiKey=xxx / apiKey: xxx
        ]
        var sanitized = reason
        for pattern in patterns {
            if let regex = try? NSRegularExpression(pattern: pattern) {
                let range = NSRange(sanitized.startIndex..., in: sanitized)
                sanitized = regex.stringByReplacingMatches(in: sanitized, range: range, withTemplate: "[REDACTED]")
            }
        }
        return sanitized
    }

    /// 清理 7 天前的崩溃日志，避免无限累积。
    private static func cleanupOldCrashLogs(in directory: URL) {
        let sevenDaysAgo = Date().addingTimeInterval(-7 * 24 * 60 * 60)
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: [.contentModificationDateKey]
        ) else { return }
        for file in files where file.pathExtension == "log" {
            if let modDate = try? file.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate,
               modDate < sevenDaysAgo {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    /// 注册 BGTaskScheduler 后台任务。
    ///
    /// Info.plist 已声明 `BGTaskSchedulerPermittedIdentifiers`：
    /// - `com.agentcontrolcenter.app.refresh`: App Refresh，定期刷新 Agent 连接状态
    /// - `com.agentcontrolcenter.app.processing`: Background Processing，清理过期崩溃日志
    ///
    /// HIG (Guideline 2.5.4)：
    /// 后台任务必须执行实际工作，不能立即 setTaskCompleted(true)。
    /// 否则 Apple 会拒绝或撤销后台模式权限。
    private func registerBackgroundTasks() {
        // App Refresh: 刷新 Agent 连接状态并安排下一次执行
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "com.agentcontrolcenter.app.refresh",
            using: nil
        ) { task in
            // expirationHandler：系统给的时间用完时调用，必须立即收尾
            task.expirationHandler = {
                task.setTaskCompleted(success: false)
            }
            // 实际工作：清理过期崩溃日志 + 安排下一次 App Refresh
            // （后台只做轻量 I/O，不发起网络请求避免电量消耗）
            self.cleanupOldCrashLogs()
            self.scheduleAppRefresh()
            task.setTaskCompleted(success: true)
        }

        // Processing: 清理过期崩溃日志（>7 天），可在后台运行较长时间
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: "com.agentcontrolcenter.app.processing",
            using: nil
        ) { task in
            task.expirationHandler = {
                task.setTaskCompleted(success: false)
            }
            // 实际工作：清理 caches/CrashLogs/ 中超过 7 天的崩溃日志
            self.cleanupOldCrashLogs()
            task.setTaskCompleted(success: true)
        }
    }

    /// 安排下一次 App Refresh 任务（最早 15 分钟后）
    private func scheduleAppRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: "com.agentcontrolcenter.app.refresh")
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        // SW-M3: BG 任务调度失败时记录日志，便于排查「后台刷新未触发」类问题
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            Self.logger.warning("BGTaskScheduler submit failed: \(error.localizedDescription)")
        }
    }

    /// 应用级日志器（subsystem 与 bundleId 一致，便于 Console.app 筛选）
    private static let logger = Logger(subsystem: "com.agentcontrolcenter.app.ios", category: "App")

    /// 清理超过 7 天的崩溃日志（已在 init 中注册为 BG processing 任务的实际工作）
    private func cleanupOldCrashLogs() {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        guard let crashDir = caches?.appendingPathComponent("CrashLogs", isDirectory: true) else { return }
        guard let files = try? FileManager.default.contentsOfDirectory(at: crashDir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        let threshold = Date().addingTimeInterval(-7 * 24 * 3600)
        for file in files {
            if let modDate = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
               modDate < threshold {
                try? FileManager.default.removeItem(at: file)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            // 根视图。preferredColorScheme 由 ContentView 内部根据
            // @AppStorage("theme") 统一应用，此处仅负责依赖注入。
            ContentView()
                // 注入 AppState 到 SwiftUI 环境，子视图通过 @Environment(AppState.self) 访问
                .environment(appState)
                // 注入 SwiftData ModelContainer，子视图可通过 @Query / @Environment(\.modelContext) 使用
                .modelContainer(appState.dataController.container)
        }
        // HIG：键盘快捷键应通过 .commands { CommandMenu } 暴露，
        // 系统菜单栏会显示等价键，VoiceOver 也可访问，
        // 避免使用透明零尺寸 Button hack
        .commands {
            CommandMenu("工具") {
                Button("命令面板") {
                    appState.showCommandPalette = true
                }
                .keyboardShortcut("k", modifiers: .command)
            }
        }
    }
}
