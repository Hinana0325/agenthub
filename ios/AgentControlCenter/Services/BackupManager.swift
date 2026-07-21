import Foundation
import Observation

// MARK: - BackupSchedule

/// 自动备份调度枚举 — 对应 Android `BackupManager.BackupSchedule`。
///
/// 取值通过 `UserDefaults` 字符串持久化，与 SwiftUI 视图中的
/// `@AppStorage("autoBackupSchedule")` 共享同一个键。
enum BackupSchedule: String, Codable, CaseIterable {
    /// 每日自动备份一次
    case daily
    /// 每周自动备份一次
    case weekly
    /// 仅手动触发备份（默认值）
    case manual

    /// 用于在 UI 中展示的本地化名称（中文）。
    var displayName: String {
        switch self {
        case .daily: return "每日"
        case .weekly: return "每周"
        case .manual: return "手动"
        }
    }
}

// MARK: - BackupSettings

/// 设置快照 — 备份/恢复时捕获的关键用户偏好。
///
/// 仅包含可安全跨设备迁移的设置项；`encryptionPassphrase` 等绑定设备的敏感凭据
/// 不在此处，由 `KeychainManager` 单独管理。
///
/// 字段命名与 Android `BackupManager.BackupSettings` 完全对齐（`themeMode` /
/// `e2eEnabled` / `autoBackup`），保证 iOS ↔ Android 跨平台备份可互读。
/// 旧版 iOS 备份中使用的 `theme` / `encryptionEnabled` 字段在解码时仍被接受，
/// 用于向前兼容。
struct BackupSettings: Codable, Equatable {
    /// 主题模式："system" / "light" / "dark"（Android 字段名 `themeMode`）
    var themeMode: String
    /// 字体大小："small" / "medium" / "large"（与 `FontSize` 的 rawValue 对齐）
    var fontSize: String
    /// 是否启用端到端加密（Android 字段名 `e2eEnabled`）
    var e2eEnabled: Bool
    /// 自动备份调度（与 `BackupSchedule.rawValue` 对齐）
    var autoBackup: String

    /// 创建一份默认设置快照（用于解码失败兜底）
    static let defaultSettings = BackupSettings(
        themeMode: "system",
        fontSize: "medium",
        e2eEnabled: false,
        autoBackup: BackupSchedule.manual.rawValue
    )

    private enum CodingKeys: String, CodingKey {
        case themeMode, fontSize, e2eEnabled, autoBackup
        // 旧版 iOS 字段名 — 仅用于解码兼容
        case legacyTheme = "theme"
        case legacyEncryptionEnabled = "encryptionEnabled"
    }

    init(themeMode: String, fontSize: String, e2eEnabled: Bool, autoBackup: String) {
        self.themeMode = themeMode
        self.fontSize = fontSize
        self.e2eEnabled = e2eEnabled
        self.autoBackup = autoBackup
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        // 优先使用新字段名 `themeMode`，回退到旧版 `theme`（向前兼容旧 iOS 备份）
        self.themeMode = try c.decodeIfPresent(String.self, forKey: .themeMode)
            ?? c.decodeIfPresent(String.self, forKey: .legacyTheme)
            ?? "system"
        self.fontSize = try c.decodeIfPresent(String.self, forKey: .fontSize) ?? "medium"
        // 优先使用 `e2eEnabled`，回退到旧版 `encryptionEnabled`
        self.e2eEnabled = try c.decodeIfPresent(Bool.self, forKey: .e2eEnabled)
            ?? c.decodeIfPresent(Bool.self, forKey: .legacyEncryptionEnabled)
            ?? false
        self.autoBackup = try c.decodeIfPresent(String.self, forKey: .autoBackup)
            ?? BackupSchedule.manual.rawValue
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        // 仅编码新字段名，保证与 Android 互读
        try c.encode(themeMode, forKey: .themeMode)
        try c.encode(fontSize, forKey: .fontSize)
        try c.encode(e2eEnabled, forKey: .e2eEnabled)
        try c.encode(autoBackup, forKey: .autoBackup)
    }
}

