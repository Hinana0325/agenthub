import Foundation
import Observation

// MARK: - AppPreferences
// 对应 Android com.agentcontrolcenter.app.core.config.ConfigRepository
//
// iOS 端统一配置仓库：包装 UserDefaults + Keychain（敏感字段 passphrase）。
// 与现有散落在视图中的 @AppStorage 共享同一份 UserDefaults 键，逐步收敛为
// 单一入口。Swift 6 strict concurrency complete 下：
// - `protocol AppPreferences: AnyObject, Sendable` + `@MainActor` 隔离
// - `DefaultAppPreferences` 为 `@MainActor @Observable`，内部 UserDefaults /
//   KeychainStoring 实例用 `@ObservationIgnored` 排除出发布追踪

// MARK: - 领域配置 Structs（与 Android AppConfiguration 字段对齐）

/// 外观配置
struct AppearanceConfig: Equatable, Sendable {
    /// 主题模式：`"light"` / `"dark"` / `"system"`（与 `AppThemePreference.rawValue` 一致，复用旧 key `"theme"`）
    var themeMode: String = AppearanceConfig.defaultThemeMode
    /// 字体大小：Android 用 `"small"`/`"medium"`/`"large"`；iOS 旧 `FontSize` 枚举使用中文 rawValue
    /// （`"小"`/`"中"`/`"大"`）。此处默认值与 Android 对齐为 `"medium"`，
    /// 实际持久化值可能为中文 rawValue（由 SettingsView 写入），读取侧均以 `.medium` 兜底。
    var fontSize: String = AppearanceConfig.defaultFontSize
    /// 动态取色：iOS 无此特性，保留字段以对齐 Android（始终 false）
    var dynamicColorEnabled: Bool = false

    static let defaultThemeMode = "system"
    static let defaultFontSize = "medium"
}

/// 安全配置（passphrase 不在此处，单独走 Keychain）
struct SecurityConfig: Equatable, Sendable {
    var e2eEncryptionEnabled: Bool = false
    var analyticsEnabled: Bool = true
}

/// Agent 默认配置
struct AgentDefaults: Equatable, Sendable {
    /// 默认模型（Android 已改为空串，新建 Agent 时由用户指定）
    var defaultModel: String = ""
    var defaultTemperature: Double = 0.7
    var defaultMaxTokens: Int = 4096
}

/// 备份配置
struct BackupConfig: Equatable, Sendable {
    var autoBackupSchedule: String = "manual"
}

/// 引导状态
struct OnboardingState: Equatable, Sendable {
    var completed: Bool = false
}

/// Feature Flag 覆盖快照。
///
/// - Note: 权威存储由 `FeatureFlagManager` 管理（JSON Data 形式）。
///   此处仅作为 `AppConfiguration` 的只读快照，便于一次性读取当前覆盖表。
struct FeatureFlagOverrides: Equatable, Sendable {
    var overrides: [String: Bool] = [:]
}

/// 应用统一配置（组合 6 个子配置，对应 Android `AppConfiguration`）
struct AppConfiguration: Equatable, Sendable {
    var appearance: AppearanceConfig = .init()
    var security: SecurityConfig = .init()
    var agentDefaults: AgentDefaults = .init()
    var backup: BackupConfig = .init()
    var onboarding: OnboardingState = .init()
    var featureFlags: FeatureFlagOverrides = .init()

    static let `default` = AppConfiguration()
}

// MARK: - PreferenceKeys（键名常量集中地）

/// 偏好键名常量。集中定义，避免散落在各视图的魔法字符串。
///
/// 多数键复用既有 `@AppStorage` 键名（`"theme"` / `"fontSize"` / `"defaultModel"` 等），
/// 使 `AppPreferences` 与现有视图共享同一份 UserDefaults 数据源。
enum PreferenceKeys {
    // Appearance
    static let themeMode = "theme"                    // 兼容旧 @AppStorage("theme")
    static let fontSize = "fontSize"                  // 兼容旧 @AppStorage("fontSize")
    static let dynamicColorEnabled = "dynamicColorEnabled"
    // Security
    static let encryptionEnabled = "encryptionEnabled"
    static let analyticsEnabled = "analyticsEnabled"  // 由 AnalyticsManager 通过 didSet 写回
    // AgentDefaults
    static let defaultModel = "defaultModel"
    static let temperature = "temperature"
    static let maxTokens = "maxTokens"
    // Backup
    static let autoBackupSchedule = "autoBackupSchedule"
    // Onboarding
    static let onboardingCompleted = "onboarding_completed"  // ContentView @AppStorage
    // FeatureFlags（JSON Data，由 FeatureFlagManager 权威管理）
    static let featureFlagOverrides = "featureFlagOverrides"

