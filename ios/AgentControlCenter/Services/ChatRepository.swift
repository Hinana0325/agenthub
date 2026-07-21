import Foundation
import Observation
import os

// MARK: - 类型别名

/// 聊天会话类型别名，复用现有 `Session` 模型。
///
/// ChatRepository 以文件持久化方式管理会话与消息，
/// 数据结构与 `Models/Session.swift` 中的 `Session` 完全一致，
/// 通过 typealias 暴露 `ChatSession` 名称以匹配接口规范。
typealias ChatSession = Session

/// 聊天消息类型别名，复用现有 `Message` 模型。
typealias ChatMessage = Message

// MARK: - ChatBackup

/// 聊天备份结构 — 用于单个会话的导出/导入。
///
/// 包含会话元数据和全部消息列表，序列化为 JSON 后
/// 可作为独立文件分享或在设备间迁移。
struct ChatBackup: Codable {
    /// 备份格式版本（用于向前兼容）
    let version: String
    /// 导出时间（ISO 8601 字符串）
    let exportedAt: String
    /// 会话数据
    let session: ChatSession
    /// 会话中的全部消息（按时间升序）
    let messages: [ChatMessage]

    /// 创建备份结构
    /// - Parameters:
    ///   - session: 会话
    ///   - messages: 消息列表
    init(session: ChatSession, messages: [ChatMessage]) {
        self.version = "1.0"
        // SW-M4: 使用现代 .iso8601 FormatStyle 替代 ISO8601DateFormatter
        self.exportedAt = Date().formatted(.iso8601)
        self.session = session
        self.messages = messages
    }
}

// MARK: - ChatRepository

/// 聊天数据仓库 — 基于文件系统的 JSON 持久化层。
///
/// 与 `DataController`（SwiftData）的区别：
/// - `DataController` 是主持久化层，使用 SwiftData 存储到 SQLite
/// - `ChatRepository` 是辅助持久化层，将会话/消息以 JSON 文件形式
///   存储到 Documents/ChatHistory/ 目录，用于：
///   1. 数据备份与恢复
///   2. 单会话导出/导入（跨设备迁移）
///   3. 为 `DataInsightsManager` 提供聚合数据源
///
/// 存储结构：
/// ```
/// Documents/ChatHistory/
///   ├── sessions.json          — 所有会话的索引文件
///   ├── <sessionId>_messages.json — 每个会话的消息文件
///   └── exports/               — 导出文件目录
///       └── <sessionId>_<timestamp>.json
/// ```
@Observable
final class ChatRepository {

    // MARK: - 目录常量

    /// 根存储目录名（相对于 Documents）
    private static let rootDirectoryName = "ChatHistory"

    /// 导出子目录名
    private static let exportsDirectoryName = "exports"

    /// 会话索引文件名
    private static let sessionsFileName = "sessions.json"

    // MARK: - 属性

    /// 当前已加载的会话列表（从磁盘读取后缓存在内存中）
    private(set) var loadedSessions: [ChatSession] = []

    /// SW-M3: 统一日志器，便于在 Console.app 中按 subsystem 筛选
    private static let logger = Logger(subsystem: "com.agentcontrolcenter.app.ios", category: "ChatRepository")

    // MARK: - 路径计算