// MARK: - BackupData

/// 备份数据载体 — 包含一次完整快照所需的全部领域数据。
///
/// 序列化为 JSON 后可作为本地备份文件或加密备份的明文载荷。
/// 与 Android 端 `BackupManager.BackupData` 结构对齐：
/// - `timestamp` 为 epoch milliseconds（Int64），对应 Android `Long`；
/// - 旧版 iOS 备份使用 ISO 8601 字符串字段 `exportedAt`，解码时仍被接受。
struct BackupData: Codable {
    /// 备份格式版本号，用于向前兼容
    let version: String
    /// 备份生成时间（epoch milliseconds，与 Android 对齐）
    let timestamp: Int64
    /// 全部会话列表
    let sessions: [Session]
    /// 全部消息列表（跨会话扁平化）
    let messages: [Message]
    /// 全部 Agent 配置列表
    ///
    /// 注意：明文备份时 `apiKey` 已被脱敏为 `"***"`，无法还原；
    /// 加密备份时保留原始 `apiKey`，可解密还原。
    let agentConfigs: [AgentConfig]
    /// 设置快照
    let settings: BackupSettings

    /// 创建一份新的备份快照
    /// - Parameters:
    ///   - sessions: 会话列表
    ///   - messages: 消息列表
    ///   - agentConfigs: Agent 配置列表
    ///   - settings: 设置快照
    init(
        sessions: [Session],
        messages: [Message],
        agentConfigs: [AgentConfig],
        settings: BackupSettings
    ) {
        self.version = Self.formatVersion
        self.timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        self.sessions = sessions
        self.messages = messages
        self.agentConfigs = agentConfigs
        self.settings = settings
    }

    /// 备份格式版本号
    static let formatVersion = "3.0.0"

    private enum CodingKeys: String, CodingKey {
        case version, timestamp, sessions, messages, agentConfigs, settings
        // 旧版 iOS 字段名 — 仅用于解码兼容
        case legacyExportedAt = "exportedAt"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.version = try c.decodeIfPresent(String.self, forKey: .version) ?? Self.formatVersion
        // 优先使用 `timestamp`（Int64 epoch ms），回退到旧版 `exportedAt`（ISO 8601 字符串）
        if let ts = try c.decodeIfPresent(Int64.self, forKey: .timestamp) {
            self.timestamp = ts
        } else if let isoString = try c.decodeIfPresent(String.self, forKey: .legacyExportedAt),
                  // SW-M4: 使用现代 .iso8601 解析策略替代 ISO8601DateFormatter
                  let date = try? Date(isoString, strategy: .iso8601) {
            self.timestamp = Int64(date.timeIntervalSince1970 * 1000)
        } else {
            self.timestamp = Int64(Date().timeIntervalSince1970 * 1000)
        }
        self.sessions = try c.decodeIfPresent([Session].self, forKey: .sessions) ?? []
        self.messages = try c.decodeIfPresent([Message].self, forKey: .messages) ?? []
        self.agentConfigs = try c.decodeIfPresent([AgentConfig].self, forKey: .agentConfigs) ?? []
        self.settings = try c.decodeIfPresent(BackupSettings.self, forKey: .settings)
            ?? .defaultSettings
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(version, forKey: .version)
        try c.encode(timestamp, forKey: .timestamp)
        try c.encode(sessions, forKey: .sessions)
        try c.encode(messages, forKey: .messages)
        try c.encode(agentConfigs, forKey: .agentConfigs)
        try c.encode(settings, forKey: .settings)
    }
}

// MARK: - BackupManager