    /// 全部需要被「清除所有数据」清理的 UserDefaults 键。
    ///
    /// 设计意图：取代散落在 SettingsView.clearAllData 中的硬编码数组，新增 key
    /// 只需在此追加，避免遗漏。
    ///
    /// 注意：
    /// - `onboardingCompleted` 不在此列表中 —— 清除数据后不应强制用户重做引导。
    /// - `featureFlagOverrides` 列入以便「清除所有数据」一并重置 Feature Flag 覆盖。
    /// - 末尾三个 `notify*` 与 `command_palette_recents` 为遗留键（部分已不再被读取），
    ///   保留清理以避免脏数据残留。
    static let all: [String] = [
        themeMode,
        fontSize,
        dynamicColorEnabled,
        encryptionEnabled,
        analyticsEnabled,
        defaultModel,
        temperature,
        maxTokens,
        autoBackupSchedule,
        featureFlagOverrides,
        "notifyHighPriority",
        "notifyMediumPriority",
        "notifyLowPriority",
        "command_palette_recents",
    ]
}

// MARK: - AppPreferences Protocol

/// 应用偏好统一仓库协议。
///
/// `@MainActor` 隔离：所有实例方法在主线程执行，便于 `@Observable` 实现安全发布
/// 变更到 SwiftUI 视图树。`Sendable` 保证协议值可跨 actor 传递。
///
/// 提取协议便于单元测试替换为内存实现（不触碰真实 UserDefaults / Keychain）。
@MainActor
protocol AppPreferences: AnyObject, Sendable {

    /// 当前应用配置（内存镜像，由 `@Observable` 实现追踪其变更）。
    var configuration: AppConfiguration { get }

    /// 原子地更新配置：在闭包内修改 `AppConfiguration`，完成后写回 UserDefaults。
    /// - Parameter block: 接收 `inout AppConfiguration` 的修改闭包
    func update(_ block: (inout AppConfiguration) -> Void)

    /// 清空全部偏好（UserDefaults 键 + Keychain passphrase），不影响 SwiftData。
    func clearAllPreferences()

    /// 读取 E2E 密码短语（Keychain）
    func loadE2EPassphrase() -> String
    /// 保存 E2E 密码短语（Keychain）
    func setE2EPassphrase(_ value: String)
    /// 清除 E2E 密码短语（Keychain）
    func clearE2EPassphrase()
}

extension AppPreferences {

    /// 全部偏好 UserDefaults 键列表（含遗留键与 AppPreferences 自管键）。
    ///
    /// 用于「清除所有数据」等场景，避免散落在各视图的硬编码列表遗漏新增键。
    /// 调用方式：`AppPreferences.allPreferenceKeys()`。
    ///
    /// `nonisolated`：此方法仅返回静态字符串列表，无 actor 隔离需求，
    /// 可在任意上下文调用（包括非 MainActor 场景）。
    nonisolated static func allPreferenceKeys() -> [String] {
        PreferenceKeys.all
    }
}

// MARK: - DefaultAppPreferences（生产实现）

/// 默认 `AppPreferences` 实现：包装 `UserDefaults` + `KeychainManager`。
///
/// - `@MainActor`：与 AppState / SwiftUI 视图树同线程，保证发布安全。
/// - `@Observable`：`configuration` 属性的变更自动驱动视图更新。
/// - `@ObservationIgnored`：`UserDefaults` / `KeychainStoring` 实例不参与发布追踪。
@MainActor
@Observable
final class DefaultAppPreferences: AppPreferences {

    /// UserDefaults 实例（@ObservationIgnored —— 不参与 @Observable 追踪）
    @ObservationIgnored private let defaults: UserDefaults
    /// Keychain 提供者（@ObservationIgnored）
    @ObservationIgnored private let keychain: KeychainStoring

    /// 当前配置（内存镜像）。@Observable 追踪其变更。
    /// 外部只读，修改通过 `update(_:)` 进行。
    private(set) var configuration: AppConfiguration

    init(defaults: UserDefaults = .standard, keychain: KeychainStoring = DefaultKeychainStorage()) {
        self.defaults = defaults
        self.keychain = keychain
        self.configuration = Self.loadConfiguration(from: defaults)
    }

    // MARK: - 更新

    func update(_ block: (inout AppConfiguration) -> Void) {
        var c = configuration
        block(&c)
        // 仅当确实变更时才赋值并写回，避免无谓的发布与 I/O
        guard c != configuration else { return }
        configuration = c
        persist(c)
    }