    /// Documents 目录 URL
    private var documentsURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
    }

    /// 聊天历史根目录 URL
    private var rootDirectoryURL: URL {
        documentsURL.appendingPathComponent(Self.rootDirectoryName)
    }

    /// 导出目录 URL
    private var exportsDirectoryURL: URL {
        rootDirectoryURL.appendingPathComponent(Self.exportsDirectoryName)
    }

    /// 会话索引文件 URL
    private var sessionsFileURL: URL {
        rootDirectoryURL.appendingPathComponent(Self.sessionsFileName)
    }

    /// 指定会话的消息文件 URL
    private func messagesFileURL(forSessionId sessionId: String) -> URL {
        rootDirectoryURL.appendingPathComponent("\(sessionId)_messages.json")
    }

    // MARK: - 初始化

    /// 创建聊天仓库并确保目录存在，同时加载已有会话索引
    init() {
        ensureDirectoryExists()
        loadedSessions = loadSessions()
    }

    // MARK: - 目录管理

    /// 确保根目录和导出目录存在
    private func ensureDirectoryExists() {
        let fm = FileManager.default
        try? fm.createDirectory(at: rootDirectoryURL, withIntermediateDirectories: true)
        try? fm.createDirectory(at: exportsDirectoryURL, withIntermediateDirectories: true)
    }

    // MARK: - 会话持久化

    /// 保存（或更新）单个会话到磁盘的索引文件。
    ///
    /// 采用 upsert 语义：若会话 ID 已存在则更新，否则追加。
    /// 保存后同步刷新内存缓存 `loadedSessions`。
    /// - Parameter session: 要保存的会话
    func saveSession(_ session: ChatSession) {
        var sessions = loadSessions()
        if let index = sessions.firstIndex(where: { $0.id == session.id }) {
            sessions[index] = session
        } else {
            sessions.append(session)
        }
        saveSessionsToDisk(sessions)
        loadedSessions = sessions
    }

    /// 从磁盘加载所有会话。
    ///
    /// 若索引文件不存在或解析失败，返回空数组。
    /// - Returns: 会话列表（按 `updatedAt` 降序排列）
    func loadSessions() -> [ChatSession] {
        // SW-M3: 文件不存在视为首次运行，静默返回空数组；解码失败需记录
        guard let data = try? Data(contentsOf: sessionsFileURL) else {
            return []
        }
        let decoder = JSONDecoder()
        do {
            let sessions = try decoder.decode([ChatSession].self, from: data)
            return sessions.sorted { $0.updatedAt > $1.updatedAt }
        } catch {
            Self.logger.warning("loadSessions: 解码会话索引失败: \(error.localizedDescription)")
            return []
        }
    }

    /// 将会话列表写入磁盘索引文件
    private func saveSessionsToDisk(_ sessions: [ChatSession]) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        do {
            let data = try encoder.encode(sessions)
            do {
                try data.write(to: sessionsFileURL, options: .atomic)
            } catch {
                Self.logger.error("saveSessionsToDisk: 写入索引文件失败: \(error.localizedDescription)")
            }
        } catch {
            Self.logger.error("saveSessionsToDisk: 编码会话列表失败: \(error.localizedDescription)")
        }
    }

    // MARK: - 消息持久化

    /// 保存指定会话的全部消息到磁盘。
    ///
    /// 采用全量覆盖策略：将传入的消息列表完整写入会话对应的消息文件。
    /// - Parameters:
    ///   - messages: 消息列表
    ///   - session: 目标会话
    func saveMessages(_ messages: [ChatMessage], forSession session: ChatSession) {
        saveMessages(messages, forSessionId: session.id)
    }

    /// 保存指定会话的全部消息到磁盘（按 sessionId 重载）。
    /// - Parameters:
    ///   - messages: 消息列表
    ///   - sessionId: 目标会话 ID
    func saveMessages(_ messages: [ChatMessage], forSessionId sessionId: String) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        do {
            let data = try encoder.encode(messages)
            let url = messagesFileURL(forSessionId: sessionId)
            do {
                try data.write(to: url, options: .atomic)
            } catch {
                Self.logger.error("saveMessages: 写入会话 \(sessionId) 消息文件失败: \(error.localizedDescription)")
            }
        } catch {
            Self.logger.error("saveMessages: 编码会话 \(sessionId) 消息失败: \(error.localizedDescription)")
        }
    }

    /// 从磁盘加载指定会话的全部消息。
    ///
    /// 若消息文件不存在或解析失败，返回空数组。
    /// - Parameter sessionId: 会话 ID
    /// - Returns: 消息列表（按 `timestamp` 升序排列）
    func loadMessages(sessionId: String) -> [ChatMessage] {
        let url = messagesFileURL(forSessionId: sessionId)
        // SW-M3: 文件不存在视为新会话，静默返回空；解码失败需记录
        guard let data = try? Data(contentsOf: url) else {
            return []
        }
        let decoder = JSONDecoder()
        do {
            let messages = try decoder.decode([ChatMessage].self, from: data)
            return messages.sorted { $0.timestamp < $1.timestamp }
        } catch {
            Self.logger.warning("loadMessages: 解码会话 \(sessionId) 消息失败: \(error.localizedDescription)")
            return []
        }
    }

    // MARK: - 删除

    /// 删除指定会话及其全部消息文件。
    ///
    /// 同时从索引文件和消息文件中移除，并刷新内存缓存。
    /// - Parameter session: 要删除的会话
    func deleteSession(_ session: ChatSession) {
        deleteSession(sessionId: session.id)
    }

    /// 删除指定会话及其全部消息文件（按 sessionId 重载）。
    /// - Parameter sessionId: 要删除的会话 ID
    func deleteSession(sessionId: String) {
        // 从索引中移除
        var sessions = loadSessions()
        sessions.removeAll { $0.id == sessionId }
        saveSessionsToDisk(sessions)
        loadedSessions = sessions

        // 删除消息文件
        let url = messagesFileURL(forSessionId: sessionId)
        try? FileManager.default.removeItem(at: url)
    }

    // MARK: - 导出

    /// 将单个会话导出为 JSON 文件。
    ///
    /// 生成包含会话元数据和全部消息的 `ChatBackup` 结构，
    /// 写入到 Documents/ChatHistory/exports/ 目录下。
    /// - Parameter session: 要导出的会话
    /// - Returns: 导出文件的 URL；若失败返回 nil
    func exportSession(_ session: ChatSession) -> URL? {
        let messages = loadMessages(sessionId: session.id)
        let backup = ChatBackup(session: session, messages: messages)

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        // SW-M3: 编码失败与写入失败均记录日志，便于排查「导出无文件」类问题
        let data: Data
        do {
            data = try encoder.encode(backup)
        } catch {
            Self.logger.error("exportSession: 编码备份失败 (\(session.id)): \(error.localizedDescription)")
            return nil
        }

        let timestamp = Int(Date().timeIntervalSince1970)
        let filename = "\(session.id)_\(timestamp).json"
        let fileURL = exportsDirectoryURL.appendingPathComponent(filename)
        do {
            try data.write(to: fileURL, options: .atomic)
            return fileURL
        } catch {
            Self.logger.error("exportSession: 写入导出文件失败 (\(session.id)): \(error.localizedDescription)")
            return nil
        }
    }

    // MARK: - 导入

    /// 从 JSON 文件导入会话及其消息。
    ///
    /// 解析 `ChatBackup` 结构后，将会话和消息分别写入索引文件和消息文件。
    /// 采用 upsert 语义：若会话 ID 已存在则覆盖更新。
    /// - Parameter url: 导入文件的 URL
    /// - Returns: 导入成功返回对应的会话；失败返回 nil
    @discardableResult
    func importSession(from url: URL) -> ChatSession? {
        // 需要读取外部文件（如用户通过文件选择器选取的文档），使用 startAccessingSecurityScopedResource
        let needsScopeAccess = url.startAccessingSecurityScopedResource
        if needsScopeAccess {
            _ = url.startAccessingSecurityScopedResource()
        }
        defer {
            if needsScopeAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }

        guard let data = try? Data(contentsOf: url) else {
            Self.logger.warning("importSession: 读取导入文件失败: \(url.path)")
            return nil
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        do {
            let backup = try decoder.decode(ChatBackup.self, from: data)
            return persistImportedBackup(backup)
        } catch {
            Self.logger.warning("importSession: 解码备份文件失败: \(error.localizedDescription)")
            return nil
        }
    }

    /// 将已解析的备份结构写入本地存储（从 importSession 抽出，便于复用）
    private func persistImportedBackup(_ backup: ChatBackup) -> ChatSession {
        saveSession(backup.session)
        saveMessages(backup.messages, forSessionId: backup.session.id)
        return backup.session
    }

    // MARK: - 查询

    /// 获取所有已存储会话的消息总数
    var totalMessageCount: Int {
        var count = 0
        for session in loadedSessions {
            count += loadMessages(sessionId: session.id).count
        }
        return count
    }

    /// 获取所有会话的全部消息（扁平化）
    func allMessages() -> [ChatMessage] {
        loadedSessions.flatMap { session in
            loadMessages(sessionId: session.id)
        }
    }
}