/// 云备份/恢复管理器 — P3-3。
///
/// 在原有 `SettingsView` 内联的 JSON 导出/导入基础上，提取为独立服务并增强：
///
/// - 支持完整数据备份（sessions / messages / agentConfigs / settings），不再仅 sessions + messages；
/// - 支持基于 `KeychainManager` 的硬件级加密备份（设备绑定密钥，AES-256-GCM）；
/// - 暴露 `autoBackup` 属性用于自动备份调度查询/设置，通过 `UserDefaults` 持久化
///   （与 SwiftUI 视图中的 `@AppStorage("autoBackupSchedule")` 共享同一个键）。
///
/// 设计说明：
/// - 使用 `@Observable` 暴露给 SwiftUI 视图树，子视图可通过 `@Environment` 或
///   `@State` 注入；
/// - 加密备份使用 `KeychainManager.encrypt` —— 密钥由 iOS Keychain 保护，
///   加密后的备份只能在同一设备（或已恢复 Keychain 的设备）上解密；
/// - 文件写入到 `Documents/Backups/` 目录，与 `ChatRepository` 的
///   `Documents/ChatHistory/exports/` 目录区分，避免混淆。
///
/// 与 Android 端 `com.agentcontrolcenter.app.data.backup.BackupManager` 行为一致。
@Observable
final class BackupManager {

    // MARK: - 常量

    /// 自动备份调度在 `UserDefaults` 中的键名。
    ///
    /// SwiftUI 视图中可通过 `@AppStorage("autoBackupSchedule")` 直接读写此值，
    /// 与本类的 `autoBackup` 计算属性保持同步。
    static let autoBackupStorageKey = "autoBackupSchedule"

    /// 备份文件存储子目录名（相对于 Documents）
    private static let backupsDirectoryName = "Backups"

    // MARK: - 依赖

    /// 数据控制器，提供 sessions / messages / agentConfigs 查询与写入
    private let dataController: DataController

    // MARK: - 状态

    /// 最近一次操作的错误信息（`nil` 表示无错误或操作成功）
    var lastError: String?

    /// 最近一次导出文件的 URL（用于分享 / 在 UI 中展示）
    var lastExportedURL: URL?

    // MARK: - 初始化

    /// 创建备份管理器。
    ///
    /// - Parameter dataController: 数据控制器，用于读写 sessions / messages / agentConfigs
    init(dataController: DataController) {
        self.dataController = dataController
    }

    // MARK: - 自动备份调度