    // MARK: - 清空

    func clearAllPreferences() {
        for key in Self.allPreferenceKeys() {
            defaults.removeObject(forKey: key)
        }
        keychain.clearPassphrase()
        // 重置内存镜像并触发 @Observable 发布
        configuration = .default
    }

    // MARK: - E2E Passphrase（Keychain，不进 UserDefaults）

    func loadE2EPassphrase() -> String { keychain.loadPassphrase() }
    func setE2EPassphrase(_ value: String) { keychain.savePassphrase(value) }
    func clearE2EPassphrase() { keychain.clearPassphrase() }

    // MARK: - 持久化

    /// 将 `AppConfiguration` 写回 UserDefaults。
    ///
    /// 注意：
    /// - `featureFlagOverrides` 由 `FeatureFlagManager` 以 JSON Data 形式权威管理，
    ///   此处不写回，避免覆盖 FeatureFlagManager 的最新状态。
    /// - `analyticsEnabled` 由 `AnalyticsManager` 通过 didSet 写回，此处不重复写入，
    ///   避免与 AnalyticsManager 的内存状态产生分歧。
    private func persist(_ config: AppConfiguration) {
        defaults.set(config.appearance.themeMode, forKey: PreferenceKeys.themeMode)
        defaults.set(config.appearance.fontSize, forKey: PreferenceKeys.fontSize)
        defaults.set(config.appearance.dynamicColorEnabled, forKey: PreferenceKeys.dynamicColorEnabled)
        defaults.set(config.security.e2eEncryptionEnabled, forKey: PreferenceKeys.encryptionEnabled)
        defaults.set(config.agentDefaults.defaultModel, forKey: PreferenceKeys.defaultModel)
        defaults.set(config.agentDefaults.defaultTemperature, forKey: PreferenceKeys.temperature)
        defaults.set(config.agentDefaults.defaultMaxTokens, forKey: PreferenceKeys.maxTokens)
        defaults.set(config.backup.autoBackupSchedule, forKey: PreferenceKeys.autoBackupSchedule)
        defaults.set(config.onboarding.completed, forKey: PreferenceKeys.onboardingCompleted)
    }

    // MARK: - 加载

    /// 从 UserDefaults 重建 `AppConfiguration`。缺失的键使用子配置的默认值。
    private static func loadConfiguration(from defaults: UserDefaults) -> AppConfiguration {
        var config = AppConfiguration()

        // Appearance
        if let v = defaults.string(forKey: PreferenceKeys.themeMode) {
            config.appearance.themeMode = v
        }
        if let v = defaults.string(forKey: PreferenceKeys.fontSize) {
            config.appearance.fontSize = v
        }
        if defaults.object(forKey: PreferenceKeys.dynamicColorEnabled) != nil {
            config.appearance.dynamicColorEnabled = defaults.bool(forKey: PreferenceKeys.dynamicColorEnabled)
        }

        // Security
        if defaults.object(forKey: PreferenceKeys.encryptionEnabled) != nil {
            config.security.e2eEncryptionEnabled = defaults.bool(forKey: PreferenceKeys.encryptionEnabled)
        }
        if defaults.object(forKey: PreferenceKeys.analyticsEnabled) != nil {
            config.security.analyticsEnabled = defaults.bool(forKey: PreferenceKeys.analyticsEnabled)
        }

        // AgentDefaults
        if let v = defaults.string(forKey: PreferenceKeys.defaultModel) {
            config.agentDefaults.defaultModel = v
        }
        if defaults.object(forKey: PreferenceKeys.temperature) != nil {
            config.agentDefaults.defaultTemperature = defaults.double(forKey: PreferenceKeys.temperature)
        }
        if defaults.object(forKey: PreferenceKeys.maxTokens) != nil {
            config.agentDefaults.defaultMaxTokens = defaults.integer(forKey: PreferenceKeys.maxTokens)
        }

        // Backup
        if let v = defaults.string(forKey: PreferenceKeys.autoBackupSchedule) {
            config.backup.autoBackupSchedule = v
        }

        // Onboarding
        if defaults.object(forKey: PreferenceKeys.onboardingCompleted) != nil {
            config.onboarding.completed = defaults.bool(forKey: PreferenceKeys.onboardingCompleted)
        }

        // FeatureFlags（JSON Data 只读快照，权威存储在 FeatureFlagManager）
        if let data = defaults.data(forKey: PreferenceKeys.featureFlagOverrides),
           let decoded = try? JSONDecoder().decode([String: Bool].self, from: data) {
            config.featureFlags.overrides = decoded
        }

        return config
    }
}
