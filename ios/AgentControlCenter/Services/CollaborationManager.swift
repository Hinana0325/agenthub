import Foundation
import Observation
import os

/// 协作管理器 — 多人 WebSocket 协作会话
/// 对应 Android CollaborationManager
///
/// 提供协作会话的基本数据结构和会话生命周期管理。
/// 实际的 WebSocket 信令（消息广播、光标同步、冲突解决等）
/// 需要配套的后端信令服务支持，此模块仅作为客户端框架。
///
/// `@MainActor` 隔离保证 `activeCollabSession` 等响应式状态的读写
/// 均在主线程，避免 SwiftUI 视图读取时发生数据竞争。
@MainActor
@Observable
final class CollaborationManager {

    /// SW-M3: 协作会话存储日志器（nonisolated：Logger 是 Sendable，可从任意上下文调用）
    private nonisolated static let logger = Logger(subsystem: "com.agentcontrolcenter.app.ios", category: "CollaborationManager")

    // MARK: - 数据模型

    /// 协作参与者角色
    enum ParticipantRole: String, Codable {
        case host       // 主持人：可管理会话和参与者
        case editor     // 编辑者：可修改会话内容
        case viewer     // 观察者：只读权限
    }

    /// 协作参与者
    struct Participant: Identifiable, Codable {
        /// 参与者唯一标识
        let id: String
        /// 显示名称
        let displayName: String
        /// 参与者角色
        let role: ParticipantRole
        /// 是否在线
        var isOnline: Bool
    }

    /// 协作会话
    struct CollabSession: Identifiable, Codable {
        /// 本地记录唯一标识
        let id: String
        /// 远端会话标识（用于 WebSocket 信令匹配）
        let sessionId: String
        /// 当前参与者列表
        var participants: [Participant]
        /// 会话创建时间
        let createdAt: Date
    }

    // MARK: - 属性

    /// 当前活跃的协作会话（nil 表示未加入任何会话）
    var activeCollabSession: CollabSession?

    /// 当前会话的参与者列表（便捷视图，与 activeCollabSession.participants 同步）
    var participants: [Participant] {
        get { activeCollabSession?.participants ?? [] }
        set {
            guard let session = activeCollabSession else { return }
            activeCollabSession = CollabSession(
                id: session.id,
                sessionId: session.sessionId,
                participants: newValue,
                createdAt: session.createdAt
            )
        }
    }

    /// 本地缓存的协作配置（sessionId -> 映射数据）
    ///
    /// 持久化到 `Application Support/Collaboration/sessions.json`，
    /// 而非 UserDefaults：UserDefaults 仅用于用户偏好设置，
    /// 业务数据（会话历史、参与者映射等）应使用独立文件，
    /// 便于备份/清理且避免污染偏好设置命名空间。
    ///
    /// SW-M6: 文件 I/O 通过 `Task.detached` 移出 MainActor，避免阻塞 UI。
    /// 读/写均通过 `loadSessionStore(from:)` / `writeSessionStore(_:to:)` 静态方法。