    /// 当前自动备份调度。
    ///
    /// 通过 `UserDefaults` 持久化，与 `@AppStorage("autoBackupSchedule")` 共享同一键。
    /// 设置为新值时自动写入 `UserDefaults`。
    var autoBackup: BackupSchedule {
        get {
            let raw = UserDefaults.standard.string(forKey: Self.autoBackupStorageKey)
                ?? BackupSchedule.manual.rawValue
            return BackupSchedule(rawValue: raw) ?? .manual
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: Self.autoBackupStorageKey)
        }
    }

    // MARK: - 明文导出 / 导入

    /// 将完整备份以明文 JSON 写入 Documents/Backups/ 目录。
    ///
    /// 安全说明：明文备份中的 `agentConfigs[].apiKey` 会被脱敏为 `"***"`，
    /// 防止备份文件外泄后泄露 API 凭据。`restoreBackup` 不会用脱敏值覆盖
    /// 数据库中的真实 apiKey，用户恢复后无需重新登录现有 Agent；
    /// 若需将 Agent 配置一并迁移到新设备，请使用 `exportEncrypted`。
    ///
    /// - Returns: 成功返回导出文件的 `URL`；失败返回 `nil` 并设置 `lastError`
    @discardableResult
    func exportToFile() -> URL? {
        lastError = nil
        do {
            var backup = collectBackupData()
            // 明文备份：对每个 AgentConfig 的 apiKey 做脱敏
            backup.agentConfigs = backup.agentConfigs.map { config in
                var masked = config
                if !masked.apiKey.isEmpty {
                    masked.apiKey = "***"
                }
                return masked
            }
            let data = try encodeBackup(backup)
            let url = try writeBackup(data: data, fileExtension: "json")
            lastExportedURL = url
            return url
        } catch {
            lastError = String(
                format: NSLocalizedString("error.export.failed", comment: ""),
                error.localizedDescription
            )
            return nil
        }
    }

    /// 从指定文件读取明文 JSON 备份并解析为 `BackupData`。
    ///
    /// 注意：本方法仅解析数据，不写回数据库。调用方可通过 `restoreBackup(_:)` 落库。
    ///
    /// - Parameter url: 备份文件 URL（如通过文件选择器获取）
    /// - Returns: 成功返回 `BackupData`；失败返回 `nil` 并设置 `lastError`
    func importFromFile(_ url: URL) -> BackupData? {
        lastError = nil
        let needsScopeAccess = url.startAccessingSecurityScopedResource
        if needsScopeAccess {
            _ = url.startAccessingSecurityScopedResource()
        }
        defer {
            if needsScopeAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }
        do {
            let data = try Data(contentsOf: url)
            return decodeBackup(data: data)
        } catch {
            lastError = String(
                format: NSLocalizedString("error.import.failed", comment: ""),
                error.localizedDescription
            )
            return nil
        }
    }

    // MARK: - 加密导出 / 导入

    /// 将完整备份加密后写入 Documents/Backups/ 目录。
    ///
    /// 加密流程：
    /// 1. 收集 `BackupData` 并序列化为 JSON；
    /// 2. 调用 `KeychainManager.encrypt` 使用设备绑定的 AES-256-GCM 密钥加密；
    /// 3. 将密文写入文件。
    ///
    /// 加密后的文件只能在持有相同 Keychain 密钥的设备上解密，适合作为设备本地
    /// 的安全备份（如上传至用户私有的云存储）。
    ///
    /// F28 修复：加密备份同样对 `agentConfigs[].apiKey` 做脱敏（与明文分支一致）。
    /// 原实现直接对 `collectBackupData()` 整体加密，备份包内含明文 apiKey。
    /// 由于 `restoreBackup` 仅恢复 sessions/messages，不恢复 agentConfigs，
    /// 备份中的 apiKey 实际从未被使用，脱敏不影响恢复功能，却消除了
    /// 「加密文件被破解/误分享后 apiKey 明文泄漏」的风险。
    ///
    /// - Returns: 成功返回导出文件的 `URL`；失败返回 `nil` 并设置 `lastError`
    @discardableResult
    func exportEncrypted() -> URL? {
        lastError = nil
        do {
            var backup = collectBackupData()
            // F28: 对 apiKey 脱敏，与明文分支 exportToFile 保持一致
            backup.agentConfigs = backup.agentConfigs.map { config in
                var masked = config
                if !masked.apiKey.isEmpty {
                    masked.apiKey = "***"
                }
                return masked
            }
            let json = try encodeBackupString(backup)
            // KeychainManager.encrypt 输出 `AKS:` 前缀的 Base64 字符串；加密失败返回 nil
            guard let encrypted = KeychainManager.encrypt(json) else {
                lastError = String(localized: "error.encrypt.failed")
                return nil
            }
            let data = Data(encrypted.utf8)
            let url = try writeBackup(data: data, fileExtension: "enc")
            lastExportedURL = url
            return url
        } catch {
            lastError = String(
                format: NSLocalizedString("error.encrypted.export", comment: ""),
                error.localizedDescription
            )
            return nil
        }
    }

    /// 从指定文件读取加密备份并解密为 `BackupData`。
    ///
    /// 解密流程：
    /// 1. 读取文件内容为字符串；
    /// 2. 调用 `KeychainManager.decrypt` 使用设备绑定的密钥解密；
    /// 3. 将解密后的 JSON 解析为 `BackupData`。
    ///
    /// 若文件并非加密格式（无 `AKS:` 前缀），将设置 `lastError` 并返回 `nil` ——
    /// 调用方应改用 `importFromFile(_:)`。
    ///
    /// - Parameter url: 加密备份文件 URL
    /// - Returns: 成功返回 `BackupData`；失败返回 `nil` 并设置 `lastError`
    func importEncrypted(_ url: URL) -> BackupData? {
        lastError = nil
        let needsScopeAccess = url.startAccessingSecurityScopedResource
        if needsScopeAccess {
            _ = url.startAccessingSecurityScopedResource()
        }
        defer {
            if needsScopeAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }
        guard let payload = String(data: try? Data(contentsOf: url) ?? Data(), encoding: .utf8) else {
            lastError = String(localized: "error.encrypted.read")
            return nil
        }
        guard let json = KeychainManager.decrypt(payload) else {
            lastError = String(localized: "error.decrypt.failed")
            return nil
        }
        guard let data = json.data(using: .utf8) else {
            lastError = String(localized: "error.decrypt.utf8")
            return nil
        }
        return decodeBackup(data: data)
    }

    // MARK: - 落库恢复

    /// 将 `BackupData` 中的会话与消息写入数据库。
    ///
    /// 采用 upsert 语义：若会话/消息 ID 已存在则覆盖更新。
    /// Agent 配置与设置不在此处自动恢复，避免覆盖用户当前配置；
    /// 调用方可按需单独处理。
    ///
    /// - Parameter data: 已解析的 `BackupData`
    /// - Returns: 成功返回 `true`；失败返回 `false` 并设置 `lastError`
    @discardableResult
    func restoreBackup(_ data: BackupData) -> Bool {
        lastError = nil
        do {
            for session in data.sessions {
                dataController.saveSession(session)
            }
            for message in data.messages {
                dataController.saveMessage(message)
            }
            return true
        } catch {
            lastError = String(
                format: NSLocalizedString("error.restore.failed", comment: ""),
                error.localizedDescription
            )
            return false
        }
    }

    // MARK: - 内部辅助

    /// 收集当前应用状态的完整快照。
    ///
    /// - sessions / messages / agentConfigs 来自 `DataController`
    /// - settings 来自 `UserDefaults`
    private func collectBackupData() -> BackupData {
        let sessions = dataController.fetchSessions()
        var messages: [Message] = []
        for session in sessions {
            messages.append(contentsOf: dataController.fetchMessages(sessionId: session.id))
        }
        let agentConfigs = dataController.fetchAgentConfigs()
        let settings = BackupSettings(
            themeMode: UserDefaults.standard.string(forKey: "theme") ?? "system",
            fontSize: UserDefaults.standard.string(forKey: "fontSize") ?? "medium",
            e2eEnabled: UserDefaults.standard.bool(forKey: "encryptionEnabled"),
            autoBackup: UserDefaults.standard.string(forKey: Self.autoBackupStorageKey)
                ?? BackupSchedule.manual.rawValue
        )
        return BackupData(
            sessions: sessions,
            messages: messages,
            agentConfigs: agentConfigs,
            settings: settings
        )
    }

    /// 将 `BackupData` 编码为 JSON `Data`。
    private func encodeBackup(_ data: BackupData) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(data)
    }

    /// 将 `BackupData` 编码为 JSON 字符串（用于加密前的明文载荷）。
    private func encodeBackupString(_ data: BackupData) throws -> String {
        let data = try encodeBackup(data)
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// 将 JSON `Data` 解码为 `BackupData`；解码失败时设置 `lastError` 并返回 `nil`。
    private func decodeBackup(data: Data) -> BackupData? {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        do {
            return try decoder.decode(BackupData.self, from: data)
        } catch {
            lastError = String(
                format: NSLocalizedString("error.backup.parse", comment: ""),
                error.localizedDescription
            )
            return nil
        }
    }

    /// 计算备份目录 URL（Documents/Backups/），若不存在则创建。
    private var backupsDirectoryURL: URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let url = documents.appendingPathComponent(Self.backupsDirectoryName)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// 将备份数据写入 `Documents/Backups/` 目录，文件名包含时间戳。
    ///
    /// - Parameters:
    ///   - data: 备份内容
    ///   - fileExtension: 文件扩展名（"json" 或 "enc"）
    /// - Returns: 写入成功的文件 URL
    private func writeBackup(data: Data, fileExtension: String) throws -> URL {
        let timestamp = Int(Date().timeIntervalSince1970)
        let filename = "agentcontrolcenter-backup-\(timestamp).\(fileExtension)"
        let url = backupsDirectoryURL.appendingPathComponent(filename)
        try data.write(to: url, options: .atomic)
        return url
    }
}