    /// 协作会话本地缓存文件 URL
    private nonisolated var sessionsStoreURL: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        return appSupport
            .appendingPathComponent("Collaboration", isDirectory: true)
            .appendingPathComponent("sessions.json")
    }

    /// SW-M6: 非隔离的同步读取辅助方法（可在任意 actor / thread 上调用）
    private nonisolated static func loadSessionStore(from url: URL) -> [String: [String: Any]] {
        guard let data = try? Data(contentsOf: url) else {
            return [:]
        }
        do {
            if let dict = try JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] {
                return dict
            }
            logger.warning("loadSessionStore: JSON 顶层结构非预期字典")
            return [:]
        } catch {
            logger.warning("loadSessionStore: 解析失败: \(error.localizedDescription)")
            return [:]
        }
    }

    /// SW-M6: 非隔离的同步写入辅助方法（在 Task.detached 中调用以避免阻塞 MainActor）
    private nonisolated static func writeSessionStore(_ store: [String: [String: Any]], to url: URL) {
        let dir = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        do {
            let data = try JSONSerialization.data(withJSONObject: store, options: [.prettyPrinted])
            do {
                try data.write(to: url, options: .atomic)
            } catch {
                logger.error("writeSessionStore: 写入文件失败: \(error.localizedDescription)")
            }
        } catch {
            logger.error("writeSessionStore: 序列化失败: \(error.localizedDescription)")
        }
    }

    // MARK: - 会话生命周期

    /// 创建新的协作会话。
    ///
    /// 创建者自动成为 host 角色的参与者。
    /// - Parameters:
    ///   - sessionId: 会话唯一标识
    ///   - userId: 创建者用户 ID
    ///   - displayName: 创建者显示名称
    func createSession(sessionId: String, userId: String = UUID().uuidString, displayName: String = "我") {
        let host = Participant(
            id: userId,
            displayName: displayName,
            role: .host,
            isOnline: true
        )

        let session = CollabSession(
            id: UUID().uuidString,
            sessionId: sessionId,
            participants: [host],
            createdAt: Date()
        )

        activeCollabSession = session
        saveSessionToStore(sessionId)

        // MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
    }

    /// 加入已有的协作会话。
    ///
    /// - Parameters:
    ///   - sessionId: 目标会话 ID
    ///   - code: 邀请码（用于后端验证）
    ///   - userId: 加入者用户 ID
    ///   - displayName: 加入者显示名称
    func joinSession(sessionId: String, code: String, userId: String = UUID().uuidString, displayName: String = "参与者") {
        // 以 editor 角色加入
        let participant = Participant(
            id: userId,
            displayName: displayName,
            role: .editor,
            isOnline: true
        )

        let session = CollabSession(
            id: UUID().uuidString,
            sessionId: sessionId,
            participants: [participant],
            createdAt: Date()
        )

        activeCollabSession = session
        saveSessionToStore(sessionId)

        // MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
    }

    /// 离开当前协作会话。
    ///
    /// 清除本地会话状态，通知后端信令服务移除参与者。
    func leaveSession() {
        guard let session = activeCollabSession else { return }

        // MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）

        removeFromStore(sessionId: session.sessionId)
        activeCollabSession = nil
    }

    // MARK: - 参与者管理

    /// 向当前会话添加参与者。
    ///
    /// 仅 host 角色有权限添加参与者。
    /// - Parameter participant: 待添加的参与者
    func addParticipant(_ participant: Participant) {
        guard var session = activeCollabSession else { return }

        // 权限检查：仅 host 可添加参与者
        guard session.participants.contains(where: { $0.role == .host && $0.isOnline }) else {
            // MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
            return
        }

        // 避免重复添加
        guard !session.participants.contains(where: { $0.id == participant.id }) else { return }

        session.participants.append(participant)
        activeCollabSession = session

        // MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
    }

    /// 从当前会话移除参与者。
    ///
    /// 仅 host 角色有权限移除参与者。
    /// - Parameter id: 待移除参与者的 ID
    func removeParticipant(id: String) {
        guard var session = activeCollabSession else { return }

        // 权限检查：仅 host 可移除参与者
        guard session.participants.contains(where: { $0.role == .host && $0.isOnline }) else {
            return
        }

        session.participants.removeAll { $0.id == id }
        activeCollabSession = session

        // MVP: 本地会话状态管理已实现。真实 WebSocket 信令推迟到 v5.2.0（见 docs/product-strategy.md 主线 B）
    }

    // MARK: - 私有方法

    /// 将会话 ID 保存到本地缓存
    /// SW-M6: 文件 I/O 通过 Task.detached 移出 MainActor，避免阻塞 UI；
    /// 内存状态（activeCollabSession）已由调用方同步更新，磁盘写入失败仅记日志
    private func saveSessionToStore(_ sessionId: String) {
        let url = sessionsStoreURL
        let entry: [String: Any] = [
            // SW-M4: 使用现代 .iso8601 FormatStyle 替代 ISO8601DateFormatter
            "joinedAt": Date().formatted(.iso8601),
            "sessionId": sessionId
        ]
        Task.detached(priority: .utility) {
            var store = Self.loadSessionStore(from: url)
            store[sessionId] = entry
            Self.writeSessionStore(store, to: url)
        }
    }

    /// 从本地缓存移除会话
    /// SW-M6: 文件 I/O 通过 Task.detached 移出 MainActor
    private func removeFromStore(sessionId: String) {
        let url = sessionsStoreURL
        Task.detached(priority: .utility) {
            var store = Self.loadSessionStore(from: url)
            store.removeValue(forKey: sessionId)
            Self.writeSessionStore(store, to: url)
        }
    }
}
